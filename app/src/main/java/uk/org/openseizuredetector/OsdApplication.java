package uk.org.openseizuredetector;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rohitss.uceh.UCEHandler;

import uk.org.openseizuredetector.utils.OsdUtil;

public class OsdApplication extends Application {
    private static final String TAG = "OsdApplication";
    private static OsdUtil sUtils;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Initialize logging as the very first thing in application lifecycle
            // This ensures all components can log from their creation onwards
            Handler mainHandler = new Handler(Looper.getMainLooper());
            sUtils = new OsdUtil(this, mainHandler);
            sUtils.writeToSysLogFile("OsdApplication.onCreate() - Application starting", "LIFECYCLE");
            sUtils.writeMemoryLog("OsdApplication.onCreate - Application startup");

            // Install UCEHandler once for the whole app
            new UCEHandler.Builder(this)
                    .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                    .build();

            Log.i(TAG, "Application initialized successfully");
            sUtils.writeToSysLogFile("OsdApplication.onCreate() - UCEHandler initialized, application ready", "LIFECYCLE");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialise application", t);
        }
    }

    /**
     * Static accessor to get the OsdUtil instance from anywhere in the app.
     * This is initialized in onCreate() before any activities/services start.
     */
    public static OsdUtil getUtils() {
        return sUtils;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        Log.i(TAG, "onTerminate() - Application terminating");
        if (sUtils != null) {
            sUtils.writeToSysLogFile("OsdApplication.onTerminate() - Application shutting down", "LIFECYCLE");
            sUtils.writeMemoryLog("OsdApplication.onTerminate - Final memory status");
        }
    }
}
