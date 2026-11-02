"""Chuyển model SR x2 đã huấn luyện (train_sr_x2.py) sang TFLite INT8 (vào/ra int8), đo PSNR bản INT8 và xuất tham số lượng tử.
  python make_sr_trained.py --pt m32.pt --videos a.mp4,b.mp4 --size 1170x540 --out out_sr_trained [--compile]
Mô hình: vào [1,3,H,W] RGB 0..1 (LR) -> ra [1,12,H,W] phần chênh lệch (GPU: pixel-shuffle + cộng bilinear)."""
import argparse, json, os, sys
import numpy as np, cv2

def extract_weights(pt):
    import torch
    d = torch.load(pt, map_location="cpu"); sd = d["sd"]
    ws = []
    for k in sorted({int(k.split(".")[1]) for k in sd if k.endswith("weight")}):
        ws.append((sd[f"body.{k}.weight"].numpy().transpose(2, 3, 1, 0), sd[f"body.{k}.bias"].numpy()))  # OIHW -> HWIO
    return ws

def crops(path, n, held, size, per=1, seed=3):
    rng = np.random.default_rng(seed); cap = cv2.VideoCapture(path); N = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    idx = np.linspace(0, N - 1, n).astype(int); tr, te = [], []
    for k, i in enumerate(idx):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(i)); ok, f = cap.read()
        if not ok: continue
        H, W = f.shape[:2]; y = int(rng.integers(0, H - size + 1)) // 2 * 2; x = int(rng.integers(0, W - size + 1)) // 2 * 2
        (te if k >= n * (1 - held) else tr).append(f[y:y + size, x:x + size, ::-1].copy())
    return tr, te

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pt", required=True); ap.add_argument("--videos", required=True)
    ap.add_argument("--size", default="1170x540"); ap.add_argument("--out", default="out_sr_trained")
    ap.add_argument("--compile", action="store_true"); ap.add_argument("--raw", default=""); ap.add_argument("--mode", default="x2")
    a = ap.parse_args(); os.makedirs(a.out, exist_ok=True)
    W, H = (int(v) for v in a.size.split("x"))
    import tensorflow as tf
    L = tf.keras.layers
    ws = extract_weights(a.pt) if a.pt.endswith(".pt") else None
    ch = ws[0][0].shape[3]
    def build(h, w):
        inp = tf.keras.Input(shape=(3, h, w), batch_size=1, name="input")
        x = L.Permute((2, 3, 1))(inp)
        for i, (k, b) in enumerate(ws):
            x = L.Conv2D(k.shape[3], 3, padding="same", activation=None if i == len(ws) - 1 else "relu")(x)
        return tf.keras.Model(inp, L.Permute((3, 1, 2))(x))
    # calib/test: LR từ khung HR (area x2)
    tr, te = [], []
    for v in a.videos.split(","):
        t1, t2 = crops(v, 400, 0.1, 256); tr += t1; te += t2
    if a.mode == "x1":   # đầu vào = ảnh bị làm mờ (thu x2 rồi phóng lại); cùng cỡ với đích
        lr = lambda hr: cv2.resize(cv2.resize(hr, (hr.shape[1] // 2, hr.shape[0] // 2), interpolation=cv2.INTER_AREA), (hr.shape[1], hr.shape[0]), interpolation=cv2.INTER_LINEAR)
    else:
        lr = lambda hr: cv2.resize(hr, (hr.shape[1] // 2, hr.shape[0] // 2), interpolation=cv2.INTER_AREA)
    tr = [lr(x) for x in tr]; te_hr = te; te = [lr(x) for x in te]
    ph, pw = tr[0].shape[:2]
    m = build(ph, pw)   # xây ở cỡ mảnh để hiệu chỉnh, rồi dựng lại đúng cỡ khung
    for l, (k, b) in zip([l for l in m.layers if isinstance(l, L.Conv2D)], ws): l.set_weights([k, b])
    def convert(model, h, w, reps):
        c = tf.lite.TFLiteConverter.from_keras_model(model)
        c.optimizations = [tf.lite.Optimize.DEFAULT]
        c.representative_dataset = lambda: ([(r.astype(np.float32).transpose(2, 0, 1)[None] / 255).astype(np.float32)] for r in reps)
        c.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        c.inference_input_type = tf.int8; c.inference_output_type = tf.int8
        return c.convert()
    reps = [tr[i] for i in np.linspace(0, len(tr) - 1, 200).astype(int)]
    blob_small = convert(m, ph, pw, reps)
    ip = tf.lite.Interpreter(model_content=blob_small); ip.allocate_tensors()
    di, do = ip.get_input_details()[0], ip.get_output_details()[0]
    si, zi = di["quantization"]; so, zo = do["quantization"]
    print("quant in", si, zi, "out", so, zo, flush=True)
    def run(x):
        q = np.clip(np.round(x.astype(np.float32).transpose(2, 0, 1)[None] / 255 / si + zi), -128, 127).astype(np.int8)
        ip.set_tensor(di["index"], q); ip.invoke()
        r = (ip.get_tensor(do["index"]).astype(np.float32) - zo) * so          # 1,12,h,w
        r = r[0].transpose(1, 2, 0).reshape(x.shape[0], x.shape[1], 2, 2, 3)  # 12 = (c, i, j)?  xem ghi chú dưới
        return r
    # torch pixel_shuffle: kênh = c*4 + i*2 + j
    def run_full(x):
        q = np.clip(np.round(x.astype(np.float32).transpose(2, 0, 1)[None] / 255 / si + zi), -128, 127).astype(np.int8)
        ip.set_tensor(di["index"], q); ip.invoke()
        r = (ip.get_tensor(do["index"]).astype(np.float32) - zo) * so
        if a.mode == "x1":
            up = x.astype(np.float32).transpose(2, 0, 1) / 255
            return np.clip(up + r[0], 0, 1), up
        r = r[0].reshape(3, 2, 2, x.shape[0], x.shape[1]).transpose(0, 3, 1, 4, 2).reshape(3, 2 * x.shape[0], 2 * x.shape[1])
        up = cv2.resize(x.astype(np.float32) / 255, (2 * x.shape[1], 2 * x.shape[0]), interpolation=cv2.INTER_LINEAR).transpose(2, 0, 1)
        return np.clip(up + r, 0, 1), up
    ps = lambda p, g: 10 * np.log10(1 / np.mean((np.clip(p, 0, 1) - g) ** 2))
    pq, pb = [], []
    for x, g in zip(te, te_hr):
        o, up = run_full(x); gt = g.astype(np.float32).transpose(2, 0, 1) / 255
        pq.append(ps(o, gt)); pb.append(ps(up, gt))
    print(f"INT8 PSNR {np.mean(pq):.3f} dB, bilinear {np.mean(pb):.3f} dB, +{np.mean(pq) - np.mean(pb):.3f} (n={len(pq)})", flush=True)
    full = convert(build(H, W), H, W, reps) if False else None
    # dựng lại đúng cỡ khung với cùng trọng số
    mf = build(H, W)
    for l, (k, b) in zip([l for l in mf.layers if isinstance(l, L.Conv2D)], ws): l.set_weights([k, b])
    tile = lambda r: np.tile(r, (H // r.shape[0] + 1, W // r.shape[1] + 1, 1))[:H, :W]
    blob = convert(mf, H, W, [tile(r) for r in reps[::2]])
    ip2 = tf.lite.Interpreter(model_content=blob); ip2.allocate_tensors()
    d2i, d2o = ip2.get_input_details()[0], ip2.get_output_details()[0]
    q = dict(in_scale=float(d2i["quantization"][0]), in_zp=int(d2i["quantization"][1]), out_scale=float(d2o["quantization"][0]), out_zp=int(d2o["quantization"][1]), w=W, h=H, psnr_i8=float(np.mean(pq)), psnr_bilinear=float(np.mean(pb)))
    print("QUANT", json.dumps(q))
    raw = os.path.join(a.out, f"sr_{a.mode}_{W}x{H}_raw.tflite"); open(raw, "wb").write(blob)
    json.dump(q, open(os.path.join(a.out, f"sr_{a.mode}_{W}x{H}.json"), "w"))
    print("size", len(blob))
if __name__ == "__main__":
    main()
