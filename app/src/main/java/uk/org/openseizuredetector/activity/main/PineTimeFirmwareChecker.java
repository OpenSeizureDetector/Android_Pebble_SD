package uk.org.openseizuredetector.activity.main;   // same package as MainActivity2

import android.app.Activity;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.data.logging.Log;

/**
 * "Update Firmware?" prompt for the PineTime watch.
 *
 * Rules (as specified):
 *  1. "Developer Mode" checked  -> no check, no prompt.
 *  2. Otherwise open https://osdapi.org.uk/static/pineTimeSdFw/index.json, read "recommendedFw" (an index),
 *     look it up in "releases" and read that entry's "version".
 *  3. If that version is higher than the version installed on the watch (watchSdVersion) -> prompt.
 *  4. The user can only choose "Update firmware" or "Not Now". "Not Now" skips the update; the prompt
 *     comes back the next time the app is started.
 *
 * One instance per MainActivity2 instance; call checkAndPrompt() repeatedly until it returns true.
 */
public class PineTimeFirmwareChecker {
    private static final String TAG = "PineTimeFwChecker";

    public static final String PREF_DEVELOPER_MODE = "pref_developer_mode";   // general_prefs.xml

    // Extras understood by the PineTime Updater (see updater/ folder)
    public static final String EXTRA_AUTO_SELECT = "auto_select";
    public static final String EXTRA_MAC_ADDRESS = "mac_address";
    public static final String EXTRA_FIRMWARE_VERSION = "firmware_version";
    public static final String EXTRA_INSTALLED_VERSION = "installed_version";

    private static final String INDEX_URL = "https://osdapi.org.uk/static/pineTimeSdFw/index.json";

    private volatile boolean mDone = false;      // decision made (prompted / nothing to do)
    private volatile boolean mRunning = false;   // network request in progress

    public interface Callback {
        /** User tapped "Update firmware" (UI thread). */
        void onUpdateAccepted(Firmware recommended);
    }

    public static class Firmware {
        public final String version;   // e.g. "0.7"
        public final String fileName;
        Firmware(String version, String fileName) { this.version = version; this.fileName = fileName; }
    }

    public boolean isDone() { return mDone; }
    public void markDone() { mDone = true; }

    /**
     * @param installedVersion mSdData.watchSdVersion (null/empty until the watch has reported it)
     * @return true when no further calls are needed for this app start
     */
    public boolean checkAndPrompt(final Activity activity, final String installedVersion, final Callback callback) {
        if (mDone) return true;
        if (mRunning) return false;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (prefs.getBoolean(PREF_DEVELOPER_MODE, false)) { mDone = true; return true; }      // rule 1
        // watchSdVersion is only a PineTime firmware version when the BLE2 data source is in use
        if (!"BLE2".equals(prefs.getString("DataSource", ""))) { mDone = true; return true; }
        if (installedVersion == null || installedVersion.trim().isEmpty()) return false;       // not read yet

        mRunning = true;
        new Thread(() -> {
            try {
                Firmware rec = fetchRecommended();                                              // rule 2
                mDone = true;
                if (rec == null) return;
                if (compareVersions(normalise(installedVersion), normalise(rec.version)) >= 0) return; // rule 3
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()
                            || prefs.getBoolean(PREF_DEVELOPER_MODE, false)) return;
                    showDialog(activity, installedVersion, rec, callback);
                });
            } catch (Exception e) {
                Log.w(TAG, "Firmware check failed (offline?), will retry: " + e);
            } finally {
                mRunning = false;
            }
        }, "PineTimeFwCheck").start();
        return false;
    }

    private void showDialog(Activity a, String installed, Firmware rec, Callback cb) {
        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.pinetime_fw_update_title)
                .setMessage(a.getString(R.string.pinetime_fw_update_message, rec.version, installed))
                .setCancelable(false)                                       // only the two buttons below
                .setPositiveButton(R.string.pinetime_fw_update_now, (d, w) -> cb.onUpdateAccepted(rec))
                .setNegativeButton(R.string.pinetime_fw_not_now, null)       // rule 4: asked again next start
                .show();
    }

    /** index.json: {"recommendedFw":0,"releases":[{"version":"0.7","description":"..","fname":"..zip"},..]} */
    static Firmware fetchRecommended() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(INDEX_URL).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return parseRecommended(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    static Firmware parseRecommended(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray releases = root.getJSONArray("releases");
        int idx = root.optInt("recommendedFw", -1);
        if (idx < 0 || idx >= releases.length()) return null;
        JSONObject o = releases.getJSONObject(idx);
        String version = o.getString("version");
        // Safety net (not in the requirements): a non-numeric entry such as "InfiniTime" cannot be compared.
        if (!normalise(version).matches("\\d+(\\.\\d+)*")) return null;
        return new Firmware(version, o.getString("fname"));
    }

    /** The watch reports "230.0.7"; index.json says "0.7". Drop the fixed "230." prefix. */
    static String normalise(String v) {
        String t = v.trim();
        return t.startsWith("230.") ? t.substring(4) : t;
    }

    /** Each dot-separated number is compared on its own: 0.10 > 0.9, 1.0 > 0.10. */
    static int compareVersions(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? x[i] : 0, yi = i < y.length ? y[i] : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int[] parse(String v) {
        String[] parts = v.trim().replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }
}
