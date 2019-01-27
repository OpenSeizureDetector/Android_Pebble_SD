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

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.format.Time;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import org.apache.http.conn.util.InetAddressUtils;

import java.io.File;
import java.io.FileWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.RunnableFuture;

/**
 * OsdUtil - OpenSeizureDetector Utilities
 * Deals with starting and stopping the background service and binding to it to receive data.
 */
public class OsdUtil implements ActivityCompat.OnRequestPermissionsResultCallback {
    private final String SYSLOG = "SysLog";
    private final String ALARMLOG = "AlarmLog";
    private final String DATALOG = "DataLog";

    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.WAKE_LOCK,
    };

    private final String[] SMS_PERMISSIONS = {
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
    };


    /**
     * Based on http://stackoverflow.com/questions/7440473/android-how-to-check-if-the-intent-service-is-still-running-or-has-stopped-running
     */
    private Context mContext;
    private Handler mHandler;
    private String TAG = "OsdUtil";
    private boolean mLogAlarms = true;
    private boolean mLogSystem = true;
    private boolean mLogData = true;
    private boolean mPermissionsRequested = false;
    private boolean mSMSPermissionsRequested = false;

    private static int mNbound = 0;

    public OsdUtil(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        updatePrefs();
        writeToSysLogFile("OsdUtil() - initialised");

    }

    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/prefs.xml
     */
    public void updatePrefs() {
        Log.v(TAG, "updatePrefs()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        try {
            mLogAlarms = SP.getBoolean("LogAlarms", true);
            Log.v(TAG, "updatePrefs() - mLogAlarms = " + mLogAlarms);
            mLogData = SP.getBoolean("LogData", false);
            Log.v(TAG, "updatePrefs() - mLogData = " + mLogData);
            mLogSystem = SP.getBoolean("LogSystem", true);
            Log.v(TAG, "updatePrefs() - mLogSystem = " + mLogSystem);

        } catch (Exception ex) {
            Log.v(TAG, "updatePrefs() - Problem parsing preferences!");
            showToast("Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!");
        }
    }


    /**
     * used to make sure timers etc. run on UI thread
     */
    public void runOnUiThread(Runnable runnable) {
        mHandler.post(runnable);
    }


    public boolean isServerRunning() {
        int nServers = 0;
        /* Log.v(TAG,"isServerRunning()...."); */
        ActivityManager manager =
                (ActivityManager) mContext.getSystemService(mContext.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            //Log.v(TAG,"Service: "+service.service.getClassName());
            if ("uk.org.openseizuredetector.SdServer"
                    .equals(service.service.getClassName())) {
                nServers = nServers + 1;
            }
        }
        if (nServers != 0) {
            Log.v(TAG, "isServerRunning() - " + nServers + " instances are running");
            return true;
        }
        else
            return false;
    }

    /**
     * Start the SdServer service
     */
    public void startServer() {
        // Start the server
        Log.d(TAG,"OsdUtil.startServer()");
        writeToSysLogFile("startServer() - starting server");
        Intent sdServerIntent;
        sdServerIntent = new Intent(mContext, SdServer.class);
        sdServerIntent.setData(Uri.parse("Start"));
        if (Build.VERSION.SDK_INT >= 26) {
            Log.i(TAG,"Starting Foreground Service (Android 8 and above)");
            mContext.startForegroundService(sdServerIntent);
        } else {
            Log.i(TAG,"Starting Normal Service (Pre-Android 8)");
            mContext.startService(sdServerIntent);
        }
    }

    /**
     * Stop the SdServer service
     */
    public void stopServer() {
        Log.d(TAG, "OsdUtil.stopServer() - stopping Server... - mNbound=" + mNbound);
        writeToSysLogFile("stopserver() - stopping server");

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
        Log.i(TAG, "OsdUtil.bindToServer() - binding to SdServer");
        writeToSysLogFile("bindToServer() - binding to SdServer");
        Intent intent = new Intent(sdServiceConnection.mContext, SdServer.class);
        activity.bindService(intent, sdServiceConnection, Context.BIND_AUTO_CREATE);
        mNbound = mNbound + 1;
        Log.i(TAG,"OsdUtil.bindToServer() - mNbound = "+mNbound);
    }

    /**
     * unbind an activity from server
     */
    public void unbindFromServer(Activity activity, SdServiceConnection sdServiceConnection) {
        // unbind this activity from the service if it is bound.
        if (sdServiceConnection.mBound) {
            Log.i(TAG, "unbindFromServer() - unbinding");
            writeToSysLogFile("unbindFromServer() - unbinding");
            try {
                activity.unbindService(sdServiceConnection);
                sdServiceConnection.mBound = false;
                mNbound = mNbound - 1;
                Log.i(TAG,"OsdUtil.unBindFromServer() - mNbound = "+mNbound);
            } catch (Exception ex) {
                Log.e(TAG, "unbindFromServer() - error unbinding service - " + ex.toString());
                writeToSysLogFile("unbindFromServer() - error unbinding service - " +ex.toString());
                Log.i(TAG,"OsdUtil.unBindFromServer() - mNbound = "+mNbound);
            }
        } else {
            Log.i(TAG, "unbindFromServer() - not bound to server - ignoring");
            writeToSysLogFile("unbindFromServer() - not bound to server - ignoring");
            Log.i(TAG,"OsdUtil.unBindFromServer() - mNbound = "+mNbound);
        }
    }

    public String getAppVersionName() {
        String versionName = "unknown";
        // From http://stackoverflow.com/questions/4471025/
        //         how-can-you-get-the-manifest-version-number-
        //         from-the-apps-layout-xml-variable
        final PackageManager packageManager = mContext.getPackageManager();
        if (packageManager != null) {
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(mContext.getPackageName(), 0);
                versionName = packageInfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                Log.v(TAG, "failed to find versionName");
                versionName = null;
            }
        }
        return versionName;
    }

    /**
     * get the ip address of the phone.
     * Based on http://stackoverflow.com/questions/11015912/how-do-i-get-ip-address-in-ipv4-format
     */
    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface
                    .getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf
                        .getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    //Log.v(TAG,"ip1--:" + inetAddress);
                    //Log.v(TAG,"ip2--:" + inetAddress.getHostAddress());

                    // for getting IPV4 format
                    if (!inetAddress.isLoopbackAddress()
                            && InetAddressUtils.isIPv4Address(
                            inetAddress.getHostAddress())) {

                        String ip = inetAddress.getHostAddress().toString();
                        //Log.v(TAG,"ip---::" + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("IP Address", ex.toString());
        }
        return null;
    }

    /**
     * Display a Toast message on screen.
     *
     * @param msg - message to display.
     */
    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(mContext, msg,
                        Toast.LENGTH_LONG).show();
            }
        });
    }


    /**
     * Write a message to the system log file, provided mLogSystem is true.
     * @param msgStr
     */
    public void writeToSysLogFile(String msgStr) {
        if (mLogSystem)
            writeToLogFile(SYSLOG,msgStr);
        else
            Log.v(TAG,"writeToSysLogFile - mLogSystem False so not writing");
    }

    /**
     * Write a message to the alarm log file, provided mLogAlarms is true.
     * @param msgStr
     */
    public void writeToAlarmLogFile(String msgStr) {
        if (mLogAlarms)
            writeToLogFile(ALARMLOG,msgStr);
        else
            Log.v(TAG,"writeToAlarmLogFile - mLogAlarms False so not writing");
    }

    /**
     * Write a message to the data log file, provided mLogData is true.
     * @param msgStr
     */
    public void writeToDataLogFile(String msgStr) {
        if (mLogData)
            writeToLogFile(DATALOG,msgStr);
        else
            Log.v(TAG,"writeToDataLogFile - mLogData False so not writing");
    }


    /**
     * Write data to SD card - writes to data log file unless alarm=true,
     * in which case writes to alarm log file.
     */
    public void writeToLogFile(String fname, String msgStr) {
        //Log.v(TAG, "writeToLogFile(" + fname + "," + msgStr + ")");
        //showToast("Logging " + msgStr);
        Time tnow = new Time(Time.getCurrentTimezone());
        tnow.setToNow();
        String dateStr = tnow.format("%Y-%m-%d");

        fname = fname + "_" + dateStr + ".txt";
        // Open output directory on SD Card.
        if (ContextCompat.checkSelfPermission(mContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG,"ERROR: We do not have permission to write to external storage");
        } else {
            if (isExternalStorageWritable()) {
                try {
                    FileWriter of = new FileWriter(getDataStorageDir().toString()
                            + "/" + fname, true);
                    if (msgStr != null) {
                        String dateTimeStr = tnow.format("%Y-%m-%d %H:%M:%S");
                        //Log.v(TAG, "writing msgStr");
                        of.append(dateTimeStr + ", "
                                + tnow.toMillis(true) + ", "
                                + msgStr + "<br/>\n");
                    }
                    of.close();
                } catch (Exception ex) {
                    Log.e(TAG, "writeToLogFile - error " + ex.toString());
                    showToast("ERROR Writing to Log File");
                }
            } else {
                Log.e(TAG, "ERROR - Can not Write to External Folder");
            }
        }
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    public File getDataStorageDir() {
        // Get the directory for the user's public directory.
        File file =
                new File(Environment.getExternalStorageDirectory()
                        , "OpenSeizureDetector");
        if (!file.isDirectory()) {
            Log.i(TAG,"getDataStorageDir() - creating directory");
            if (!file.mkdirs()) {
                Log.e(TAG, "Failed to create directory");
            }
        }
        return file;
    }

    public String getPreferredPebbleAppPackageName() {
        // returns the package name of the preferred Android Pebble App.
        return "com.getpebble.android.basalt";
    }

    public String isPebbleAppInstalled() {
        // Returns the package name of the installed pebble App or null if it is not installed
        String pkgName;
        pkgName = "com.getpebble.android";
        if (isPackageInstalled(pkgName)) return pkgName;
        pkgName = "com.getpebble.android.basalt";
        if (isPackageInstalled(pkgName)) return pkgName;
        return null;
    }

    private boolean isPackageInstalled(String packagename) {
        PackageManager pm = mContext.getPackageManager();
        try {
            pm.getPackageInfo(packagename, PackageManager.GET_ACTIVITIES);
            //showToast("found "+packagename);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            //showToast(packagename + " not found");
            return false;
        }
    }

    public boolean arePermissionsOK() {
        boolean allOk = true;
        Log.v(TAG,"arePermissionsOK");
        for (int i = 0; i< REQUIRED_PERMISSIONS.length; i++) {
            if (ContextCompat.checkSelfPermission(mContext, REQUIRED_PERMISSIONS[i])
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, REQUIRED_PERMISSIONS[i] + " Permission Not Granted");
                allOk = false;
            }
        }
        return allOk;
    }

    public boolean areSMSPermissionsOK() {
        boolean allOk = true;
        Log.v(TAG,"areSMSPermissionsOK()");
        for (int i = 0; i< SMS_PERMISSIONS.length; i++) {
            if (ContextCompat.checkSelfPermission(mContext, SMS_PERMISSIONS[i])
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, SMS_PERMISSIONS[i] + " Permission Not Granted");
                allOk = false;
            }
        }
        return allOk;
    }



    public void requestPermissions(Activity activity) {
        if (mPermissionsRequested) {
            Log.i(TAG,"requestPermissions() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestPermissions() - requesting permissions");
            for (int i = 0; i < REQUIRED_PERMISSIONS.length; i++) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                        REQUIRED_PERMISSIONS[i])) {
                    Log.i(TAG, "shouldShowRationale for permission" + REQUIRED_PERMISSIONS[i]);
                }
            }
            ActivityCompat.requestPermissions(activity,
                    REQUIRED_PERMISSIONS,
                    42);
            mPermissionsRequested = true;
        }
    }

    public void requestSMSPermissions(Activity activity) {
        if (mSMSPermissionsRequested) {
            Log.i(TAG,"requestSMSPermissions() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestSMSPermissions() - requesting permissions");
            for (int i = 0; i < SMS_PERMISSIONS.length; i++) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                        SMS_PERMISSIONS[i])) {
                    Log.i(TAG, "shouldShowRationale for permission" + SMS_PERMISSIONS[i]);
                }
            }
            ActivityCompat.requestPermissions(activity,
                    SMS_PERMISSIONS,
                    43);
            mSMSPermissionsRequested = true;
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.i(TAG,"onequestPermissionsResult - Permission" + permissions + " = " + grantResults);
    }
}
