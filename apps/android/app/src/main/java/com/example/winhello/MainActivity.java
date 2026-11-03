package com.example.winhello;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ProgressBar;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import android.app.KeyguardManager;
import android.widget.EditText;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Html;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public final class MainActivity extends Activity {
    public static final String ACTION_SCAN = "com.example.winhello.SCAN_LAN";

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Map<String, LanClient.Pc> online = new HashMap<>();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            scanLan();
            refreshHandler.postDelayed(this, 5000);
        }
    };
    private LanClient lanClient;
    private TextView status;
    private LinearLayout devices;
    private boolean scanInFlight;
    private String lastSsid;
    private boolean mkvTotpVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lanClient = new LanClient(this);
        createLayout();
        if (ACTION_SCAN.equals(getIntent().getAction())) scanLan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_SCAN.equals(intent.getAction())) scanLan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        online.clear();
        for (LanClient.Pc pc : PcStatusStore.recent(this)) online.put(pc.deviceId, pc);
        showDevices();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("ssid_permission_asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("ssid_permission_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
        }
        refreshHandler.removeCallbacks(refresh);
        refreshHandler.post(refresh);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    private void createLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 18, 32));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(100));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription(getString(R.string.app_name));
        header.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView title = new TextView(this);
        title.setText("WinHello");
        title.setTextSize(25);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, 1);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton info = iconButton(android.R.drawable.ic_menu_info_details, "Hướng dẫn");
        info.setOnClickListener(view -> showAppInfoDialog());
        info.setOnLongClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            checkForAppUpdate();
            return true;
        });
        header.addView(info, new LinearLayout.LayoutParams(dp(44), dp(44)));

        status = new TextView(this);
        status.setTextColor(Color.rgb(145, 163, 186));
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(16));
        page.addView(status, new LinearLayout.LayoutParams(-1, -2));

        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        page.addView(devices, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView add = new TextView(this);
        add.setText("+");
        add.setTextSize(34);
        add.setTextColor(Color.WHITE);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription("Thêm PC bằng mã QR TOTP");
        add.setBackground(round(Color.rgb(31, 117, 230), 22));
        add.setElevation(dp(8));
        add.setOnClickListener(view -> scanEnrollmentQr());
        FrameLayout.LayoutParams addLayout = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM | Gravity.RIGHT);
        addLayout.setMargins(0, 0, dp(22), dp(24));
        root.addView(add, addLayout);
        setContentView(root);
    }

    private void scanLan() {
        if (scanInFlight) return;
        scanInFlight = true;
        background.execute(() -> {
            try {
                List<LanClient.Pc> found = lanClient.discover();
                SignInWidgetProvider.updateFromPoll(this, found);
                runOnUiThread(() -> {
                    String currentSsid = lanClient.currentSsid();
                    boolean changed = online.size() != found.size() ||
                            !java.util.Objects.equals(lastSsid, currentSsid);
                    for (LanClient.Pc pc : found) {
                        LanClient.Pc old = online.get(pc.deviceId);
                        if (old == null || old.pending != pc.pending ||
                                !old.sessionState.equals(pc.sessionState) || !old.name.equals(pc.name))
                            changed = true;
                    }
                    online.clear();
                    for (LanClient.Pc pc : found) online.put(pc.deviceId, pc);
                    lastSsid = currentSsid;
                    scanInFlight = false;
                    if (changed) showDevices();
                });
            } catch (Exception error) {
                SignInWidgetProvider.updateFromPoll(this, java.util.Collections.emptyList());
                runOnUiThread(() -> {
                    String currentSsid = lanClient.currentSsid();
                    boolean changed = !online.isEmpty() ||
                            !java.util.Objects.equals(lastSsid, currentSsid);
                    online.clear();
                    lastSsid = currentSsid;
                    scanInFlight = false;
                    if (changed) showDevices();
                });
            }
        });
    }

    private void scanEnrollmentQr() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setCaptureActivity(QrCaptureActivity.class)
                .setPrompt("")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || result.getContents() == null) return;
        try {
            String raw = result.getContents().trim();
            if (raw.matches("(?i)[A-Z2-7]{32}")) {
                saveVaultMasterKeyDirectly(raw, "Mã Master Key");
                return;
            }
            Uri uri = Uri.parse(raw);
            if ("winhello".equals(uri.getScheme()) && "vault-unlock".equals(uri.getHost())) {
                handleVaultUnlockQr(uri);
                return;
            }
            if ("otpauth".equals(uri.getScheme())) {
                handleOtpAuthQr(uri);
                return;
            }
            if (("winhello".equals(uri.getScheme()) && "vault-key".equals(uri.getHost())) || "mkv".equalsIgnoreCase(uri.getScheme())) {
                String key = uri.getQueryParameter("key");
                if (key == null && uri.getSchemeSpecificPart() != null) key = uri.getSchemeSpecificPart();
                if (key != null && key.matches("(?i)[A-Z2-7= -]{16,64}")) {
                    saveVaultMasterKeyDirectly(key, "MasterKeyVault");
                    return;
                }
            }
            String pcId = uri.getQueryParameter("pcid");
            String secret = uri.getQueryParameter("secret");
            String pin = uri.getQueryParameter("pin");
            if (!"winhello".equals(uri.getScheme()) || !"enroll".equals(uri.getHost()) ||
                    pcId == null || secret == null || pin == null || !pin.matches("(?i)[0-9a-f]{64}"))
                throw new IllegalArgumentException("Mã QR không đúng định dạng WinHello hoặc MKV.");
            java.util.UUID.fromString(pcId);
            if (!secret.matches("(?i)[A-Z2-7]{32,64}"))
                throw new IllegalArgumentException("Mã QR chứa secret TOTP không hợp lệ.");
            lanClient.saveEnrollment(pcId, secret, pin);
            showDevices();
            status.setText("Đã lưu QR. Đang tìm PC để xác nhận TOTP...");
            scanLan();
        } catch (Exception error) {
            status.setText("Quét QR thất bại: " + error.getMessage());
        }
    }

    private void handleVaultUnlockQr(Uri uri) {
        String endpoint = uri.getQueryParameter("endpoint");
        String pin = uri.getQueryParameter("pin");
        String token = uri.getQueryParameter("token");
        if (endpoint == null || pin == null || token == null || !pin.matches("(?i)[0-9a-f]{64}")) {
            status.setText("Mã QR Vault Unlock không hợp lệ.");
            Toast.makeText(this, "Mã QR mở khóa không hợp lệ.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!VaultMasterKeyStore.hasKey(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Chưa có Master Key")
                    .setMessage("Điện thoại chưa lưu Master Key của MasterKeyVault.\n\nHãy dùng nút [+] quét mã QR Authenticator từ MKV để lưu key trước.")
                    .setPositiveButton("Đã hiểu", null)
                    .show();
            return;
        }

        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure() || keyguard.isDeviceLocked()) {
            status.setText("Hãy cài khóa màn hình và mở khóa điện thoại trước khi mở vault.");
            Toast.makeText(this, "Hãy mở khóa điện thoại trước khi mở vault.", Toast.LENGTH_LONG).show();
            return;
        }

        String targetHost = Uri.parse(endpoint).getHost();
        BiometricManager biometricManager = getSystemService(BiometricManager.class);
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText("Cần cài đặt vân tay hoặc sinh trắc học mạnh để mở khóa PC.");
            Toast.makeText(this, "Cần vân tay để mở khóa PC.", Toast.LENGTH_SHORT).show();
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Mở khóa MasterKeyVault")
                .setSubtitle("Xác thực vân tay để gửi Master Key cho PC (" + targetHost + ")")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Hủy", getMainExecutor(), (d, w) -> {
                    status.setText("Đã hủy mở khóa MasterKeyVault.");
                })
                .build();

        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult authResult) {
                status.setText("Đang gửi Master Key đến PC (" + targetHost + ")...");
                background.execute(() -> {
                    try {
                        String masterKey = VaultMasterKeyStore.load(MainActivity.this);
                        if (masterKey == null) throw new IllegalStateException("Không tìm thấy Master Key.");
                        lanClient.unlockVault(endpoint, pin, token, masterKey);
                        runOnUiThread(() -> {
                            status.setText("Đã mở khóa MasterKeyVault trên PC (" + targetHost + ") thành công!");
                            Toast.makeText(MainActivity.this, "Đã mở khóa PC thành công!", Toast.LENGTH_LONG).show();
                        });
                    } catch (Exception ex) {
                        runOnUiThread(() -> {
                            status.setText("Mở khóa vault thất bại: " + ex.getMessage());
                            Toast.makeText(MainActivity.this, "Lỗi mở khóa: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                status.setText("Hủy xác thực: " + errString);
            }
        });
    }

    private void handleOtpAuthQr(Uri uri) {
        String secret = uri.getQueryParameter("secret");
        if (secret == null || !secret.matches("(?i)[A-Z2-7= -]{16,64}")) {
            status.setText("Mã QR Authenticator không có secret hợp lệ.");
            return;
        }
        String issuer = uri.getQueryParameter("issuer");
        String label = uri.getPath() != null ? uri.getPath().replace("/", "") : "";
        String title = (issuer != null && !issuer.isEmpty()) ? issuer : (label.isEmpty() ? "MasterKeyVault" : label);
        saveVaultMasterKeyDirectly(secret, title);
    }

    private void saveVaultMasterKeyDirectly(String secret, String title) {
        try {
            VaultMasterKeyStore.save(this, secret);
            runOnUiThread(() -> {
                status.setText("Đã lưu Master Key Vault (" + title + ") vào Keystore!");
                Toast.makeText(this, "Đã lưu Master Key Vault!", Toast.LENGTH_SHORT).show();
                showDevices();
            });
        } catch (Exception ex) {
            status.setText("Lỗi lưu Master Key: " + ex.getMessage());
            Toast.makeText(this, "Lỗi: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void authenticateDeleteVaultKey() {
        BiometricManager biometricManager = getSystemService(BiometricManager.class);
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText("Cần cài đặt vân tay hoặc sinh trắc học mạnh để xóa Master Key.");
            Toast.makeText(this, "Cần vân tay để xóa Master Key.", Toast.LENGTH_SHORT).show();
            showDevices();
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Xóa Master Key Vault?")
                .setSubtitle("Xác thực vân tay để xóa Master Key khỏi Keystore phần cứng")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Hủy", getMainExecutor(), (d, w) -> showDevices())
                .build();

        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                VaultMasterKeyStore.delete(MainActivity.this);
                runOnUiThread(() -> {
                    status.setText("Đã xóa Master Key Vault an toàn khỏi điện thoại.");
                    Toast.makeText(MainActivity.this, "Đã xóa Master Key Vault", Toast.LENGTH_SHORT).show();
                    showDevices();
                });
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                runOnUiThread(() -> {
                    status.setText("Hủy xóa Master Key.");
                    showDevices();
                });
            }
        });
    }

    private void renderMkvCard() {
        FrameLayout mkvContainer = new FrameLayout(this);
        LinearLayout.LayoutParams containerLayout = new LinearLayout.LayoutParams(-1, -2);
        containerLayout.bottomMargin = dp(12);
        devices.addView(mkvContainer, containerLayout);

        FrameLayout deleteUnderlay = new FrameLayout(this);
        deleteUnderlay.setBackground(round(Color.rgb(220, 38, 38), 20));
        mkvContainer.addView(deleteUnderlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout deleteAction = new LinearLayout(this);
        deleteAction.setOrientation(LinearLayout.VERTICAL);
        deleteAction.setGravity(Gravity.CENTER);
        deleteAction.setPadding(dp(16), 0, dp(16), 0);
        deleteUnderlay.addView(deleteAction, new FrameLayout.LayoutParams(dp(84), -1, Gravity.START | Gravity.CENTER_VERTICAL));

        ImageView trashIcon = new ImageView(this);
        trashIcon.setImageResource(android.R.drawable.ic_menu_delete);
        trashIcon.setColorFilter(Color.WHITE);
        deleteAction.addView(trashIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView trashLabel = new TextView(this);
        trashLabel.setText("Xóa");
        trashLabel.setTextColor(Color.WHITE);
        trashLabel.setTextSize(12);
        trashLabel.setTypeface(null, Typeface.BOLD);
        trashLabel.setPadding(0, dp(2), 0, 0);
        deleteAction.addView(trashLabel);

        deleteUnderlay.setOnClickListener(view -> authenticateDeleteVaultKey());

        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(16), dp(12), dp(16));
        card.setBackground(round(Color.rgb(24, 36, 55), 20));
        mkvContainer.addView(card, new FrameLayout.LayoutParams(-1, -2));

        ImageView vaultIcon = new ImageView(this);
        vaultIcon.setImageResource(android.R.drawable.ic_lock_lock);
        vaultIcon.setColorFilter(Color.rgb(245, 158, 11)); // Amber Gold
        card.addView(vaultIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(6), 0);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView title = new TextView(this);
        title.setText("MasterKeyVault");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        labels.addView(title);

        TextView totpCode = new TextView(this);
        totpCode.setTextSize(18);
        totpCode.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        totpCode.setPadding(0, dp(4), 0, 0);
        labels.addView(totpCode);

        TotpCountdownRingView countdownRing = new TotpCountdownRingView(this);
        card.addView(countdownRing, new LinearLayout.LayoutParams(dp(36), dp(36)));

        ImageButton eyeBtn = iconButton(android.R.drawable.ic_menu_view, "Ẩn/Hiện mã TOTP");
        LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        eyeLp.leftMargin = dp(6);
        card.addView(eyeBtn, eyeLp);

        Runnable updateTotpDisplay = () -> {
            if (mkvTotpVisible) {
                try {
                    String key = VaultMasterKeyStore.load(this);
                    String code = key != null ? VaultMasterKeyStore.currentTotp(key) : "------";
                    if (code.length() == 6) {
                        totpCode.setText(code.substring(0, 3) + " " + code.substring(3));
                    } else {
                        totpCode.setText(code);
                    }
                } catch (Exception ex) {
                    totpCode.setText("------");
                }
                totpCode.setTextColor(Color.rgb(56, 189, 248));
                eyeBtn.setColorFilter(Color.rgb(56, 189, 248));
            } else {
                totpCode.setText("● ● ● ● ● ●");
                totpCode.setTextColor(Color.rgb(148, 163, 184));
                eyeBtn.setColorFilter(Color.rgb(148, 163, 184));
            }
        };

        updateTotpDisplay.run();

        countdownRing.setOnRolloverListener(() -> runOnUiThread(updateTotpDisplay));

        eyeBtn.setOnClickListener(v -> {
            mkvTotpVisible = !mkvTotpVisible;
            updateTotpDisplay.run();
        });

        final float revealWidth = dp(84);
        card.setOnTouchListener(new View.OnTouchListener() {
            private float startX = 0f;
            private float startY = 0f;
            private float initialTranslation = 0f;
            private boolean isSwiping = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        initialTranslation = card.getTranslationX();
                        isSwiping = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;

                        if (!isSwiping) {
                            if (Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                                isSwiping = true;
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }

                        if (isSwiping) {
                            float target = initialTranslation + dx;
                            if (target < 0) target = 0;
                            if (target > revealWidth * 1.3f) target = revealWidth * 1.3f;
                            card.setTranslationX(target);
                            return true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if (isSwiping) {
                            float trans = card.getTranslationX();
                            if (trans > revealWidth * 0.45f) {
                                card.animate().translationX(revealWidth).setDuration(180).start();
                            } else {
                                card.animate().translationX(0).setDuration(180).start();
                            }
                            return true;
                        }
                        if (initialTranslation > dp(10)) {
                            card.animate().translationX(0).setDuration(180).start();
                            return true;
                        }
                        break;
                }
                return true;
            }
        });
    }

    private void showDevices() {
        devices.removeAllViews();
        boolean hasMkv = VaultMasterKeyStore.hasKey(this);
        if (hasMkv) {
            renderMkvCard();
        }

        List<LanClient.SavedPc> saved = lanClient.savedPcs();
        if (saved.isEmpty()) {
            if (!hasMkv) {
                status.setText("");
                TextView empty = new TextView(this);
                empty.setText("Chưa có PC\n\nNhấn + để thêm");
                empty.setTextColor(Color.rgb(145, 163, 186));
                empty.setTextSize(18);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(100), 0, 0);
                devices.addView(empty, new LinearLayout.LayoutParams(-1, dp(260)));
            } else {
                status.setText("Chưa có PC nào được lưu  •  Đã kích hoạt MasterKeyVault");
            }
            return;
        }
        status.setText(saved.size() + " PC đã lưu  •  tự cập nhật mỗi 5 giây");
        String currentSsid = lanClient.currentSsid();
        for (LanClient.SavedPc item : saved) {
            LanClient.Pc pc = online.get(item.deviceId);
            boolean connected = pc != null;
            boolean hasFast = (pc != null && pc.fastActive()) || item.fastActive();

            FrameLayout container = new FrameLayout(this);
            LinearLayout.LayoutParams containerLayout = new LinearLayout.LayoutParams(-1, -2);
            containerLayout.bottomMargin = dp(12);
            devices.addView(container, containerLayout);

            FrameLayout deleteUnderlay = new FrameLayout(this);
            deleteUnderlay.setBackground(round(Color.rgb(220, 38, 38), 20));
            container.addView(deleteUnderlay, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout deleteAction = new LinearLayout(this);
            deleteAction.setOrientation(LinearLayout.VERTICAL);
            deleteAction.setGravity(Gravity.CENTER);
            deleteAction.setPadding(dp(16), 0, dp(16), 0);
            deleteUnderlay.addView(deleteAction, new FrameLayout.LayoutParams(dp(84), -1, Gravity.START | Gravity.CENTER_VERTICAL));

            ImageView trashIcon = new ImageView(this);
            trashIcon.setImageResource(android.R.drawable.ic_menu_delete);
            trashIcon.setColorFilter(Color.WHITE);
            deleteAction.addView(trashIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));

            TextView trashLabel = new TextView(this);
            trashLabel.setText("Xóa");
            trashLabel.setTextColor(Color.WHITE);
            trashLabel.setTextSize(12);
            trashLabel.setTypeface(null, Typeface.BOLD);
            trashLabel.setPadding(0, dp(2), 0, 0);
            deleteAction.addView(trashLabel);

            deleteUnderlay.setOnClickListener(view -> confirmForget(item, pc));

            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(16), dp(12), dp(16));
            card.setBackground(round(Color.rgb(24, 36, 55), 20));
            container.addView(card, new FrameLayout.LayoutParams(-1, -2));

            ImageView pcIcon = new ImageView(this);
            pcIcon.setImageResource(R.drawable.ic_pc);
            pcIcon.setColorFilter(connected ? Color.rgb(80, 211, 177) : Color.rgb(130, 145, 165));
            card.addView(pcIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, dp(6), 0);
            card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            labels.addView(nameRow);

            TextView name = new TextView(this);
            name.setText(connected ? pc.name : item.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(17);
            name.setTypeface(null, Typeface.BOLD);
            nameRow.addView(name);

            if (hasFast) {
                TextView fastBadge = new TextView(this);
                fastBadge.setText("⚡ Mở nhanh");
                fastBadge.setTextColor(Color.rgb(52, 211, 153));
                fastBadge.setTextSize(11);
                fastBadge.setTypeface(null, Typeface.BOLD);
                fastBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
                fastBadge.setBackground(round(Color.argb(50, 16, 185, 129), 8));
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
                badgeLp.leftMargin = dp(8);
                nameRow.addView(fastBadge, badgeLp);
                fastBadge.setOnClickListener(v -> showFastUnlockDetails(item, pc));
            }

            TextView detail = new TextView(this);
            String offlineState = currentSsid == null || item.ssid == null ? "●  Không kết nối" :
                    currentSsid.equals(item.ssid) ? "●  Tắt / không phản hồi" : "●  Mất kết nối";
            detail.setText(!connected ? offlineState :
                    !item.paired ? "●  Chưa ghép đôi" :
                    pc.pending ? "●  Chờ mở khóa" :
                    "locked".equals(pc.sessionState) ? "●  Đã khóa" :
                    "unlocked".equals(pc.sessionState) ? "●  Đã mở" : "●  Trực tuyến");
            detail.setTextColor(!connected ? Color.rgb(145, 163, 186) :
                    pc.pending || "locked".equals(pc.sessionState) ? Color.rgb(255, 179, 83) : Color.rgb(80, 211, 177));
            detail.setTextSize(13);
            detail.setPadding(0, dp(5), 0, 0);
            labels.addView(detail);

            if (connected && (!item.paired || pc.pending)) {
                LinearLayout actionBtn = new LinearLayout(this);
                actionBtn.setOrientation(LinearLayout.HORIZONTAL);
                actionBtn.setGravity(Gravity.CENTER);
                actionBtn.setBackground(round(pc.pending ? Color.rgb(245, 158, 11) : Color.rgb(31, 117, 230), 16));
                actionBtn.setPadding(dp(12), dp(8), dp(14), dp(8));

                ImageView actionIcon = new ImageView(this);
                actionIcon.setImageResource(item.paired ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_menu_send);
                actionIcon.setColorFilter(Color.WHITE);
                actionBtn.addView(actionIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));

                TextView actionText = new TextView(this);
                actionText.setText(item.paired ? " Duyệt" : " Ghép");
                actionText.setTextColor(Color.WHITE);
                actionText.setTextSize(13);
                actionText.setTypeface(null, Typeface.BOLD);
                actionBtn.addView(actionText);

                actionBtn.setOnClickListener(view -> {
                    if (item.paired) approvePending(pc);
                    else pairPhone(pc);
                });
                card.addView(actionBtn, new LinearLayout.LayoutParams(-2, dp(38)));
            }

            final float revealWidth = dp(84);
            final Runnable[] longPressRef = new Runnable[1];
            card.setOnTouchListener(new View.OnTouchListener() {
                private float startX = 0f;
                private float startY = 0f;
                private float initialTranslation = 0f;
                private boolean isSwiping = false;
                private boolean longPressed = false;
                private long downTime = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = event.getRawX();
                            startY = event.getRawY();
                            initialTranslation = card.getTranslationX();
                            isSwiping = false;
                            longPressed = false;
                            downTime = System.currentTimeMillis();

                            longPressRef[0] = () -> {
                                longPressed = true;
                                card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                showFastUnlockDetails(item, pc);
                            };
                            card.postDelayed(longPressRef[0], ViewConfiguration.getLongPressTimeout());
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - startX;
                            float dy = event.getRawY() - startY;

                            if (Math.hypot(dx, dy) > dp(10) && longPressRef[0] != null) {
                                card.removeCallbacks(longPressRef[0]);
                                longPressRef[0] = null;
                            }

                            if (!isSwiping) {
                                if (Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                                    isSwiping = true;
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                            }

                            if (isSwiping) {
                                float target = initialTranslation + dx;
                                if (target < 0) target = 0;
                                if (target > revealWidth * 1.3f) target = revealWidth * 1.3f;
                                card.setTranslationX(target);
                                return true;
                            }
                            break;

                        case MotionEvent.ACTION_UP:
                            if (longPressRef[0] != null) {
                                card.removeCallbacks(longPressRef[0]);
                                longPressRef[0] = null;
                            }
                            v.getParent().requestDisallowInterceptTouchEvent(false);

                            if (isSwiping) {
                                float trans = card.getTranslationX();
                                if (trans > revealWidth * 0.45f) {
                                    card.animate().translationX(revealWidth).setDuration(180).start();
                                } else {
                                    card.animate().translationX(0).setDuration(180).start();
                                }
                                return true;
                            }

                            if (!longPressed) {
                                if (initialTranslation > dp(10)) {
                                    card.animate().translationX(0).setDuration(180).start();
                                    return true;
                                }
                                if (Math.hypot(event.getRawX() - startX, event.getRawY() - startY) < dp(12) &&
                                        System.currentTimeMillis() - downTime < 500) {
                                    if (connected && item.paired && pc != null && pc.pending) {
                                        approvePending(pc);
                                    } else {
                                        showFastUnlockDetails(item, pc);
                                    }
                                    return true;
                                }
                            }
                            break;

                        case MotionEvent.ACTION_CANCEL:
                            if (longPressRef[0] != null) {
                                card.removeCallbacks(longPressRef[0]);
                                longPressRef[0] = null;
                            }
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            if (isSwiping) {
                                float trans = card.getTranslationX();
                                card.animate().translationX(trans > revealWidth * 0.5f ? revealWidth : 0).setDuration(180).start();
                            }
                            break;
                    }
                    return true;
                }
            });
        }
    }

    private void showFastUnlockDetails(LanClient.SavedPc item, LanClient.Pc pc) {
        String pcName = pc != null ? pc.name : item.name;
        String rawExpires = (pc != null && !pc.fastExpiresAtUtc.isEmpty()) ? pc.fastExpiresAtUtc : item.fastExpiresAtUtc;
        if (rawExpires.isEmpty()) {
            rawExpires = lanClient.fastExpiresAt(item.deviceId);
        }

        boolean active = false;
        String remainingText = "Chưa kích hoạt";
        String expireDateText = "Không có";

        if (!rawExpires.isEmpty()) {
            try {
                java.time.Instant expires = java.time.Instant.parse(rawExpires);
                java.time.Instant now = java.time.Instant.now();
                active = expires.isAfter(now);

                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                        .ofPattern("HH:mm - dd/MM/yyyy")
                        .withZone(java.time.ZoneId.systemDefault());
                expireDateText = formatter.format(expires);

                if (active) {
                    long totalSeconds = java.time.Duration.between(now, expires).getSeconds();
                    long days = totalSeconds / 86400;
                    long hours = (totalSeconds % 86400) / 3600;
                    long minutes = (totalSeconds % 3600) / 60;
                    if (days > 0) {
                        remainingText = days + " ngày " + hours + " giờ " + minutes + " phút";
                    } else if (hours > 0) {
                        remainingText = hours + " giờ " + minutes + " phút";
                    } else {
                        remainingText = minutes + " phút " + (totalSeconds % 60) + " giây";
                    }
                } else {
                    remainingText = "Đã hết hạn";
                }
            } catch (Exception ignored) { }
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), dp(16));
        scroll.addView(content);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        int cardBgColor = active ? Color.rgb(236, 253, 245) : Color.rgb(241, 245, 249);
        statusCard.setBackground(round(cardBgColor, 16));
        content.addView(statusCard);

        TextView statusHeader = new TextView(this);
        statusHeader.setText(active ? "🟢 ĐANG HOẠT ĐỘNG" : (!rawExpires.isEmpty() ? "🔴 ĐÃ HẾT HẠN" : "⚪ CHƯA KÍCH HOẠT"));
        statusHeader.setTextColor(active ? Color.rgb(5, 150, 105) : Color.rgb(100, 116, 139));
        statusHeader.setTextSize(12);
        statusHeader.setTypeface(null, Typeface.BOLD);
        statusCard.addView(statusHeader);

        TextView timeLabel = new TextView(this);
        timeLabel.setText(remainingText);
        timeLabel.setTextColor(active ? Color.rgb(4, 120, 87) : Color.rgb(30, 41, 59));
        timeLabel.setTextSize(22);
        timeLabel.setTypeface(null, Typeface.BOLD);
        timeLabel.setPadding(0, dp(6), 0, dp(4));
        statusCard.addView(timeLabel);

        if (!rawExpires.isEmpty()) {
            TextView expireDate = new TextView(this);
            expireDate.setText((active ? "Hết hạn vào: " : "Đã hết hạn vào lúc: ") + expireDateText);
            expireDate.setTextColor(Color.rgb(100, 116, 139));
            expireDate.setTextSize(13);
            statusCard.addView(expireDate);
        }

        TextView desc = new TextView(this);
        desc.setPadding(0, dp(16), 0, 0);
        desc.setLineSpacing(dp(4), 1.1f);
        desc.setTextSize(13);
        desc.setTextColor(Color.rgb(51, 65, 85));

        String htmlDesc = "<b>⚡ Fast Unlock là gì?</b><br>"
                + "Tính năng cho phép bạn mở khóa máy tính <b>" + pcName + "</b> tức thì ngay từ Widget hoặc ứng dụng mà không cần chờ hay quét vân tay lặp lại, miễn là điện thoại đang ở trạng thái đã mở khóa.<br><br>"
                + "<b>🔒 Cách kích hoạt hoặc gia hạn:</b><br>"
                + "Mỗi lần bạn duyệt mở khóa PC bằng vân tay trên điện thoại thành công, thời hạn Fast Unlock sẽ được tự động kích hoạt hoặc gia hạn thêm theo chính sách bảo mật của PC (mặc định 7 ngày).";

        desc.setText(Html.fromHtml(htmlDesc, Html.FROM_HTML_MODE_COMPACT));
        content.addView(desc);

        new AlertDialog.Builder(this)
                .setTitle("⚡ Mở Nhanh - " + pcName)
                .setView(scroll)
                .setPositiveButton("Đóng", null)
                .show();
    }

    private void pairPhone(LanClient.Pc pc) {
        status.setText("Confirming setup QR with the current TOTP code...");
        background.execute(() -> {
            try {
                lanClient.pair(pc);
                runOnUiThread(() -> {
                    status.setText("Phone enrollment confirmed. Future approvals require TOTP and biometric confirmation.");
                    Toast.makeText(this, "TOTP enrollment confirmed", Toast.LENGTH_SHORT).show();
                    showDevices();
                    scanLan();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Enrollment confirmation failed: " + error.getMessage()));
            }
        });
    }

    private void forgetPc(LanClient.Pc pc) {
        status.setText("Đang thu hồi phương thức WinHello trên PC...");
        background.execute(() -> {
            try {
                lanClient.unpair(pc);
                runOnUiThread(() -> {
                    status.setText("Đã gỡ WinHello trên PC và xóa dữ liệu trên điện thoại.");
                    showDevices();
                    scanLan();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText(error.getMessage().contains("423")
                        ? "Hãy mở khóa Windows rồi thử xóa lại."
                        : "Không thể thu hồi ghép đôi: " + error.getMessage()));
            }
        });
    }

    private void confirmForget(LanClient.SavedPc item, LanClient.Pc pc) {
        if (pc != null && !"unlocked".equals(pc.sessionState)) {
            new AlertDialog.Builder(this)
                    .setTitle("Hãy mở khóa Windows")
                    .setMessage("PC phải đang mở trong Windows để thu hồi và ẩn phương thức đăng nhập WinHello.")
                    .setPositiveButton("Đã hiểu", null)
                    .setOnDismissListener(dialog -> showDevices())
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Xóa " + item.name + "?")
                .setMessage(pc == null
                        ? "Xóa dữ liệu trên điện thoại sau xác nhận vân tay. PC đang offline nên khóa đã ghép trên PC chưa được thu hồi."
                        : "Xác nhận vân tay để thu hồi ghép đôi, ẩn tùy chọn WinHello trên PC và xóa dữ liệu trên điện thoại.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xác nhận vân tay", (dialog, which) -> authenticateForget(item, pc))
                .setOnDismissListener(dialog -> showDevices())
                .show();
    }

    private void authenticateForget(LanClient.SavedPc item, LanClient.Pc pc) {
        BiometricManager biometricManager = getSystemService(BiometricManager.class);
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText("Cần cài đặt vân tay hoặc sinh trắc học mạnh để xóa PC.");
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Xác nhận xóa " + item.name)
                .setSubtitle("Dùng vân tay để xác nhận")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Hủy", getMainExecutor(), (dialog, which) -> { })
                .setConfirmationRequired(true)
                .build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        if (pc != null) forgetPc(pc);
                        else {
                            background.execute(() -> {
                                try {
                                    lanClient.forgetLocal(item.deviceId);
                                    runOnUiThread(() -> {
                                        online.remove(item.deviceId);
                                        showDevices();
                                        status.setText("Đã xóa trên điện thoại. PC chưa thu hồi khóa ghép đôi.");
                                    });
                                } catch (Exception error) {
                                    runOnUiThread(() -> status.setText("Không thể xóa PC: " + error.getMessage()));
                                }
                            });
                        }
                    }
                });
    }
    private void approvePending(LanClient.Pc pc) {
        status.setText("Checking for a pending PC request…");
        background.execute(() -> {
            try {
                LanClient.Challenge challenge = lanClient.getPendingChallenge(pc);
                if (challenge == null) {
                    runOnUiThread(() -> status.setText("No pending request. Select WinHello Phone on the Windows sign-in screen, then try again."));
                    return;
                }
                Signature signature = PhoneSigningKey.newSignature(lanClient.keyAlias(pc));
                runOnUiThread(() -> showBiometricPrompt(pc, challenge, signature));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not prepare approval: " + error.getMessage()));
            }
        });
    }

    private void showBiometricPrompt(LanClient.Pc pc, LanClient.Challenge challenge, Signature signature) {
        BiometricManager biometricManager = getSystemService(BiometricManager.class);
        int availability = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText("Set up a strong fingerprint/biometric on this phone before approving.");
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Confirm your fingerprint")
                .setSubtitle("Approve sign-in to " + pc.name)
                .setDescription("The biometric unlocks a one-time signing key. It is not sent to the PC.")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel", getMainExecutor(), (dialog, which) -> {
                    status.setText("Approval canceled.");
                })
                .setConfirmationRequired(true)
                .build();

        prompt.authenticate(
                new BiometricPrompt.CryptoObject(signature),
                new CancellationSignal(),
                getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        background.execute(() -> signAndApprove(pc, challenge, result));
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        status.setText("Approval canceled: " + errString);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        status.setText("Fingerprint was not recognized. Try again.");
                    }
                });
        status.setText("Waiting for fingerprint confirmation…");
    }

    private void signAndApprove(LanClient.Pc pc, LanClient.Challenge challenge,
                                BiometricPrompt.AuthenticationResult result) {
        try {
            Signature signing = result.getCryptoObject().getSignature();
            String payload = challenge.pcDeviceId + "\n" + challenge.requestId + "\n" + challenge.nonceBase64;
            signing.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] signature = signing.sign();
            lanClient.approve(pc, challenge, signature);
            runOnUiThread(() -> {
                status.setText("Phone approval accepted by " + pc.name + ". Windows sign-in is completing...");
                scanLan();
            });
        } catch (Exception error) {
            runOnUiThread(() -> status.setText("Approval failed: " + error.getMessage()));
        }
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(Color.rgb(170, 205, 245));
        button.setContentDescription(description);
        button.setBackground(round(Color.rgb(33, 50, 73), 14));
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        return button;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }


    private void showAppInfoDialog() {
        LinearLayout customTitle = new LinearLayout(this);
        customTitle.setOrientation(LinearLayout.VERTICAL);
        customTitle.setPadding(dp(24), dp(20), dp(24), dp(8));

        TextView appTitle = new TextView(this);
        appTitle.setText("WinHello");
        appTitle.setTextSize(21);
        appTitle.setTypeface(null, Typeface.BOLD);
        appTitle.setTextColor(Color.rgb(30, 41, 59));

        TextView versionLabel = new TextView(this);
        versionLabel.setText("Phiên bản: " + appVersion());
        versionLabel.setTextSize(14);
        versionLabel.setTextColor(Color.rgb(31, 117, 230));
        versionLabel.setTypeface(null, Typeface.BOLD);
        versionLabel.setPadding(0, dp(4), 0, 0);

        TextView hintLabel = new TextView(this);
        hintLabel.setText("(Nhấn giữ số phiên bản để kiểm tra cập nhật)");
        hintLabel.setTextSize(12);
        hintLabel.setTextColor(Color.rgb(100, 116, 139));
        hintLabel.setPadding(0, dp(2), 0, 0);

        customTitle.addView(appTitle);
        customTitle.addView(versionLabel);
        customTitle.addView(hintLabel);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(16));
        scroll.addView(body);

        TextView content = new TextView(this);
        content.setLineSpacing(dp(4), 1.15f);
        content.setTextSize(13);
        content.setTextColor(Color.rgb(51, 65, 85));

        String guideHtml = ""
                + "<font color='#0284c7'><b>➕ THÊM &amp; GHÉP ĐÔI PC</b></font><br>"
                + "&bull; Nhấn nút <b><font color='#1d4ed8'>[ + ]</font></b> màu xanh ở góc dưới để quét mã <b><font color='#0f172a'>QR TOTP</font></b> trên máy tính.<br>"
                + "&bull; Xác nhận ghép đôi một lần duy nhất để lưu khóa bảo mật liên kết hai thiết bị.<br><br>"

                + "<font color='#0284c7'><b>🔄 DÒ TÌM TRONG MẠNG LAN</b></font><br>"
                + "&bull; Khi mở ứng dụng: Tự động dò tìm các PC đã lưu <b><font color='#059669'>mỗi 5 giây</font></b>.<br>"
                + "&bull; Khi app đóng: Nhấn biểu tượng <b><font color='#d97706'>↻ (Làm mới)</font></b> trên Widget để quét dò một lần.<br>"
                + "&bull; <i>Widget không quét ngầm liên tục nhằm tiết kiệm tối đa pin điện thoại.</i><br><br>"

                + "<font color='#0284c7'><b>⚡ FAST UNLOCK (MỞ NHANH)</b></font><br>"
                + "&bull; Cho phép mở khóa PC tức thì từ <b><font color='#059669'>Widget</font></b> hoặc ứng dụng khi màn hình điện thoại đã mở.<br>"
                + "&bull; <b><font color='#2563eb'>Nhấn giữ thẻ PC</font></b> để xem chi tiết thời gian hiệu lực còn lại.<br>"
                + "&bull; Tự động gia hạn thêm sau mỗi lần duyệt mở khóa bằng vân tay thành công.<br><br>"

                + "<font color='#0284c7'><b>👆 DUYỆT MỞ KHÓA PC</b></font><br>"
                + "&bull; Khi PC ở màn hình khóa, nhấn <b><font color='#2563eb'>Duyệt mở khóa</font></b> trên Widget hoặc nhấn trực tiếp thẻ PC rồi <b><font color='#0f172a'>xác thực vân tay</font></b>.<br><br>"

                + "<font color='#0284c7'><b>🔐 MASTERKEYVAULT (MKV)</b></font><br>"
                + "&bull; Nhấn nút <b><font color='#1d4ed8'>[ + ]</font></b> quét mã <b><font color='#0f172a'>QR Authenticator</font></b> từ MKV để lưu Master Key vào Keystore an toàn.<br>"
                + "&bull; Thẻ MKV tự động xuất hiện ở <b><font color='#059669'>đầu danh sách</font></b> với mã 6 số TOTP và <b><font color='#0284c7'>vòng tròn đếm ngược 30 giây</font></b> khuyết dần.<br>"
                + "&bull; Nhấn biểu tượng <b><font color='#d97706'>[ 👁️ ]</font></b> trên thẻ để ẩn hoặc hiện mã 6 số.<br>"
                + "&bull; <b>Mở khóa DPAPI</b>: Khi PC yêu cầu mở khóa két, nhấn nút <b><font color='#1d4ed8'>[ + ]</font></b> quét mã QR trên màn hình PC và <b><font color='#059669'>xác thực vân tay</font></b> để mở ngay lập tức.<br>"
                + "&bull; <b>Xóa Master Key</b>: <b><font color='#dc2626'>Vuốt thẻ MKV sang phải</font></b> và xác thực vân tay để xóa key bảo vệ.<br><br>"

                + "<font color='#0284c7'><b>🗑️ XÓA THIẾT BỊ (THAO TÁC VUỐT)</b></font><br>"
                + "&bull; <b><font color='#dc2626'>Vuốt thẻ PC sang phải</font></b> để mở nút <b>Xóa</b> màu đỏ.<br>"
                + "&bull; Có thể xóa thiết bị cả khi offline (<i>lưu ý: PC sẽ chưa kịp thu hồi khóa nếu xóa lúc offline</i>).<br><br>"

                + "<font color='#0284c7'><b>📶 TRẠNG THÁI &amp; KẾT NỐI WI-FI</b></font><br>"
                + "&bull; <b><font color='#d97706'>Tắt / không phản hồi</font></b>: Điện thoại và PC đang dùng chung Wi-Fi đã lưu nhưng PC tắt hoặc đang ngủ.<br>"
                + "&bull; <b><font color='#64748b'>Mất kết nối</font></b>: Điện thoại đang kết nối sang một mạng Wi-Fi khác.<br>"
                + "&bull; <i>Bắt buộc cấp quyền Vị trí (Location) để Android cho phép ứng dụng đọc tên SSID mạng Wi-Fi hiện tại.</i>";

        content.setText(Html.fromHtml(guideHtml, Html.FROM_HTML_MODE_COMPACT));
        body.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(customTitle)
                .setView(scroll)
                .setPositiveButton("Đã hiểu", null)
                .create();

        View.OnLongClickListener triggerCheck = v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            dialog.dismiss();
            checkForAppUpdate();
            return true;
        };

        versionLabel.setOnLongClickListener(triggerCheck);
        appTitle.setOnLongClickListener(triggerCheck);
        hintLabel.setOnLongClickListener(triggerCheck);

        dialog.show();
    }

    private void checkForAppUpdate() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(24), dp(20), dp(24), dp(20));

        ProgressBar spinner = new ProgressBar(this);
        layout.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView msg = new TextView(this);
        msg.setText("Đang kết nối đến máy chủ kiểm tra bản cập nhật...");
        msg.setTextSize(14);
        msg.setTextColor(Color.rgb(30, 41, 59));
        msg.setPadding(dp(16), 0, 0, 0);
        layout.addView(msg, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Kiểm tra cập nhật")
                .setView(layout)
                .setNegativeButton("Hủy", (d, w) -> d.dismiss())
                .setCancelable(true)
                .create();
        progressDialog.show();

        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection conn = null;
            try {
                String baseUrl = BuildConfig.UPDATE_BASE_URL;
                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    baseUrl = "https://api-183.interface-daodung.workers.dev";
                }
                URL url = new URL(baseUrl.replaceAll("/+$", "") + "/latest.json");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("Máy chủ trả về HTTP " + code);
                }

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] temp = new byte[4096];
                    int r;
                    while ((r = in.read(temp)) != -1) {
                        buffer.write(temp, 0, r);
                    }
                }
                String body = buffer.toString(StandardCharsets.UTF_8.name());
                JSONObject json = new JSONObject(body);
                String remoteVersion = json.optString("version", "").trim();
                String apkName = json.optString("apk", "").trim();
                String sha256 = json.optString("sha256", "").trim();

                String currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                final String finalBaseUrl = baseUrl;

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressDialog.dismiss();

                    if (isNewerVersion(remoteVersion, currentVersion)) {
                        showUpdateAvailableDialog(remoteVersion, currentVersion, apkName, sha256, finalBaseUrl);
                    } else {
                        showUpToDateDialog(currentVersion);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressDialog.dismiss();
                    showUpdateErrorDialog(e.getMessage());
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private boolean isNewerVersion(String remote, String current) {
        if (remote == null || remote.trim().isEmpty()) return false;
        if (current == null || current.trim().isEmpty()) return true;

        String r = remote.trim();
        if (r.startsWith("v") || r.startsWith("V")) r = r.substring(1).trim();
        String c = current.trim();
        if (c.startsWith("v") || c.startsWith("V")) c = c.substring(1).trim();

        String[] rParts = r.split("\\.");
        String[] cParts = c.split("\\.");
        int max = Math.max(rParts.length, cParts.length);

        for (int i = 0; i < max; i++) {
            int rNum = 0;
            if (i < rParts.length) {
                try { rNum = Integer.parseInt(rParts[i].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            }
            int cNum = 0;
            if (i < cParts.length) {
                try { cNum = Integer.parseInt(cParts[i].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            }
            if (rNum > cNum) return true;
            if (rNum < cNum) return false;
        }
        return false;
    }

    private void showUpdateAvailableDialog(String remoteVersion, String currentVersion, String apkName, String sha256, String baseUrl) {
        String msg = "Đã có phiên bản mới sẵn sàng để cập nhật:\n\n" +
                "• Phiên bản hiện tại: v" + currentVersion + "\n" +
                "• Phiên bản mới nhất: v" + remoteVersion + "\n\n" +
                "Bạn có muốn tải về và cài đặt bản cập nhật này không?";

        new AlertDialog.Builder(this)
                .setTitle("Có bản cập nhật mới!")
                .setMessage(msg)
                .setPositiveButton("Tải về", (dialog, which) -> {
                    startDownloadAndInstall(remoteVersion, apkName, sha256, baseUrl);
                })
                .setNegativeButton("Để sau", null)
                .show();
    }

    private void showUpToDateDialog(String currentVersion) {
        new AlertDialog.Builder(this)
                .setTitle("Kiểm tra cập nhật")
                .setMessage("Bạn đang sử dụng phiên bản mới nhất (" + (currentVersion.startsWith("v") ? currentVersion : "v" + currentVersion) + ").")
                .setPositiveButton("Đã hiểu", null)
                .show();
    }

    private void showUpdateErrorDialog(String errorDetail) {
        String message = "Không thể kiểm tra bản cập nhật mới.\n";
        if (errorDetail != null && !errorDetail.trim().isEmpty()) {
            message += "\nChi tiết: " + errorDetail;
        } else {
            message += "\nVui lòng kiểm tra lại kết nối mạng hoặc thử lại sau.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Kiểm tra cập nhật")
                .setMessage(message)
                .setPositiveButton("Đóng", null)
                .setNeutralButton("Thử lại", (dialog, which) -> checkForAppUpdate())
                .show();
    }

    private void startDownloadAndInstall(String remoteVersion, String apkName, String sha256, String baseUrl) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(16));

        TextView statusText = new TextView(this);
        statusText.setText("Đang chuẩn bị tải v" + remoteVersion + "...");
        statusText.setTextSize(14);
        statusText.setTextColor(Color.rgb(30, 41, 59));
        box.addView(statusText);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(false);
        pb.setMax(100);
        pb.setProgress(0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, dp(18));
        pbLp.setMargins(0, dp(12), 0, dp(8));
        box.addView(pb, pbLp);

        TextView progressDetail = new TextView(this);
        progressDetail.setText("0%");
        progressDetail.setTextSize(12);
        progressDetail.setTextColor(Color.rgb(100, 116, 139));
        box.addView(progressDetail);

        AtomicBoolean cancelled = new AtomicBoolean(false);

        AlertDialog downloadDialog = new AlertDialog.Builder(this)
                .setTitle("Tải về bản cập nhật")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Hủy", (d, w) -> {
                    cancelled.set(true);
                    d.dismiss();
                })
                .create();
        downloadDialog.show();

        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            File tempApk = null;
            try {
                String downloadUrl = apkName.startsWith("http://") || apkName.startsWith("https://")
                        ? apkName
                        : (baseUrl.replaceAll("/+$", "") + "/" + apkName);

                URL url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("Máy chủ trả về HTTP " + responseCode);
                }

                long totalBytes = conn.getContentLength();
                tempApk = new File(getCacheDir(), "winhello-update.apk");
                if (tempApk.exists()) tempApk.delete();

                in = conn.getInputStream();
                out = new FileOutputStream(tempApk);
                MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");

                byte[] buf = new byte[32768];
                long downloadedBytes = 0;
                int read;
                long lastUiUpdate = 0;

                while ((read = in.read(buf)) != -1) {
                    if (cancelled.get()) {
                        out.close();
                        tempApk.delete();
                        return;
                    }
                    out.write(buf, 0, read);
                    sha256Digest.update(buf, 0, read);
                    downloadedBytes += read;

                    long now = System.currentTimeMillis();
                    if (now - lastUiUpdate > 100 || downloadedBytes == totalBytes) {
                        lastUiUpdate = now;
                        final long cur = downloadedBytes;
                        final long tot = totalBytes;
                        runOnUiThread(() -> {
                            if (cancelled.get()) return;
                            if (tot > 0) {
                                int percent = (int) ((cur * 100) / tot);
                                pb.setProgress(percent);
                                statusText.setText("Đang tải: " + percent + "%");
                                progressDetail.setText(String.format(Locale.US, "%.1f MB / %.1f MB", cur / 1048576.0, tot / 1048576.0));
                            } else {
                                pb.setIndeterminate(true);
                                statusText.setText("Đang tải bản cập nhật...");
                                progressDetail.setText(String.format(Locale.US, "%.1f MB đã tải", cur / 1048576.0));
                            }
                        });
                    }
                }
                out.flush();
                out.close();
                out = null;

                if (sha256 != null && !sha256.trim().isEmpty()) {
                    byte[] digest = sha256Digest.digest();
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest) {
                        hex.append(String.format("%02x", b));
                    }
                    if (!hex.toString().equalsIgnoreCase(sha256.trim())) {
                        tempApk.delete();
                        throw new IOException("Mã kiểm tra tệp (SHA-256) không khớp với thông tin phát hành.");
                    }
                }

                final File finalApk = tempApk;
                runOnUiThread(() -> {
                    if (cancelled.get()) return;
                    downloadDialog.dismiss();
                    installDownloadedApk(finalApk);
                });

            } catch (Exception e) {
                if (tempApk != null && tempApk.exists()) {
                    tempApk.delete();
                }
                runOnUiThread(() -> {
                    if (cancelled.get()) return;
                    downloadDialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Tải bản cập nhật thất bại")
                            .setMessage("Lỗi: " + e.getMessage())
                            .setPositiveButton("Đóng", null)
                            .show();
                });
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void installDownloadedApk(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this)
                        .setTitle("Cấp quyền cài đặt")
                        .setMessage("Để tiếp tục cài đặt bản cập nhật, vui lòng cho phép WinHello cài đặt ứng dụng từ nguồn này trong Cài đặt hệ thống.")
                        .setPositiveButton("Mở Cài đặt", (d, w) -> {
                            try {
                                Intent manageIntent = new Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName())
                                );
                                startActivity(manageIntent);
                            } catch (Exception ex) {
                                Toast.makeText(this, "Không thể mở cài đặt: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Hủy", null)
                        .show();
                return;
            }
        }

        try {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            int sessionId = packageInstaller.createSession(params);
            PackageInstaller.Session session = packageInstaller.openSession(sessionId);

            try (OutputStream out = session.openWrite("winhello_update", 0, apkFile.length());
                 FileInputStream in = new FileInputStream(apkFile)) {
                byte[] buffer = new byte[65536];
                int c;
                while ((c = in.read(buffer)) != -1) {
                    out.write(buffer, 0, c);
                }
                session.fsync(out);
            }

            getSharedPreferences("winhello_update", MODE_PRIVATE).edit()
                    .putInt("session_id", sessionId)
                    .putBoolean("session_committed", true)
                    .apply();

            Intent callbackIntent = new Intent(this, UpdateInstallReceiver.class);
            callbackIntent.setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT);
            callbackIntent.putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, sessionId);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, sessionId, callbackIntent, flags);
            session.commit(pendingIntent.getIntentSender());
            session.close();
        } catch (Exception e) {
            Toast.makeText(this, "Không thể khởi chạy cài đặt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String appVersion() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }
}
