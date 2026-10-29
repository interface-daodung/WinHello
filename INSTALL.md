# Cài đặt và gỡ WinHello LAN Demo

> Đây là demo ghép đôi TOTP và duyệt bằng điện thoại. Nó chưa xác thực tài khoản Windows và không mở khóa Windows. Chỉ đăng ký tile trong VM thử nghiệm, sau khi bảo đảm còn phương thức đăng nhập dự phòng.

## Cài đặt

Mở **PowerShell 64-bit bằng Run as administrator**, chuyển đến thư mục dự án rồi chạy:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-winhello.ps1 -IUnderstandThisChangesLogonUI
```

Installer tự build DLL, publish LAN Agent self-contained, tạo TLS certificate và secret TOTP, cài Windows Service, thêm firewall rule chỉ cho profile Private, rồi mở QR enrollment. Quét QR trong app Android bằng **Scan PC setup QR**; tiếp đó chọn PC vừa tìm thấy và bấm **Confirm TOTP enrollment**. Installer chỉ đăng ký Credential Provider khi TOTP enrollment thành công. Mặc định chờ 180 giây; có thể đổi timeout từ 30 đến 900 giây bằng `-EnrollmentTimeoutSeconds 300`.

QR chỉ chứa PC ID, secret TOTP và TLS pin; secret không nằm trong tham số dòng lệnh. File QR/payload tạm được tạo trong `C:\ProgramData\WinHelloLanDemo` với ACL chỉ cho SYSTEM và Administrators, rồi xóa sau enrollment. TOTP secret và pairing record vẫn nằm trong thư mục đó để service dùng; uninstaller xóa chúng.

## Gỡ cài đặt

Từ PowerShell 64-bit chạy bằng Administrator:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\uninstall-winhello.ps1
```

Uninstaller dừng và xóa Windows Service trước; chỉ khi Service đã biến mất mới xóa Credential Provider registry keys, hai firewall rule, TLS certificate đúng thumbprint, thư mục cài đặt và dữ liệu TOTP/pairing. Nếu Windows giữ Service ở trạng thái pending deletion, script sẽ giữ lại file để tránh xóa executable đang được dùng; khởi động lại Windows rồi chạy lại lệnh gỡ.

TOTP đã quét trên điện thoại được mã hóa bằng Android Keystore. Gỡ app Android sẽ xóa dữ liệu app; hoặc dùng **Remove phone pairing from this PC** trong app để thu hồi public key trên PC và xóa secret/key cục bộ. Nếu mất điện thoại, đổi secret và ghép đôi máy mới bằng QR mới:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\reset-phone-enrollment.ps1
```

Lệnh reset xoay TOTP secret, thu hồi enrollment cũ, mở QR mới và khôi phục cấu hình trước đó nếu enrollment mới hết thời gian chờ.

## Build thủ công

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-credential-provider.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-lan-agent.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

APK debug: `android\app\build\outputs\apk\debug\app-debug.apk`.
