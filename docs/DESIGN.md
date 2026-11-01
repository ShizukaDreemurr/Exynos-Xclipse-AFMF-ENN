# Thiết kế giao diện FluidFrames

Giao diện làm lại theo tinh thần **One UI** (Samsung) nhưng là thiết kế gốc: **không dùng logo, tên sản phẩm hay
tài sản thương hiệu của Samsung / Exynos / Xclipse**. Xclipse chỉ là nguồn cảm hứng cho hình "nhật thực"
(eclipse) – biểu tượng của app tự vẽ bằng code.

## Nguyên tắc lấy từ One UI (developer.samsung.com/one-ui)
- **Vùng xem (viewing area) ở trên – vùng thao tác (interaction area) ở dưới**: tiêu đề lớn căn giữa, lề rộng, không đặt
  điều khiển cảm ứng trong vùng xem; mọi nút nằm trong tầm ngón cái.
- Thẻ (card) bo góc lớn; nút dạng viên thuốc (pill); mật độ thoáng; công tắc đặt ở góc phải dưới thẻ khi có thể.
- Lề hai bên 24dp; điều khiển cao tối thiểu 48dp; chữ phải co giãn tới 200% không bị cắt.
- Chế độ tối/sáng theo hệ thống; thông báo trạng thái hiển thị bền (không dùng toast thoáng qua cho xác nhận).
- Mọi tác vụ có đường thay thế không dùng cử chỉ.

## Điểm mới của One UI 8.5 được áp dụng (theo các bản xem trước)
- Bo góc nhiều hơn, thanh điều hướng dưới dạng **viên thuốc nổi**; ô tìm kiếm/thao tác dời xuống dưới.
- Hiệu ứng mờ (frosted) nhẹ cho lớp nổi (thanh điều hướng, bảng overlay).
- Hiển thị số thay vì biểu tượng khi có thể (ví dụ phần trăm, ms).

## Bảng màu "Eclipse" (gốc, không phải token chính thức của Samsung)
| Vai trò | Tối | Sáng |
|---|---|---|
| Nền | #0A0C12 | #F2F3F8 |
| Thẻ | #141824 | #FFFFFF |
| Thẻ phụ | #1C2232 | #E9EBF3 |
| Viền | #2A3146 | #D6DAE6 |
| Chữ | #F3F5FA | #12141C |
| Chữ phụ | #9AA3B8 | #5A6174 |
| Nhấn 1 (tím) | #7C6CFF | #5B49F0 |
| Nhấn 2 (cyan) | #35D8FF | #0AA6D6 |
| Tốt / Cảnh báo / Lỗi | #3DDC97 / #FFC857 / #FF6B7A | #17A672 / #B77700 / #D6334A |

## Cấu trúc màn hình
1. **Trang chủ** – nhật thực + trạng thái, nút Bắt đầu/Dừng, danh sách thiết lập, số liệu trực tiếp khi đang chạy.
2. **Cài đặt** – preset (Cân bằng / Chất lượng / Tiết kiệm), backend, độ phân giải, HUD, thử nghiệm.
3. **NPU** – chẩn đoán ENN/NPU, đo tốc độ, sao chép/chia sẻ báo cáo.
4. **Overlay trong game** – bong bóng nhật thực kéo được, bảng điều khiển bo góc, HUD dạng viên thuốc.

Nguồn: developer.samsung.com/one-ui (overview, basic layout), bài xem trước One UI 8.5 của Android Authority.
