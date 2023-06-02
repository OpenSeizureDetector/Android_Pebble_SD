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
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.format.Time;
import android.text.util.Linkify;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;


/**
 * SdDataSource AW
 * A data source that uses an Android Wear Device.   This data source is simple, with the
 * communication with the Android Wear device taking place via a separate companion app.
 * This data source and the companion app communicate via INTENTS.
 *
 * Bram Regtien, 2023
 *
 */
public class SdDataSourceAw extends SdDataSource {
    private String TAG = "SdDataSourceAw";
    private final String mAppPackageName = "uk.org.openseizuredetector.aw.mobile";
    //private final String mAppPackageName = "uk.org.openseizuredetector";
    private int mNrawdata = 0;
    private static int MAX_RAW_DATA = 125;
    private int nRawData = 0;
    private double[] rawData = new double[MAX_RAW_DATA];

    public SdDataSourceAw(Context context, Handler handler,
                          SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "AndroidWear";
        // Set default settings from XML files (mContext is set by super().
        PreferenceManager.setDefaultValues(mContext,
                R.xml.network_passive_datasource_prefs, true);
    }


    /**
     * Start the datasource updating
     */
    public void start() {
        Log.i(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSourceAw.start()");
        super.start();
        mNrawdata = 0;


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
            // FIXME:  The android wear companion app should now start to send us data via intents.
            // FIXME: Register the onDataReceived() method to receive intents from the companion app.
        }
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop()");
        mUtil.writeToSysLogFile("SdDataSourceAw.stop()");
        super.stop();
        // FIXME - send an intent to tell the Android Wear companion app to shutdown.
    }

    private void installAwApp() {
        // FIXME - I don't think this works!
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

    /**
     * onDataReceived(Intent i)
     * FIXME - this does not do anything!
     * This method should be registered as a receiver for intents in the onStart() method and de-registered
     * in the onStop() method.
     * @param intent - received intent.
     */
    public void onDataReceived(Intent intent) {
        Log.v(TAG, "onDataReceived");

        // FIXME - Check the type of data received.

        // If data is heart rate {
        final int heartRate = -1; // FIXME - read the heart rate from the intent.
        mSdData.mHR = (double) heartRate;
        Log.d(TAG, String.format("Received heart rate: %d", heartRate));
        // }

        // else if data is acceleration {
        byte[] rawDataBytes = { 0, 1 };  // FIXME - read the accelerometer data from the intent.
        Log.v(TAG, "CHAR_OSD_ACC_DATA: numSamples = " + rawDataBytes.length);
        for (int i = 0; i < rawDataBytes.length;i++) {
            if (mNrawdata < MAX_RAW_DATA) {
                rawData[mNrawdata] = 1000 * rawDataBytes[i] / 64;   // Scale to mg
                mNrawdata++;
            } else {
                Log.i(TAG, "RawData Buffer Full - processing data");
                // Re-start collecting raw data.
                mSdData.watchAppRunning = true;
                for (i = 0; i < rawData.length; i++) {
                    mSdData.rawData[i] = rawData[i];
                    //Log.v(TAG,"onDataReceived() i="+i+", "+rawData[i]);
                }
                mSdData.mNsamp = rawData.length;
                mWatchAppRunningCheck = true;
                mDataStatusTime = new Time(Time.getCurrentTimezone());
                doAnalysis();
                mNrawdata = 0;
            }
        }
        // }
        // else if (data is battery level) {
        byte batteryPc = -1;   // FIXME - read battery level from intent.
        mSdData.batteryPc = batteryPc;
        Log.v(TAG,"Received Battery Data" + String.format("%d", batteryPc));
        mSdData.haveSettings = true;
        // }
        // else {
            Log.v(TAG,"Unrecognised intent data type");
        //}
    }

}







