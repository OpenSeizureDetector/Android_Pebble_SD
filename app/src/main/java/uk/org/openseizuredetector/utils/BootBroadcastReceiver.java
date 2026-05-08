/*
  Android_Pebble_SD - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

  This file is part of pebble_sd.

  Android_Pebble_SD is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_Pebble_Sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_Pebble_SD.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import uk.org.openseizuredetector.data.logging.Log;
import uk.org.openseizuredetector.activity.startup.StartupActivity;

/**
 * Broadcast Receiver responds to the BOOT_COMPLETED broadcast and, if the 'AutoStart'
 * preference is true, starts the OpenSeizureDetector SDServer service.
 *
 * Also handles ACTION_LOCKED_BOOT_COMPLETED (Android 7+) so the receiver fires even
 * when Direct Boot is active, giving us a log entry as early as possible.
 */
public class BootBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "BootBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : "null";

        // Initialise OsdUtil so Log.i/w/e are wired to the persistent file.
        // OsdApplication.onCreate() will already have run (Android starts the app process
        // first), but Log.init() may not have been called yet if LogManager hasn't been
        // created. Creating OsdUtil here is cheap because it is idempotent (the singleton
        // PersistentFileLogger is reused, and Log.init() is called inside the constructor).
        try {
            new OsdUtil(context, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            android.util.Log.e(TAG, "onReceive(): failed to initialise OsdUtil - " + e.getMessage());
        }

        Log.i(TAG, "onReceive(): action=" + action
                + ", pid=" + android.os.Process.myPid()
                + ", androidSdk=" + Build.VERSION.SDK_INT);

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            Log.w(TAG, "onReceive(): unexpected action '" + action + "' - ignoring");
            return;
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        boolean autoStart = PreferenceUtils.getBooleanFromXml(sp, "AutoStart");

        Log.i(TAG, "onReceive(): DEVICE BOOT DETECTED - autoStart=" + autoStart);

        if (autoStart) {
            Log.i(TAG, "onReceive(): AutoStart is enabled - launching StartupActivity");
            try {
                Intent startUpIntent = new Intent(context, StartupActivity.class);
                startUpIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(startUpIntent);
                Log.i(TAG, "onReceive(): StartupActivity launched successfully");
            } catch (Exception e) {
                Log.e(TAG, "onReceive(): failed to launch StartupActivity - " + e.getMessage());
            }
        } else {
            Log.i(TAG, "onReceive(): AutoStart is DISABLED - OSD will NOT restart automatically after this boot");
        }
    }
}

