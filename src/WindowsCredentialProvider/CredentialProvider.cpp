#include <Windows.h>
#include <credentialprovider.h>
#include <new>

namespace
{
    /* ── KERB structures for building Windows authentication blobs ── */

    struct WinHelloUnicodeString
    {
        USHORT Length;
        USHORT MaximumLength;
        PWSTR  Buffer;
    };

    struct WinHelloKerbLogon
    {
        ULONG  MessageType;          // 2 = Interactive, 7 = WorkstationUnlock
        WinHelloUnicodeString LogonDomainName;
        WinHelloUnicodeString UserName;
        WinHelloUnicodeString Password;
    };

    struct WinHelloKerbUnlockLogon
    {
        WinHelloKerbLogon Logon;
        LUID              LogonId;   // {0,0} — Windows fills it for unlock
    };

    /* ── Constants ── */

    constexpr CLSID CLSID_WinHelloCredentialProvider =
        {0x82e53a3d, 0x6c08, 0x42b6, {0x9d, 0x47, 0x8d, 0x86, 0xb2, 0x81, 0xa5, 0x10}};

    constexpr wchar_t kPipePath[] = L"\\\\.\\pipe\\WinHello.LanAgent";
    constexpr DWORD kFieldImage  = 0;
    constexpr DWORD kFieldTitle  = 1;
    constexpr DWORD kFieldStatus = 2;
    constexpr DWORD kFieldSubmit = 3;
    constexpr DWORD kFieldCount  = 4;
    volatile LONG g_moduleReferences = 0;

    const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFieldDescriptors[kFieldCount] = {
        {kFieldImage,  CPFT_TILE_IMAGE,     const_cast<LPWSTR>(L"WinHello Phone icon"),       GUID_NULL},
        {kFieldTitle,  CPFT_LARGE_TEXT,     const_cast<LPWSTR>(L"WinHello Phone"),            GUID_NULL},
        {kFieldStatus, CPFT_SMALL_TEXT,     const_cast<LPWSTR>(L"LAN service is ready"),      GUID_NULL},
        {kFieldSubmit, CPFT_SUBMIT_BUTTON,  const_cast<LPWSTR>(L"Unlock with phone approval"), GUID_NULL}
    };

    /* ── Helpers ── */

    LPWSTR DuplicateString(PCWSTR value)
    {
        if (value == nullptr) return nullptr;
        const auto chars = lstrlenW(value) + 1;
        auto* copy = static_cast<LPWSTR>(CoTaskMemAlloc(static_cast<SIZE_T>(chars) * sizeof(wchar_t)));
        if (copy != nullptr)
            CopyMemory(copy, value, static_cast<SIZE_T>(chars) * sizeof(wchar_t));
        return copy;
    }

    /* Standard blocking query to the LAN Agent named pipe */
    bool QueryLanAgent(PCWSTR command, wchar_t* response, DWORD responseChars)
    {
        if (response == nullptr || responseChars < 2)
            return false;

        HANDLE pipe = INVALID_HANDLE_VALUE;
        for (int attempt = 0; attempt < 5; ++attempt)
        {
            pipe = CreateFileW(kPipePath, GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                               OPEN_EXISTING, 0, nullptr);
            if (pipe != INVALID_HANDLE_VALUE)
                break;

            if (GetLastError() == ERROR_PIPE_BUSY)
                WaitNamedPipeW(kPipePath, 300);
            else
                Sleep(50);
        }
        if (pipe == INVALID_HANDLE_VALUE)
            return false;

        const auto requestLength = lstrlenW(command);
        char request[32]{};
        if (requestLength + 2 > ARRAYSIZE(request))
        {
            CloseHandle(pipe);
            return false;
        }

        for (DWORD i = 0; i < static_cast<DWORD>(requestLength); ++i)
            request[i] = static_cast<char>(command[i]);
        request[requestLength] = '\n';
        const auto requestBytes = static_cast<DWORD>(requestLength + 1);

        DWORD written = 0;
        if (!WriteFile(pipe, request, requestBytes, &written, nullptr) || written != requestBytes)
        {
            CloseHandle(pipe);
            return false;
        }

        char bytes[1024]{};
        DWORD received = 0;
        bool success = false;
        if (ReadFile(pipe, bytes, sizeof(bytes) - 1, &received, nullptr) && received > 0)
        {
            bytes[received] = '\0';
            const int decoded = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, bytes,
                                                     static_cast<int>(received), response,
                                                     static_cast<int>(responseChars - 1));
            if (decoded <= 0)
            {
                CloseHandle(pipe);
                return false;
            }
            response[decoded] = L'\0';
            DWORD end = static_cast<DWORD>(decoded);
            while (end > 0 && (response[end - 1] == L'\n' || response[end - 1] == L'\r'))
                response[--end] = L'\0';
            success = end > 0;
        }

        CloseHandle(pipe);
        return success;
    }

    /* ── Negotiate auth-package ID (cached) ── */

    ULONG ResolveNegotiateAuthPackage()
    {
        static ULONG  cached   = ULONG_MAX;
        static bool   resolved = false;
        if (resolved) return cached;

        HMODULE secur32 = LoadLibraryW(L"secur32.dll");
        if (!secur32) return ULONG_MAX;

        struct LsaStr { USHORT Length; USHORT MaximumLength; PCHAR Buffer; };
        using ConnectFn = LONG (NTAPI*)(PHANDLE);
        using LookupFn  = LONG (NTAPI*)(HANDLE, LsaStr*, PULONG);
        using CloseFn   = LONG (NTAPI*)(HANDLE);

        auto connectFn = reinterpret_cast<ConnectFn>(GetProcAddress(secur32, "LsaConnectUntrusted"));
        auto lookupFn  = reinterpret_cast<LookupFn>(GetProcAddress(secur32, "LsaLookupAuthenticationPackage"));
        auto closeFn   = reinterpret_cast<CloseFn>(GetProcAddress(secur32, "LsaDeregisterLogonProcess"));

        if (connectFn && lookupFn && closeFn)
        {
            HANDLE lsa = nullptr;
            if (connectFn(&lsa) == 0)
            {
                char negotiate[] = "Negotiate";
                LsaStr name{};
                name.Buffer        = negotiate;
                name.Length         = 9;
                name.MaximumLength  = 10;
                if (lookupFn(lsa, &name, &cached) == 0)
                    resolved = true;
                closeFn(lsa);
            }
        }
        FreeLibrary(secur32);
        return cached;
    }

    /* ── Parse CREDENTIAL|domain|username|password ── */

    bool ParseCredentialResponse(const wchar_t* resp,
        wchar_t* domain,   DWORD domainMax,
        wchar_t* username, DWORD usernameMax,
        wchar_t* password, DWORD passwordMax)
    {
        const wchar_t prefix[] = L"CREDENTIAL|";
        const DWORD prefixLen = 11;
        for (DWORD i = 0; i < prefixLen; ++i)
            if (resp[i] != prefix[i]) return false;

        const wchar_t* pos = resp + prefixLen;

        /* domain */
        const wchar_t* sep1 = nullptr;
        for (const wchar_t* p = pos; *p; ++p) { if (*p == L'|') { sep1 = p; break; } }
        if (!sep1) return false;
        DWORD len = static_cast<DWORD>(sep1 - pos);
        if (len >= domainMax) return false;
        for (DWORD i = 0; i < len; ++i) domain[i] = pos[i];
        domain[len] = L'\0';

        /* username */
        pos = sep1 + 1;
        const wchar_t* sep2 = nullptr;
        for (const wchar_t* p = pos; *p; ++p) { if (*p == L'|') { sep2 = p; break; } }
        if (!sep2) return false;
        len = static_cast<DWORD>(sep2 - pos);
        if (len >= usernameMax) return false;
        for (DWORD i = 0; i < len; ++i) username[i] = pos[i];
        username[len] = L'\0';

        /* password */
        pos = sep2 + 1;
        len = static_cast<DWORD>(lstrlenW(pos));
        if (len >= passwordMax) return false;
        for (DWORD i = 0; i < len; ++i) password[i] = pos[i];
        password[len] = L'\0';
        return true;
    }

    /* ── Pack KERB_INTERACTIVE_UNLOCK_LOGON serialization blob ── */

    bool PackKerbLogon(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario,
        PCWSTR domain, PCWSTR username, PCWSTR password,
        BYTE** outBuffer, DWORD* outSize)
    {
        const DWORD domainBytes   = static_cast<DWORD>(lstrlenW(domain))   * sizeof(WCHAR);
        const DWORD usernameBytes = static_cast<DWORD>(lstrlenW(username)) * sizeof(WCHAR);
        const DWORD passwordBytes = static_cast<DWORD>(lstrlenW(password)) * sizeof(WCHAR);
        const DWORD totalSize     = sizeof(WinHelloKerbUnlockLogon) + domainBytes + usernameBytes + passwordBytes;

        auto* buffer = static_cast<BYTE*>(CoTaskMemAlloc(totalSize));
        if (!buffer) return false;
        ZeroMemory(buffer, totalSize);

        auto* logon = reinterpret_cast<WinHelloKerbUnlockLogon*>(buffer);
        logon->Logon.MessageType = (scenario == CPUS_UNLOCK_WORKSTATION) ? 7u : 2u;

        BYTE* pos = buffer + sizeof(WinHelloKerbUnlockLogon);

        /* domain */
        logon->Logon.LogonDomainName.Length        = static_cast<USHORT>(domainBytes);
        logon->Logon.LogonDomainName.MaximumLength = static_cast<USHORT>(domainBytes);
        logon->Logon.LogonDomainName.Buffer        = reinterpret_cast<PWSTR>(static_cast<ULONG_PTR>(pos - buffer));
        CopyMemory(pos, domain, domainBytes);
        pos += domainBytes;

        /* username */
        logon->Logon.UserName.Length        = static_cast<USHORT>(usernameBytes);
        logon->Logon.UserName.MaximumLength = static_cast<USHORT>(usernameBytes);
        logon->Logon.UserName.Buffer        = reinterpret_cast<PWSTR>(static_cast<ULONG_PTR>(pos - buffer));
        CopyMemory(pos, username, usernameBytes);
        pos += usernameBytes;

        /* password */
        logon->Logon.Password.Length        = static_cast<USHORT>(passwordBytes);
        logon->Logon.Password.MaximumLength = static_cast<USHORT>(passwordBytes);
        logon->Logon.Password.Buffer        = reinterpret_cast<PWSTR>(static_cast<ULONG_PTR>(pos - buffer));
        CopyMemory(pos, password, passwordBytes);

        *outBuffer = buffer;
        *outSize   = totalSize;
        return true;
    }

    /* ══════════════════════════════════════════════════════════════
       Credential  — implements ICredentialProviderCredential2
       so Windows associates it with the current locked user SID!
       ══════════════════════════════════════════════════════════════ */

    class Credential final : public ICredentialProviderCredential2
    {
    public:
        Credential(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario, PCWSTR userSid)
            : scenario_(scenario),
              userSid_(DuplicateString(userSid))
        {
            InterlockedIncrement(&g_moduleReferences);
        }

        HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) override
        {
            if (object == nullptr)
                return E_POINTER;
            *object = nullptr;
            if (InlineIsEqualGUID(iid, __uuidof(IUnknown)) ||
                InlineIsEqualGUID(iid, __uuidof(ICredentialProviderCredential)) ||
                InlineIsEqualGUID(iid, __uuidof(ICredentialProviderCredential2)))
            {
                *object = static_cast<ICredentialProviderCredential2*>(this);
                AddRef();
                return S_OK;
            }
            return E_NOINTERFACE;
        }

        ULONG STDMETHODCALLTYPE AddRef() override
        {
            return static_cast<ULONG>(InterlockedIncrement(&references_));
        }

        ULONG STDMETHODCALLTYPE Release() override
        {
            const auto remaining = InterlockedDecrement(&references_);
            if (remaining == 0)
            {
                this->~Credential();
                HeapFree(GetProcessHeap(), 0, this);
            }
            return static_cast<ULONG>(remaining);
        }

        /* ── ICredentialProviderCredential2 ── */
        HRESULT STDMETHODCALLTYPE GetUserSid(LPWSTR* sid) override
        {
            if (sid == nullptr)
                return E_POINTER;
            *sid = DuplicateString(userSid_);
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE Advise(ICredentialProviderCredentialEvents* events) override
        {
            if (events_ != nullptr)
                events_->Release();
            events_ = events;
            if (events_ != nullptr)
                events_->AddRef();
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE UnAdvise() override
        {
            if (events_ != nullptr)
            {
                events_->Release();
                events_ = nullptr;
            }
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE SetSelected(BOOL* autoLogon) override
        {
            if (autoLogon == nullptr)
                return E_POINTER;
            *autoLogon = FALSE;
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE SetDeselected() override { return S_OK; }

        HRESULT STDMETHODCALLTYPE GetFieldState(DWORD fieldId, CREDENTIAL_PROVIDER_FIELD_STATE* state,
                                                 CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* interactive) override
        {
            if (state == nullptr || interactive == nullptr)
                return E_POINTER;
            if (fieldId >= kFieldCount)
                return E_INVALIDARG;
            *state = CPFS_DISPLAY_IN_BOTH;
            *interactive = fieldId == kFieldSubmit ? CPFIS_NONE : CPFIS_READONLY;
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetStringValue(DWORD fieldId, LPWSTR* value) override
        {
            if (value == nullptr)
                return E_POINTER;
            *value = nullptr;
            switch (fieldId)
            {
            case kFieldTitle:
                *value = DuplicateString(L"WinHello Phone");
                break;
            case kFieldStatus:
            {
                wchar_t status[512]{};
                if (QueryLanAgent(L"STATUS", status, ARRAYSIZE(status)))
                {
                    if (wcsstr(status, L"APPROVED|") != nullptr)
                        *value = DuplicateString(L"\x2705 Phone approved. Click the arrow to sign in.");
                    else if (wcsstr(status, L"PENDING|") != nullptr)
                        *value = DuplicateString(L"Waiting for phone approval. Tap Approve on your phone.");
                    else
                        *value = DuplicateString(L"Ready. Click the arrow to request phone approval.");
                }
                else
                {
                    *value = DuplicateString(L"LAN agent is starting\x2026 Click arrow to retry.");
                }
                break;
            }
            case kFieldSubmit:
                *value = DuplicateString(L"Unlock with phone approval");
                break;
            default:
                return E_INVALIDARG;
            }
            return *value == nullptr ? E_OUTOFMEMORY : S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetBitmapValue(DWORD fieldId, HBITMAP* bitmap) override
        {
            if (bitmap == nullptr)
                return E_POINTER;
            *bitmap = nullptr;
            if (fieldId != kFieldImage)
                return E_INVALIDARG;

            BITMAPINFO info{};
            info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
            info.bmiHeader.biWidth = 64;
            info.bmiHeader.biHeight = -64;
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = BI_RGB;
            void* bits = nullptr;
            HBITMAP image = CreateDIBSection(nullptr, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
            if (image == nullptr || bits == nullptr)
                return E_OUTOFMEMORY;

            auto* pixels = static_cast<DWORD*>(bits);
            for (int y = 0; y < 64; ++y)
            {
                for (int x = 0; x < 64; ++x)
                {
                    const bool phone = x >= 19 && x <= 44 && y >= 10 && y <= 53;
                    const bool screen = x >= 22 && x <= 41 && y >= 16 && y <= 45;
                    const bool button = x >= 30 && x <= 33 && y >= 48 && y <= 50;
                    pixels[y * 64 + x] = button || (phone && !screen) ? 0xFFFFFFFF :
                                         screen ? 0xFF244978 : 0xFF1A70C8;
                }
            }
            *bitmap = image;
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetCheckboxValue(DWORD, BOOL* checked, LPWSTR* label) override
        {
            if (checked != nullptr) *checked = FALSE;
            if (label != nullptr) *label = nullptr;
            return E_NOTIMPL;
        }

        HRESULT STDMETHODCALLTYPE GetSubmitButtonValue(DWORD fieldId, DWORD* adjacentTo) override
        {
            if (adjacentTo == nullptr)
                return E_POINTER;
            if (fieldId != kFieldSubmit)
                return E_INVALIDARG;
            *adjacentTo = kFieldStatus;
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetComboBoxValueCount(DWORD, DWORD* count, DWORD* selected) override
        {
            if (count != nullptr) *count = 0;
            if (selected != nullptr) *selected = 0;
            return E_NOTIMPL;
        }

        HRESULT STDMETHODCALLTYPE GetComboBoxValueAt(DWORD, DWORD, LPWSTR* value) override
        {
            if (value != nullptr) *value = nullptr;
            return E_NOTIMPL;
        }

        HRESULT STDMETHODCALLTYPE SetStringValue(DWORD, LPCWSTR) override { return E_NOTIMPL; }
        HRESULT STDMETHODCALLTYPE SetCheckboxValue(DWORD, BOOL) override { return E_NOTIMPL; }
        HRESULT STDMETHODCALLTYPE SetComboBoxSelectedValue(DWORD, DWORD) override { return E_NOTIMPL; }
        HRESULT STDMETHODCALLTYPE CommandLinkClicked(DWORD) override { return E_NOTIMPL; }

        /* ── GetSerialization ── */
        HRESULT STDMETHODCALLTYPE GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* response,
                                                   CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization,
                                                   LPWSTR* statusText,
                                                   CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon) override
        {
            if (response == nullptr || serialization == nullptr || statusText == nullptr || statusIcon == nullptr)
                return E_POINTER;
            ZeroMemory(serialization, sizeof(*serialization));
            *statusText = nullptr;
            *statusIcon = CPSI_NONE;

            /* 1. Request approval */
            wchar_t result[512]{};
            if (!QueryLanAgent(L"START", result, ARRAYSIZE(result)))
            {
                *response   = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
                *statusText = DuplicateString(L"Connecting to WinHello service\x2026 Click again.");
                *statusIcon = CPSI_WARNING;
                return *statusText ? S_OK : E_OUTOFMEMORY;
            }

            if (wcsstr(result, L"APPROVED|") != result)
            {
                *response   = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
                *statusText = DuplicateString(L"Request sent to phone. Open the app, approve with fingerprint, then click arrow.");
                *statusIcon = CPSI_NONE;
                return *statusText ? S_OK : E_OUTOFMEMORY;
            }

            /* 2. Retrieve decrypted credential from agent */
            wchar_t credential[1024]{};
            if (!QueryLanAgent(L"CREDENTIAL", credential, ARRAYSIZE(credential)) ||
                wcsstr(credential, L"CREDENTIAL|") != credential)
            {
                *response   = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
                *statusText = DuplicateString(L"Phone approved! No stored Windows credential was found.");
                *statusIcon = CPSI_WARNING;
                return *statusText ? S_OK : E_OUTOFMEMORY;
            }

            wchar_t domain[128]{}, username[128]{}, password[512]{};
            if (!ParseCredentialResponse(credential, domain, 128, username, 128, password, 512))
            {
                SecureZeroMemory(credential, sizeof(credential));
                *response   = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
                *statusText = DuplicateString(L"Could not parse authentication credential.");
                *statusIcon = CPSI_ERROR;
                return *statusText ? S_OK : E_OUTOFMEMORY;
            }
            SecureZeroMemory(credential, sizeof(credential));

            /* 3. Resolve Negotiate auth package */
            ULONG authPackage = ResolveNegotiateAuthPackage();
            if (authPackage == ULONG_MAX)
            {
                SecureZeroMemory(password, sizeof(password));
                *response   = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
                *statusText = DuplicateString(L"Negotiate authentication package is unavailable.");
                *statusIcon = CPSI_ERROR;
                return *statusText ? S_OK : E_OUTOFMEMORY;
            }

            /* 4. Build KERB logon blob */
            BYTE* blob     = nullptr;
            DWORD blobSize = 0;
            if (!PackKerbLogon(scenario_, domain, username, password, &blob, &blobSize))
            {
                SecureZeroMemory(password, sizeof(password));
                *response   = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
                *statusText = DuplicateString(L"Could not pack logon credentials.");
                *statusIcon = CPSI_ERROR;
                return *statusText ? S_OK : E_OUTOFMEMORY;
            }
            SecureZeroMemory(password, sizeof(password));

            /* 5. Hand to Windows LSA */
            serialization->ulAuthenticationPackage = authPackage;
            serialization->clsidCredentialProvider  = CLSID_WinHelloCredentialProvider;
            serialization->rgbSerialization         = blob;
            serialization->cbSerialization          = blobSize;

            *response   = CPGSR_RETURN_CREDENTIAL_FINISHED;
            *statusText = DuplicateString(L"Signing in with phone approval\x2026");
            *statusIcon = CPSI_SUCCESS;
            return *statusText ? S_OK : E_OUTOFMEMORY;
        }

        HRESULT STDMETHODCALLTYPE ReportResult(NTSTATUS ntsStatus, NTSTATUS ntsSubstatus,
                                                LPWSTR* statusText,
                                                CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon) override
        {
            if (statusText != nullptr)
            {
                if (ntsStatus == 0)
                    *statusText = DuplicateString(L"Phone unlock succeeded.");
                else
                {
                    wchar_t message[160]{};
                    wsprintfW(message, L"Windows sign-in failed (0x%08lX, substatus 0x%08lX).",
                              static_cast<ULONG>(ntsStatus), static_cast<ULONG>(ntsSubstatus));
                    *statusText = DuplicateString(message);
                }
            }
            if (statusIcon != nullptr)
                *statusIcon = ntsStatus == 0 ? CPSI_SUCCESS : CPSI_ERROR;
            return S_OK;
        }

    private:
        ~Credential()
        {
            if (userSid_ != nullptr)
                CoTaskMemFree(userSid_);
            if (events_ != nullptr)
                events_->Release();
            InterlockedDecrement(&g_moduleReferences);
        }

        LONG references_ = 1;
        ICredentialProviderCredentialEvents* events_ = nullptr;
        CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario_ = CPUS_INVALID;
        LPWSTR userSid_ = nullptr;
    };

    /* ══════════════════════════════════════════════════════════════
       CredentialProvider  — implements ICredentialProvider
       and ICredentialProviderSetUserArray so Windows places the tile
       under the current user's "Sign-in options"!
       ══════════════════════════════════════════════════════════════ */

    class CredentialProvider final : public ICredentialProvider,
                                     public ICredentialProviderSetUserArray
    {
    public:
        CredentialProvider() { InterlockedIncrement(&g_moduleReferences); }

        HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) override
        {
            if (object == nullptr)
                return E_POINTER;
            *object = nullptr;
            if (InlineIsEqualGUID(iid, __uuidof(IUnknown)) ||
                InlineIsEqualGUID(iid, __uuidof(ICredentialProvider)))
            {
                *object = static_cast<ICredentialProvider*>(this);
                AddRef();
                return S_OK;
            }
            if (InlineIsEqualGUID(iid, __uuidof(ICredentialProviderSetUserArray)))
            {
                *object = static_cast<ICredentialProviderSetUserArray*>(this);
                AddRef();
                return S_OK;
            }
            return E_NOINTERFACE;
        }

        ULONG STDMETHODCALLTYPE AddRef() override
        {
            return static_cast<ULONG>(InterlockedIncrement(&references_));
        }

        ULONG STDMETHODCALLTYPE Release() override
        {
            const auto remaining = InterlockedDecrement(&references_);
            if (remaining == 0)
            {
                this->~CredentialProvider();
                HeapFree(GetProcessHeap(), 0, this);
            }
            return static_cast<ULONG>(remaining);
        }

        /* ── ICredentialProviderSetUserArray ── */
        HRESULT STDMETHODCALLTYPE SetUserArray(ICredentialProviderUserArray* users) override
        {
            for (DWORD i = 0; i < userCount_; ++i)
            {
                if (userSids_[i] != nullptr)
                {
                    CoTaskMemFree(userSids_[i]);
                    userSids_[i] = nullptr;
                }
            }
            userCount_ = 0;

            if (users != nullptr)
            {
                DWORD count = 0;
                if (SUCCEEDED(users->GetCount(&count)))
                {
                    if (count > kMaxUsers) count = kMaxUsers;
                    for (DWORD i = 0; i < count; ++i)
                    {
                        ICredentialProviderUser* user = nullptr;
                        if (SUCCEEDED(users->GetAt(i, &user)) && user != nullptr)
                        {
                            LPWSTR sid = nullptr;
                            if (SUCCEEDED(user->GetSid(&sid)) && sid != nullptr)
                            {
                                userSids_[userCount_++] = sid;
                            }
                            user->Release();
                        }
                    }
                }
            }
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario, DWORD) override
        {
            scenario_ = scenario;
            return scenario == CPUS_LOGON || scenario == CPUS_UNLOCK_WORKSTATION ? S_OK : E_NOTIMPL;
        }

        HRESULT STDMETHODCALLTYPE SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*) override
        {
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE Advise(ICredentialProviderEvents* events, UINT_PTR context) override
        {
            AcquireSRWLockExclusive(&eventsLock_);
            if (events_ != nullptr) events_->Release();
            events_ = events;
            context_ = context;
            if (events_ != nullptr) events_->AddRef();
            ReleaseSRWLockExclusive(&eventsLock_);

            InterlockedExchange(&stopPoll_, FALSE);
            if (pollThread_ == nullptr)
                pollThread_ = CreateThread(nullptr, 0, PollForAgentChanges, this, 0, nullptr);
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE UnAdvise() override
        {
            StopPolling();
            AcquireSRWLockExclusive(&eventsLock_);
            if (events_ != nullptr) events_->Release();
            events_ = nullptr;
            ReleaseSRWLockExclusive(&eventsLock_);
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetFieldDescriptorCount(DWORD* count) override
        {
            if (count == nullptr)
                return E_POINTER;
            *count = kFieldCount;
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetFieldDescriptorAt(DWORD index, CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** descriptor) override
        {
            if (descriptor == nullptr)
                return E_POINTER;
            *descriptor = nullptr;
            if (index >= kFieldCount)
                return E_INVALIDARG;

            auto* copy = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(
                CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
            if (copy == nullptr)
                return E_OUTOFMEMORY;
            *copy = kFieldDescriptors[index];
            copy->pszLabel = DuplicateString(kFieldDescriptors[index].pszLabel);
            if (copy->pszLabel == nullptr)
            {
                CoTaskMemFree(copy);
                return E_OUTOFMEMORY;
            }
            *descriptor = copy;
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetCredentialCount(DWORD* count, DWORD* defaultIndex, BOOL* autoLogon) override
        {
            if (count == nullptr || defaultIndex == nullptr || autoLogon == nullptr)
                return E_POINTER;

            *count = userCount_ > 0 ? userCount_ : 1;
            *defaultIndex = CREDENTIAL_PROVIDER_NO_DEFAULT;
            *autoLogon = FALSE;
            if (userCount_ == 1)
            {
                wchar_t status[512]{};
                if (QueryLanAgent(L"STATUS", status, ARRAYSIZE(status)) &&
                    wcsstr(status, L"|APPROVED|") != nullptr)
                {
                    *defaultIndex = 0;
                    *autoLogon = TRUE;
                }
            }
            return S_OK;
        }

        HRESULT STDMETHODCALLTYPE GetCredentialAt(DWORD index, ICredentialProviderCredential** credential) override
        {
            if (credential == nullptr)
                return E_POINTER;
            *credential = nullptr;
            const DWORD maxCount = userCount_ > 0 ? userCount_ : 1;
            if (index >= maxCount)
                return E_INVALIDARG;

            PCWSTR sid = (userCount_ > 0 && index < userCount_) ? userSids_[index] : nullptr;
            void* memory = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(Credential));
            if (memory == nullptr)
                return E_OUTOFMEMORY;
            auto* value = ::new (memory) Credential(scenario_, sid);
            *credential = value;
            return S_OK;
        }

    private:
        static DWORD WINAPI PollForAgentChanges(LPVOID context)
        {
            auto* self = static_cast<CredentialProvider*>(context);
            while (InterlockedCompareExchange(&self->stopPoll_, FALSE, FALSE) == FALSE)
            {
                wchar_t status[512]{};
                const bool available = QueryLanAgent(L"STATUS", status, ARRAYSIZE(status)) &&
                    wcsstr(status, L"READY|") == status;
                const LONG current = available ? 1 : 0;
                const LONG previous = InterlockedCompareExchange(&self->lastAvailable_, current, -1);
                const LONG approved = available && wcsstr(status, L"|APPROVED|") != nullptr ? 1 : 0;
                const LONG previousApproved = InterlockedExchange(&self->lastApproved_, approved);
                const bool approvalArrived = previousApproved == 0 && approved == 1;
                if (previous != current)
                {
                    if (InterlockedCompareExchange(&self->candidateAvailable_, current, current) == current)
                        ++self->candidateSamples_;
                    else
                    {
                        InterlockedExchange(&self->candidateAvailable_, current);
                        self->candidateSamples_ = 1;
                    }
                }
                else
                {
                    self->candidateSamples_ = 0;
                }
                const bool availabilityChanged = previous != -1 && previous != current && self->candidateSamples_ >= 3;
                if (availabilityChanged)
                {
                    InterlockedExchange(&self->lastAvailable_, current);
                    self->candidateSamples_ = 0;
                }
                if (availabilityChanged || approvalArrived)
                {
                    ICredentialProviderEvents* events = nullptr;
                    UINT_PTR contextValue = 0;
                    AcquireSRWLockShared(&self->eventsLock_);
                    events = self->events_;
                    contextValue = self->context_;
                    if (events != nullptr) events->AddRef();
                    ReleaseSRWLockShared(&self->eventsLock_);
                    if (events != nullptr)
                    {
                        events->CredentialsChanged(contextValue);
                        events->Release();
                    }
                }
                Sleep(1000);
            }
            return 0;
        }

        void StopPolling()
        {
            InterlockedExchange(&stopPoll_, TRUE);
            if (pollThread_ != nullptr)
            {
                WaitForSingleObject(pollThread_, 2000);
                CloseHandle(pollThread_);
                pollThread_ = nullptr;
            }
        }

        ~CredentialProvider()
        {
            StopPolling();
            for (DWORD i = 0; i < userCount_; ++i)
            {
                if (userSids_[i] != nullptr)
                {
                    CoTaskMemFree(userSids_[i]);
                    userSids_[i] = nullptr;
                }
            }
            AcquireSRWLockExclusive(&eventsLock_);
            if (events_ != nullptr) events_->Release();
            events_ = nullptr;
            ReleaseSRWLockExclusive(&eventsLock_);
            InterlockedDecrement(&g_moduleReferences);
        }

        LONG references_ = 1;
        CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario_ = CPUS_INVALID;
        ICredentialProviderEvents* events_ = nullptr;
        UINT_PTR context_ = 0;
        SRWLOCK eventsLock_ = SRWLOCK_INIT;
        HANDLE pollThread_ = nullptr;
        volatile LONG stopPoll_ = FALSE;
        volatile LONG lastAvailable_ = -1;
        volatile LONG lastApproved_ = -1;
        volatile LONG candidateAvailable_ = -1;
        DWORD candidateSamples_ = 0;
        static constexpr DWORD kMaxUsers = 16;
        LPWSTR userSids_[kMaxUsers]{};
        DWORD userCount_ = 0;
    };

    /* ══════════════════════════════════════════════════════════════
       COM class factory
       ══════════════════════════════════════════════════════════════ */

    class CredentialProviderFactory final : public IClassFactory
    {
    public:
        CredentialProviderFactory() { InterlockedIncrement(&g_moduleReferences); }

        HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) override
        {
            if (object == nullptr)
                return E_POINTER;
            *object = nullptr;
            if (InlineIsEqualGUID(iid, __uuidof(IUnknown)) ||
                InlineIsEqualGUID(iid, __uuidof(IClassFactory)))
            {
                *object = static_cast<IClassFactory*>(this);
                AddRef();
                return S_OK;
            }
            return E_NOINTERFACE;
        }

        ULONG STDMETHODCALLTYPE AddRef() override
        {
            return static_cast<ULONG>(InterlockedIncrement(&references_));
        }

        ULONG STDMETHODCALLTYPE Release() override
        {
            const auto remaining = InterlockedDecrement(&references_);
            if (remaining == 0)
            {
                this->~CredentialProviderFactory();
                HeapFree(GetProcessHeap(), 0, this);
            }
            return static_cast<ULONG>(remaining);
        }

        HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer, REFIID iid, void** object) override
        {
            if (outer != nullptr)
                return CLASS_E_NOAGGREGATION;
            void* memory = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(CredentialProvider));
            if (memory == nullptr)
                return E_OUTOFMEMORY;
            auto* provider = ::new (memory) CredentialProvider();
            const auto result = provider->QueryInterface(iid, object);
            provider->Release();
            return result;
        }

        HRESULT STDMETHODCALLTYPE LockServer(BOOL) override { return S_OK; }

    private:
        ~CredentialProviderFactory() { InterlockedDecrement(&g_moduleReferences); }
        LONG references_ = 1;
    };
}

extern "C" HRESULT STDMETHODCALLTYPE DllGetClassObject(REFCLSID classId, REFIID iid, void** object)
{
    if (!InlineIsEqualGUID(classId, CLSID_WinHelloCredentialProvider))
        return CLASS_E_CLASSNOTAVAILABLE;

    void* memory = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(CredentialProviderFactory));
    if (memory == nullptr)
        return E_OUTOFMEMORY;
    auto* factory = ::new (memory) CredentialProviderFactory();
    const auto result = factory->QueryInterface(iid, object);
    factory->Release();
    return result;
}

extern "C" HRESULT STDMETHODCALLTYPE DllCanUnloadNow()
{
    return InterlockedCompareExchange(&g_moduleReferences, 0, 0) == 0 ? S_OK : S_FALSE;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH)
        DisableThreadLibraryCalls(instance);
    return TRUE;
}
