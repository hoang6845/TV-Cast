# Thay đổi Flow Casting

## Tóm tắt các thay đổi

### 1. Không tự động ngắt kết nối Cast khi thoát màn
**Các file đã sửa:**
- `CastMediaFragment.kt` - Xóa dialog yêu cầu ngắt kết nối khi back
- `CastWebFragment.kt` - Xóa dialog yêu cầu ngắt kết nối khi back
- `CastYoutubeFragment.kt` - Xóa dialog yêu cầu ngắt kết nối khi back
- `IPTVFragment.kt` - Xóa dialog yêu cầu ngắt kết nối khi back
- `ScreenMirroringFragment.kt` - Thay đổi `endSession = true` thành `endSession = false`

**Kết quả:** Khi thoát các màn cast, ứng dụng sẽ chỉ dừng casting nhưng vẫn giữ kết nối với device. Người dùng có thể tiếp tục sử dụng kết nối này ở các màn khác.

### 2. Cập nhật nút "Connect TV" ở Home
**Các file đã sửa:**
- `fragment_home.xml` - Thêm ID cho TextView và ImageView để có thể thay đổi text/màu
- `HomeFragment.kt` - Thêm logic:
  - Lắng nghe trạng thái Cast session
  - Cập nhật text hiển thị tên device khi đã kết nối
  - Thay đổi màu nút khi kết nối (xanh lá) vs chưa kết nối (màu mặc định)
  - Click vào nút sẽ mở bottom sheet khác nhau tùy trạng thái

### 3. Bottom Sheet hiển thị thông tin device đã kết nối
**Các file mới:**
- `layout_home_connected_device_sheet.xml` - Layout bottom sheet
- `bg_disconnect_button.xml` - Background nút disconnect (đỏ)
- `bg_button_secondary.xml` - Background nút cancel (xám)
- `bg_circle_primary.xml` - Background icon TV (xanh)
- `bg_connect_active.xml` - Background nút connect khi đã kết nối (xanh lá)
- `bg_bottom_sheet.xml` - Background của bottom sheet
- `bg_circle.xml` - Background dot trạng thái

**Các string mới:**
- `text_connected_device` - "Connected Device"
- `text_connected_to` - "Connected to %1$s"

**Chức năng:**
- Hiển thị tên device đang kết nối
- Hiển thị thông tin model của device
- Có nút "Disconnect" để ngắt kết nối
- Có nút "Cancel" để đóng dialog
- Hiển thị dot màu xanh lá cho trạng thái kết nối

## Cách hoạt động

### Khi chưa kết nối:
1. Nút "Connect TV" hiển thị màu mặc định (xám)
2. Text: "Connect TV"
3. Click vào → mở bottom sheet danh sách devices

### Khi đã kết nối:
1. Nút "Connect TV" đổi màu xanh lá (#84FF6A)
2. Text đổi thành tên device (ví dụ: "Living Room TV")
3. Icon và text đổi màu đen để dễ đọc trên nền xanh
4. Click vào → mở bottom sheet thông tin device kết nối

### Khi thoát màn cast:
1. **Trước:** Hiện dialog hỏi có muốn ngắt kết nối không, nếu đồng ý sẽ ngắt cast session
2. **Sau:** Chỉ dừng casting, GIỮ NGUYÊN kết nối với device, quay về màn trước

## Lợi ích
- Người dùng không phải kết nối lại mỗi lần chuyển màn
- Trải nghiệm mượt mà hơn khi sử dụng nhiều tính năng cast
- Dễ dàng quản lý kết nối từ màn Home
- Tiết kiệm thời gian khi muốn cast nhiều loại nội dung khác nhau
