package uk.org.openseizuredetector.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import uk.org.openseizuredetector.data.logging.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import uk.org.openseizuredetector.R;

public final class SettingsUtil {
    private static final String TAG = "SettingsUtil";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private SettingsUtil() {
    }

    public static int[] getPreferenceResourceIds() {
        return new int[] {
                R.xml.general_prefs,
                R.xml.data_source_prefs,
                R.xml.alarm_prefs,
                R.xml.logging_prefs,
                R.xml.sd_prefs_main,
                R.xml.sd_prefs_voting,
                R.xml.sd_prefs_osd,
                R.xml.sd_prefs_flap,
                R.xml.sd_prefs_ml,
                R.xml.sd_prefs_hr,
                R.xml.sd_prefs_o2,
                R.xml.sd_prefs_fall,
                R.xml.sd_prefs_fidget,
                R.xml.pebble_datasource_prefs,
                R.xml.network_datasource_prefs,
                R.xml.network_passive_datasource_prefs
        };
    }

    public static void initialiseDefaultValues(Context context, boolean readAgain) {
        Log.i(TAG, "initialiseDefaultValues(force=" + readAgain + ")");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        boolean flagsChanged = false;

        for (int resId : getPreferenceResourceIds()) {
            String flagKey = "defaults_applied_" + resId;
            if (readAgain || !prefs.getBoolean(flagKey, false)) {
                PreferenceManager.setDefaultValues(context, resId, true);
                editor.putBoolean(flagKey, true);
                flagsChanged = true;
            }
        }

        if (flagsChanged) {
            editor.apply();
        }
    }

    public static JSONObject getPreferencesAsJson(Context context) {
        initialiseDefaultValues(context, false);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> values = prefs.getAll();
        Set<String> allowedKeys = getPreferenceKeys(context);
        JSONObject result = new JSONObject();
        for (String key : allowedKeys) {
            Object value = values.get(key);
            try {
                if (value == null) {
                    result.put(key, JSONObject.NULL);
                } else if (value instanceof Set) {
                    JSONArray arr = new JSONArray();
                    for (Object item : (Set<?>) value) {
                        arr.put(String.valueOf(item));
                    }
                    result.put(key, arr);
                } else if (value instanceof Float) {
                    result.put(key, ((Float) value).doubleValue());
                } else {
                    result.put(key, value);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to serialize preference: " + key, e);
            }
        }
        return result;
    }

    public static UpdateResult applyPreferencesFromJson(Context context, String jsonString) {
        UpdateResult result = new UpdateResult();
        if (jsonString == null || jsonString.trim().isEmpty()) {
            result.errorMessage = "Empty JSON payload";
            return result;
        }

        initialiseDefaultValues(context, false);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> currentValues = prefs.getAll();
        Set<String> allowedKeys = getPreferenceKeys(context);

        try {
            JSONObject obj = new JSONObject(jsonString);
            SharedPreferences.Editor editor = prefs.edit();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowedKeys.contains(key)) {
                    result.ignoredKeys.put(key);
                    continue;
                }
                if (obj.isNull(key)) {
                    editor.remove(key);
                    result.clearedKeys.put(key);
                    continue;
                }

                Object newValue = obj.get(key);
                Object existingValue = currentValues.get(key);
                if (applyValue(editor, key, existingValue, newValue)) {
                    result.updatedKeys.put(key);
                } else {
                    result.failedKeys.put(key);
                }
            }
            boolean committed = editor.commit();
            if (!committed) {
                result.errorMessage = "Failed to persist preferences";
            }
        } catch (Exception e) {
            result.errorMessage = "Invalid JSON payload: " + e.getMessage();
        }

        return result;
    }

    private static boolean applyValue(SharedPreferences.Editor editor, String key, Object existingValue, Object newValue) {
        if (newValue == null) {
            return false;
        }

        if (existingValue instanceof Boolean) {
            Boolean boolValue = coerceBoolean(newValue);
            if (boolValue != null) {
                editor.putBoolean(key, boolValue);
                return true;
            }
            return false;
        }

        if (existingValue instanceof Integer) {
            Number number = coerceNumber(newValue);
            if (number != null) {
                editor.putInt(key, number.intValue());
                return true;
            }
            return false;
        }

        if (existingValue instanceof Long) {
            Number number = coerceNumber(newValue);
            if (number != null) {
                editor.putLong(key, number.longValue());
                return true;
            }
            return false;
        }

        if (existingValue instanceof Float) {
            Number number = coerceNumber(newValue);
            if (number != null) {
                editor.putFloat(key, number.floatValue());
                return true;
            }
            return false;
        }

        if (existingValue instanceof Set) {
            Set<String> setValue = coerceStringSet(newValue);
            if (setValue != null) {
                editor.putStringSet(key, setValue);
                return true;
            }
            return false;
        }

        if (newValue instanceof Boolean) {
            editor.putBoolean(key, (Boolean) newValue);
            return true;
        }

        if (newValue instanceof Number) {
            editor.putString(key, String.valueOf(newValue));
            return true;
        }

        if (newValue instanceof JSONArray) {
            Set<String> setValue = coerceStringSet(newValue);
            if (setValue != null) {
                editor.putStringSet(key, setValue);
                return true;
            }
            return false;
        }

        editor.putString(key, String.valueOf(newValue));
        return true;
    }

    private static Boolean coerceBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = String.valueOf(value);
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private static Number coerceNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        try {
            String text = String.valueOf(value);
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Set<String> coerceStringSet(Object value) {
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            Set<String> result = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.optString(i, ""));
            }
            return result;
        }
        return null;
    }

    public static Set<String> getPreferenceKeys(Context context) {
        Set<String> keys = new HashSet<>();
        for (int resId : getPreferenceResourceIds()) {
            XmlResourceParser parser = null;
            try {
                parser = context.getResources().getXml(resId);
                int eventType = parser.getEventType();
                while (eventType != XmlResourceParser.END_DOCUMENT) {
                    if (eventType == XmlResourceParser.START_TAG) {
                        String key = parser.getAttributeValue(ANDROID_NS, "key");
                        if (key != null && !key.trim().isEmpty()) {
                            keys.add(key);
                        }
                    }
                    eventType = parser.next();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse preference XML: " + resId, e);
            } finally {
                if (parser != null) {
                    parser.close();
                }
            }
        }
        return keys;
    }

    public static class UpdateResult {
        public final JSONArray updatedKeys = new JSONArray();
        public final JSONArray clearedKeys = new JSONArray();
        public final JSONArray ignoredKeys = new JSONArray();
        public final JSONArray failedKeys = new JSONArray();
        public String errorMessage;

        public boolean hasChanges() {
            return updatedKeys.length() > 0 || clearedKeys.length() > 0;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("updatedKeys", updatedKeys);
                obj.put("clearedKeys", clearedKeys);
                obj.put("ignoredKeys", ignoredKeys);
                obj.put("failedKeys", failedKeys);
                if (errorMessage != null) {
                    obj.put("error", errorMessage);
                }
            } catch (Exception ignored) {
            }
            return obj;
        }
    }
}
