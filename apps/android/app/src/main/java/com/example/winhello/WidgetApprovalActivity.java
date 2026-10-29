package com.example.winhello;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WidgetApprovalActivity extends Activity {
    static final String ACTION_APPROVE = "com.example.winhello.WIDGET_APPROVE";
    static final String EXTRA_PC_ID = "pc_id";
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private LanClient client;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        client = new LanClient(this);
        String pcId = getIntent().getStringExtra(EXTRA_PC_ID);
        if (!ACTION_APPROVE.equals(getIntent().getAction()) || pcId == null) {
            finish();
            return;
        }
        background.execute(() -> {
            try {
                LanClient.Pc pc = client.savedPc(pcId);
                if (pc == null) throw new IllegalStateException("PC không còn trong danh sách đã ghép đôi.");
                LanClient.Challenge challenge = client.getPendingChallenge(pc);
                if (challenge == null || !pc.deviceId.equals(challenge.pcDeviceId))
                    throw new IllegalStateException("Yêu cầu đã hết hạn hoặc PC không còn chờ duyệt.");
                Signature signature = PhoneSigningKey.newSignature(client.keyAlias(pc));
                runOnUiThread(() -> showFingerprint(pc, challenge, signature));
            } catch (Exception error) {
                SignInWidgetProvider.clearPending(this);
                runOnUiThread(() -> closeWithMessage(error.getMessage()));
            }
        });
    }

    private void showFingerprint(LanClient.Pc pc, LanClient.Challenge challenge, Signature signature) {
        BiometricManager manager = getSystemService(BiometricManager.class);
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            closeWithMessage("Cần cài đặt vân tay hoặc sinh trắc học mạnh.");
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Duyệt đăng nhập " + pc.name)
                .setSubtitle("Xác nhận vân tay để mở khóa PC")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Hủy", getMainExecutor(), (dialog, which) -> finish())
                .setConfirmationRequired(true)
                .build();
        prompt.authenticate(new BiometricPrompt.CryptoObject(signature), new CancellationSignal(),
                getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        background.execute(() -> signAndApprove(pc, challenge, result));
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence message) {
                        finish();
                    }
                });
    }

    private void signAndApprove(LanClient.Pc pc, LanClient.Challenge challenge,
                                BiometricPrompt.AuthenticationResult result) {
        try {
            Signature signing = result.getCryptoObject().getSignature();
            String payload = challenge.pcDeviceId + "\n" + challenge.requestId + "\n" + challenge.nonceBase64;
            signing.update(payload.getBytes(StandardCharsets.UTF_8));
            client.approve(pc, challenge, signing.sign());
            SignInWidgetProvider.updateFromPoll(this, client.pollSavedPcs());
            runOnUiThread(() -> closeWithMessage("Đã duyệt đăng nhập " + pc.name));
        } catch (Exception error) {
            runOnUiThread(() -> closeWithMessage("Không thể duyệt: " + error.getMessage()));
        }
    }

    private void closeWithMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }
}
