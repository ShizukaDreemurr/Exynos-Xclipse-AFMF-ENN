"""Đường 2: ExecuTorch + backend Samsung (Exynos) — chỉ E9955/E9965.

Cần build ExecuTorch từ source với -DEXECUTORCH_BUILD_EXYNOS=ON và Samsung ENN SDK/toolchain
(đăng nhập Exynos Developer Society). Xem docs/ENN_SDK_GUIDE.md.

CHƯA CHẠY THỬ. Tên module/hàm bên dưới lấy theo tài liệu ExecuTorch Samsung backend ở thời điểm
viết; kiểm tra lại với phiên bản bạn build (backends/samsung trong repo pytorch/executorch).
"""
import argparse

import torch

from flow_net import FlowNet


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--long-side", type=int, required=True)
    ap.add_argument("--aspect", default="20:9")
    ap.add_argument("--ckpt", default=None)
    ap.add_argument("--out", default="flow_enn.pte")
    a = ap.parse_args()

    from export_tflite import proc_size

    w, h = proc_size(a.long_side, a.aspect)
    net = FlowNet().eval()
    if a.ckpt:
        net.load_state_dict(torch.load(a.ckpt, map_location="cpu"))
    example = (torch.rand(1, 6, h, w),)

    from executorch.exir import to_edge_transform_and_lower
    from executorch.backends.samsung.partition.enn_partitioner import EnnPartitioner
    from executorch.backends.samsung.serialization.compile_options import (
        gen_samsung_backend_compile_spec,
    )

    spec = gen_samsung_backend_compile_spec("E9965")
    exported = torch.export.export(net, example)
    prog = to_edge_transform_and_lower(exported, partitioner=[EnnPartitioner(spec)]).to_executorch()
    with open(a.out, "wb") as f:
        f.write(prog.buffer)
    print("Đã ghi", a.out)


if __name__ == "__main__":
    main()
