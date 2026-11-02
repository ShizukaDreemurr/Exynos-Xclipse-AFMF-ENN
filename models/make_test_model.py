"""Tạo model flow (Keras, trọng số ngẫu nhiên hoặc --seed) đúng hợp đồng NCHW của FluidFrames và AOT-compile cho Exynos 2600.

Input  float32 [1,6,H,W]  ->  Output float32 [1,5,H/2,W/2]
Chỉ dùng: transpose, conv2d, relu, add, tanh, mul, logistic, concat, slice (đều có builder Samsung).
"""
import argparse, os, sys, traceback
import numpy as np
import tensorflow as tf

MAX_FLOW_PX = 24.0


def build(h, w, width=32):
    inp = tf.keras.Input(shape=(6, h, w), batch_size=1, name="input")
    x = tf.keras.layers.Permute((2, 3, 1))(inp)                      # NCHW -> NHWC
    x = tf.keras.layers.Conv2D(width, 3, strides=2, padding="same", activation="relu")(x)
    r = tf.keras.layers.Conv2D(width, 3, padding="same", activation="relu")(x)
    r = tf.keras.layers.Conv2D(width, 3, padding="same", activation="relu")(r)
    x = tf.keras.layers.Add()([x, r])
    r = tf.keras.layers.Conv2D(width, 3, padding="same", activation="relu")(x)
    r = tf.keras.layers.Conv2D(width, 3, padding="same", activation="relu")(r)
    x = tf.keras.layers.Add()([x, r])
    o = tf.keras.layers.Conv2D(5, 3, padding="same")(x)
    flow = tf.keras.layers.Activation("tanh")(o[..., :4])
    flow = tf.keras.layers.Rescaling(MAX_FLOW_PX)(flow)
    mask = tf.keras.layers.Activation("sigmoid")(o[..., 4:5])
    y = tf.keras.layers.Concatenate(axis=-1)([flow, mask])
    y = tf.keras.layers.Permute((3, 1, 2))(y)                         # NHWC -> NCHW
    return tf.keras.Model(inp, y)


def build_probe(nchw, n=64):
    """Model cực nhỏ để kiểm tra NPU chạy được hay không (1 conv)."""
    if nchw:
        inp = tf.keras.Input(shape=(6, n, n), batch_size=1, name="input")
        x = tf.keras.layers.Permute((2, 3, 1))(inp)
        x = tf.keras.layers.Conv2D(5, 3, strides=2, padding="same")(x)
        y = tf.keras.layers.Permute((3, 1, 2))(x)
    else:
        inp = tf.keras.Input(shape=(n, n, 6), batch_size=1, name="input")
        y = tf.keras.layers.Conv2D(5, 3, strides=2, padding="same")(inp)
    return tf.keras.Model(inp, y)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sizes", default="320x128,384x160,480x192,640x288,960x416,1280x576")
    ap.add_argument("--out", default="out_models")
    ap.add_argument("--compile", action="store_true")
    ap.add_argument("--ifr", default="", help="checkpoint IFRNet-S .pth: dùng flow thô của IFRNet thay model ngẫu nhiên")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for s in a.sizes.split(",") + ["probe_nchw", "probe_nhwc"]:
        if s.startswith("probe"):
            long_side = s
            m = build_probe(s.endswith("nchw"))
        else:
            w, h = (int(v) for v in s.split("x"))
            long_side = max(w, h)
            if a.ifr:
                import torch, ifrnet_coarse
                m = ifrnet_coarse.build(h, w, torch.load(a.ifr, map_location="cpu"), half_out=True)
            else:
                m = build(h, w)
        conv = tf.lite.TFLiteConverter.from_keras_model(m)
        raw = os.path.join(a.out, f"flow_{long_side}_raw.tflite")
        open(raw, "wb").write(conv.convert())
        print("raw", raw, os.path.getsize(raw))
        if a.compile:
            from ai_edge_litert.aot import aot_compile as aot
            from ai_edge_litert.aot.vendors.samsung import target as st
            tgt = st.Target(st.SocModel.E9965)
            try:
                res = aot.aot_compile(raw, output_dir=os.path.join(a.out, f"c{long_side}"), target=tgt, keep_going=False)
            except ValueError as e:
                import re
                print("AOTERR", e)
                for pth in re.findall(r"(/tmp/\S+\.error)", str(e)):
                    print("AOTERRFILE", open(pth, errors="replace").read()[-2500:])
                raise
            print("compiled", res)
            for attr in ("failed_backends","models_with_backend"):
                print(attr, getattr(res, attr, None))


if __name__ == "__main__":
    main()
