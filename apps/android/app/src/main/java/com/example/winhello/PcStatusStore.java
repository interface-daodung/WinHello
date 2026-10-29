package com.example.winhello;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Latest observed PC state shared by the app and widget. Never stores enrollment secrets. */
final class PcStatusStore {
    private static final String PREFS = "winhello_pc_status";
    private static final String SNAPSHOT = "snapshot";
    private static final String UPDATED_AT = "updated_at";
    private static final long MAX_AGE_MS = 30_000;

    static void update(Context context, List<LanClient.Pc> pcs) {
        JSONArray entries = new JSONArray();
        LanClient client = new LanClient(context);
        for (LanClient.Pc pc : pcs) {
            if (!client.hasEnrollment(pc.deviceId)) continue;
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", pc.deviceId);
                entry.put("name", pc.name);
                entry.put("address", pc.address.getHostAddress());
                entry.put("port", pc.httpsPort);
                entry.put("pending", pc.pending);
                entry.put("sessionState", pc.sessionState);
                entries.put(entry);
            } catch (Exception ignored) { }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(SNAPSHOT, entries.toString())
                .putLong(UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    static List<LanClient.Pc> recent(Context context) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long age = System.currentTimeMillis() - prefs.getLong(UPDATED_AT, 0);
        List<LanClient.Pc> pcs = new ArrayList<>();
        if (age < 0 || age > MAX_AGE_MS) return pcs;
        try {
            LanClient client = new LanClient(context);
            JSONArray entries = new JSONArray(prefs.getString(SNAPSHOT, "[]"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                LanClient.Pc saved = client.savedPc(entry.getString("id"));
                if (saved == null) continue;
                LanClient.Pc pc = new LanClient.Pc(saved.deviceId, entry.optString("name", saved.name),
                        saved.address, saved.httpsPort, entry.optBoolean("pending", false),
                        saved.certificatePin);
                pc.sessionState = entry.optString("sessionState", "unknown");
                pcs.add(pc);
            }
        } catch (Exception ignored) { }
        return pcs;
    }
}
