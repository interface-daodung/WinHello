# Mục tiêu dự án WinHello: Android → Windows qua LAN

> Tài liệu kế hoạch ban đầu, giữ lại để tham khảo. Trạng thái hiện tại và cấu trúc monorepo nằm trong [README.md](README.md) và [docs/architecture.md](docs/architecture.md).

## Mục tiêu

Tạo demo đăng nhập Windows được khởi tạo từ widget Android, dùng chung LAN. Điện thoại tìm PC bằng broadcast UDP, quét QR enrollment khi cài, lưu TOTP an toàn, rồi xác nhận yêu cầu bằng TOTP và sinh trắc học.

**Mục tiêu unlock Windows thật chưa đạt.** Phone approval/TOTP chỉ xác minh người dùng đã duyệt; Windows vẫn cần một cơ chế xác thực được Windows/LSA chấp nhận. Không gửi hoặc lưu mật khẩu Windows trong app.

BLE để phiên bản sau. Cloud relay, internet access, quản lý mật khẩu Windows và triển khai production chưa thuộc phạm vi hiện tại.

## Trạng thái hiện tại (2026-10-28)

| Thành phần | Đã làm | Đã xác minh | Chưa xác minh / còn thiếu |
|---|---|---|---|
| Windows LAN Agent | .NET 10 Windows Service host, UDP ping/pong, HTTPS pin, enrollment/approval TOTP, QR generation, named pipe, ghép đôi và thu hồi phone key | Debug build và self-contained win-x64 publish thành công | Chưa cài hoặc chạy service trên VM; chưa thử LAN giữa PC và phone |
| Credential Provider | C++/COM tile theo trạng thái service, polling để cập nhật LogonUI, yêu cầu phone approval | DLL build thành công bằng LLVM-MinGW/CMake/Ninja | Chưa đăng ký hoặc chạy trong LogonUI; chưa xác minh tile runtime; không submit Windows credential |
| Android app/widget | QR scan, lưu TOTP mã hóa bằng Android Keystore, UDP discovery, TOTP, sinh trắc học cho chữ ký, thu hồi enrollment | Debug APK build thành công | Chưa cài APK lên thiết bị; chưa thử camera, widget, Wi-Fi discovery hay biometric trên phone thật |
| Cài đặt/gỡ | Script build/publish, install có QR và timeout, rollback, uninstall, reset TOTP và revoke | PowerShell scripts parse không lỗi cú pháp | Chưa chạy install/uninstall; chưa xác minh rollback, dọn service/registry/firewall/certificate trên Windows VM |
| Windows authentication | Chưa triển khai | Chưa có | Cần xác định account type và phương thức xác thực được Windows chấp nhận; hiện tại demo không mở khóa được account |

## Luồng demo dự kiến

1. Installer build DLL và LAN Agent, tạo certificate TLS, TOTP secret, cài service và firewall rules cho Private profile.
2. Installer mở QR. Android quét QR, mã hóa TOTP bằng Android Keystore, tìm PC bằng một broadcast ping và gửi TOTP để xác nhận enrollment.
3. Chỉ sau enrollment mới đăng ký Credential Provider. Provider hỏi service tạo challenge; Android xác nhận bằng sinh trắc học, tạo chữ ký và gửi cùng TOTP.
4. Service xác thực proof và provider hiển thị kết quả demo. Luồng kết thúc ở phone approval; chưa phải Windows logon/unlock.

## Việc đã hoàn thành trong mã

- **LAN protocol:** UDP ping/pong; HTTPS pinned certificate; TOTP 6 số RFC 6238; challenge ngẫu nhiên, hết hạn, chữ ký Android ECDSA và thu hồi phone key.
- **PC enrollment:** secret ngẫu nhiên theo PC; QR tạm có PC ID/TOTP/TLS pin; service config và pairing files trong ProgramData với ACL SYSTEM/Administrators.
- **Android:** widget home-screen; app Java; ZXing QR scanner; TOTP mã hóa AES-GCM bằng Android Keystore; private signing key được bảo vệ bằng biometric mạnh.
- **Windows tile:** Credential Provider native x64; tile chỉ được liệt kê khi named pipe trả trạng thái service sẵn sàng.
- **Cài/gỡ:** script cài có xác nhận rõ việc thay đổi LogonUI, chờ enrollment, rollback khi lỗi; gỡ service trước, rồi dọn provider registry, firewall, certificate và dữ liệu PC.
- **Toolchain:** VS Code, LLVM-MinGW UCRT, CMake, Ninja, .NET 10, JDK 17, Gradle 8.13 và Android SDK CLI. Không dùng Visual Studio 2022/workload.

## Việc chưa làm

1. Cài APK và chạy toàn bộ enrollment/approval trên thiết bị Android thật cùng PC trong cùng LAN.
2. Cài trong VM Windows: xác minh service tự chạy, tile xuất hiện/biến mất theo trạng thái service, pairing, TOTP, biometric, restart và uninstall sạch.
3. Xác nhận Windows version/build, architecture và loại account (local/Microsoft/domain/Entra).
4. Chọn và triển khai cơ chế đăng nhập Windows thực sự tương thích với loại account đó. Không được coi TOTP hoặc Credential Provider tile là đủ để xác thực Windows.
5. Thêm manual end-to-end checklist, log chẩn đoán và kiểm tra phục hồi sau sự cố cài đặt.
6. BLE, code signing/release signing, MSI/enterprise deployment và hardening production.

## Tiêu chí chấp nhận cho demo LAN

- Android quét QR enrollment; service chỉ đánh dấu paired sau khi TOTP hợp lệ.
- Một broadcast ping tìm được agent đang chạy; agent dừng thì không còn trả pong và tile biến mất sau lần cập nhật.
- Phone approval yêu cầu biometric và chữ ký challenge; TOTP sai, challenge hết hạn hoặc chữ ký sai phải bị từ chối.
- Reset xoay secret và vô hiệu enrollment cũ; uninstall xóa service, provider registration, firewall rules, certificate và PC-side secret/pairing data.
- Built-in Windows recovery providers không bị tắt. Test trong VM trước khi thử nơi khác.
- Tài liệu và giao diện ghi rõ demo chưa đăng nhập/mở khóa Windows.

## Bước tiếp theo

1. Cài APK lên điện thoại và thử quét QR/biometric trên LAN test.
2. Tạo VM Windows có snapshot và phương thức đăng nhập dự phòng; chạy installer, checklist runtime, rồi uninstall.
3. Chốt account type và Windows authentication design trước khi code phần mở khóa thật.

Chi tiết build/cài/gỡ: [README.md](README.md) và [INSTALL.md](INSTALL.md). BLE vẫn nằm ngoài milestone hiện tại.

