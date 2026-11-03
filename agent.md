# AGENT DIRECTIVE: QUẢN LÝ GIT & VERSION LIÊN TỤC

> **BẮT BUỘC DÀNH CHO MỌI AI AGENT / ASSISTANT:**
> Tài liệu này thiết lập các quy tắc quản trị bắt buộc khi làm việc trong repository `WinHello`. AI Agent phải **chủ động thực hiện tự động** mọi quy trình Git và quản lý Version trong mỗi lượt làm việc mà **KHÔNG ĐƯỢC CHỜ NGƯỜI DÙNG PHẢI NHẮC LẠI**.

---

## 1. QUẢN LÝ VERSION LIÊN TỤC (CONTINUOUS VERSIONING)

### 1.1. Nguyên tắc Semantic Versioning (`MAJOR.MINOR.PATCH`)
Mỗi khi có thay đổi trong mã nguồn, Agent **phải tự động đánh giá và tăng phiên bản**:
- **Tăng `PATCH` (x.y.Z+1)**: Khi sửa lỗi (bugfix), cải tiến UI/UX nhỏ, tối ưu hiệu năng hoặc sửa logic mà không thêm tính năng mới.
- **Tăng `MINOR` (x.Y+1.0)**: Khi thêm tính năng mới, thêm giao thức kết nối, thêm endpoint HTTP/LAN, thêm màn hình/tính năng cho app Android hoặc CLI.
- **Tăng `MAJOR` (X+1.0.0)**: Khi có thay đổi phá vỡ tính tương thích ngược (breaking changes).

### 1.2. Đồng bộ tệp Version
Tệp nguồn chân lý (Single Source of Truth) là **`VERSION`** ở thư mục gốc:
1. Cập nhật chuỗi phiên bản trong tệp `VERSION` (ví dụ: `1.1.0`).
2. Bản build Android (`apps/android/app/build.gradle.kts`) tự động phân tích `VERSION` thành `versionName` và `versionCode` (`major * 1.000.000 + minor * 1.000 + patch`).
3. Nếu thay đổi có liên quan đến bộ cài đặt Windows, cập nhật đồng bộ tham số version trong `packaging/WinHello.iss` và `scripts/build-release.ps1`.

---

## 2. QUẢN LÝ GIT LIÊN TỤC (CONTINUOUS GIT MANAGEMENT)

### 2.1. Trạng thái Working Tree
- Trước khi thực hiện thay đổi: Luôn chạy `git status` để nắm các tệp đang chỉnh sửa.
- Không để tồn tại các tệp rác, tệp biên dịch tạm thời không được theo dõi. Thường xuyên cập nhật `.gitignore` cho các artifact sinh ra (`publish/`, `build/`, `*.apk`, `*.exe`, `.tmp`, v.v.).

### 2.2. Tự động Commit sau mỗi Task
Sau khi hoàn thành một nhiệm vụ, tính năng hoặc sửa lỗi và đã xác minh bản build chạy thành công:
- **Bắt buộc tự động thực hiện commit** vào Git với thông điệp theo chuẩn Conventional Commits:
  - `feat: <mô tả>` cho tính năng mới.
  - `fix: <mô tả>` cho sửa lỗi.
  - `refactor: <mô tả>` khi tối ưu/cấu trúc lại code.
  - `chore: <mô tả>` cho việc dọn dẹp, cập nhật cấu hình/tài liệu.
- Định dạng commit nên kèm phiên bản mới (ví dụ: `feat(android): ho tro mo khoa MasterKeyVault qua LAN (v1.1.0)`).

---

## 3. QUY TRÌNH KIỂM TRA & XÁC MINH (VERIFICATION)

Trước khi coi một tác vụ là hoàn thành:
1. **Kiểm tra biên dịch**:
   - Chạy `.\scripts\build-android.ps1` để xác nhận ứng dụng Android biên dịch thành công, không phát sinh lỗi hoặc cảnh báo nghiêm trọng.
   - Nếu có can thiệp vào CLI/Agent Windows: Chạy `dotnet build` hoặc script build tương ứng.
2. **Báo cáo kết quả**:
   - Luôn nêu rõ phiên bản mới (ví dụ `v1.1.0`), commit ID, đường dẫn tệp build hoàn chỉnh (file `.apk` hoặc `.exe`) và hướng dẫn thao tác cụ thể cho người dùng.
