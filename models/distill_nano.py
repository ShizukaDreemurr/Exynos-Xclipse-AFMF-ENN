"""Chưng cất flow thô IFRNet-S (thầy) -> model nano (trò) bằng video gameplay.
  python distill_nano.py data  --videos a.mp4 b.mp4 --size 640x288 --pairs 1200 --out /tmp/nano/d640.npz
  python distill_nano.py train --data /tmp/nano/d640.npz --out /tmp/nano/s640 --epochs 20
Trò: conv thuần (NPU ok), vào [1,6,H,W] (hợp đồng app), ra [1,5,H/2,W/2] như thầy."""
import argparse, os, sys, time
import numpy as np, tensorflow as tf
L = tf.keras.layers


def build_student(h, w, ch=(16, 24, 32), bs=1, R=3):
    """Mã hoá chung 2 khung -> cost volume cục bộ (±R ô ở H/8, chỉ pad/slice/mul/sum) -> conv -> flow."""
    inp = tf.keras.Input(shape=(6, h, w), batch_size=bs, name="input")
    x = L.Permute((2, 3, 1))(inp)
    x = L.Rescaling(1.0, offset=-0.5)(x)
    enc = [L.Conv2D(c, 3, strides=2, padding="same", activation="relu") for c in ch]
    def encode(t):
        for e in enc: t = e(t)
        return t
    f0, f1 = encode(x[..., 0:3]), encode(x[..., 3:6])
    pad = L.ZeroPadding2D((R, R))(f1)
    hh, ww = h // 8, w // 8
    vols = []
    for dy in range(2 * R + 1):
        for dx in range(2 * R + 1):
            sh = L.Lambda(lambda t, dy=dy, dx=dx: t[:, dy:dy + hh, dx:dx + ww, :])(pad)
            vols.append(L.Lambda(lambda t: tf.reduce_mean(t[0] * t[1], axis=-1, keepdims=True))([f0, sh]))
    cv = L.Concatenate()(vols)
    z = L.Concatenate()([cv, f0])
    z = L.Conv2D(48, 3, padding="same", activation="relu")(z)
    z = L.Conv2D(32, 3, padding="same", activation="relu")(z)
    y = L.Conv2D(4, 3, padding="same")(z)
    y = L.Rescaling(8.0)(y)
    y = L.Resizing(h // 2, w // 2, interpolation="bilinear")(y)
    mask = L.Lambda(lambda t: tf.zeros_like(t[..., :1]) + 0.5, name="mask")(y)
    y = L.Concatenate()([y, mask])
    return tf.keras.Model(inp, L.Permute((3, 1, 2))(y))


def cmd_data(a):
    import cv2, torch
    sys.path.insert(0, os.path.dirname(__file__)); import ifrnet_coarse
    w, h = (int(v) for v in a.size.split("x"))
    sd = torch.load(a.ckpt, map_location="cpu")
    teacher = ifrnet_coarse.build(h, w, sd, half_out=True)
    rng = np.random.default_rng(0)
    X = []; Y = []
    per = a.pairs // len(a.videos)
    t0 = time.time()
    for v in a.videos:
        cap = cv2.VideoCapture(v); n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        frames = {}
        def get(i):
            if i not in frames:
                cap.set(cv2.CAP_PROP_POS_FRAMES, i); ok, f = cap.read()
                frames[i] = cv2.cvtColor(cv2.resize(f, (w, h), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB) if ok else None
            return frames[i]
        got = 0
        while got < per:
            i = int(rng.integers(0, n - 8)); gap = int(rng.choice(a.gaps))
            f0, f1 = get(i), get(i + gap)
            if f0 is None or f1 is None: continue
            if np.abs(f0.astype(np.int16) - f1.astype(np.int16)).mean() < 0.3: continue  # bỏ cặp tĩnh/trùng khung
            inp = np.concatenate([f0, f1], -1).transpose(2, 0, 1)[None].astype(np.float32) / 255
            X.append((inp[0] * 255).astype(np.uint8)); Y.append(teacher(inp, training=False).numpy()[0, :4].astype(np.float16)); got += 1
            if len(frames) > 64: frames.clear()
            if got % 100 == 0: print(v[-20:], got, "/", per, "%.0fs" % (time.time() - t0), flush=True)
    np.savez(a.out, x=np.stack(X), y=np.stack(Y), size=np.array([w, h]))
    print("saved", a.out, len(X))


def cmd_train(a):
    d = np.load(a.data); X, Y = d["x"], d["y"]; w, h = (int(v) for v in d["size"])
    n = len(X); idx = np.random.default_rng(1).permutation(n); nv = max(20, n // 10)
    va, tr = idx[:nv], idx[nv:]
    cw, chh, bs = min(w, 320), min(h, 160), 8
    student = build_student(chh, cw, bs=bs)
    opt = tf.keras.optimizers.Adam(tf.keras.optimizers.schedules.CosineDecay(2e-3, a.epochs * (len(tr) // bs)))
    def batch(ids, rand=True):
        xs, ys = [], []
        for i in ids:
            ox = int(np.random.randint(0, w - cw + 1) // 2 * 2) if rand else 0
            oy = int(np.random.randint(0, h - chh + 1) // 2 * 2) if rand else 0
            xs.append(X[i][:, oy:oy + chh, ox:ox + cw].astype(np.float32) / 255)
            ys.append(Y[i][:, oy // 2:(oy + chh) // 2, ox // 2:(ox + cw) // 2].astype(np.float32))
        return np.stack(xs), np.stack(ys)
    @tf.function
    def step(x, y):
        with tf.GradientTape() as t:
            p = student(x, training=True)[:, :4]
            loss = tf.reduce_mean(tf.sqrt(tf.reduce_sum(tf.square(p - y), 1) + 1e-3))
        opt.apply_gradients(zip(t.gradient(loss, student.trainable_variables), student.trainable_variables)); return loss
    def epe(ids):
        e = []
        for k in range(0, len(ids) - bs + 1, bs):
            x, y = batch(ids[k:k + bs], rand=False)
            e.append(float(tf.reduce_mean(tf.sqrt(tf.reduce_sum(tf.square(student(x)[:, :4] - y), 1)))))
        return float(np.mean(e))
    def zero(ids):
        return float(np.mean([np.sqrt((Y[i].astype(np.float32) ** 2).sum(0)).mean() for i in ids]))
    print("EPE nếu đoán flow=0 (val):", zero(va), flush=True)
    t0 = time.time()
    for ep in range(a.epochs):
        np.random.shuffle(tr); L_ = []
        for k in range(0, len(tr) - bs + 1, bs):
            L_.append(float(step(*batch(tr[k:k + bs]))))
        print("epoch", ep, "loss %.3f" % np.mean(L_), "val EPE %.3f" % epe(va), "%.0fs" % (time.time() - t0), flush=True)
    os.makedirs(a.out, exist_ok=True)
    full = build_student(h, w, bs=1); full.set_weights(student.get_weights())
    open(os.path.join(a.out, f"flow_{max(w, h)}_nano_raw.tflite"), "wb").write(tf.lite.TFLiteConverter.from_keras_model(full).convert())
    student.save_weights(os.path.join(a.out, "nano.weights.h5")); print("saved", a.out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); sp = ap.add_subparsers(dest="c")
    d = sp.add_parser("data"); d.add_argument("--videos", nargs="+"); d.add_argument("--size", default="640x288")
    d.add_argument("--pairs", type=int, default=1200); d.add_argument("--gaps", type=int, nargs="+", default=[1, 2, 3])
    d.add_argument("--ckpt", default="weights/IFRNet_S_Vimeo90K.pth"); d.add_argument("--out", required=True)
    t = sp.add_parser("train"); t.add_argument("--data", required=True); t.add_argument("--out", required=True); t.add_argument("--epochs", type=int, default=20)
    a = ap.parse_args()
    if a.c == "data": cmd_data(a)
    else: cmd_train(a)
