# Xclipse ENN-SS

Nội suy khung hình 30→60 FPS kiểu AFMF cho Android (MediaProjection → Vulkan compute → overlay), flow qua NPU Exynos (LiteRT/ENN) hoặc block-matching GPU.

Module: `core-capture` (MediaProjection + ring buffer) · `core-telemetry` (số liệu, governor) · `core-overlay` (cửa sổ video/HUD/menu) · `core-vulkan` (NDK: AHB import, shader, swapchain) · `core-enn` (backend suy luận) · `app`.

## Trạng thái thật
- **Chưa build, chưa chạy trên thiết bị.** Chỉ `pipeline.cpp` được kiểm cú pháp (header giả). Shader chưa qua glslang.
- Present dùng **Vulkan swapchain + vkCmdBlitImage**, không phải ASurfaceControl/fence AHB.
- Cần bật **dịch vụ Trợ năng** thủ công để cảm ứng xuyên qua ở độ mờ 100%.
- **Rủi ro chưa kiểm:** MediaProjection có thể chụp chính overlay (xem docs/TEST_PLAN.md §2).
- Model Refine chưa nối. Không kèm trọng số flow đã huấn luyện (xem models/README.md).
- Đường LiteRT không zero-copy; `EnnNativeBackend` là stub chờ SDK (docs/ENN_SDK_GUIDE.md).
- Ring buffer dùng mutex (không lock-free).

## Build
Push lên GitHub → Actions build APK debug. Hoặc Android Studio (NDK 27.0.12077973, CMake 3.22.1). Gradle wrapper jar chưa có: chạy `gradle wrapper` một lần.
