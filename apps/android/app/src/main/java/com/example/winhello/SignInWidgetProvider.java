package com.example.winhello;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

public final class SignInWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.example.winhello.WIDGET_REFRESH";
    private static final String PREFS = "winhello_widget";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        render(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("scanning", true).putBoolean("scanned", true).apply();
            render(context);
            PendingResult result = goAsync();
            new Thread(() -> {
                try {
                    LanClient client = new LanClient(context);
                    try { client.discover(); } catch (Exception ignored) { }
                    updateFromPoll(context, client.pollSavedPcs());
                } catch (Exception ignored) {
                    updateFromPoll(context, new java.util.ArrayList<>());
                } finally {
                    result.finish();
                }
            }, "WinHelloWidgetRefresh").start();
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onDisabled(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("pending_id").remove("pending_name").remove("scanning").apply();
    }

    static void updateFromPoll(Context context, List<LanClient.Pc> pcs) {
        PcStatusStore.update(context, pcs);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("scanned", true)
                .putBoolean("scanning", false).apply();
        render(context);
    }

    static void clearPending(Context context) {
        List<LanClient.Pc> pcs = PcStatusStore.recent(context);
        List<LanClient.Pc> withoutPending = new java.util.ArrayList<>();
        for (LanClient.Pc pc : pcs) {
            LanClient.Pc updated = new LanClient.Pc(pc.deviceId, pc.name, pc.address,
                    pc.httpsPort, false, pc.certificatePin);
            updated.sessionState = pc.sessionState;
            withoutPending.add(updated);
        }
        PcStatusStore.update(context, withoutPending);
        render(context);
    }

    static void render(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, SignInWidgetProvider.class));
        if (ids.length == 0) return;
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean scanned = prefs.getBoolean("scanned", false);
        boolean scanning = prefs.getBoolean("scanning", false);
        List<LanClient.Pc> pcs = PcStatusStore.recent(context);
        LanClient.Pc pending = null;
        for (LanClient.Pc pc : pcs) if (pc.pending) { pending = pc; break; }
        String pendingId = pending == null ? null : pending.deviceId;
        String pendingName = pending == null ? "PC" : pending.name;
        int onlineCount = pcs.size();
        LanClient.Pc onlyPc = onlineCount == 1 ? pcs.get(0) : null;
        String version;
        try { version = "v" + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { version = ""; }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent refresh = PendingIntent.getBroadcast(context, 3,
                new Intent(context, SignInWidgetProvider.class).setAction(ACTION_REFRESH), flags);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.sign_in_widget);
            views.setTextViewText(R.id.widget_version, version);
            views.setTextViewText(R.id.widget_status, scanning ? "Đang quét LAN…" : pendingId != null
                    ? "●  " + pendingName + " đang chờ xác nhận"
                    : !scanned ? "Nhấn ↻ để quét PC gần đây"
                    : onlineCount == 0 ? "Không thấy PC • nhấn ↻ để thử lại"
                    : onlyPc != null && "unlocked".equals(onlyPc.sessionState)
                    ? "●  " + onlyPc.name + " đã mở khóa"
                    : onlyPc != null && "locked".equals(onlyPc.sessionState)
                    ? "●  " + onlyPc.name + " đã khóa"
                    : onlineCount + " PC trên LAN • chưa có yêu cầu");
            views.setTextColor(R.id.widget_status, pendingId != null ? 0xFF64E0C7 : 0xFFA8BED6);
            views.setViewVisibility(R.id.widget_approve, pendingId == null || scanning ? View.GONE : View.VISIBLE);
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh);
            if (pendingId != null) {
                Intent approve = new Intent(context, WidgetApprovalActivity.class)
                        .setAction(WidgetApprovalActivity.ACTION_APPROVE)
                        .setData(Uri.parse("winhello://approve/" + pendingId))
                        .putExtra(WidgetApprovalActivity.EXTRA_PC_ID, pendingId);
                views.setOnClickPendingIntent(R.id.widget_approve,
                        PendingIntent.getActivity(context, 4, approve, flags));
            }
            manager.updateAppWidget(id, views);
        }
    }
}
