/*
  Pebble_sd - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

  This file is part of pebble_sd.

  Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import android.view.MenuItem;

/**
 * OsdUtil - OpenSeizureDetector Utilities
 * Deals with starting and stopping the background service and binding to it to receive data.
 */
public class OsdUtil {
    /**
     * Based on http://stackoverflow.com/questions/7440473/android-how-to-check-if-the-intent-service-is-still-running-or-has-stopped-running
     */
    private Context mContext;
    private String TAG = "OsdUtil";

    public OsdUtil(Context context) {
        mContext = context;
    }

    public boolean isServerRunning() {
        //Log.v(TAG,"isServerRunning()................");
        ActivityManager manager =
                (ActivityManager) mContext.getSystemService(mContext.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            //Log.v(TAG,"Service: "+service.service.getClassName());
            if ("uk.org.openseizuredetector.SdServer"
                    .equals(service.service.getClassName())) {
                //Log.v(TAG,"Yes!");
                return true;
            }
        }
        //Log.v(TAG,"No!");
        return false;
    }

    /**
     * Start the SdServer service
     */
    public void startServer() {
        // Start the server
        Intent sdServerIntent;
        sdServerIntent = new Intent(mContext, SdServer.class);
        sdServerIntent.setData(Uri.parse("Start"));
        mContext.startService(sdServerIntent);
    }

    /**
     * Stop the SdServer service
     */
    public void stopServer() {
        Log.v(TAG, "stopping Server...");

        // then send an Intent to stop the service.
        Intent sdServerIntent;
        sdServerIntent = new Intent(mContext, SdServer.class);
        sdServerIntent.setData(Uri.parse("Stop"));
        mContext.stopService(sdServerIntent);
    }


    /**
     * bind an activity to to an already running server.
     */
    public void bindToServer(Activity activity, SdServiceConnection sdServiceConnection) {
        Log.v(TAG, "bindToServer() - binding to SdServer");
        Intent intent = new Intent(sdServiceConnection.mContext, SdServer.class);
        activity.bindService(intent, sdServiceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * unbind an activity from server
     */
    public void unbindFromServer(Activity activity, SdServiceConnection sdServiceConnection) {
        // unbind this activity from the service if it is bound.
        if (sdServiceConnection.mBound) {
            Log.v(TAG, "unbindFromServer() - unbinding");
            try {
                activity.unbindService(sdServiceConnection);
                sdServiceConnection.mBound = false;
            } catch (Exception ex) {
                Log.e(TAG, "unbindFromServer() - error unbinding service - " + ex.toString());
            }
        } else {
            Log.v(TAG, "unbindFromServer() - not bound to server - ignoring");
        }
    }




}
