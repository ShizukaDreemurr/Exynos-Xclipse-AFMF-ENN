# Lấy ENN SDK / Exynos AI LiteCore cho Exynos 2600 (E9965)

Mình không tải hộ được (cần tài khoản + đồng ý license). Các đường dưới đây dựa trên tài liệu công khai.

## Đường 1 (khuyến nghị): LiteRT CompiledModel + Samsung LiteCore
- Exynos 2600 (E9965) và 2500 (E9955) được LiteRT hỗ trợ qua Exynos AI LiteCore.
- Kotlin runtime: `com.google.ai.edge.litert:litert` (+ `litert-gpu` cho fallback GPU). Đã nằm trong `core-enn/build.gradle.kts`.
- Chạy model `.tflite` trực tiếp trên NPU bằng **biên dịch on-device (JIT)**: bỏ file `.tflite` vào `assets/` là đủ,
  không cần SDK Samsung trên máy dev. Thư viện dispatch/NPU của Samsung phải có mặt trong APK: kiểm tra Maven xem
  gói `litert-npu-runtime-*` dành cho Samsung (từ LiteRT 2.3.0 các gói NPU runtime có trên Maven;
  trang tài liệu chỉ ví dụ gói Google Tensor, nên tên gói Samsung bạn cần tự xác nhận).
- Biên dịch AOT (nhanh khởi động hơn) cần môi trường riêng: Ubuntu 22.04, Bazel 7.4.1, libc++ 16+,
  Android API 36 / NDK. Google có Docker "quick system setup" trong repo LiteRT (README mục Docker).
- Gói pip `ai-edge-litert-sdk-samsung-nightly` (Python 3.8–3.11, Apache-2.0) là SDK Exynos AI LiteCore cho LiteRT
  dùng cho bước AOT: `pip install ai-edge-litert-sdk-samsung-nightly`. Trang PyPI không mô tả nội dung/API,
  nên đọc README của repo LiteRT và notebook Colab AOT trong hướng dẫn "NPU acceleration with LiteRT".

## Đường 2: ENN SDK / Exynos AI Studio của Samsung
1. Vào Exynos Developer Society (soc-developer.semiconductor.samsung.com), tạo tài khoản và đăng nhập.
2. Mục **AI LiteCore** (SDK runtime) và **Exynos AI Studio** (toolchain: PyTorch/ONNX/TFLite → IR → quantize → compile).
3. **Exynos Lab**: JupyterLab chạy sẵn SDK trên web, không phải cài môi trường. Đây là cách nhanh nhất để thử compile model.
4. Có Device Farm để thử trên thiết bị thật nếu cần.
5. Mẫu code: github.com/SamsungDS/exynos-eco (app ENN: DenseNet, DETR, DDRNet, PoseNet, Real-ESRGAN, MiDaS).
6. ENNDelegate: github.com/Samsung/ENNDelegate là file tự giải nén; chạy `ENNDelegate_<version>_archive`, đồng ý license,
   lấy thư viện. Header/signature thật của ENN native API nằm trong SDK; adapter `EnnNativeBackend` ở `core-enn`
   để trống đúng chỗ đó.

## Đường 3: ExecuTorch Samsung backend
- Chỉ hỗ trợ E9955 và E9965. Phải build ExecuTorch từ source với `-DEXECUTORCH_BUILD_EXYNOS=ON` và cần Exynos AI LiteCore SDK.
- Export: `EnnPartitioner` + `gen_samsung_backend_compile_spec("E9965")` → `to_edge_transform_and_lower`.
- Hỗ trợ FP16, int8/uint8 và int16/uint16 static quantization.

## Cái app dùng ngay
`core-enn` ưu tiên: ENN native (nếu bạn gắn SDK) → LiteRT NPU → LiteRT GPU → CPU → tắt NN, dùng flow GPU.
