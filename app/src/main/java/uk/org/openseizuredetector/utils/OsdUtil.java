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
package uk.org.openseizuredetector.utils;
import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.client.SdServiceConnection;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.data.logging.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import uk.org.openseizuredetector.SdServer;
/**
 * OsdUtil - OpenSeizureDetector Utilities
 * Deals with starting and stopping the background service and binding to it to receive data.
 */
public class OsdUtil {
    public final static String PRIVACY_POLICY_URL = "https://www.openseizuredetector.org.uk/?page_id=1415";
    public final static String DATA_SHARING_URL = "https://www.openseizuredetector.org.uk/?page_id=1818";

    /**
     * Based on http://stackoverflow.com/questions/7440473/android-how-to-check-if-the-intent-service-is-still-running-or-has-stopped-running
     */
    // Always store application context to prevent memory leaks (safe for static field)
    @SuppressWarnings("StaticFieldLeak") // Safe: Always stores application context, not activity context
    private static Context mContext;
    private Handler mHandler;
    private static String TAG = "OsdUtil";
    private boolean mPermissionsRequested = false;
    private boolean mSMSPermissionsRequested = false;

    // File-based persistent logger (static is safe because it stores application context only)
    // PersistentFileLogger internally calls context.getApplicationContext() to avoid leaks
    @SuppressWarnings("StaticFieldLeak") // Safe: PersistentFileLogger uses application context internally
    private static PersistentFileLogger mFileLogger = null;

    private static int mNbound = 0;

    public final String[] BT_PERMISSIONS_API30 = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            //Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
    };
    public final String[] BT_PERMISSIONS_OLD = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
    };
    public String[] BT_PERMISSIONS;

    public final String[] ACTIVITY_PERMISSIONS_API34 = {
            Manifest.permission.FOREGROUND_SERVICE_HEALTH,
            Manifest.permission.ACTIVITY_RECOGNITION
    };

    public final String[] ACTIVITY_PERMISSIONS_OLD = {};
    public String[] ACTIVITY_PERMISSIONS;


    public OsdUtil(Context context, Handler handler) {
        // Always use application context to prevent memory leaks with static fields
        mContext = context.getApplicationContext();
        mHandler = handler;

        // Initialize file logger (thread-safe singleton)
        if (mFileLogger == null) {
            mFileLogger = new PersistentFileLogger(mContext);
            Log.i(TAG, "PersistentFileLogger initialized: " + mFileLogger.getCurrentLogPath());
        }

        writeToSysLogFile("OsdUtil() - initialised");

    }

    /**
     * Centralised method to apply the application theme based on user preference.
     * Supports: "system" (default), "light", and "dark".
     */
    public static void applyTheme(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String themePref = sp.getString("darkMode", "SET_FROM_XML");

        Log.i(TAG, "applyTheme(): Setting theme to: " + themePref);
        
        switch (themePref) {
            case "light":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "dark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "system":
            default:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                } else {
                    // MODE_NIGHT_AUTO_BATTERY was added in API 21 and recommended for pre-Q
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
                }
                break;
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
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);

        // getRunningServices is deprecated since Android 8.0 (API 26) for background tasks,
        // but remains supported for retrieving the app's own services.
        // Since our minSdk is now 26, we can use it consistently for our own service check.
        @SuppressWarnings("deprecation")
        List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
        if (services != null) {
            for (ActivityManager.RunningServiceInfo service : services) {
                if ("uk.org.openseizuredetector.SdServer"
                        .equals(service.service.getClassName())) {
                    nServers = nServers + 1;
                }
            }
        }

        if (nServers != 0) {
            //Log.v(TAG, "isServerRunning() - " + nServers + " instances are running");
            return true;
        } else
            return false;
    }

    /**
     * Start the SdServer service
     */
    public void startServer() {
        // Start the server
        Log.d(TAG, "OsdUtil.startServer()");
        Log.i(TAG, "startServer() - starting server");
        Intent sdServerIntent;
        sdServerIntent = new Intent(mContext, SdServer.class);
        sdServerIntent.setData(Uri.parse("Start"));
        Log.i(TAG, "Starting Foreground Service (Android 8 and above)");
        mContext.startForegroundService(sdServerIntent);
    }

    /**
     * Stop the SdServer service
     */
    public void stopServer() {
        Log.i(TAG, "OsdUtil.stopServer() - stopping Server... - mNbound=" + mNbound);
        Log.i(TAG, "stopserver() - stopping server");

        // then send an Intent to stop the service.
        Intent sdServerIntent;
        sdServerIntent = new Intent(mContext, SdServer.class);
        sdServerIntent.setData(Uri.parse("Stop"));
        mContext.stopService(sdServerIntent);
    }


    /**
     * bind an activity to to an already running server.
     */
    public void bindToServer(Context activity, SdServiceConnection sdServiceConnection) {
        Log.i(TAG, "OsdUtil.bindToServer() - binding to SdServer");
        Log.i(TAG, "bindToServer() - binding to SdServer");
        Intent intent = new Intent(sdServiceConnection.mContext, SdServer.class);
        activity.bindService(intent, sdServiceConnection, Context.BIND_AUTO_CREATE);
        mNbound = mNbound + 1;
        Log.i(TAG, "OsdUtil.bindToServer() - mNbound = " + mNbound);
    }

    /**
     * unbind an activity from server
     */
    public void unbindFromServer(Context activity, SdServiceConnection sdServiceConnection) {
        // unbind this activity from the service if it is bound.
        if (sdServiceConnection.mBound) {
            Log.i(TAG, "unbindFromServer() - unbinding");
            Log.i(TAG, "unbindFromServer() - unbinding");
            try {
                activity.unbindService(sdServiceConnection);
                sdServiceConnection.mBound = false;
                mNbound = mNbound - 1;
                Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
            } catch (Exception ex) {
                Log.e(TAG, "unbindFromServer() - error unbinding service - " + ex.toString());
                Log.e(TAG, "unbindFromServer() - error unbinding service - " + ex.toString());
                Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
            }
        } else {
            Log.i(TAG, "unbindFromServer() - not bound to server - ignoring");
            Log.i(TAG, "unbindFromServer() - not bound to server - ignoring");
            Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
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
                    if (!inetAddress.isLoopbackAddress()) {
                        String ip = inetAddress.getHostAddress();
                        // Check for IPv4 manually or use a more modern method if available
                        if (ip != null && ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("IP Address", ex.toString());
        }
        return null;
    }

    public boolean isMobileDataActive() {
        // return true if we are using mobile data, otherwise return false
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        android.net.Network network = cm.getActiveNetwork();
        android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    public boolean isNetworkConnected() {
        // return true if we have a network connection, otherwise false.
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        android.net.Network network = cm.getActiveNetwork();
        android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null && (
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        );
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
     * Write a message to the system log database.
     *
     * @param msgStr
     */
    /**
     * Write to system log file with specified log type and exception handling.
     * Wraps all logging operations in try-catch to prevent crashes during logging.
     * Log types: INFO, LIFECYCLE, MEMORY, EXCEPTION, SHUTDOWN, TIMER, WARNING, etc.
     */
    public void writeToSysLogFile(String msgStr, String logType) {
        try {
            if (mFileLogger != null) {
                mFileLogger.log(logType.toUpperCase(), msgStr);
            } else {
                Log.w(TAG, "File logger not initialized: " + msgStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing to sys log file: " + e.getMessage(), e);
        }
    }

    /**
     * Write to system log file with INFO log level and exception handling.
     */
    public void writeToSysLogFile(String msgStr) {
        try {
            if (mFileLogger != null) {
                mFileLogger.log("INFO", msgStr);
            } else {
                Log.w(TAG, "File logger not initialized: " + msgStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing to sys log file: " + e.getMessage(), e);
        }
    }

    /**
     * Write memory status (used by watchdog or periodic timers).
     * Log level: MEMORY
     * Usage: mUtil.writeToSysLogFile("Memory check reason", "MEMORY")
     */
    public void writeMemoryLog(String reason) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            long usedMemory = totalMemory - freeMemory;

            // Get memory info from ActivityManager
            ActivityManager actMgr = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            if (actMgr != null) {
                actMgr.getMemoryInfo(memInfo);
            }

            String memMsg = String.format(
                "Used=%dMB, Free=%dMB, Total=%dMB, Max=%dMB, AvailSystemMem=%dMB, LowMemory=%s [Reason: %s, PID=%d]",
                usedMemory / (1024 * 1024),
                freeMemory / (1024 * 1024),
                totalMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                memInfo.availMem / (1024 * 1024),
                memInfo.lowMemory,
                reason,
                android.os.Process.myPid()
            );

            if (mFileLogger != null) {
                mFileLogger.log("MEMORY", memMsg);
            } else {
                Log.i(TAG, memMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing memory log: " + e.getMessage(), e);
        }
    }

    /**
     * Log an exception with full stack trace for shutdown diagnosis.
     * Log level: EXCEPTION
     * Usage: mUtil.writeToSysLogFile("ComponentName.method - " + exception.getMessage(), "EXCEPTION")
     */
    public void writeExceptionLog(String component, String method, Throwable exception) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(component).append(".").append(method);
            sb.append(" - ").append(exception.getClass().getSimpleName());
            sb.append(": ").append(exception.getMessage());

            if (mFileLogger != null) {
                mFileLogger.log("EXCEPTION", sb.toString());

                // Log stack trace elements
                StackTraceElement[] stackTrace = exception.getStackTrace();
                for (int i = 0; i < Math.min(stackTrace.length, 5); i++) {
                    mFileLogger.log("EXCEPTION", "  at " + stackTrace[i].toString());
                }
            } else {
                Log.e(TAG, sb.toString(), exception);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing exception log: " + e.getMessage(), e);
        }
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

    public boolean isPackageInstalled(String packagename) {
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

    /**
     * string2date - returns a Date object represented by string dateStr
     * It first attempts to parse it as a long integer, in which case it is assumed to
     * be a unix timestamp (milliseconds).
     * If that fails it attempts to parse it as ISO 8601 format with various timezone representations:
     * - yyyy-MM-dd'T'HH:mm:ss'Z' (UTC)
     * - yyyy-MM-dd'T'HH:mm:ss+00:00 (with timezone offset)
     * - yyyy-MM-dd HH:mm:ss (basic datetime without timezone)
     *
     * @param dateStr String representing a date
     * @return Date object or null if parsing fails.
     */
    public Date string2date(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            Log.w(TAG, "string2date: Empty or null date string provided");
            return null;
        }

        Date dataTime = null;
        try {
            // First try parsing as unix timestamp (milliseconds)
            Long tstamp = Long.parseLong(dateStr);
            dataTime = new Date(tstamp);
            Log.v(TAG, "string2date: Successfully parsed as unix timestamp");
            return dataTime;
        } catch (NumberFormatException e) {
            Log.v(TAG, "string2date: Not a unix timestamp, attempting ISO 8601 format");
        }

        // Try various ISO 8601 formats
        String[] dateFormats = {
            "yyyy-MM-dd'T'HH:mm:ss'Z'",           // UTC with Z suffix
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",       // UTC with milliseconds and Z suffix
            "yyyy-MM-dd'T'HH:mm:ss",              // Without timezone
            "yyyy-MM-dd'T'HH:mm:ss.SSS",          // With milliseconds
            "yyyy-MM-dd HH:mm:ss",                // Space separator
            "yyyy-MM-dd HH:mm:ss.SSS"             // Space separator with milliseconds
        };

        for (String format : dateFormats) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat(format, Locale.US);
                dateFormat.setLenient(false);

                // Remove timezone offset if present (e.g., +00:00, -05:00)
                String cleanedDateStr = dateStr.replaceAll("[+-]\\d{2}:\\d{2}$", "");

                dataTime = dateFormat.parse(cleanedDateStr);
                Log.v(TAG, "string2date: Successfully parsed using format: " + format);
                return dataTime;
            } catch (ParseException e2) {
                Log.v(TAG, "string2date: Format '" + format + "' failed, trying next");
                // Continue to next format
            }
        }

        // If all parsing attempts fail
        Log.e(TAG, "string2date: Failed to parse date string: " + dateStr);
        return null;
    }


    public final int ALARM_STATUS_WARNING = 1;
    public final int ALARM_STATUS_ALARM = 2;
    public final int ALARM_STATUS_FALL = 3;
    public final int ALARM_STATUS_MANUAL = 5;

    public String alarmStatusToString(int eventAlarmStatus) {
        String retVal = "Unknown";
        switch (eventAlarmStatus) {
            case ALARM_STATUS_WARNING: // Warning
                retVal = "WARNING";
                break;
            case ALARM_STATUS_ALARM: // alarm
                retVal = "ALARM";
                break;
            case ALARM_STATUS_FALL: // fall
                retVal = "FALL";
                break;
            case ALARM_STATUS_MANUAL: // Manual alarm
                retVal = "MANUAL ALARM";
                break;

        }
        return (retVal);
    }



    public String[] getRequiredBtPermissions() {
        // API 31 is Android 12 - see https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
        if (Build.VERSION.SDK_INT >= 31) {
            Log.d(TAG, "getRequiredBtPermissions() - using new Bluetooth Permissions");
            BT_PERMISSIONS = BT_PERMISSIONS_API30;
        } else {
            Log.d(TAG, "getRequiredBtPermissions() - using old Bluetooth Permissions");
            BT_PERMISSIONS = BT_PERMISSIONS_OLD;
        }
        return (BT_PERMISSIONS);
    }
    public boolean areBtPermissionsOk() {
        String[] btPermissions = getRequiredBtPermissions();
        boolean allOk = true;
        Log.d(TAG, "areBTPermissions OK()");
        for (int i = 0; i < btPermissions.length; i++) {
            if (ContextCompat.checkSelfPermission(mContext, btPermissions[i])
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, btPermissions[i] + " Permission Not Granted");
                allOk = false;
            }
        }
        return allOk;
    }

    public String[] getRequiredActivityPermissions() {
        // API 34 is Android 14 - see https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
        if (Build.VERSION.SDK_INT >= 34) {
            Log.d(TAG, "getRequiredActivityPermissions() - using new Activity Permissions");
            ACTIVITY_PERMISSIONS = ACTIVITY_PERMISSIONS_API34;
        } else {
            Log.d(TAG, "getRequiredActivityPermissions() - using old Activity Permissions");
            ACTIVITY_PERMISSIONS = ACTIVITY_PERMISSIONS_OLD;
        }
        return (ACTIVITY_PERMISSIONS);
    }
    public boolean areActivityPermissionsOk() {
        String[] activityPermissions = getRequiredActivityPermissions();
        boolean allOk = true;
        Log.d(TAG, "areActivityPermissions OK()");
        for (int i = 0; i < activityPermissions.length; i++) {
            if (ContextCompat.checkSelfPermission(mContext, activityPermissions[i])
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, activityPermissions[i] + " Permission Not Granted");
                allOk = false;
            }
        }
        return allOk;
    }



    public double parseToDouble(String userInput) {
        /**
         * Parse a string to a double value, taking localisation into account.
         * Using NumberFormat as recommended by https://docs.oracle.com/javase%2F7%2Fdocs%2Fapi%2F%2F/java/lang/Double.html#valueOf(java.lang.String)
         */
        double retVal;
        try {
            // Since minSdk is 26, we can use the modern getLocales() API (added in API 24)
            Locale currentLocale = mContext.getResources().getConfiguration().getLocales().get(0);
            NumberFormat nf = NumberFormat.getInstance(currentLocale);
            retVal = nf.parse(userInput).doubleValue();
        } catch (ParseException e) {
            // Handle invalid input (e.g., non-numeric characters)
            showToast("Invalid input. Please enter a valid numeric value.");
            retVal = 0.0;
        }
        return(retVal);
    }

    public File[] getLogFiles() {
        if (mFileLogger != null) {
            return mFileLogger.getLogFiles();
        }
        return new File[0];
    }

    public String getCurrentLogPath() {
        if (mFileLogger != null) {
            return mFileLogger.getCurrentLogPath();
        }
        return null;
    }

    public PersistentFileLogger getFileLogger() {
        return mFileLogger;
    }
}
