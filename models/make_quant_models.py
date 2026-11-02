"""Tạo bản FP16 và INT8 (hiệu chuẩn bằng khung gameplay trong calib/) của flow IFRNet-S rồi AOT-compile cho Exynos NPU.
In dòng 'QRESULT ...' để đọc từ log CI. Chỉ xuất file INT8 khi sai số so với FP32 đủ nhỏ (xem --max-err).
  python make_quant_models.py --ifr weights/IFRNet_S_Vimeo90K.pth --sizes 320x128 --compile
"""
import argparse, glob, os, sys
import numpy as np, tensorflow as tf, torch, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ifrnet_coarse


def calib_pairs(w, h, n=40):
    files = sorted(glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), "calib", "p*_a.jpg")))[:n]
    out = []
    for fa in files:
        a = cv2.imread(fa); b = cv2.imread(fa.replace("_a.jpg", "_b.jpg"))
        a = cv2.cvtColor(cv2.resize(a, (w, h), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
        b = cv2.cvtColor(cv2.resize(b, (w, h), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
        out.append((np.concatenate([a, b], -1).transpose(2, 0, 1)[None].astype(np.float32) / 255))
    return out


def run_tflite(blob, x):
    it = tf.lite.Interpreter(model_content=blob); it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]["index"], x); it.invoke()
    return it.get_tensor(it.get_output_details()[0]["index"])


def convert(model, kind, pairs):
    c = tf.lite.TFLiteConverter.from_keras_model(model)
    if kind == "fp16":
        c.optimizations = [tf.lite.Optimize.DEFAULT]
        c.target_spec.supported_types = [tf.float16]
        return c.convert()
    c.optimizations = [tf.lite.Optimize.DEFAULT]
    c.representative_dataset = lambda: ([p] for p in pairs)
    try:
        c.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        return c.convert()
    except Exception as e:  # một số op chưa có kernel int8 -> cho phép trộn
        print("QRESULT int8 strict_fail", str(e)[:160].replace("\n", " "))
        c = tf.lite.TFLiteConverter.from_keras_model(model)
        c.optimizations = [tf.lite.Optimize.DEFAULT]
        c.representative_dataset = lambda: ([p] for p in pairs)
        c.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS, tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        return c.convert()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ifr", required=True)
    ap.add_argument("--sizes", default="192x64,256x96,320x128,384x160,480x192,640x288,960x416,1280x576")
    ap.add_argument("--out", default="out_quant")
    ap.add_argument("--compile", action="store_true")
    ap.add_argument("--max-err", type=float, default=0.6, help="sai số flow trung bình tối đa (pixel) so với FP32 để cho phép INT8")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    sd = torch.load(a.ifr, map_location="cpu")
    for s in a.sizes.split(","):
        w, h = (int(v) for v in s.split("x")); long_side = max(w, h)
        model = ifrnet_coarse.build(h, w, sd, half_out=True)
        pairs = calib_pairs(w, h)
        ref_blob = tf.lite.TFLiteConverter.from_keras_model(model).convert()
        for kind in ("fp16", "int8"):
            try:
                blob = convert(model, kind, pairs)
            except Exception as e:
                print("QRESULT", long_side, kind, "CONVERT_FAIL", str(e)[:200].replace("\n", " ")); continue
            errs = [float(np.abs(run_tflite(blob, p)[:, :4] - run_tflite(ref_blob, p)[:, :4]).mean()) for p in pairs[:8]]
            err = float(np.mean(errs))
            print("QRESULT", long_side, kind, "size=%d" % len(blob), "err_px=%.3f" % err)
            if kind == "int8" and err > a.max_err:
                print("QRESULT", long_side, kind, "BỎ (sai số > %.2f px)" % a.max_err); continue
            raw = os.path.join(a.out, f"flow_{long_side}_{kind}_raw.tflite")
            open(raw, "wb").write(blob)
            if a.compile:
                from ai_edge_litert.aot import aot_compile as aot
                from ai_edge_litert.aot.vendors.samsung import target as st
                tgt = st.Target(st.SocModel.E9965)
                d = os.path.join(a.out, f"c{long_side}_{kind}")
                for strict in (True, False):
                    try:
                        aot.aot_compile(raw, output_dir=d, target=tgt, keep_going=not strict)
                        print("QRESULT", long_side, kind, "AOT_OK" if strict else "AOT_PARTIAL"); break
                    except Exception as e:
                        print("QRESULT", long_side, kind, "AOT_STRICT_FAIL" if strict else "AOT_FAIL", str(e)[:200].replace("\n", " "))


if __name__ == "__main__":
    main()
