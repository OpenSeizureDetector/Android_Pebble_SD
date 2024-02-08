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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;
import android.widget.Toast;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
//uncommented due to deprication
//import org.apache.http.conn.util.InetAddressUtils;

import com.github.mikephil.charting.data.LineDataSet;

import java.io.File;
import java.io.FileWriter;
//instead of InetAddressUtils use
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
//use java.Util.Objects as comparetool.
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OsdUtil - OpenSeizureDetector Utilities
 * Deals with starting and stopping the background service and binding to it to receive data.
 */
public class OsdUtil {
    public final static String PRIVACY_POLICY_URL = "https://www.openseizuredetector.org.uk/?page_id=1415";
    public final static String DATA_SHARING_URL = "https://www.openseizuredetector.org.uk/?page_id=1818";

    private final String SYSLOG = "SysLog";
    private final String ALARMLOG = "AlarmLog";
    private final String DATALOG = "DataLog";

    /**
     * Based on http://stackoverflow.com/questions/7440473/android-how-to-check-if-the-intent-service-is-still-running-or-has-stopped-running
     */
    private static Context mContext;
    private Handler mHandler;
    private static String TAG = "OsdUtil";
    private boolean mLogAlarms = true;
    private boolean mLogSystem = true;
    private boolean mLogData = true;
    private boolean mPermissionsRequested = false;
    private boolean mSMSPermissionsRequested = false;
    private static final String mSysLogTableName = "SysLog";
    //private LogManager mLm;
    static private SQLiteDatabase mSysLogDb = null;   // SQLite Database for data and log entries.
    private final static Long mMinPruneInterval = TimeUnit.MINUTES.toMillis(5); // minimum time between syslog pruning is 5 minutes
    private static Long mLastPruneMillis = new Long(0);   // Record of the last time we pruned the syslog db.

    private static int mNbound = 0;
    //save startId of SdServer
    private int wearReceiverStartId;

    public OsdUtil(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        updatePrefs();
        //Log.i(TAG,"Creating Log Manager instance");
        //mLm = new LogManager(mContext,false,false,null,0,0,false,0);
        openDb();
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
            mLogData = SP.getBoolean("LogData", true);
            Log.v(TAG, "OsdUtil.updatePrefs() - mLogData = " + mLogData);
            mLogSystem = SP.getBoolean("LogSystem", true);
            Log.v(TAG, "updatePrefs() - mLogSystem = " + mLogSystem);

        } catch (Exception ex) {
            Log.v(TAG, "updatePrefs() - Problem parsing preferences!");
            showToast(mContext.getString(R.string.ParsePreferenceWarning));
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
        for (ActivityManager.RunningServiceInfo service :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            //Log.v(TAG,"Service: "+service.service.getClassName());
            if ("uk.org.openseizuredetector.SdServer"
                    .equals(service.service.getClassName())) {
                nServers = nServers + 1;
            }
        }

        //simplify statement:
        return nServers != 0;
    }

    /**
     * Start the SdServer service
     * without parameters always sends Uri://Start
     */
    public void  startServer(){
        startServer(Constants.GLOBAL_CONSTANTS.mStartUri);
    }
    //overload startServer without parameters
    public void startServer(Uri setData ) {
        // Start the server
        Log.d(TAG, "OsdUtil.startServer()");
        writeToSysLogFile("startServer() - starting server");
        Intent sdServerIntent;
        sdServerIntent = new Intent(mContext, SdServer.class);
        sdServerIntent.setData(setData);
        sdServerIntent.addFlags(Intent.FLAG_FROM_BACKGROUND|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 26) {
            Log.i(TAG, "Starting Foreground Service (Android 8 and above)");
            mContext.startForegroundService(sdServerIntent);
        } else {
            Log.i(TAG, "Starting Normal Service (Pre-Android 8)");
            mContext.startService(sdServerIntent);
        }
    }

    /**
     * Stop the SdServer service
     */
    public void stopServer() {
        Log.i(TAG, "OsdUtil.stopServer() - stopping Server... - mNbound=" + mNbound);
        writeToSysLogFile("stopserver() - stopping server");

        // then send an Intent to stop the service.
        Intent sdServerIntent;
        sdServerIntent = new Intent(mContext, SdServer.class);
        sdServerIntent.setData(Constants.GLOBAL_CONSTANTS.mStopUri);
        sdServerIntent.setAction(Constants.ACTION.STOPFOREGROUND_ACTION);
        if (Build.VERSION.SDK_INT >= 26) {
            Log.i(TAG, "Starting Foreground Service (Android 8 and above)");
            mContext.startForegroundService( sdServerIntent);
        } else {
            Log.i(TAG, "Starting Normal Service (Pre-Android 8)");
            mContext.startService(sdServerIntent);
        }
    }

    public void restartServer() {
        stopServer();
        // Wait 1 second to give the server chance to shutdown, then re-start it
        mHandler.postDelayed(() -> {
                startServer();
            }
        , 1000);
    }
    /**
     * bind an activity to to an already running server.
     *
     * @return
     */
    public boolean bindToServer(Context activity, SdServiceConnection sdServiceConnection) {
        Log.i(TAG, "OsdUtil.bindToServer() - binding to SdServer");
        writeToSysLogFile("bindToServer() - binding to SdServer");
        Intent intent = new Intent(sdServiceConnection.mContext, SdServer.class);
        intent.setAction(Constants.ACTION.BIND_ACTION);
        //because @startServer the service is created, we do not need to create the service @bind
        //Set bind flag as BIND_ADJUST_WITH_ACTIVITY
        boolean returnValue = activity.bindService(intent, sdServiceConnection, Context.BIND_ADJUST_WITH_ACTIVITY);
        mNbound = mNbound + 1;
        Log.i(TAG, "OsdUtil.bindToServer() - mNbound = " + mNbound);
        return returnValue;
    }

    /**
     * unbind an activity from server
     */
    public void unbindFromServer(Context activity, SdServiceConnection sdServiceConnection) {
        // unbind this activity from the service if it is bound.
        if (Objects.nonNull(sdServiceConnection)) {
            if (sdServiceConnection.mBound) {
                Log.i(TAG, "unbindFromServer() - unbinding");
                writeToSysLogFile("unbindFromServer() - unbinding");
                try {
                    sdServiceConnection.mBound = false;
                    activity.unbindService(sdServiceConnection);
                    mNbound = mNbound - 1;
                    Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
                    sdServiceConnection.mBound= false;
                } catch (Exception ex) {
                    Log.e(TAG, "unbindFromServer() - error unbinding service - " + ex.toString(), ex);
                    writeToSysLogFile("unbindFromServer() - error unbinding service : \n" + ex.getMessage() + "\n" +Arrays.toString(Thread.currentThread().getStackTrace()));
                    Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
                }
            } else {
                Log.i(TAG, "unbindFromServer() - not bound to server - ignoring");
                writeToSysLogFile("unbindFromServer() - not bound to server - ignoring");
                Log.i(TAG, "OsdUtil.unBindFromServer() - mNbound = " + mNbound);
            }
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

                    //updated from https://stackoverflow.com/questions/32141785/android-api-23-inetaddressutils-replacement
                    // for getting IPV4 format
                    if (!inetAddress.isLoopbackAddress()
                            && inetAddress instanceof Inet4Address
                            && inetAddress.isSiteLocalAddress()
                    ) {

                        String ip = inetAddress.getHostAddress();
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

    public boolean isMobileDataActive() {
        // return true if we are using mobile data, otherwise return false
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (capabilities == null) {
                    return false;
                }

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ) {
                    return true;
                }else
                    return false;
            }else {
                /**
                 * has @Deprecation!
                 * see https://developer.android.com/reference/android/net/NetworkInfo
                 * @return
                 */
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork == null) return false;
                if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) {
                    return true;
                } else {
                    return false;
                }
            }
        }
        else
            return  false;

    }

    public boolean isNetworkConnected() {
        // return true if we have a network connection, otherwise false.
        // modified because networkInfo is deprecated. Solution:
        // https://stackoverflow.com/questions/32547006/connectivitymanager-getnetworkinfoint-deprecated
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (capabilities == null) {
                    return false;
                }

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }else
                    return false;
            }else {
                /**
                 * has @Deprecation!
                 * see https://developer.android.com/reference/android/net/NetworkInfo
                 * @return
                 */
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null) {
                    return (activeNetwork.isConnected());
                } else {
                    return (false);
                }
            }
        }
        else
            return  false;
    }

    // simplifying text
    /**
     * Display a Toast message on screen.
     *
     * @param msg - message to display.
     */
    public void showToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(mContext, msg,
                Toast.LENGTH_LONG).show());
    }


    /**
     * Write a message to the system log database.
     *
     * @param msgStr
     */
    public void writeToSysLogFile(String msgStr, String logType) {
        writeLogEntryToLocalDb(msgStr, logType);
    }

    public void writeToSysLogFile(String msgStr) {
        writeLogEntryToLocalDb(msgStr, "v");
    }


    /**
     * Write a message to the alarm log file, provided mLogAlarms is true.
     *
     * @param msgStr
     */
    public void writeToAlarmLogFile(String msgStr) {
        if (mLogAlarms)
            writeToLogFile(ALARMLOG, msgStr);
        else
            Log.v(TAG, "writeToAlarmLogFile - mLogAlarms False so not writing");
    }

    /**
     * Write a message to the data log file, provided mLogData is true.
     *
     * @param msgStr
     */
    public void writeToDataLogFile(String msgStr) {
        if (mLogData)
            writeToLogFile(DATALOG, msgStr);
        else
            Log.v(TAG, "writeToDataLogFile - mLogData False so not writing");
    }


    public static double calculateAverage(List<Double> marks) {
        double sum = 0;
        if (!marks.isEmpty()) {
            for (Double mark : marks) {
                sum += mark;
            }
            return sum / marks.size();
        }
        return sum;
    }

    /**
     * Function to convert sensor acceleration from metres per
     * second squared to milliGal(mGal)
     * @param mms value in metres per second squared
     * @return mms * math.pow(10,5}
     */
    public static double convertMetresPerSecondSquaredToMilliG(double mms){
        return (mms/ SensorManager.GRAVITY_EARTH) *Math.pow(10,5);
    }

/**
 * Get return average of list of Entries
 */
    public static int getAverageValueFromListOfEntry(@NonNull LineDataSet listToAverage){
        return (int) (listToAverage.getYValueSum()/listToAverage.getYVals().size());
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
            Log.e(TAG, "ERROR: We do not have permission to write to external storage");
        } else {
            if (isExternalStorageWritable()) {
                try {
                    FileWriter of = new FileWriter(getDataStorageDir() + "/" + fname, true);
                    if (msgStr != null) {
                        String dateTimeStr = tnow.format("%Y-%m-%d %H:%M:%S");
                        //Log.v(TAG, "writing msgStr");
                        of.append(dateTimeStr + ", "
                                + tnow.toMillis(true) + ", "
                                + msgStr + "<br/>\n");
                    }
                    of.close();
                } catch (Exception ex) {
                    Log.e(TAG, "writeToLogFile - error " + ex.toString(), ex);
                    for (int i = 0; i < (ex.getStackTrace().length); i++) {
                        Log.e(TAG, "writeToLogFile - error " + ex.getStackTrace()[i]);
                    }
                    showToast(mContext.getString(R.string.ErrorWritingLogFileWarning) + ex.getMessage() + "\n" +
                            Arrays.toString(Thread.currentThread().getStackTrace()));
                }
            } else {
                Log.e(TAG, "ERROR - Can not Write to External Folder");
            }
        }
    }

    public File[] getDataFilesList() {
        File[] files = getDataStorageDir().listFiles();
        Log.d("Files", "Size: " + files.length);
        for (int i = 0; i < files.length; i++) {
            Log.d("Files", "FileName:" + files[i].getName());
        }
        return (files);
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
        File file = mContext.getExternalFilesDir(null);
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
     * convertTimeUnit -- Convert From TimeUnit to TimeUnit in Double format
     * @param amount Enter in double format value to convert
     * @param from Enter TimeUnit Origin like TimeUnit.SECONDS
     * @param to Enter TimeUnit.MICROSECONDS
     * <p>
     * if from equals to, the original value returns.
     *
     * @return Double converted value.
     * */

    public static double convertTimeUnit(double amount, TimeUnit from, TimeUnit to) {
        // if the same unit is passed, avoid the conversion
        if (from == to) {
            return amount;
        }
        // is from or to the larger unit?
        if (from.ordinal() < to.ordinal()) { // from is smaller
            return amount / from.convert(1, to);
        } else {
            return amount * to.convert(1, from);
        }
    }


    /**
     * string2date - returns a Date object represented by string dateStr
     * It first attempts to parse it as a long integer, in which case it is assumed to
     * be a unix timestamp.
     * If that fails it attempts to parse it as yyyy-MM-dd'T'HH:mm:ss'Z' format.
     * @param dateStr String representing a date
     * @return Date object or null if parsing fails.
     */
    public Date string2date(String dateStr) {
        Date dataTime = null;
        try {
            long tstamp = Long.parseLong(dateStr);
            dataTime = new Date(tstamp);
        } catch (NumberFormatException e) {
            Log.v(TAG, "remoteEventsAdapter.getView: Error Parsing dataDate as Long: " + e.getLocalizedMessage() + " trying as string",e);
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                dataTime = dateFormat.parse(dateStr);
            } catch (ParseException e2) {
                Log.e(TAG, "remoteEventsAdapter.getView: Error Parsing dataDate " + e2.getLocalizedMessage());
                dataTime = null;
            }
        }
        return (dataTime);
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

    private static boolean openDb() {
        Log.d(TAG, "openDb");
        try {
            if (mSysLogDb == null) {
                Log.i(TAG, "openDb: mSysLogDb is null - initialising");
                mSysLogDb = new OsdSysLogHelper(mContext).getWritableDatabase();
            } else {
                Log.i(TAG, "openDb: mSysLogDb has been initialised already so not doing anything");
            }
            if (!checkTableExists(mSysLogDb, mSysLogTableName)) {
                Log.e(TAG, "ERROR - Table " + mSysLogTableName + " does not exist");
                return false;
            } else {
                Log.d(TAG, "table " + mSysLogTableName + " exists ok");
            }
        } catch (SQLException e) {
            Log.e(TAG, "Failed to open Database: " + e.toString(), e);
            return false;
        }
        return true;
    }

    private static boolean checkTableExists(SQLiteDatabase osdDb, String osdTableName) {
        Cursor c = null;
        boolean tableExists = false;
        Log.d(TAG, "checkTableExists()");
        try {
            c = osdDb.query(osdTableName, null,
                    null, null, null, null, null);
            tableExists = true;
            c.close();
        } catch (Exception e) {
            Log.d(TAG, osdTableName + " doesn't exist :(((");
        }
        return tableExists;
    }

    /**
     * Write syslog string to local database
     * FIXME - I am sure we should not be using raw SQL Srings to do this!
     */
    public void writeLogEntryToLocalDb(String logText, String statusVal) {
        Log.v(TAG, "writeLogEntryToLocalDb()");
        Date curDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String dateStr = dateFormat.format(curDate);
        String SQLStr = "SQLStr";

        try {
            SQLStr = "INSERT INTO " + mSysLogTableName
                    + "(dataTime, logLevel, dataJSON, uploaded)"
                    + " VALUES("
                    + "'" + dateStr + "',"
                    + DatabaseUtils.sqlEscapeString(statusVal) + ","
                    + DatabaseUtils.sqlEscapeString(logText) + ","
                    + 0
                    + ")";
            mSysLogDb.execSQL(SQLStr);
            Log.v(TAG, "syslog entry written to database: " + logText);
            pruneSysLogDb();

        } catch (SQLException e) {
            Log.e(TAG, "writeLogEngryToLocalDb(): Error Writing Data: " + e.toString(), e);
            Log.e(TAG, "SQLStr was " + SQLStr);
        }

    }

    /**
     * Return an array list of objects representing the syslog entries in the database by calling the specified callback function.
     *
     * @return True on successful start or false if call fails.
     */
    public boolean getSysLogList(Consumer<ArrayList<HashMap<String, String>>> callback) {
        Log.v(TAG, "getSysLogList");
        ArrayList<HashMap<String, String>> eventsList = new ArrayList<>();

        String whereClause = "";
        String[] whereArgs = {};
        String[] columns = {"*"};
        new SelectQueryTask(mSysLogTableName, columns, null, null,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Log.v(TAG, "getSysLogList - returned " + cursor);
            if (cursor != null) {
                Log.v(TAG, "getSysLogList - returned " + cursor.getCount() + " records");
                while (!cursor.isAfterLast()) {
                    HashMap<String, String> event = new HashMap<>();
                    //event.put("id", cursor.getString(cursor.getColumnIndex("id")));
                    try {
                        event.put("dataTime", cursor.getString(cursor.getColumnIndexOrThrow("dataTime")));
                        String loglevel = cursor.getString(cursor.getColumnIndexOrThrow("logLevel"));
                        event.put("loglevel", loglevel);
                        event.put("dataJSON", cursor.getString(cursor.getColumnIndexOrThrow("dataJSON")));
                        //event.put("dataJSON", cursor.getString(cursor.getColumnIndex("dataJSON")));
                        eventsList.add(event);
                    }catch (IllegalArgumentException illegalArgumentException){
                        Log.e(TAG,"getSysLogList(): Ignoring current event: Result of Cursor.getString: -1");
                    }
                    cursor.moveToNext();
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                callback.accept(eventsList); // .accept requires API_SDK_LEVEL >= ANDROID.VERSION_N
            }
            else showToast("Not supported action at this version of Android. Please concider upgrading.");
        }).execute();
        return (true);
    }

    public void setBound(boolean valueToSet, SdServiceConnection sdServiceConnection) {
        if (Objects.nonNull(sdServiceConnection))
            if (Objects.nonNull(sdServiceConnection.mSdServer))
                sdServiceConnection.mSdServer.setBound(valueToSet);
    }

    /**
     * Executes the sqlite query (=SELECT statement)
     * Use as new SelectQueryTask(xxx,xxx,xx,xxxx).execute()
     */
    static private class SelectQueryTask extends AsyncTask<Void, Void, Cursor> {
        // Based on https://stackoverflow.com/a/21120199/2104584
        String mTable;
        String[] mColumns;
        String mSelection;
        String[] mSelectionArgs;
        String mGroupBy;
        String mHaving;
        String mOrderBy;
        Consumer<Cursor> mCallback;

        //query(String table, String[] columns, String selection, String[] selectionArgs,
        // String groupBy, String having, String orderBy)
        SelectQueryTask(String table, String[] columns, String selection, String[] selectionArgs,
                        String groupBy, String having, String orderBy, Consumer<Cursor> callback) {
            // list all the parameters like in normal class define
            this.mTable = table;
            this.mColumns = columns;
            this.mSelection = selection;
            this.mSelectionArgs = selectionArgs;
            this.mGroupBy = groupBy;
            this.mHaving = having;
            this.mOrderBy = orderBy;
            this.mCallback = callback;

        }

        @Override
        protected Cursor doInBackground(Void... params) {
            Log.v(TAG, "runSelect.doInBackground()");
            Log.v(TAG, "SelectQueryTask.doInBackground: mTable=" + mTable + ", mColumns=" + Arrays.toString(mColumns)
                    + ", mSelection=" + mSelection + ", mSelectionArgs=" + Arrays.toString(mSelectionArgs) + ", mGroupBy=" + mGroupBy
                    + ", mHaving =" + mHaving + ", mOrderBy=" + mOrderBy);

            try {
                Cursor resultSet = mSysLogDb.query(mTable, mColumns, mSelection,
                        mSelectionArgs, mGroupBy, mHaving, mOrderBy);
                resultSet.moveToFirst();
                return (resultSet);
            } catch (SQLException e) {
                Log.e(TAG, "SelectQueryTask.doInBackground(): Error selecting Data: " + e.toString(), e);
                return (null);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "SelectQueryTask.doInBackground(): Illegal Argument Exception: " + e.toString(), e);
                return (null);
            }
        }

        @Override
        protected void onPostExecute(final Cursor result) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mCallback.accept(result);
            }
            else Toast.makeText(mContext,"Not supported action at this version of Android. Please concider upgrading.", Toast.LENGTH_SHORT).show();
            //OsdUtil.showToast call will not be available in this function. Recreate new one.
        } // .accept requires API_SDK_LEVEL >= ANDROID.VERSION_N
    }


    /**
     * pruneSysLogDb() removes data that is older than 7 days
     */
    public int pruneSysLogDb() {
        //Log.v(TAG, "pruneSysLogDb()");
        int retVal;
        long currentDateMillis = new Date().getTime();
        if (currentDateMillis > mLastPruneMillis + mMinPruneInterval) {
            mLastPruneMillis = currentDateMillis;
            // FIXME - change this to something sensible like 7 days after testing
            long endDateMillis = currentDateMillis - TimeUnit.MINUTES.toMillis(5);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String endDateStr = dateFormat.format(new Date(endDateMillis));
            Log.v(TAG, "pruneSysLogDb - endDateStr=" + endDateStr);
            try {
                String selectStr = "DataTime<=?";
                String[] selectArgs = {endDateStr};
                retVal = mSysLogDb.delete(mSysLogTableName, selectStr, selectArgs);
            } catch (Exception e) {
                Log.e(TAG, "Error deleting log entries" + e.toString(), e);
                retVal = 0;
            }
            if (retVal > 0) {
                Log.v(TAG, String.format("pruneSysLogDb() - deleted %d records", retVal));
            }
            return (retVal);
        } else {
            return (0);
        }
    }


    public static class OsdSysLogHelper extends SQLiteOpenHelper {
        // If you change the database schema, you must increment the database version.
        public static final int DATABASE_VERSION = 1;
        public static final String DATABASE_NAME = "OsdSysLog.db";
        private static final String TAG = "LogManager.OsdSysLogHelper";

        public OsdSysLogHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            Log.d(TAG, "OsdSysLogHelper constructor");
        }

        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "onCreate - TableName=" + mSysLogTableName);
            String SQLStr = "CREATE TABLE IF NOT EXISTS " + mSysLogTableName + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "logLevel TEXT,"
                    + "dataJSON TEXT,"
                    + "uploaded INT"
                    + ");";
            db.execSQL(SQLStr);
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This database is only a cache for online data, so its upgrade policy is
            // to simply to discard the data and start over
            Log.i(TAG, "onUpgrade()");
            db.execSQL("Drop table if exists " + mSysLogTableName + ";");
            onCreate(db);
        }

        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "onDowngrade()");
            onUpgrade(db, oldVersion, newVersion);
        }
    }

    public void waitForConnection(SdServiceConnection mConnection) {
        // We want the UI to update as soon as it is displayed, but it takes a finite time for
        // the mConnection to bind to the service, so we delay half a second to give it chance
        // to connect before trying to update the UI for the first time (it happens again periodically using the uiTimer)
        if (mConnection.mBound) {
            Log.d(TAG, "waitForConnection - Bound!");
        } else {
            Log.v(TAG, "waitForConnection - waiting...");
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    waitForConnection(mConnection);
                }
            }, 100);
        }
    }

}
