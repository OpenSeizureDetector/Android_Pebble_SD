package uk.org.openseizuredetector;

import android.app.Application;
import android.util.Log;

import com.rohitss.uceh.UCEHandler;

public class OsdApplication extends Application {
    private static final String TAG = "OsdApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Install UCEHandler once for the whole app
            new UCEHandler.Builder(this)
                    .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                    .build();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialise UCEHandler", t);
        }
    }
}
