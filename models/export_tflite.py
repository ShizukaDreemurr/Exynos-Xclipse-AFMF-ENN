"""Xuất FlowNet -> .tflite (FP32 / FP16 / INT8) cho từng tier, đặt vào app/src/main/assets/models/.

Chưa chạy thử trong môi trường này (không có ai-edge-torch). API thư viện có thể đổi theo phiên bản —
nếu lệnh lỗi, đối chiếu tài liệu ai-edge-torch / ai-edge-quantizer hiện hành.

Cài đặt (Python 3.10/3.11, Linux):
    pip install torch ai-edge-torch ai-edge-quantizer numpy

Ví dụ:
    python export_tflite.py --long-side 1280 --aspect 20:9 --precision fp16 --ckpt flow.pt
    python export_tflite.py --long-side 960  --aspect 20:9 --precision int8 --calib calib_pairs.npy --ckpt flow.pt
"""
import argparse
import os

import numpy as np
import torch

from flow_net import FlowNet


def proc_size(long_side, aspect):
    aw, ah = (float(v) for v in aspect.split(":"))
    w = long_side
    h = long_side * ah / aw
    return (int(w) // 32) * 32, max(64, (int(h) // 32) * 32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--long-side", type=int, required=True)
    ap.add_argument("--aspect", default="20:9", help="tỉ lệ màn hình, landscape: dài:ngắn")
    ap.add_argument("--precision", choices=["fp32", "fp16", "int8"], default="fp16")
    ap.add_argument("--ckpt", default=None)
    ap.add_argument("--calib", default=None, help=".npy float32 [N,6,H,W] cho INT8")
    ap.add_argument("--out", default="../app/src/main/assets/models")
    args = ap.parse_args()

    w, h = proc_size(args.long_side, args.aspect)
    print(f"proc size = {w}x{h}")

    net = FlowNet().eval()
    if args.ckpt:
        net.load_state_dict(torch.load(args.ckpt, map_location="cpu"))
    else:
        print("CẢNH BÁO: không có --ckpt, xuất trọng số ngẫu nhiên (chỉ để kiểm tra pipeline).")

    sample = (torch.rand(1, 6, h, w),)

    import ai_edge_torch

    edge_model = ai_edge_torch.convert(net, sample)
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"flow_{args.long_side}.tflite")

    if args.precision == "fp32":
        edge_model.export(path)
    else:
        tmp = path + ".fp32"
        edge_model.export(tmp)
        from ai_edge_quantizer import quantizer, recipe

        qt = quantizer.Quantizer(tmp)
        if args.precision == "fp16":
            qt.load_quantization_recipe(recipe.weight_only_wfp16_afp32())
            qt.quantize().export_model(path)
        else:
            if not args.calib:
                raise SystemExit("INT8 cần --calib (xem make_calibration.py)")
            qt.load_quantization_recipe(recipe.static_wi8_ai8())
            data = np.load(args.calib).astype(np.float32)
            cal = {"serving_default": [{"args_0": data[i:i + 1]} for i in range(len(data))]}
            res = qt.calibrate(cal)
            qt.quantize(res).export_model(path)
        os.remove(tmp)
    print("Đã ghi", path)


if __name__ == "__main__":
    main()
