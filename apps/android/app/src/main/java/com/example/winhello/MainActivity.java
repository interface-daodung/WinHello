package com.example.winhello;

import android.app.Activity;
import android.app.AlertDialog;
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
        showDevices();
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
        info.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("WinHello")
                .setMessage("Nhấn + để quét QR TOTP trên PC. Máy đã ghép luôn nằm trong danh sách. App tự tìm máy trên LAN mỗi 5 giây khi màn hình này mở. Khi PC yêu cầu đăng nhập, nhấn biểu tượng khóa và xác nhận vân tay. Nhấn giữ thẻ PC khi đang online để xóa ghép đôi. Máy tắt hoặc mất kết nối vẫn được giữ trong danh sách.")
                .setPositiveButton("Đã hiểu", null).show());
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
                runOnUiThread(() -> {
                    boolean changed = online.size() != found.size();
                    for (LanClient.Pc pc : found) {
                        LanClient.Pc old = online.get(pc.deviceId);
                        if (old == null || old.pending != pc.pending ||
                                !old.sessionState.equals(pc.sessionState) || !old.name.equals(pc.name))
                            changed = true;
                    }
                    online.clear();
                    for (LanClient.Pc pc : found) online.put(pc.deviceId, pc);
                    scanInFlight = false;
                    if (changed) showDevices();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    boolean changed = !online.isEmpty();
                    online.clear();
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
            Uri uri = Uri.parse(result.getContents());
            String pcId = uri.getQueryParameter("pcid");
            String secret = uri.getQueryParameter("secret");
            String pin = uri.getQueryParameter("pin");
            if (!"winhello".equals(uri.getScheme()) || !"enroll".equals(uri.getHost()) ||
                    pcId == null || secret == null || pin == null || !pin.matches("(?i)[0-9a-f]{64}"))
                throw new IllegalArgumentException("This is not a valid WinHello setup QR.");
            java.util.UUID.fromString(pcId);
            if (!secret.matches("(?i)[A-Z2-7]{32,64}"))
                throw new IllegalArgumentException("The QR contains an invalid TOTP secret.");
            lanClient.saveEnrollment(pcId, secret, pin);
            showDevices();
            status.setText("Đã lưu QR. Đang tìm PC để xác nhận TOTP...");
            scanLan();
        } catch (Exception error) {
            status.setText("QR enrollment failed: " + error.getMessage());
        }
    }

    private void showDevices() {
        devices.removeAllViews();
        List<LanClient.SavedPc> saved = lanClient.savedPcs();
        if (saved.isEmpty()) {
            status.setText("");
            TextView empty = new TextView(this);
            empty.setText("Chưa có PC\n\nNhấn + để thêm");
            empty.setTextColor(Color.rgb(145, 163, 186));
            empty.setTextSize(18);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(100), 0, 0);
            devices.addView(empty, new LinearLayout.LayoutParams(-1, dp(260)));
            return;
        }
        status.setText(saved.size() + " PC đã lưu  •  tự cập nhật mỗi 5 giây");
        for (LanClient.SavedPc item : saved) {
            LanClient.Pc pc = online.get(item.deviceId);
            boolean connected = pc != null;
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(16), dp(10), dp(16));
            card.setBackground(round(Color.rgb(24, 36, 55), 20));
            LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, -2);
            cardLayout.bottomMargin = dp(12);
            devices.addView(card, cardLayout);

            ImageView pcIcon = new ImageView(this);
            pcIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
            pcIcon.setColorFilter(connected ? Color.rgb(80, 211, 177) : Color.rgb(130, 145, 165));
            card.addView(pcIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, dp(4), 0);
            card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            TextView name = new TextView(this);
            name.setText(connected ? pc.name : item.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(17);
            name.setTypeface(null, 1);
            labels.addView(name);

            TextView detail = new TextView(this);
            detail.setText(!connected ? "●  Tắt / không kết nối" :
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
                ImageButton action = iconButton(item.paired ? android.R.drawable.ic_lock_idle_lock :
                        android.R.drawable.ic_menu_send, item.paired ? "Duyệt mở khóa" : "Xác nhận ghép đôi");
                action.setOnClickListener(view -> {
                    if (item.paired) approvePending(pc);
                    else pairPhone(pc);
                });
                card.addView(action, new LinearLayout.LayoutParams(dp(44), dp(44)));
            }
            card.setOnLongClickListener(view -> {
                if (pc == null) {
                    Toast.makeText(this, "Kết nối PC để thu hồi ghép đôi", Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Xóa ghép đôi?")
                            .setMessage("Thu hồi khóa trên PC và xóa TOTP trên điện thoại.")
                            .setNegativeButton("Hủy", null)
                            .setPositiveButton("Xóa", (dialog, which) -> forgetPc(pc)).show();
                }
                return true;
            });
        }
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
        status.setText("Revoking the phone key on the PC...");
        background.execute(() -> {
            try {
                lanClient.unpair(pc);
                runOnUiThread(() -> {
                    status.setText("Pairing revoked on the PC and local TOTP removed.");
                    showDevices();
                    scanLan();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not revoke pairing: " + error.getMessage()));
            }
        });
    }
    private void approvePending(LanClient.Pc pc) {
        status.setText("Checking for a pending PC request…");
        background.execute(() -> {
            try {
                LanClient.Challenge challenge = lanClient.getPendingChallenge(pc);
                if (challenge == null) {
                    runOnUiThread(() -> status.setText("No pending request. Select Request phone approval on the Windows tile, then scan again."));
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
}
