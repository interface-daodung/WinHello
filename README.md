# WinHello LAN demo

Project goal and milestones: [LAN_DEMO_PLAN.md](LAN_DEMO_PLAN.md). Installation, phone enrollment, reset, and removal commands: [INSTALL.md](INSTALL.md). BLE is explicitly deferred.

## Mục tiêu hiện tại

Tạo demo LAN trong đó Android widget tìm PC bằng một UDP broadcast, quét QR enrollment khi cài, dùng TOTP và sinh trắc học để duyệt yêu cầu. Windows Credential Provider chỉ hiện tile khi LAN Agent hoạt động.

**Demo hiện chưa thể đăng nhập hoặc mở khóa tài khoản Windows.** TOTP và chữ ký điện thoại chỉ xác nhận sự chấp thuận; Credential Provider chưa gửi thông tin xác thực mà Windows/LSA chấp nhận.

## Đã làm

- LAN Agent .NET 10 có UDP ping/pong, HTTPS pinning, TOTP enrollment/approval, tạo QR, named pipe và tích hợp Windows Service.
- Credential Provider C++/COM có tile theo trạng thái LAN Agent và polling cập nhật trạng thái.
- Android Java app/widget có quét QR, lưu TOTP mã hóa bằng Android Keystore, LAN discovery, TOTP và xác nhận sinh trắc học cho chữ ký challenge, cùng thao tác thu hồi phone pairing.
- Có script cài đặt/rollback, gỡ cài đặt, reset enrollment, build .NET, build DLL và build APK.

## Đã build, chưa chạy thực tế

- Windows Credential Provider DLL đã biên dịch bằng LLVM-MinGW, CMake và Ninja.
- LAN Agent đã build và publish self-contained win-x64.
- Android debug APK đã build tại `android/app/build/outputs/apk/debug/app-debug.apk`.
- Script PowerShell đã kiểm tra cú pháp.
- Installer/uninstaller **chưa được chạy**; APK **chưa được cài lên điện thoại**. Chưa xác minh thực tế việc quét QR, TOTP, UDP qua Wi-Fi, tile trong LogonUI hoặc gỡ cài đặt trên VM.

## Chưa làm / việc tiếp theo

1. Thử cài, enrollment, hiện/ẩn tile và gỡ sạch trên VM Windows; xác minh Android trên điện thoại thật.
2. Kiểm tra rollback khi timeout/lỗi, khôi phục đăng nhập, firewall, service restart và thu hồi điện thoại.
3. Xác định loại tài khoản Windows và thiết kế cơ chế xác thực được Windows chấp nhận. Chỉ sau đó mới triển khai unlock thật; không lưu hoặc gửi mật khẩu Windows.
4. BLE, ký số phát hành, bộ cài MSI và triển khai production chưa thuộc bản này.

## Build

Chạy trong terminal VS Code:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-lan-agent.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-credential-provider.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

Không đăng ký provider trên máy dùng hàng ngày. Chỉ cài trong VM thử nghiệm và giữ lại phương thức đăng nhập Windows dự phòng.

