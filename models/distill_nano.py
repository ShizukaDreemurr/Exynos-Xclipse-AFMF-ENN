"""Chưng cất flow thô IFRNet-S (thầy) -> model nano (trò) bằng video gameplay.
  python distill_nano.py data  --videos a.mp4 b.mp4 --size 640x288 --pairs 1200 --out /tmp/nano/d640.npz
  python distill_nano.py train --data /tmp/nano/d640.npz --out /tmp/nano/s640 --epochs 20
Trò: conv thuần (NPU ok), vào [1,6,H,W] (hợp đồng app), ra [1,5,H/2,W/2] như thầy."""
import argparse, os, sys, time
import numpy as np, tensorflow as tf
L = tf.keras.layers


def build_student(h, w, ch=(16, 24, 32), head=32):
    inp = tf.keras.Input(shape=(6, h, w), batch_size=None if False else 1, name="input")
    x = L.Permute((2, 3, 1))(inp)
    x = L.Rescaling(1.0, offset=-0.5)(x)
    for c in ch:
        x = L.Conv2D(c, 3, strides=2, padding="same", activation="relu")(x)
    x = L.Conv2D(head, 3, padding="same", activation="relu")(x)
    x = L.Conv2D(head, 3, padding="same", activation="relu")(x)
    y = L.Conv2D(4, 3, padding="same")(x)
    y = L.Rescaling(8.0)(y)  # đơn vị pixel của độ phân giải vào
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
    cw, ch_ = min(w, 320), min(h, 160)
    student = build_student(ch_, cw)  # train trên crop (conv thuần), suy luận ở full size
    full = build_student(h, w)
    opt = tf.keras.optimizers.Adam(1e-3)
    def batch(ids, rand=True):
        xs, ys = [], []
        for i in ids:
            if rand:
                ox = int(np.random.randint(0, w - cw + 1) // 2 * 2); oy = int(np.random.randint(0, h - ch_ + 1) // 2 * 2)
            else: ox = oy = 0
            xs.append(X[i][:, oy:oy + ch_, ox:ox + cw].astype(np.float32) / 255)
            ys.append(Y[i][:, oy // 2:(oy + ch_) // 2, ox // 2:(ox + cw) // 2].astype(np.float32))
        return np.stack(xs), np.stack(ys)
    @tf.function
    def step(x, y):
        with tf.GradientTape() as t:
            p = student(x, training=True)[:, :4]
            loss = tf.reduce_mean(tf.sqrt(tf.reduce_sum(tf.square(p - y), 1) + 1e-3))  # EPE xấp xỉ
        opt.apply_gradients(zip(t.gradient(loss, student.trainable_variables), student.trainable_variables)); return loss
    bs = 8
    student.build((bs, 6, ch_, cw))
    # tf.function cần batch_size cố định = 1 trong Input -> dùng batch 1 lặp tích luỹ đơn giản
    return student, full, batch, tr, va, step, opt


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); sp = ap.add_subparsers(dest="c")
    d = sp.add_parser("data"); d.add_argument("--videos", nargs="+"); d.add_argument("--size", default="640x288")
    d.add_argument("--pairs", type=int, default=1200); d.add_argument("--gaps", type=int, nargs="+", default=[1, 2, 3])
    d.add_argument("--ckpt", default="weights/IFRNet_S_Vimeo90K.pth"); d.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.c == "data": cmd_data(a)
