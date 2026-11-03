package com.example.winhello;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_RESULT = "com.example.winhello.UPDATE_INSTALL_RESULT";
    static final String EXTRA_SESSION_ID = "session_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int sessionId = intent.getIntExtra(EXTRA_SESSION_ID,
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1));
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            }
            return;
        }

        if (sessionId >= 0 && status != PackageInstaller.STATUS_SUCCESS) {
            try { context.getPackageManager().getPackageInstaller().abandonSession(sessionId); }
            catch (Exception ignored) { }
        }
        context.getSharedPreferences("winhello_update", Context.MODE_PRIVATE).edit()
                .remove("session_id").remove("session_committed").apply();
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "Cài cập nhật đã hủy hoặc thất bại" +
                    (message == null || message.isEmpty() ? "." : ": " + message), Toast.LENGTH_LONG).show();
        }
    }
}
