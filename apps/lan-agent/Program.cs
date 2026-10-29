using System.Collections.Concurrent;
using System.IO.Pipes;
using System.Net;
using System.Net.Sockets;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using QRCoder;

const int HttpsPort = 54431;
const int DiscoveryPort = 45876;
const string PipeName = "WinHello.LanAgent";

if (args.Length == 1 && args[0] == "--print-enrollment-qr")
{
    var enrollmentUri = Console.In.ReadLine();
    if (string.IsNullOrWhiteSpace(enrollmentUri) || !enrollmentUri.StartsWith("winhello://enroll?", StringComparison.Ordinal))
    {
        Console.Error.WriteLine("A valid enrollment URI is required on standard input.");
        return 2;
    }
    using var generator = new QRCodeGenerator();
    using var qrData = generator.CreateQrCode(enrollmentUri, QRCodeGenerator.ECCLevel.Q);
    Console.WriteLine(new AsciiQRCode(qrData).GetGraphicSmall(true, false));
    return 0;
}

var stateDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "WinHelloLanDemo");
Directory.CreateDirectory(stateDirectory);
var configPath = Environment.GetEnvironmentVariable("WINHELLO_CONFIG_PATH") ?? Path.Combine(stateDirectory, "agent.json");
AgentConfiguration? configuration = File.Exists(configPath)
    ? JsonSerializer.Deserialize<AgentConfiguration>(File.ReadAllText(configPath))
    : null;
if (configuration is null && string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("WINHELLO_TLS_PFX")))
{
    Console.Error.WriteLine($"Agent configuration was not found: {configPath}");
    Console.Error.WriteLine("Use scripts/install-winhello.ps1, or set WINHELLO_TLS_PFX for a development run.");
    return 2;
}

using var certificate = AgentBootstrap.LoadCertificate(configuration);
var deviceId = configuration?.DeviceId ?? DeviceIdStore.LoadOrCreate(stateDirectory);
var totpSecret = configuration is null ? RandomNumberGenerator.GetBytes(20) : TotpEncoding.DecodeBase32(configuration.TotpSecret);
var state = new DemoState(deviceId, totpSecret, Path.Combine(stateDirectory, "pairing.json"));
var certificatePin = Convert.ToHexString(SHA256.HashData(certificate.RawData));

Console.WriteLine("WinHello LAN demo agent is starting.");
Console.WriteLine($"PC ID: {deviceId}");
Console.WriteLine($"TLS SHA-256 pin (compare with the Android app before pairing): {certificatePin}");
Console.WriteLine($"HTTPS: {HttpsPort}; UDP discovery: {DiscoveryPort}; local pipe: \\\\.\\pipe\\{PipeName}");
Console.WriteLine("This demo verifies a TOTP and biometric-backed phone signature; Windows account authentication is not integrated.");

if (configuration is null)
{
    var enrollmentUri = AgentBootstrap.BuildEnrollmentUri(deviceId, TotpEncoding.EncodeBase32(totpSecret), certificatePin);
    using var generator = new QRCodeGenerator();
    using var qrData = generator.CreateQrCode(enrollmentUri, QRCodeGenerator.ECCLevel.Q);
    Console.WriteLine(new AsciiQRCode(qrData).GetGraphicSmall(true, false));
    Console.WriteLine("Scan this development enrollment QR from the terminal.");
}

var builder = WebApplication.CreateBuilder(args);
builder.Host.UseWindowsService(options => options.ServiceName = "WinHello LAN Agent");
builder.WebHost.ConfigureKestrel(server =>
    server.ListenAnyIP(HttpsPort, endpoint => endpoint.UseHttps(certificate)));

var app = builder.Build();
app.MapGet("/health", () => Results.Ok(new
{
    status = "lan-demo-ready",
    deviceId,
    pending = state.HasPendingRequest,
    paired = state.IsPaired,
    windowsAuthentication = "not-integrated"
}));

app.MapPost("/v1/pair", (PairRequest request) =>
{
    if (!state.TryPair(request.DeviceId, request.TotpCode, request.PublicKey))
        return Results.Unauthorized();

    return Results.Ok(new { paired = true, deviceId, windowsAuthentication = "not-integrated" });
});

app.MapPost("/v1/unpair", (PairRequest request) =>
{
    if (!state.TryUnpair(request.DeviceId, request.TotpCode))
        return Results.Unauthorized();
    return Results.Ok(new { unpaired = true });
});

app.MapGet("/v1/demo/pending", (string deviceId) =>
{
    var challenge = state.GetChallenge(deviceId);
    return challenge is null
        ? Results.NotFound(new { error = "no_pending_request_or_phone_not_paired" })
        : Results.Ok(challenge);
});

app.MapPost("/v1/demo/approve", (ApprovalRequest request) =>
{
    if (!state.TryApprove(request.DeviceId, request.RequestId, request.Signature, request.TotpCode))
        return Results.Json(new { error = "invalid_expired_or_replayed_approval" }, statusCode: StatusCodes.Status401Unauthorized);

    return Results.Ok(new
    {
        approved = true,
        windowsAuthentication = "not-integrated",
        message = "Phone approval received. No Windows credential was submitted."
    });
});

using var shutdown = new CancellationTokenSource();
app.Lifetime.ApplicationStopping.Register(shutdown.Cancel);
var pipeTask = NamedPipeStatusServer.RunAsync(PipeName, state, certificate, stateDirectory, shutdown.Token);
var discoveryTask = LanDiscoveryServer.RunAsync(DiscoveryPort, HttpsPort, deviceId, certificatePin, state, shutdown.Token);

try
{
    await app.RunAsync();
}
finally
{
    shutdown.Cancel();
    await Task.WhenAll(pipeTask, discoveryTask);
}

return 0;

internal static class AgentBootstrap
{
    public static X509Certificate2 LoadCertificate(AgentConfiguration? configuration)
    {
        if (configuration is not null)
        {
            using var store = new X509Store(StoreName.My, StoreLocation.LocalMachine);
            store.Open(OpenFlags.ReadOnly | OpenFlags.OpenExistingOnly);
            var thumbprint = configuration.CertificateThumbprint.Replace(" ", "", StringComparison.Ordinal);
            var certificate = store.Certificates.Cast<X509Certificate2>()
                .FirstOrDefault(item => string.Equals(item.Thumbprint, thumbprint, StringComparison.OrdinalIgnoreCase));
            if (certificate is null || !certificate.HasPrivateKey)
                throw new InvalidOperationException("The configured TLS certificate or its private key is missing from LocalMachine\\My.");
            return certificate;
        }

        var path = Environment.GetEnvironmentVariable("WINHELLO_TLS_PFX");
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
            throw new InvalidOperationException("Set WINHELLO_TLS_PFX to a TLS certificate (.pfx) for a development run.");
        return X509CertificateLoader.LoadPkcs12FromFile(path, Environment.GetEnvironmentVariable("WINHELLO_TLS_PASSWORD"));
    }

    public static string BuildEnrollmentUri(string deviceId, string secret, string certificatePin) =>
        $"winhello://enroll?pcid={Uri.EscapeDataString(deviceId)}&secret={Uri.EscapeDataString(secret)}&pin={certificatePin}&https=54431";
}

internal static class TotpEncoding
{
    private const string Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static string EncodeBase32(ReadOnlySpan<byte> data)
    {
        var result = new StringBuilder((data.Length * 8 + 4) / 5);
        var buffer = 0;
        var bits = 0;
        foreach (var value in data)
        {
            buffer = (buffer << 8) | value;
            bits += 8;
            while (bits >= 5)
            {
                result.Append(Alphabet[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) result.Append(Alphabet[(buffer << (5 - bits)) & 31]);
        return result.ToString();
    }

    public static byte[] DecodeBase32(string value)
    {
        var result = new List<byte>();
        var buffer = 0;
        var bits = 0;
        foreach (var character in value.TrimEnd('=').ToUpperInvariant())
        {
            var index = Alphabet.IndexOf(character);
            if (index < 0) throw new FormatException("TOTP secret must be Base32.");
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8)
            {
                result.Add((byte)((buffer >> (bits - 8)) & 255));
                bits -= 8;
            }
        }
        return result.ToArray();
    }

    public static bool Verify(byte[] secret, string? code)
    {
        if (string.IsNullOrEmpty(code) || code.Length != 6 || code.Any(character => character is < '0' or > '9')) return false;
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 30;
        var supplied = Encoding.ASCII.GetBytes(code);
        for (var skew = -1; skew <= 1; skew++)
        {
            var expected = Encoding.ASCII.GetBytes(CreateCode(secret, now + skew));
            if (CryptographicOperations.FixedTimeEquals(expected, supplied)) return true;
        }
        return false;
    }

    private static string CreateCode(byte[] secret, long counter)
    {
        Span<byte> message = stackalloc byte[8];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(message, counter);
        var hash = HMACSHA1.HashData(secret, message);
        var offset = hash[^1] & 0x0f;
        var binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) |
            ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return (binary % 1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture);
    }
}

internal sealed record AgentConfiguration(string DeviceId, string TotpSecret, string CertificateThumbprint);
internal sealed record PairRequest(string DeviceId, string TotpCode, string PublicKey);
internal sealed record ApprovalRequest(string DeviceId, Guid RequestId, string Signature, string TotpCode);
internal sealed record PhoneChallenge(Guid RequestId, string DeviceId, string Nonce, DateTimeOffset ExpiresAt);
internal sealed record PendingRequest(Guid RequestId, byte[] Nonce, DateTimeOffset ExpiresAt, bool Approved);

internal sealed class DemoState
{
    private readonly string deviceId;
    private readonly byte[] totpSecret;
    private readonly string pairingPath;
    private readonly object gate = new();
    private readonly ConcurrentDictionary<Guid, PendingRequest> requests = new();
    private byte[]? publicKey;
    private string? pairedDeviceId;
    private int pairingAttempts;
    private DateTimeOffset pairingBlockedUntil;

    public DemoState(string deviceId, byte[] totpSecret, string pairingPath)
    {
        this.deviceId = deviceId;
        this.totpSecret = totpSecret;
        this.pairingPath = pairingPath;
        try
        {
            var saved = JsonSerializer.Deserialize<SavedPairing>(File.ReadAllText(pairingPath));
            if (saved is not null)
            {
                publicKey = Convert.FromBase64String(saved.PublicKey);
                pairedDeviceId = saved.PhoneDeviceId;
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or FormatException)
        {
            Console.Error.WriteLine($"Ignoring unreadable pairing record: {ex.Message}");
        }
    }

    public bool IsPaired
    {
        get { lock (gate) return publicKey is not null; }
    }

    public bool HasPendingRequest
    {
        get
        {
            RemoveExpired();
            return requests.Values.Any(request => !request.Approved);
        }
    }

    public bool TryPair(string phoneDeviceId, string totpCode, string publicKeyBase64)
    {
        lock (gate)
        {
            if (publicKey is not null || DateTimeOffset.UtcNow < pairingBlockedUntil)
                return false;
            if (!TotpEncoding.Verify(totpSecret, totpCode))
            {
                if (++pairingAttempts >= 5)
                {
                    pairingAttempts = 0;
                    pairingBlockedUntil = DateTimeOffset.UtcNow.AddMinutes(1);
                }
                return false;
            }

            try
            {
                var keyBytes = Convert.FromBase64String(publicKeyBase64);
                using var verifier = ECDsa.Create();
                verifier.ImportSubjectPublicKeyInfo(keyBytes, out _);
                File.WriteAllText(pairingPath, JsonSerializer.Serialize(new SavedPairing(
                    phoneDeviceId, Convert.ToBase64String(keyBytes))));
                publicKey = keyBytes;
                pairedDeviceId = phoneDeviceId;
                pairingAttempts = 0;
                return true;
            }
            catch (CryptographicException)
            {
                return false;
            }
            catch (FormatException)
            {
                return false;
            }
            catch (IOException ex)
            {
                Console.Error.WriteLine($"Could not persist the phone pairing: {ex.Message}");
                return false;
            }
        }
    }

    public bool TryUnpair(string phoneDeviceId, string totpCode)
    {
        if (!TotpEncoding.Verify(totpSecret, totpCode)) return false;
        lock (gate)
        {
            if (publicKey is null || !string.Equals(pairedDeviceId, phoneDeviceId, StringComparison.Ordinal))
                return false;
            try { File.Delete(pairingPath); }
            catch (IOException ex) { Console.Error.WriteLine($"Could not remove pairing record: {ex.Message}"); return false; }
            CryptographicOperations.ZeroMemory(publicKey);
            publicKey = null;
            pairedDeviceId = null;
            pairingAttempts = 0;
            pairingBlockedUntil = DateTimeOffset.MinValue;
            return true;
        }
    }

    public string StartOrGetRequest()
    {
        RemoveExpired();
        var current = requests.Values.FirstOrDefault();
        if (current is not null)
            return $"{(current.Approved ? "APPROVED" : "PENDING")}|{current.RequestId:D}";

        var request = new PendingRequest(Guid.NewGuid(), RandomNumberGenerator.GetBytes(32), DateTimeOffset.UtcNow.AddMinutes(2), false);
        requests[request.RequestId] = request;
        return $"PENDING|{request.RequestId:D}";
    }

    public string GetCredential(X509Certificate2 certificate, string stateDirectory)
    {
        lock (gate)
        {
            RemoveExpired();
            var approved = requests.Values.FirstOrDefault(r => r.Approved);
            if (approved is null)
                return "ERROR|not_approved";

            var encPath = Path.Combine(stateDirectory, "credential.enc");
            if (!File.Exists(encPath))
                return "ERROR|no_credential_stored";

            try
            {
                var encrypted = File.ReadAllBytes(encPath);
                using var rsa = certificate.GetRSAPrivateKey();
                if (rsa is null)
                    return "ERROR|no_private_key";

                var decryptedBytes = rsa.Decrypt(encrypted, RSAEncryptionPadding.OaepSHA256);
                var decryptedText = Encoding.UTF8.GetString(decryptedBytes);
                requests.TryRemove(approved.RequestId, out _);
                return $"CREDENTIAL|{decryptedText}";
            }
            catch (Exception ex)
            {
                return $"ERROR|decrypt_failed|{ex.Message}";
            }
        }
    }

    public string GetStatus()
    {
        RemoveExpired();
        var request = requests.Values.FirstOrDefault();
        var requestStatus = request is null
            ? "IDLE"
            : $"{(request.Approved ? "APPROVED" : "PENDING")}|{request.RequestId:D}";
        return $"READY|{deviceId}|{requestStatus}";
    }

    public PhoneChallenge? GetChallenge(string phoneDeviceId)
    {
        RemoveExpired();
        lock (gate)
        {
            if (publicKey is null || !string.Equals(pairedDeviceId, phoneDeviceId, StringComparison.Ordinal))
                return null;

            var request = requests.Values.FirstOrDefault(item => !item.Approved);
            if (request is null)
                return null;

            return new PhoneChallenge(
                request.RequestId,
                deviceId,
                Convert.ToBase64String(request.Nonce),
                request.ExpiresAt);
        }
    }

    public bool TryApprove(string phoneDeviceId, Guid requestId, string signatureBase64, string totpCode)
    {
        if (!TotpEncoding.Verify(totpSecret, totpCode))
            return false;

        PendingRequest? request;
        byte[] keyBytes;
        lock (gate)
        {
            if (publicKey is null || !string.Equals(pairedDeviceId, phoneDeviceId, StringComparison.Ordinal))
                return false;
            keyBytes = publicKey;
        }

        if (!requests.TryGetValue(requestId, out request) || request.Approved || request.ExpiresAt <= DateTimeOffset.UtcNow)
            return false;

        try
        {
            var payload = Encoding.UTF8.GetBytes($"{deviceId}\n{requestId:D}\n{Convert.ToBase64String(request.Nonce)}");
            var signature = Convert.FromBase64String(signatureBase64);
            using var verifier = ECDsa.Create();
            verifier.ImportSubjectPublicKeyInfo(keyBytes, out _);
            if (!verifier.VerifyData(payload, signature, HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence))
                return false;

            if (!requests.TryUpdate(requestId, request with { Approved = true }, request))
                return false;

            CryptographicOperations.ZeroMemory(request.Nonce);
            return true;
        }
        catch (CryptographicException)
        {
            return false;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private void RemoveExpired()
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var (id, request) in requests)
        {
            if (request.ExpiresAt <= now && requests.TryRemove(id, out var removed))
                CryptographicOperations.ZeroMemory(removed.Nonce);
        }
    }

}

internal sealed record SavedPairing(string PhoneDeviceId, string PublicKey);

internal static class DeviceIdStore
{
    public static string LoadOrCreate(string folder)
    {
        Directory.CreateDirectory(folder);
        var path = Path.Combine(folder, "device-id.txt");
        if (File.Exists(path) && Guid.TryParse(File.ReadAllText(path), out var existing))
            return existing.ToString("D");

        var deviceId = Guid.NewGuid().ToString("D");
        File.WriteAllText(path, deviceId);
        return deviceId;
    }
}

internal static class NamedPipeStatusServer
{
    private static NamedPipeServerStream CreateServerPipe(string pipeName)
    {
        if (OperatingSystem.IsWindows())
        {
            var security = new PipeSecurity();
            // LogonUI runs as LocalSystem. The pipe can return the stored Windows
            // credential after phone approval, so ordinary users must not connect.
            security.AddAccessRule(new PipeAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                PipeAccessRights.FullControl,
                AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
                PipeAccessRights.FullControl,
                AccessControlType.Allow));

            return NamedPipeServerStreamAcl.Create(
                pipeName,
                PipeDirection.InOut,
                NamedPipeServerStream.MaxAllowedServerInstances,
                PipeTransmissionMode.Byte,
                PipeOptions.Asynchronous,
                0,
                0,
                security);
        }

        return new NamedPipeServerStream(
            pipeName,
            PipeDirection.InOut,
            NamedPipeServerStream.MaxAllowedServerInstances,
            PipeTransmissionMode.Byte,
            PipeOptions.Asynchronous);
    }

    public static async Task RunAsync(string pipeName, DemoState state, X509Certificate2 certificate, string stateDirectory, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using var pipe = CreateServerPipe(pipeName);
                await pipe.WaitForConnectionAsync(cancellationToken);
                using var reader = new StreamReader(pipe, Encoding.UTF8, leaveOpen: true);
                await using var writer = new StreamWriter(pipe, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
                var command = await reader.ReadLineAsync(cancellationToken);
                var response = command switch
                {
                    "STATUS" => state.GetStatus(),
                    "START" => state.StartOrGetRequest(),
                    "CREDENTIAL" => state.GetCredential(certificate, stateDirectory),
                    _ => "ERROR|unknown_command"
                };
                await writer.WriteLineAsync(response.AsMemory(), cancellationToken);
                try { if (OperatingSystem.IsWindows()) pipe.WaitForPipeDrain(); } catch { }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (IOException ex)
            {
                Console.Error.WriteLine($"Named pipe request failed: {ex.Message}");
            }
        }
    }
}

internal static class LanDiscoveryServer
{
    public static async Task RunAsync(
        int discoveryPort,
        int httpsPort,
        string deviceId,
        string certificatePin,
        DemoState state,
        CancellationToken cancellationToken)
    {
        using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, discoveryPort));
        Console.WriteLine($"Listening for LAN discovery pings on UDP {discoveryPort}.");
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var packet = await udp.ReceiveAsync(cancellationToken);
                var text = Encoding.UTF8.GetString(packet.Buffer);
                if (!string.Equals(text, "WINHELLO_DISCOVER_V1", StringComparison.Ordinal))
                    continue;

                var response = string.Join('|',
                    "WINHELLO_PONG_V1",
                    deviceId,
                    Environment.MachineName,
                    httpsPort,
                    state.HasPendingRequest ? "1" : "0",
                    certificatePin);
                var bytes = Encoding.UTF8.GetBytes(response);
                await udp.SendAsync(bytes, packet.RemoteEndPoint, cancellationToken);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (SocketException ex)
            {
                Console.Error.WriteLine($"LAN discovery socket failed: {ex.Message}");
                await Task.Delay(500, cancellationToken);
            }
        }
    }
}
