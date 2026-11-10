# CameraStampGPS

App Android (dùng cá nhân) chụp ảnh có in dấu: giờ thực (HH:mm:ss), thứ/ngày, địa chỉ, tọa độ GPS, sai số, độ cao, tên đơn vị, ghi chú, logo.

## Build APK bằng GitHub Actions
1. Tạo repo mới trên GitHub, ví dụ `CameraStampGPS`.
2. Tải toàn bộ nội dung thư mục này lên nhánh `main` (giữ nguyên thư mục `.github/workflows`).
3. Mở tab **Actions** → workflow **Build APK** tự chạy (hoặc bấm *Run workflow*).
4. Khi xong, tải artifact **CameraStampGPS-apk** → giải nén → cài `app-release.apk` lên điện thoại.

## Thông số
- Android 8.0+ (minSdk 26), Kotlin, CameraX 1.3.4, Google Play Services Location.
- Ảnh lưu tại `Pictures/CameraStampGPS`, kèm EXIF (thời gian + GPS).
