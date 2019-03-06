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
package uk.org.openseizuredetector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Broadcast Receiver responds to the BOOT_COMPLETED broadcast and if the 'AutoStart' preference is true,
 * will start the OpenSeizureDetector SDServer service.
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
