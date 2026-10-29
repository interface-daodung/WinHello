# Cài đặt WinHello

Chạy các lệnh từ thư mục gốc của repo. Để cài hoặc cập nhật bản đang chạy, mở PowerShell 64-bit bằng **Run as Administrator**. Luôn giữ một phương thức đăng nhập Windows dự phòng.

## Cài đặt và ghép điện thoại

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-winhello.ps1 -IUnderstandThisChangesLogonUI
```

Script build Credential Provider và LAN Agent, tạo chứng chỉ TLS cùng TOTP secret, cài Windows Service và firewall rule cho mạng Private, rồi mở QR enrollment. Trong app Android, chọn **Scan PC setup QR** và **Confirm TOTP enrollment**. Script chỉ đăng ký tile sau khi ghép thành công. Mặc định chờ 180 giây; có thể đặt `-EnrollmentTimeoutSeconds 300`.

Khi được hỏi, nhập mật khẩu Windows của tài khoản cần đăng nhập. Mật khẩu được mã hóa trong `C:\ProgramData\WinHelloLanDemo`; sau khi điện thoại duyệt, LAN Agent trả nó qua named pipe chỉ cho LocalSystem và Administrators để Credential Provider gửi cho Windows. TOTP và vân tay trên điện thoại dùng để duyệt yêu cầu, còn Windows xác thực bằng mật khẩu đã lưu.

## Cập nhật bản đang cài

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\update-binaries.ps1
```

Script build từ `apps/lan-agent/` và `apps/credential-provider/`, dừng service, thay file trong `C:\Program Files\WinHelloLanDemo`, rồi chạy lại service. Việc chuyển thư mục nguồn trong repo không tự thay đổi bản đang cài.

## Build riêng

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-credential-provider.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-lan-agent.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

APK debug: `apps\android\app\build\outputs\apk\debug\app-debug.apk`.

## Đặt lại ghép đôi hoặc gỡ cài đặt

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-phone-enrollment.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\uninstall-winhello.ps1
```

Uninstaller dừng và xóa service trước khi xóa Credential Provider, firewall rule, chứng chỉ và dữ liệu ghép đôi. Nếu service đang chờ Windows xóa, khởi động lại rồi chạy lại lệnh gỡ.
