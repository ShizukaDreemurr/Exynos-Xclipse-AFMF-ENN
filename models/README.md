# Mô hình flow

## Hợp đồng
| | |
|---|---|
| Input | float32 NCHW `[1,6,H,W]` — prev RGB, cur RGB, 0..1; H,W bội số 32 |
| Output | float32 NCHW `[1,5,H/2,W/2]` — `F_t→0` (x,y), `F_t→1` (x,y) tính bằng **pixel ở độ phân giải H×W**, mask (sigmoid = trọng số phía prev) |
| File | `app/src/main/assets/models/flow_<cạnh dài>.tflite`, tier: 1280 / 960 / 640 |

Không có file → app tự rơi về flow block-matching trên GPU (chất lượng thấp hơn, vẫn chạy).

## Quy trình
1. **Huấn luyện / chưng cất** `flow_net.py` (hoặc thay bằng RIFE/IFRNet đã cắt nhỏ, giữ đúng hợp đồng I/O). Repo này **không** kèm trọng số đã huấn luyện.
2. `make_calibration.py` → `calib_pairs.npy` từ gameplay thật (cho INT8).
3. `export_tflite.py --precision fp16|int8` cho từng tier.
4. (Tùy chọn, nhanh hơn) AOT cho E9965 bằng LiteCore/ENN toolchain hoặc `export_executorch_enn.py` — xem `docs/ENN_SDK_GUIDE.md`.

## Op hay làm NPU từ chối (tránh trong mạng)
| Op | Vấn đề | Cách xử lý |
|---|---|---|
| `grid_sample` | thường không được hỗ trợ / rơi về CPU | warp làm trong `warp.comp` (Vulkan) |
| `PixelShuffle`, `Resize(align_corners)` | hỗ trợ thất thường | dùng conv stride / giữ đầu ra H/2 |
| `PReLU`, `LayerNorm`, `Softmax` lớn | chậm hoặc lượng tử hóa kém | dùng ReLU; BN gộp vào conv |
| Dynamic shape | NPU cần shape tĩnh | một file mỗi tier |
| Tensor FP32 lớn qua CPU | băng thông | INT8/FP16; zero-copy cần adapter native |

Luôn kiểm tra log lúc nạp (`LiteRtBackend`): nếu báo rơi về GPU, mô hình chứa op NPU không nhận.
