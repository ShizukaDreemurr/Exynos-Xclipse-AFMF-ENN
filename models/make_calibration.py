"""Tạo tập hiệu chuẩn INT8 từ video gameplay: các cặp frame liên tiếp -> [N,6,H,W] float32 0..1.

    pip install opencv-python numpy
    python make_calibration.py gameplay.mp4 --width 960 --height 416 --count 200 --out calib_pairs.npy

Dùng đúng loại nội dung bạn sẽ chơi (UI, cảnh tối, hiệu ứng hạt); calibration lệch phân bố = INT8 vỡ hình.
"""
import argparse

import cv2
import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--width", type=int, required=True)
    ap.add_argument("--height", type=int, required=True)
    ap.add_argument("--count", type=int, default=200)
    ap.add_argument("--out", default="calib_pairs.npy")
    a = ap.parse_args()

    cap = cv2.VideoCapture(a.video)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    step = max(1, total // a.count)
    pairs = []
    for idx in range(0, total - 1, step):
        cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
        ok1, f1 = cap.read()
        ok2, f2 = cap.read()
        if not (ok1 and ok2):
            continue
        t = []
        for f in (f1, f2):
            f = cv2.resize(f, (a.width, a.height), interpolation=cv2.INTER_AREA)
            t.append(cv2.cvtColor(f, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0)
        x = np.concatenate(t, axis=2).transpose(2, 0, 1)  # [6,H,W]
        pairs.append(x)
        if len(pairs) >= a.count:
            break
    np.save(a.out, np.stack(pairs))
    print("Đã lưu", a.out, len(pairs), "cặp")


if __name__ == "__main__":
    main()
