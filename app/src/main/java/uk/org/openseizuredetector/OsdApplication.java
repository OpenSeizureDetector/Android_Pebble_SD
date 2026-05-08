package uk.org.openseizuredetector;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import uk.org.openseizuredetector.data.logging.Log;

import com.rohitss.uceh.UCEHandler;

import androidx.preference.PreferenceManager;
import android.content.SharedPreferences;

import uk.org.openseizuredetector.utils.OsdUtil;

public class OsdApplication extends Application {
    private static final String TAG = "OsdApplication";
    private static OsdUtil sUtils;

    /** Shared-prefs key under which we persist the PID of the previous process */
    private static final String PREF_PREV_PID = "OsdApplication_PrevPid";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Log.i(TAG, "onCreate() - Application starting");
            // Initialize logging as the very first thing in application lifecycle
            Handler mainHandler = new Handler(Looper.getMainLooper());
            sUtils = new OsdUtil(this, mainHandler);

            int thisPid = android.os.Process.myPid();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            int prevPid = prefs.getInt(PREF_PREV_PID, -1);
            Log.i(TAG, "onCreate(): PID=" + thisPid + ", prevPID=" + prevPid
                    + (prevPid > 0 && thisPid < prevPid
                            ? " (PID is LOWER than previous - device may have rebooted)"
                            : ""));
            prefs.edit().putInt(PREF_PREV_PID, thisPid).apply();

            // Apply the user's selected theme (Light/Dark/System)
            Log.d(TAG, "Applying theme");
            OsdUtil.applyTheme(this);

            Log.i(TAG, "OsdApplication.onCreate() - Application starting");
            sUtils.writeMemoryLog("OsdApplication.onCreate - Application startup");

            // Install UCEHandler once for the whole app
            new UCEHandler.Builder(this)
                    .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                    .build();

            Log.i(TAG, "Application initialized successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialise application", t);
        }
    }

    /**
     * Static accessor to get the OsdUtil instance from anywhere in the app.
     */
    public static OsdUtil getUtils() {
        return sUtils;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        Log.i(TAG, "onTerminate() - Application terminating");
        if (sUtils != null) {
            Log.i(TAG, "OsdApplication.onTerminate() - Application shutting down");
            sUtils.writeMemoryLog("OsdApplication.onTerminate - Final memory status");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "onLowMemory() - System is low on memory! This may cause the app to be killed.");
        if (sUtils != null) {
            sUtils.writeMemoryLog("OsdApplication.onLowMemory - System low memory warning");
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        String levelName = trimMemoryLevelName(level);
        Log.w(TAG, "onTrimMemory(): level=" + level + " (" + levelName + ")");
        if (sUtils != null) {
            sUtils.writeMemoryLog("OsdApplication.onTrimMemory - level=" + levelName);
        }
    }

    private String trimMemoryLevelName(int level) {
        switch (level) {
            case TRIM_MEMORY_COMPLETE:          return "COMPLETE (app may be killed)";
            case TRIM_MEMORY_MODERATE:          return "MODERATE";
            case TRIM_MEMORY_BACKGROUND:        return "BACKGROUND";
            case TRIM_MEMORY_UI_HIDDEN:         return "UI_HIDDEN";
            case TRIM_MEMORY_RUNNING_CRITICAL:  return "RUNNING_CRITICAL";
            case TRIM_MEMORY_RUNNING_LOW:       return "RUNNING_LOW";
            case TRIM_MEMORY_RUNNING_MODERATE:  return "RUNNING_MODERATE";
            default:                            return "unknown(" + level + ")";
        }
    }
}
