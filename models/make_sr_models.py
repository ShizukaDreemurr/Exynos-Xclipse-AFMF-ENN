"""Model super-resolution x2 THỬ NGHIỆM (trọng số ngẫu nhiên) để ĐO tốc độ NPU Exynos, chưa phải model chất lượng.
x2: vào [1,3,H,W] (RGB 0..1) -> ra [1,12,H,W] (4 điểm con x 3 kênh, GPU xếp lại) hoặc [1,3,2H,2W] (biến thể d2s = DEPTH_TO_SPACE trên NPU).
x1: vào [1,3,H,W] -> ra [1,3,H,W] (phần chênh lệch để cộng vào ảnh gốc: làm nét/khử nhiễu, KHÔNG đổi độ phân giải).
Biến thể: mạng s16/m32, giao diện float32 (f32) hoặc số nguyên 8-bit (i8). In dòng 'SRRESULT ...' để đọc từ log CI.
  python make_sr_models.py --compile
"""
import argparse, os
import numpy as np, tensorflow as tf

L = tf.keras.layers
SIZES = ["640x288", "960x432", "1152x528"]          # x2: độ phân giải thấp (LR); xuất gấp đôi
SIZES1 = ["640x288", "1152x528", "1536x704"]        # x1: độ phân giải xử lý = độ phân giải xuất
NETS = {"s16": (16, 2), "m32": (32, 3)}             # (số kênh, số lớp giữa)


def build(h, w, ch, mid, d2s=False, out_ch=12):
    inp = tf.keras.Input(shape=(3, h, w), batch_size=1, name="input")
    x = L.Permute((2, 3, 1))(inp)
    x = L.Conv2D(ch, 3, padding="same", activation="relu")(x)
    for _ in range(mid):
        x = L.Conv2D(ch, 3, padding="same", activation="relu")(x)
    x = L.Conv2D(out_ch, 3, padding="same")(x)
    if d2s:
        x = L.Lambda(lambda t: tf.nn.depth_to_space(t, 2))(x)
    return tf.keras.Model(inp, L.Permute((3, 1, 2))(x))


def convert(model, h, w, io):
    rng = np.random.default_rng(1)
    c = tf.lite.TFLiteConverter.from_keras_model(model)
    c.optimizations = [tf.lite.Optimize.DEFAULT]
    c.representative_dataset = lambda: ([rng.random((1, 3, h, w), dtype=np.float32)] for _ in range(8))
    if io == "i8":
        c.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        c.inference_input_type = tf.int8
        c.inference_output_type = tf.int8
    else:
        c.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8, tf.lite.OpsSet.TFLITE_BUILTINS]
    return c.convert()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out_sr")
    ap.add_argument("--compile", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    jobs = [("x2", s, n, io, False) for s in SIZES for n in NETS for io in ("f32", "i8")]
    jobs += [("x2", "640x288", "s16", io, True) for io in ("f32", "i8")]    # thăm dò DEPTH_TO_SPACE trên NPU
    jobs += [("x1", s, n, io, False) for s in SIZES1 for n in NETS for io in ("f32", "i8")]
    for mode, s, net, io, d2s in jobs:
        w, h = (int(v) for v in s.split("x"))
        name = f"{mode}_{s}_{net}_{io}" + ("_d2s" if d2s else "")
        ch, mid = NETS[net]
        try:
            blob = convert(build(h, w, ch, mid, d2s, 3 if mode == "x1" else 12), h, w, io)
        except Exception as e:
            print("SRRESULT", name, "CONVERT_FAIL", str(e)[:160].replace("\n", " ")); continue
        raw = os.path.join(a.out, f"{name}_raw.tflite")
        open(raw, "wb").write(blob)
        print("SRRESULT", name, "size=%d" % len(blob))
        if not a.compile:
            continue
        from ai_edge_litert.aot import aot_compile as aot
        from ai_edge_litert.aot.vendors.samsung import target as st
        tgt = st.Target(st.SocModel.E9965)
        for strict in (True, False):
            try:
                aot.aot_compile(raw, output_dir=os.path.join(a.out, f"c_{name}"), target=tgt, keep_going=not strict)
                print("SRRESULT", name, "AOT_OK" if strict else "AOT_PARTIAL"); break
            except Exception as e:
                print("SRRESULT", name, "AOT_STRICT_FAIL" if strict else "AOT_FAIL", str(e)[:200].replace("\n", " "))


if __name__ == "__main__":
    main()
