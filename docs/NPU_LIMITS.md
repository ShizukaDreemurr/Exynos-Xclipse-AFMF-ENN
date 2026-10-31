# NPU/ENN không làm được gì — và việc đó chạy ở đâu

| Việc | NPU/ENN | Chạy ở | Lý do |
|---|---|---|---|
| Ước lượng flow + mask (conv thuần) | Làm được | NPU (LiteRT/LiteCore) | Đây là phần duy nhất đáng đưa lên NPU |
| `grid_sample` / backward warp | Thường không hỗ trợ | Vulkan `warp.comp` | Lấy mẫu ngẫu nhiên theo flow, NPU hay rơi về CPU |
| Blend, mask, occlusion fallback, giữ HUD | Không đáng | Vulkan | Phép toán theo pixel, rẻ trên GPU |
| Thu nhỏ ảnh, chuẩn hóa, NCHW | Không | Vulkan `preprocess.comp` | Cần đọc thẳng AHardwareBuffer |
| Phát hiện scene cut | Không | Vulkan (atomic) | Rẻ, cần kết quả tức thì |
| Nhận frame, present, vsync | Không | CPU/GPU | NPU không có đường ra màn hình |
| Dynamic shape | Không | — | Mỗi tier một mô hình tĩnh |
| FP32 đầy đủ ở độ phân giải cao | Chậm/bị từ chối | FP16/INT8 | Băng thông + hỗ trợ op |

Hệ quả: đường LiteRT hiện tại copy float qua CPU (không zero-copy). Zero-copy AHardwareBuffer cần adapter native
(`EnnNativeBackend`) và header SDK thật. Nếu độ trễ copy lớn, đo bằng HUD (`flow` ms) trước khi tối ưu.
