"""Thử xem NPU Exynos 2600 (ENN) nhận được các phép WARP nào, bằng model siêu nhỏ [1,6,64,64].
Mỗi model: ảnh (3 kênh) + flow (2 kênh, đơn vị pixel) + 1 kênh đệm -> ảnh đã warp [1,3,64,64].
In dòng 'OPRESULT <tên> <KẾT QUẢ>' để đọc từ log CI. Model biên dịch được copy ra out_ops/ để app chạy thử."""
import os, sys, numpy as np, tensorflow as tf

N = 64
L = tf.keras.layers


def _inp():
    inp = tf.keras.Input(shape=(6, N, N), batch_size=1, name="input")
    x = L.Permute((2, 3, 1))(inp)
    return inp, x[..., 0:3], x[..., 3:5] * 6.0


def m_gather():
    # chỉ một phép tf.gather với chỉ số hằng
    inp, img, _ = _inp()
    idx = np.random.default_rng(0).permutation(N * N).astype(np.int32)
    def f(t):
        flat = tf.reshape(t, [N * N, 3])
        return tf.reshape(tf.gather(flat, idx), [1, N, N, 3])
    y = L.Lambda(f)(img)
    return tf.keras.Model(inp, L.Permute((3, 1, 2))(y))


def m_gather_warp():
    # bilinear backward warp đầy đủ bằng gather: floor/cast/clip + 4 gather
    inp, img, flow = _inp()
    gy, gx = np.meshgrid(np.arange(N, dtype=np.float32), np.arange(N, dtype=np.float32), indexing="ij")
    base = np.stack([gx, gy], -1)[None]
    def f(args):
        im, fl = args
        c = tf.clip_by_value(base + fl, 0.0, N - 1.001)
        x0 = tf.floor(c[..., 0:1]); y0 = tf.floor(c[..., 1:2])
        wx = c[..., 0:1] - x0; wy = c[..., 1:2] - y0
        x0i = tf.cast(x0, tf.int32); y0i = tf.cast(y0, tf.int32)
        flat = tf.reshape(im, [N * N, 3])
        def g(xi, yi):
            return tf.reshape(tf.gather(flat, tf.reshape(yi * N + xi, [N * N])), [1, N, N, 3])
        a = g(x0i, y0i); b = g(x0i + 1, y0i); c2 = g(x0i, y0i + 1); d = g(x0i + 1, y0i + 1)
        return (a * (1 - wx) + b * wx) * (1 - wy) + (c2 * (1 - wx) + d * wx) * wy
    y = L.Lambda(f)([img, flow])
    return tf.keras.Model(inp, L.Permute((3, 1, 2))(y))


def m_gather_nd():
    inp, img, flow = _inp()
    gy, gx = np.meshgrid(np.arange(N), np.arange(N), indexing="ij")
    base = np.stack([gy, gx], -1)[None].astype(np.int32)
    def f(args):
        im, fl = args
        d = tf.cast(tf.round(tf.reverse(fl, [-1])), tf.int32)
        idx = tf.clip_by_value(base + d, 0, N - 1)
        return tf.gather_nd(im, idx, batch_dims=1)
    y = L.Lambda(f)([img, flow])
    return tf.keras.Model(inp, L.Permute((3, 1, 2))(y))


def m_shift25():
    # warp KHÔNG gather: 25 ảnh dịch (-2..2) chọn theo flow làm tròn, chỉ pad/slice/mul/add
    inp, img, flow = _inp()
    def f(args):
        im, fl = args
        r = tf.clip_by_value(tf.round(fl), -2.0, 2.0)
        pad = tf.pad(im, [[0, 0], [2, 2], [2, 2], [0, 0]], mode="SYMMETRIC")
        out = 0.0
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                sh = pad[:, 2 + dy:2 + dy + N, 2 + dx:2 + dx + N, :]
                sel = tf.cast(tf.abs(r[..., 0:1] - dx) < 0.5, tf.float32) * tf.cast(tf.abs(r[..., 1:2] - dy) < 0.5, tf.float32)
                out = out + sh * sel
        return out
    y = L.Lambda(f)([img, flow])
    return tf.keras.Model(inp, L.Permute((3, 1, 2))(y))


def m_resize_only():
    # đối chứng: resize bilinear (đã biết chạy)
    inp, img, _ = _inp()
    y = L.Resizing(N, N, interpolation="bilinear")(L.Resizing(N // 2, N // 2)(img))
    return tf.keras.Model(inp, L.Permute((3, 1, 2))(y))


MODELS = {"op_resize": m_resize_only, "op_gather": m_gather, "op_gather_warp": m_gather_warp,
          "op_gather_nd": m_gather_nd, "op_shift25": m_shift25}

if __name__ == "__main__":
    out = "out_ops"; os.makedirs(out, exist_ok=True)
    from ai_edge_litert.aot import aot_compile as aot
    from ai_edge_litert.aot.vendors.samsung import target as st
    tgt = st.Target(st.SocModel.E9965)
    for name, mk in MODELS.items():
        try:
            m = mk()
            raw = os.path.join(out, f"{name}_raw.tflite")
            open(raw, "wb").write(tf.lite.TFLiteConverter.from_keras_model(m).convert())
        except Exception as e:
            print("OPRESULT", name, "CONVERT_FAIL", str(e)[:200].replace("\n", " ")); continue
        for strict in (True, False):
            try:
                r = aot.aot_compile(raw, output_dir=os.path.join(out, f"c_{name}_{int(strict)}"), target=tgt, keep_going=not strict)
                print("OPRESULT", name, "STRICT_OK" if strict else "PARTIAL_OK(keep_going)", "failed_backends=", getattr(r, "failed_backends", None))
                if strict: break
            except Exception as e:
                print("OPRESULT", name, "STRICT_FAIL" if strict else "PARTIAL_FAIL", str(e)[:300].replace("\n", " "))
