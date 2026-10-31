# Kế hoạch kiểm thử (trên Exynos 2600)

Mọi thứ dưới đây **chưa chạy** — code chưa từng được build trên thiết bị. Làm theo thứ tự; dừng ở bước đầu tiên lỗi.

## 0. Build
1. Push lên GitHub, xem CI (`.github/workflows/build.yml`). Lỗi biên dịch shader/CMake/Kotlin sẽ hiện ở đây trước tiên.
2. Cài APK debug. Android 13+ sideload: Thông tin ứng dụng → ⋮ → "Cho phép cài đặt bị hạn chế", rồi bật Trợ năng.

## 1. Khói
- Mở app → Bật Trợ năng → Bắt đầu → cấp quyền ghi màn hình toàn màn hình.
- Kỳ vọng: HUD hiện chữ; không crash. Xem `adb logcat -s ff-native FramePipeline LiteRtBackend ProjectionService`.
- Lỗi "Thiếu extension" → GPU/driver không đủ Vulkan AHB; pipeline không chạy được (không có đường lùi).

## 2. Vòng lặp phản hồi (RỦI RO LỚN NHẤT)
MediaProjection có thể chụp luôn overlay của chính mình → hình lồng vô hạn/nhấp nháy.
- Mở video 30 fps. Quan sát: hình ổn định? HUD `src` ≈ 30?
- Nếu lồng hình/đen: bật menu → "Overlay secure (thử nghiệm)" và lặp lại. Ghi lại kết quả (bị bỏ qua hay đen).
- Nếu cả hai đều hỏng, kiến trúc "overlay toàn màn hình" không dùng được trên máy này; cần hướng khác.

## 3. Cảm ứng
- Có Trợ năng: chạm/vuốt xuyên qua overlay vào game, không trễ/mất sự kiện. Menu bong bóng "FF" vẫn bấm được.
- Không Trợ năng (alpha 0.8): kiểm tra cảm ứng xuyên qua và độ trộn hình.

## 4. Chất lượng
Nguồn 30 fps: chuyển cảnh cắt, văn bản cuộn, HUD cố định, hạt/khói, vật thể che nhau. Ghi nhận ảo ảnh (ghosting), méo HUD,
nhấp nháy ở scene cut. So sánh GPU block-match vs NN (cần model đã huấn luyện).

## 5. Hiệu năng (HUD)
Mục tiêu tham khảo (chưa đo): `work` < 0.5 × chu kỳ nguồn (≈16 ms ở 30 fps), `drop` < 1%, `out` ≈ 60 khi `src` ≈ 30.
Chạy 15–30 phút: theo dõi `therm`, tier tự hạ, NN→GPU, passthrough.

## 6. Độ bền
Xoay màn hình, khóa/mở, chuyển app, rút/cắm tai nghe, thu hồi quyền ghi màn hình, Dừng từ notification.
