package uk.org.openseizuredetector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by graham on 14/12/16.
 */

public class BootBroadcastReceiver extends BroadcastReceiver {
    private String TAG = "BroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v(TAG, "onReceive()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(context);
        boolean autoStart = SP.getBoolean("AutoStart",false);
        Log.v(TAG,"onReceive() - autoStart = "+autoStart);
        if (autoStart && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent startUpIntent = new Intent(context, StartupActivity.class);
            startUpIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(startUpIntent);
        }
    }
}
