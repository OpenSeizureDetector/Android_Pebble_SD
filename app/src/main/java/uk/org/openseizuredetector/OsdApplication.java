package uk.org.openseizuredetector;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import uk.org.openseizuredetector.data.logging.Log;

import com.rohitss.uceh.UCEHandler;

import uk.org.openseizuredetector.utils.OsdUtil;

public class OsdApplication extends Application {
    private static final String TAG = "OsdApplication";
    private static OsdUtil sUtils;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Log.i(TAG, "onCreate() - Application starting");
            // Initialize logging as the very first thing in application lifecycle
            Handler mainHandler = new Handler(Looper.getMainLooper());
            sUtils = new OsdUtil(this, mainHandler);

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
}
