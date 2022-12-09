/*
  Android_Pebble_sd - Android alarm client for openseizuredetector..

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015, 2016

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

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.util.Linkify;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;


/**
 * A Passive data source that expects a device to send it data periodically by sending a POST request.
 * The POST network request is handled in the SDWebServer class, which calls the 'updateFrom JSON()'
 * function to send the data to this datasource.
 * SdWebServer expects POST requests to /data and /settings URLs to send data or watch settings.
 */
public class SdDataSourceAw extends SdDataSource {
    private String TAG = "SdDataSourceAw";
    private final String mAppPackageName = "uk.org.openseizuredetector.aw.mobile";
    //private final String mAppPackageName = "uk.org.openseizuredetector";

    public SdDataSourceAw(Context context, Handler handler,
                          SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "AndroidWear";
        // Set default settings from XML files (mContext is set by super().
        PreferenceManager.setDefaultValues(mContext,
                R.xml.network_passive_datasource_prefs, true);
    }


    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        Log.i(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSourceAw.start()");
        super.start();

        // Now start the AndroidWear companion app
        Intent i = new Intent(Intent.ACTION_MAIN);
        PackageManager manager = mContext.getPackageManager();
        i = manager.getLaunchIntentForPackage(mAppPackageName);
        if (i == null) {
            mUtil.showToast("Error - OpenSeizureDetector Android Wear App is not installed - please install it and run it");
            installAwApp();
        } else {
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            mContext.startActivity(i);
        }
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop()");
        mUtil.writeToSysLogFile("SdDataSourceAw.stop()");
        super.stop();
    }

    private void installAwApp() {
        // from https://stackoverflow.com/questions/11753000/how-to-open-the-google-play-store-directly-from-my-android-application
        // First tries to open Play Store, then uses URL if play store is not installed.
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + mAppPackageName));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(i);
        } catch (android.content.ActivityNotFoundException anfe) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + mAppPackageName));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(i);
        }
    }

    

}







