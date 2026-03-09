package uk.org.openseizuredetector.utils;

import android.content.SharedPreferences;
import uk.org.openseizuredetector.data.logging.Log;

/**
 * Utility helpers for accessing SharedPreferences entries that are defined in XML.
 * Ensures we consistently rely on the XML defaults (via PreferenceManager.setDefaultValues)
 * instead of duplicating literal defaults throughout the codebase.
 */
public final class PreferenceUtils {
    private static final String TAG = "PreferenceUtils";

    private PreferenceUtils() { }

    public static boolean getBooleanFromXml(SharedPreferences prefs, String key) {
        if (!prefs.contains(key)) {
            Log.w(TAG, "Preference '" + key + "' not initialised - returning XML fallback");
        }
        return prefs.getBoolean(key, false);
    }
}

