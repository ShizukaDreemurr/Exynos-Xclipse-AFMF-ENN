"""IFRNet-S (MIT, Kong et al. CVPR'22) -> phần ước lượng flow THÔ (encoder + decoder4), không có grid_sample.

Chỉ dùng conv / prelu (phân rã thành relu,mul,add) / concat / slice / conv_transpose => hợp NPU.
Input  float32 [1,6,H,W]  (prev RGB, cur RGB, 0..1; H,W bội số 16)
Output float32 [1,5,H/8,W/8] = F_t->0 xy, F_t->1 xy (pixel ở độ phân giải H,W), mask 0.5
Phần tinh chỉnh ở độ phân giải cao do GPU (warp.comp) đảm nhiệm.
Dùng:  python ifrnet_coarse.py --ckpt IFRNet_S_Vimeo90K.pth --check
"""
import argparse
import numpy as np
import tensorflow as tf

L = tf.keras.layers
import itertools
_ctr = itertools.count()


def prelu(x, alpha, name):
    # prelu(x) = relu(x) - a * relu(-x)   (chỉ cần relu / mul / add)
    a = alpha.reshape(1, 1, 1, -1).astype(np.float32)
    pos = L.ReLU()(x)
    neg = L.ReLU()(L.Rescaling(-1.0)(x))
    neg = L.Lambda(lambda t: t * (-a), name=f"{name}_neg{next(_ctr)}")(neg)
    return L.Add()([pos, neg])


def conv(x, sd, key, stride=1, name=None):
    w = sd[key + ".weight"].numpy().transpose(2, 3, 1, 0)
    b = sd[key + ".bias"].numpy()
    if stride == 2:  # torch pad đối xứng 1; Keras "same" lệch phải -> pad tay
        x = L.ZeroPadding2D(((1, 1), (1, 1)))(x)
    c = L.Conv2D(w.shape[3], w.shape[0], strides=stride, padding="valid" if stride == 2 else "same", name=name)
    x = c(x)
    c.set_weights([w, b])
    return x


def convrelu(x, sd, key, stride=1):
    x = conv(x, sd, key + ".0", stride)
    return prelu(x, sd[key + ".1.weight"].numpy(), key.replace(".", "_"))


def resblock(x, sd, key, side=24):
    ch = x.shape[-1]
    o = convrelu(x, sd, key + ".conv1")
    o = L.Concatenate()([o[..., : ch - side], convrelu(o[..., ch - side:], sd, key + ".conv2")])
    o = convrelu(o, sd, key + ".conv3")
    o = L.Concatenate()([o[..., : ch - side], convrelu(o[..., ch - side:], sd, key + ".conv4")])
    o = conv(o, sd, key + ".conv5")
    return prelu(L.Add()([x, o]), sd[key + ".prelu.weight"].numpy(), key.replace(".", "_") + "_p")


def encoder(x, sd):
    for i in range(1, 5):
        x = convrelu(x, sd, f"encoder.pyramid{i}.0", stride=2)
        x = convrelu(x, sd, f"encoder.pyramid{i}.1")
    return x


def build(h, w, sd, scale_px=8.0, half_out=False):
    inp = tf.keras.Input(shape=(6, h, w), batch_size=1, name="input")
    x = L.Permute((2, 3, 1))(inp)
    x = L.Rescaling(1.0, offset=-0.5)(x)  # thay mean_ toàn cục bằng 0.5
    f0 = encoder(x[..., 0:3], sd)
    f1 = encoder(x[..., 3:6], sd)
    emb = L.Lambda(lambda t: tf.zeros_like(t[..., :1]) + 0.5, name="embt")(f0)
    y = L.Concatenate()([f0, f1, emb])
    y = convrelu(y, sd, "decoder4.convblock.0")
    y = resblock(y, sd, "decoder4.convblock.1")
    # ConvTranspose2d(144,58,4,2,1) -> chỉ giữ 4 kênh flow đầu
    wt = sd["decoder4.convblock.2.weight"].numpy()[:, :4].transpose(2, 3, 1, 0)  # kh,kw,out,in
    bt = sd["decoder4.convblock.2.bias"].numpy()[:4]
    ct = L.Conv2DTranspose(4, 4, strides=2, padding="same", name="ct")
    y = ct(y)
    ct.set_weights([wt, bt])
    y = L.Rescaling(scale_px)(y)  # pixel ở H/8 -> pixel ở H
    if half_out:  # hợp đồng app: [1,5,H/2,W/2]
        y = L.Resizing(h // 2, w // 2, interpolation="bilinear")(y)
    mask = L.Lambda(lambda t: tf.zeros_like(t[..., :1]) + 0.5, name="mask")(y)
    y = L.Concatenate()([y, mask])
    y = L.Permute((3, 1, 2))(y)
    return tf.keras.Model(inp, y)


def torch_ref(ckpt, a, b):
    import sys, torch
    sys.path.insert(0, "/tmp/IFRNet")
    from models.IFRNet_S import Model  # type: ignore
    m = Model().eval()
    m.load_state_dict(torch.load(ckpt, map_location="cpu"))
    with torch.no_grad():
        a = a - 0.5; b = b - 0.5
        f0 = m.encoder(a)[3]; f1 = m.encoder(b)[3]
        o = m.decoder4(f0, f1, torch.full((1, 1, 1, 1), 0.5))[:, :4]
    return o.numpy() * 8.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--size", default="640x288")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    import torch
    sd = torch.load(a.ckpt, map_location="cpu")
    w, h = (int(v) for v in a.size.split("x"))
    m = build(h, w, sd)
    if a.check:
        rng = np.random.default_rng(0)
        base = rng.random((1, 3, h, w), dtype=np.float32)
        x0 = base; x1 = np.roll(base, 8, axis=3)
        inp = np.concatenate([x0, x1], 1)
        got = m(inp).numpy()[:, :4]
        ref = torch_ref(a.ckpt, torch.from_numpy(x0), torch.from_numpy(x1))
        print("shape", m.output_shape, "max|diff|", float(np.abs(got - ref).max()), "ref range", float(np.abs(ref).max()))
    if a.out:
        conv_ = tf.lite.TFLiteConverter.from_keras_model(m)
        open(a.out, "wb").write(conv_.convert())
        print("wrote", a.out)


if __name__ == "__main__":
    main()
