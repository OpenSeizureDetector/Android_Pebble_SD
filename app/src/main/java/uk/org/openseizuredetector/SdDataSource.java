/*
  Android_Pebble_sd - Android alarm client for openseizuredetector..

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

  This file is part of pebble_sd.

  Android_Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

interface SdDataReceiver {
    public void onSdDataReceived(SdData sdData);
    public void onSdDataFault(SdData sdData);
}

/**
 * Abstract class for a seizure detector data source.  Subclasses include a pebble smart watch data source and a
 * network data source.
 */
public abstract class SdDataSource {
    public SdData mSdData;
    public String mName = "undefined";
    protected Context mContext;
    protected SdDataReceiver mSdDataReceiver;
    private String TAG = "SdDataSource";

    public SdDataSource(Context context, SdDataReceiver sdDataReceiver) {
        Log.v(TAG, "SdDataSource() Constructor");
        mContext = context;
        mSdDataReceiver = sdDataReceiver;
        mSdData = new SdData();
    }

    /**
     * Returns the SdData object stored by this class.
     * @return
     */
    public SdData getSdData() {
        return mSdData;
    }

    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        Log.v(TAG, "start()");
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.v(TAG, "stop()");
    }

    /**
     * Install the watch app on the watch.
     */
    public void installWatchApp() { Log.v(TAG,"installWatchApp"); }

    /**
     * Display a Toast message on screen.
     * @param msg - message to display.
     */
    public void showToast(String msg) {
        Toast.makeText(mContext, msg,
                Toast.LENGTH_LONG).show();
    }


}
