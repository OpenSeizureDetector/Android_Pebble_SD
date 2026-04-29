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
import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.utils.PreferenceUtils;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.service.notification.StatusBarNotification;
import android.telephony.SmsManager;
import uk.org.openseizuredetector.data.logging.Log;

import java.util.Calendar;

import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.rohitss.uceh.UCEHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Timer;

import uk.org.openseizuredetector.data.logging.LogManager;
import uk.org.openseizuredetector.alg.MlModelManager;
import uk.org.openseizuredetector.alg.SeizureDetector;
import uk.org.openseizuredetector.data.AlarmState;
import uk.org.openseizuredetector.datasource.SdDataReceiver;
import uk.org.openseizuredetector.datasource.SdDataSource;
import uk.org.openseizuredetector.datasource.SdDataSourceAw;
import uk.org.openseizuredetector.datasource.SdDataSourceBLE;
import uk.org.openseizuredetector.datasource.SdDataSourceBLE2;
import uk.org.openseizuredetector.datasource.SdDataSourceGarmin;
import uk.org.openseizuredetector.datasource.SdDataSourceNetwork;
import uk.org.openseizuredetector.datasource.SdDataSourcePebble;
import uk.org.openseizuredetector.datasource.SdDataSourcePhone;
import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.CircBufHistoryLoader;
import uk.org.openseizuredetector.utils.LocationFinder;
import uk.org.openseizuredetector.utils.SdLocationReceiver;
import uk.org.openseizuredetector.utils.OsdUtil;
import uk.org.openseizuredetector.webserver.SdWebServer;
import uk.org.openseizuredetector.activity.auth.AuthenticateActivity;
import uk.org.openseizuredetector.activity.logging.LogManagerControlActivity;
import uk.org.openseizuredetector.activity.main.MainActivity2;
/**
 * Based on example at:
 * http://stackoverflow.com/questions/14309256/using-nanohttpd-in-android
 * and
 * http://developer.android.com/guide/components/services.html#ExtendingService
 */
public class SdServer extends Service implements SdDataReceiver {
    private String mUuidStr = "0f675b21-5a36-4fe7-9761-fd0c691651f3";  // UUID to Identify OSD.

    // SharedPreferences key for tracking shutdown reason across process restarts
    private static final String PREF_SHUTDOWN_REASON = "SdServer_ShutdownReason";
    private static final String PREF_SHUTDOWN_TIME   = "SdServer_ShutdownTime";
    private static final String PREF_LAST_PID        = "SdServer_LastPid";
    /** Shutdown reason values stored in SharedPreferences */
    private static final String SHUTDOWN_REASON_PLANNED  = "planned";
    private static final String SHUTDOWN_REASON_RUNNING  = "running";   // set at startup, means crash if found on next start

    // Notification ID
    private final int NOTIFICATION_ID = 1;
    // On Android 8+, notification sound is controlled by the channel, NOT by setSound() on the
    // notification builder. We therefore use a separate channel per alarm level, each pre-configured
    // with its own sound. Channel IDs are versioned so a fresh channel is created if they change.
    // Single silent notification channel used for the foreground service status display.
    // Sound is played directly via MediaPlayer — not via notification channels.
    private static final String NOTCH_OK = "OSD_NOTIFICATION_v1";

    // Data Sharing Status Message to be displayed in the notification
    private String mDataShareMsg = null;

    private NotificationManager mNM;
    // NotificationCompat.Builder is kept but we pass the correct channel ID per alarm level.
    private NotificationCompat.Builder mNotificationBuilder;
    private Notification mNotification;
    private int mCurrentNotificationAlarmLevel = -999;
    private String mCurrentNotificationContent = null; // tracks last content text to avoid redundant notify() calls
    private boolean mIsDestroying = false;  // Flag to prevent actions during shutdown
    private SdWebServer webServer = null;
    private final static String TAG = "SdServer";
    //private Timer dataLogTimer = null;
    private CancelAudibleTimer mCancelAudibleTimer = null;
    private int mCancelAudiblePeriod = 10;  // Cancel Audible Period in minutes
    private long mCancelAudibleTimeRemaining = 0;
    private FaultTimer mFaultTimer = null;
    private CheckEventsTimer mEventsTimer = null;
    private int mFaultTimerPeriod = 30;  // Fault Timer Period in sec
    private boolean mFaultTimerCompleted = false;

    private CheckModelUpdateTimer mModelUpdateTimer = null;
    private long mModelUpdateCheckPeriod = 86400;  // Model update check period in seconds (default: 1 day)
    private MlModelManager mMlModelManager = null;

    // Watchdog for detecting silent failures
    private WatchdogThread mWatchdogThread = null;
    private long mLastDataReceivedMillis = 0;
    private long mLastWatchdogHeartbeatMillis = 0;
    private long mLastDatasourceRestartMillis = 0;
    private boolean mDataSourceRestartInProgress = false;
    private long mServiceStartMillis = 0;
    private boolean mHasReceivedData = false;
    private long mLastWatchdogFaultMillis = 0;

    private HandlerThread thread;
    private WakeLock mWakeLock = null;
    private LocationFinder mLocationFinder = null;
    public SdDataSource mSdDataSource;
    public SdData mSdData = null;
    public uk.org.openseizuredetector.data.SdDataHistory mSdDataHistory = null;  // Persistent history buffers
    public String mSdDataSourceName = "undefined";  // The name of the data source specified in the preferences.
    private SeizureDetector mSeizureDetector = null;  // Coordinator for all seizure detection algorithms
    private boolean mLatchAlarms = false;
    private int mLatchAlarmPeriod = 0;
    private LatchAlarmTimer mLatchAlarmTimer = null;
    private boolean mCancelAudible = false;
    public boolean mAudibleAlarm = false;   // set to public because it is accessed by MainActivity
    private boolean mAudibleWarning = false;
    private boolean mAudibleFaultWarning = false;
    private boolean mMp3Alarm = false;
    // User-selected MP3 URIs (null/empty = use bundled res/raw/ file)
    private String mMp3WarningUri = "";
    private String mMp3AlarmUri   = "";
    private String mMp3FaultUri   = "";
    private boolean mPhoneAlarm = false;
    private boolean mSMSAlarm = false;

    // Phone battery low-battery fault
    private boolean mPhoneBatteryAlarmActive = true;
    private int mPhoneBatteryAlarmThreshold = 5;  // percent (value set in updatePrefs())
    private String[] mSMSNumbers;
    private String mSMSMsgStr = "default SMS Message";
    private String mSMSFalseAlarmMsgStr = "default SMS False Alarm Message";
    public long mSMSTimeMillis = 0;  // last time we sent an SMS Alarm (limited to one per minute) - in milliseconds
    public SmsTimer mSmsTimer = null;  // Timer to wait for specified time before sending an alert to give the user chance to cancel it.
    public int mSmsTimerSecs = 10;    // Time delay in seconds before sending SMS alert.
    private AlertDialog.Builder mSMSAlertDialog;   // Dialog shown during countdown to sending SMS.

    // Data Logging Parameters
    private boolean mLogAlarms = true;
    public boolean mLogData = false;
    public boolean mLogDataRemote = false;
    public boolean mLogDataRemoteMobile = false;
    public boolean mLogNDA = false;

    private String mAuthToken = null;
    private long mEventsTimerPeriod = 600; // Number of seconds between checks to see if there are unvalidated remote events.
    private long mEventDuration = 120;   // event duration in seconds - uploads datapoints that cover this time range centred on the event time.
    public long mDataRetentionPeriod = 1; // Prunes the local db so it only retains data younger than this duration (in days)
    private long mRemoteLogPeriod = 6; // Period in seconds between uploads to the remote server.
    private long mAutoPrunePeriod = 3600;  // Prune the database every hour
    private boolean mAutoPruneDb;

    private String mOSDUrl = "";

    private OsdUtil mUtil;
    private Handler mHandler;
    private ToneGenerator mToneGenerator;
    private android.media.MediaPlayer mMediaPlayer = null; // used for MP3 alarm sounds

    private NetworkBroadcastReceiver mNetworkBroadcastReceiver;

    private final IBinder mBinder = new SdBinder();

    public LogManager mLm;

    /**
     * class to handle binding the MainApp activity to this service
     * so it can access mSdData.
     */
    public class SdBinder extends Binder {
        public SdServer getService() {
            return SdServer.this;
        }
    }

    /**
     * Constructor for SdServer class - does not do much!
     */
    public SdServer() {
        super();
        Log.i(TAG, "SdServer Created");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "sdServer.onBind()");
        return mBinder;
    }

    /**
     * used to make sure timers run on UI thread
     */
    private void runOnUiThread(Runnable runnable) {
        mHandler.post(runnable);
    }

    /**
     * onCreate() - called when services is created.  Starts message
     * handler process to listen for messages from other processes.
     */
    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        mHandler = new Handler(Looper.getMainLooper());
        mSdData = new SdData();
        mSdDataHistory = new uk.org.openseizuredetector.data.SdDataHistory();  // Initialize history buffers
        mToneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);

        mUtil = new OsdUtil(getApplicationContext(), mHandler);
        Log.i(TAG, "SdServer.onCreate()");

        // Create a wake lock, but don't use it until the service is started.
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "OSD:WakeLock");

        // Initialise Notification channels for SDK 26+.
        // On Android 8+, sound is controlled by the channel, NOT by setSound() on the notification.
        // We create a separate channel per alarm level so each can have its own sound.
        // The OK channel has no sound (IMPORTANCE_DEFAULT); alert channels use IMPORTANCE_HIGH.
        mNM = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannels();
        // Builder will be re-created per-notification with the correct channel ID.
        mNotificationBuilder = new NotificationCompat.Builder(this, NOTCH_OK);

        // Display a notification icon in the status bar of the phone to
        // show the service is running.
        Log.v(TAG, "showing Notification and calling startForeground");
        Log.i(TAG, "SdServer.onCreate() - showing Notification and calling startForeground");
        showNotification(0);

        // Check for required permissions before starting foreground service (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String dataSourceName = prefs.getString("DataSource", "SET_FROM_XML");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                int foregroundServiceType = resolveForegroundServiceTypeForDataSource(dataSourceName);
                boolean requiresHealthPermissions = foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
                boolean hasActivityRecognition = checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
                boolean hasBodySensors = checkSelfPermission(android.Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
                boolean hasBluetoothConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
                boolean hasFineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean hasCoarseLocation = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                Log.i(TAG, "FGS resolve: sdk=" + Build.VERSION.SDK_INT
                        + ", datasource=" + dataSourceName
                        + ", type=" + foregroundServiceTypeToLabel(foregroundServiceType)
                        + "(" + foregroundServiceType + ")"
                        + ", activityRec=" + hasActivityRecognition
                        + ", bodySensors=" + hasBodySensors
                        + ", btConnect=" + hasBluetoothConnect
                        + ", fineLocation=" + hasFineLocation
                        + ", coarseLocation=" + hasCoarseLocation);

                if (requiresHealthPermissions && !(hasActivityRecognition || hasBodySensors)) {
                    Log.e(TAG, "FATAL: Health foreground service permissions not granted for Phone Datasource!");
                    throw new SecurityException("Health foreground service permissions not granted - app should have exited in StartupActivity");
                }

                Log.i(TAG, "Starting foreground service for Datasource (" + dataSourceName + "), type="
                        + foregroundServiceTypeToLabel(foregroundServiceType) + "(" + foregroundServiceType + ")");
                try {
                    startForeground(NOTIFICATION_ID, mNotification, foregroundServiceType);
                } catch (IllegalArgumentException e) {
                    // Defensive fallback for vendor-specific behavior.
                    Log.e(TAG, "Invalid foreground service type " + foregroundServiceType + ". Falling back to manifest default.", e);
                    startForeground(NOTIFICATION_ID, mNotification);
                } catch (android.app.ForegroundServiceStartNotAllowedException e) {
                    // Android 15+: time limit for the requested foreground service type has been
                    // exhausted (e.g. dataSync is capped at 6 h/day). Try the next best type
                    // (connectedDevice has no such cap) before giving up.
                    Log.e(TAG, "ForegroundServiceStartNotAllowedException for type "
                            + foregroundServiceTypeToLabel(foregroundServiceType)
                            + " – retrying with connectedDevice type.", e);
                    try {
                        startForeground(NOTIFICATION_ID, mNotification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                    } catch (Exception e2) {
                        Log.e(TAG, "startForeground fallback also failed – notifying user and stopping service.", e2);
                        showFgsStartupFailureNotification();
                        stopSelf();
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID, mNotification);
            }
        } else {
            // Android 8-11: just start foreground
            startForeground(NOTIFICATION_ID, mNotification);
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private int resolveForegroundServiceTypeForDataSource(String dataSourceName) {
        if ("Phone".equals(dataSourceName)) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
        }
        if ("BLE".equals(dataSourceName)
                || "BLE2".equals(dataSourceName)
                || "Pebble".equals(dataSourceName)
                || "AndroidWear".equals(dataSourceName)) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }
        if ("Network".equals(dataSourceName) || "Garmin".equals(dataSourceName)) {
            // connectedDevice has no time limit (unlike dataSync which is capped at 6h/day on
            // Android 15+) and is semantically correct – these are connections to remote devices.
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }

        // Unknown source: default to connectedDevice – it has no time limit and avoids the
        // Android 15 dataSync 6-hour exhaustion that previously caused a crash here.
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
    }

    /**
     * Shows a Toast AND a persistent status-bar notification when the foreground service
     * fails to start (e.g. Android 15 dataSync time-limit exhausted and all fallbacks failed).
     * Both channels are used so the user sees feedback whether or not they are actively
     * looking at the screen.
     */
    private void showFgsStartupFailureNotification() {
        // Immediate on-screen feedback for users who are actively looking at their phone.
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this,
                        getString(R.string.fgs_start_failed_toast),
                        Toast.LENGTH_LONG).show()
        );

        // Persistent status-bar notification so the user is informed even if they miss the Toast.
        // Use a dedicated HIGH-importance channel so it appears as a heads-up banner.
        final String errorChId = "OSD_Error_Channel";
        NotificationChannel errorChannel = new NotificationChannel(
                errorChId,
                getString(R.string.fgs_error_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        mNM.createNotificationChannel(errorChannel);

        Notification errorNotification = new NotificationCompat.Builder(this, errorChId)
                .setSmallIcon(R.drawable.logo_new_simplified_48x48)
                .setContentTitle(getString(R.string.fgs_start_failed_notification_title))
                .setContentText(getString(R.string.fgs_start_failed_notification_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.fgs_start_failed_notification_text)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        mNM.notify(NOTIFICATION_ID + 1, errorNotification);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private String foregroundServiceTypeToLabel(int foregroundServiceType) {
        if (foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) {
            return "health";
        }
        if (foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
            return "connectedDevice";
        }
        if (foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) {
            return "location";
        }
        if (foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
            return "dataSync";
        }
        return "unknown";
    }

    /**
     * onStartCommand - start the web server and the message loop for
     * communications with other processes.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand() - SdServer service starting");
        Log.i(TAG, "SdServer.onStartCommand()");

        // Track service start time for watchdog
        mServiceStartMillis = System.currentTimeMillis();
        mHasReceivedData = false;

        // ---- Shutdown-reason diagnostics ----
        SharedPreferences diagPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String prevReason   = diagPrefs.getString(PREF_SHUTDOWN_REASON, "unknown");
        String prevShutTime = diagPrefs.getString(PREF_SHUTDOWN_TIME,   "unknown");
        int    prevPid      = diagPrefs.getInt(PREF_LAST_PID, -1);
        int    thisPid      = android.os.Process.myPid();
        Log.i(TAG, "onStartCommand(): PID=" + thisPid + " (prev PID=" + prevPid + ")"
                + ", prevShutdownReason=" + prevReason
                + ", prevShutdownTime=" + prevShutTime);
        if (SHUTDOWN_REASON_RUNNING.equals(prevReason)) {
            Log.w(TAG, "onStartCommand(): PREVIOUS SESSION DID NOT SHUT DOWN CLEANLY"
                    + " (last running at " + prevShutTime + ", PID=" + prevPid + ")"
                    + " - likely a silent crash or OS kill");
        } else if (SHUTDOWN_REASON_PLANNED.equals(prevReason)) {
            Log.i(TAG, "onStartCommand(): previous session shut down cleanly (user/planned stop)");
        }
        // Mark ourselves as running so a crash leaves "running" in prefs
        diagPrefs.edit()
                .putString(PREF_SHUTDOWN_REASON, SHUTDOWN_REASON_RUNNING)
                .putString(PREF_SHUTDOWN_TIME,   new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(new java.util.Date()))
                .putInt(PREF_LAST_PID, thisPid)
                .apply();
        // ---- End shutdown-reason diagnostics ----

        // Update preferences.
        Log.v(TAG, "onStartCommand() - calling updatePrefs()");
        updatePrefs();


        // CRITICAL: Stop any existing data source to prevent duplicate instances
        // This can happen if onStartCommand() is called multiple times due to service restarts
        if (mSdDataSource != null) {
            try {
                Log.i(TAG, "onStartCommand() - Stopping existing SdDataSource instance to prevent duplicates");
                Log.i(TAG, "onStartCommand() - Stopping existing SdDataSource instance");
                mSdDataSource.stop();
                mSdDataSource = null;
            } catch (Exception e) {
                Log.e(TAG, "Error stopping existing SdDataSource: " + e.getMessage());
                Log.i(TAG, "ERROR: Failed to stop SdDataSource: " + e.getMessage());
            }
        }

        Log.i(TAG, "onStartCommand: Datasource =" + mSdDataSourceName + ", phoneAppVersion=" + mUtil.getAppVersionName());
        mSdData.dataSourceName = mSdDataSourceName;
        mSdData.phoneAppVersion = mUtil.getAppVersionName();
        switch (mSdDataSourceName) {
            case "Pebble":
                Log.i(TAG, "Selecting Pebble DataSource");
                mSdDataSource = new SdDataSourcePebble(this.getApplicationContext(), mHandler, this);
                break;
            case "AndroidWear":
                Log.i(TAG, "Selecting Android Wear DataSource");
                mSdDataSource = new SdDataSourceAw(this.getApplicationContext(), mHandler, this);
                break;
            case "Network":
                Log.i(TAG, "Selecting Network DataSource");
                mSdDataSource = new SdDataSourceNetwork(this.getApplicationContext(), mHandler, this);
                Log.i(TAG, "Disabling remote logging when using network data source");
                mLogDataRemote = false;
                break;
            case "Garmin":
                Log.i(TAG, "Selecting Garmin DataSource");
                mSdDataSource = new SdDataSourceGarmin(this.getApplicationContext(), mHandler, this);
                break;
            case "BLE":
                Log.i(TAG, "Selecting BLE DataSource");
                mSdDataSource = new SdDataSourceBLE(this.getApplicationContext(), mHandler, this);
                break;
            case "BLE2":
                Log.i(TAG, "Selecting BLE2 DataSource");
                mSdDataSource = new SdDataSourceBLE2(this.getApplicationContext(), mHandler, this);
                break;
            case "Phone":
                Log.i(TAG, "Selecting Phone Sensor DataSource");
                mSdDataSource = new SdDataSourcePhone(this.getApplicationContext(), mHandler, this);
                break;
            default:
                Log.e(TAG, "Datasource " + mSdDataSourceName + " not recognised - Defaulting to Phone");
                mUtil.showToast(getString(R.string.DatasourceTitle) + " " + mSdDataSourceName + getString(R.string.DefaultingToPhoneMsg));
                mSdDataSource = new SdDataSourcePhone(this.getApplicationContext(), mHandler, this);
        }


        // Ensure SeizureDetector is fresh by closing any pre-existing instance
        if (mSeizureDetector != null) {
            Log.i(TAG, "onStartCommand() - Closing existing SeizureDetector");
            mSeizureDetector.close();
            mSeizureDetector = null; // Clear it so we know to make a new one
        }
        // Create the seizure detector instance
        // Initialize SeizureDetector for coordinating all seizure detection algorithms
        mSeizureDetector = new SeizureDetector(getApplicationContext());
        Log.i(TAG, "onStartCommand() - SeizureDetector initialized");
        Log.i(TAG, "SdServer.onStartCommand() - SeizureDetector initialized");



        // Create our log manager.
        // If there's an old instance (e.g. settings-triggered restart), stop it asynchronously
        // so the main thread is not blocked by the upload-wait inside LogUploader.requestShutdown().
        if (mLm != null) {
            Log.i(TAG, "onStartCommand() - Stopping existing LogManager instance asynchronously before re-creating");
            final LogManager oldLm = mLm;
            mLm = null;
            new Thread(() -> {
                try {
                    oldLm.stop();
                    LogManager.close();
                    Log.d(TAG, "onStartCommand(): old LogManager stopped on background thread");
                } catch (Exception e) {
                    Log.e(TAG, "onStartCommand(): error stopping old LogManager: " + e.getMessage());
                }
            }, "LogManager-old-shutdown").start();
        }
        mLm = new LogManager(this, mLogDataRemote, mLogDataRemoteMobile, mAuthToken, mEventDuration,
                mRemoteLogPeriod, mLogNDA, mAutoPruneDb, mDataRetentionPeriod, mSdData);

        // Load persisted CircBuf history from database to restore graph history on startup
        // This is done asynchronously on a background thread to avoid UI blocking
        if (mLogData && mSdDataHistory != null) {
            Log.d(TAG, "onStartCommand() - mLogData=true, loading CircBuf history from database into SdDataHistory");
            CircBufHistoryLoader.loadHistoryFromDatabase(
                mSdDataHistory,
                new CircBufHistoryLoader.HistoryLoadCallback() {
                    @Override
                    public void onHistoryLoaded(boolean success) {
                        Log.d(TAG, "onHistoryLoaded() callback invoked with success=" + success);
                        if (success) {
                            Log.i(TAG, "CircBuf history loaded successfully into SdDataHistory");
                            Log.i(TAG, "SdServer - CircBuf history restored from database");
                        } else {
                            Log.i(TAG, "No history data found in database (first run or empty DB)");
                        }
                    }
                }
            );
        } else {
            if (!mLogData) {
                Log.d(TAG, "onStartCommand() - Data logging is disabled (mLogData=false), skipping history load");
            } else {
                Log.e(TAG, "onStartCommand() - SdDataHistory is null, cannot load history");
            }
        }

        if (mSMSAlarm) {
            Log.v(TAG, "Creating LocationFinder");
            mLocationFinder = new LocationFinder(getApplicationContext());
        }
        Log.i(TAG, "SdServer.onStartCommand() - starting SdDataSource");
        mSdDataSource.start();


        // Record last time we sent an SMS so we can limit rate of SMS
        // sending to one per minute.   We set it to one minute ago (60000 milliseconds)
        mSMSTimeMillis = System.currentTimeMillis() - 60000;


        // Start timer to log data regularly..
        /*
        if (dataLogTimer == null) {
            Log.v(TAG, "onStartCommand(): starting dataLog timer");
            Log.i(TAG, "SdServer.onStartCommand() - starting dataLog timer");
            dataLogTimer = new Timer();
            dataLogTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Log.v(TAG,"dataLogTimer.run()");
                    logData();
                }
            }, 0, 1000 * 60);

        } else {
            Log.v(TAG, "onStartCommand(): dataLog timer already running.");
            Log.i(TAG, "SdServer.onStartCommand() - dataLog timer already running???");
        }
        */


        if (mLogDataRemote) {
            startEventsTimer();
        }


        // Initialize ML Model Manager and start model update check timer
        if (mMlModelManager == null) {
            mMlModelManager = new MlModelManager(this);
        }
        if (mModelUpdateCheckPeriod > 0) {
            startModelUpdateTimer();
        }

        // Start the web server
        Log.i(TAG, "SdServer.onStartCommand() - starting web server");
        startWebServer();

        // Apply the wake-lock to prevent CPU sleeping (very battery intensive!)
        if (mWakeLock != null) {
            mWakeLock.acquire();
            Log.v(TAG, "Applied Wake Lock to prevent device sleeping");
            Log.i(TAG, "SdServer.onStartCommand() - applying wake lock");
        } else {
            Log.d(TAG, "mmm...mWakeLock is null, so not aquiring lock.  This shouldn't happen!");
            Log.i(TAG, "SdServer.onStartCommand() - mWakeLock is not null - this shouldn't happen???");
        }

        checkEvents();

        // Start watchdog thread to detect silent failures
        if (mWatchdogThread == null || !mWatchdogThread.isAlive()) {
            Log.i(TAG, "onStartCommand(): Starting watchdog thread");
            Log.i(TAG, "SdServer.onStartCommand() - starting watchdog thread");
            mWatchdogThread = new WatchdogThread();
            mWatchdogThread.start();
            mLastWatchdogHeartbeatMillis = System.currentTimeMillis();
        }

        // Return START_STICKY so the OS will restart the service if it is killed unexpectedly.
        // A planned stop (user pressing "Exit") must call setPlannedShutdown() BEFORE stopping
        // the service, which writes SHUTDOWN_REASON_PLANNED to SharedPreferences.  When the
        // service then restarts via START_STICKY the onStartCommand() diagnostics above will
        // see the "planned" reason and will NOT treat it as a crash.  The calling code (e.g.
        // OsdUtil.stopServer) should use the static helper stopServicePlanned() so the flag
        // is written atomically before stopService() is called.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "SdServer.onDestroy()");
        mUtil.writeMemoryLog("SdServer.onDestroy");

        Log.i(TAG, "onDestroy(): SdServer Service stopping");
        Log.i(TAG, "SdServer.onDestroy() - releasing wakelock");

        // Mark this as a planned (clean) shutdown so that if START_STICKY restarts the service,
        // onStartCommand() will not treat it as a crash.
        try {
            SharedPreferences diagPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            Log.i(TAG, "onDestroy(): recording planned shutdown in SharedPreferences");
            diagPrefs.edit()
                    .putString(PREF_SHUTDOWN_REASON, SHUTDOWN_REASON_PLANNED)
                    .putString(PREF_SHUTDOWN_TIME,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                    java.util.Locale.getDefault()).format(new java.util.Date()))
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "onDestroy(): could not write shutdown reason: " + e.getMessage());
        }

        // Set flag to prevent notifications and other actions during shutdown
        mIsDestroying = true;

        // release the wake lock to allow CPU to sleep and reduce
        // battery drain.
        if (mWakeLock != null) {
            try {
                mWakeLock.release();
                Log.d(TAG, "Released Wake Lock to allow device to sleep.");
            } catch (Exception e) {
                Log.e(TAG, "Error Releasing Wakelock - " + e.toString());
                Log.i(TAG, "SdServer.onDestroy() - Error releasing wakelock.");
                mUtil.showToast(getString(R.string.ErrorReleasingWakelockMsg));
            }
        } else {
            Log.d(TAG, "mmm...mWakeLock is null, so not releasing lock.  This shouldn't happen!");
            Log.i(TAG, "SdServer.onDestroy() - mWakeLock is null so not releasing lock - this Shouldn't happen???");
        }

        if (mSdDataSource != null) {
            Log.d(TAG, "stopping mSdDataSource");
            Log.i(TAG, "SdServer.onDestroy() - stopping mSdDataSource");

            // Stop datasource in a separate thread with timeout to prevent blocking
            final Thread stopThread = new Thread(() -> {
                try {
                    mSdDataSource.stop();
                    Log.d(TAG, "onDestroy(): datasource stopped successfully");
                } catch (Exception e) {
                    Log.e(TAG, "onDestroy(): Error stopping datasource: " + e.getMessage());
                }
            });

            stopThread.start();

            // Wait for datasource to stop, but not indefinitely
            try {
                stopThread.join(6000); // Wait max 6 seconds for stop to complete
                if (stopThread.isAlive()) {
                    Log.w(TAG, "onDestroy(): datasource stop timed out, continuing with shutdown");
                    Log.i(TAG, "SdServer.onDestroy() - datasource stop timed out");
                    stopThread.interrupt();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "onDestroy(): interrupted while waiting for datasource to stop");
            }
        } else {
            Log.e(TAG, "ERROR - mSdDataSource is null - why????");
            Log.i(TAG, "SdServer.onDestroy() - mSdDataSource is null - why???");
        }

        // Close the SeizureDetector
        if (mSeizureDetector != null) {
            Log.d(TAG, "onDestroy(): closing SeizureDetector");
            mSeizureDetector.close();
            mSeizureDetector = null;
        }

        // Stop the Cancel Audible timer
        if (mCancelAudibleTimer != null) {
            Log.d(TAG, "onDestroy(): cancelling Cancel_Audible timer");
            mCancelAudibleTimer.cancel();
            //mCancelAudibleTimer.purge();
            mCancelAudibleTimer = null;
        }

        // Stop the watchdog thread
        if (mWatchdogThread != null) {
            Log.d(TAG, "onDestroy(): stopping watchdog thread");
            Log.i(TAG, "SdServer.onDestroy() - stopping watchdog thread");
            mWatchdogThread.shutdown();
            try {
                mWatchdogThread.join(2000); // Wait max 2 seconds
                if (mWatchdogThread.isAlive()) {
                    Log.w(TAG, "onDestroy(): watchdog did not stop, forcing interrupt");
                    mWatchdogThread.interrupt();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "onDestroy(): interrupted while waiting for watchdog");
            }
            mWatchdogThread = null;
        }

        // Stop the Fault timer
        if (mFaultTimer != null) {
            Log.d(TAG, "onDestroy(): cancelling fault timer");
            mFaultTimer.cancel();
            mFaultTimer = null;
        }

        // Stop the Event timer
        if (mEventsTimer != null) {
            Log.d(TAG, "onDestroy(): Cancelling events timer");
            stopEventsTimer();
        }

        // Stop the Cancel Alarm Latch timer
        Log.d(TAG, "onDestroy(): stopping alarm latch timer");
        stopLatchTimer();


        // Stop the location finder.
        if (mLocationFinder != null) {
            Log.d(TAG, "onDestroy(): stopping Location Finder");
            mLocationFinder.destroy();
            mLocationFinder = null;
        }


        try {
            // Stop web server
            Log.d(TAG, "onDestroy(): stopping web server");
            Log.i(TAG, "SdServer.onDestroy() - stopping Web Server");
            stopWebServer();

            stopMp3();  // release MediaPlayer if active (must be before ToneGenerator release)
            Log.i(TAG, "SdServer.onDestroy() - releasing mToneGenerator");
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }

            this.stopForeground(STOP_FOREGROUND_REMOVE);
            // Cancel the notification.
            Log.d(TAG, "onDestroy(): cancelling notification");
            Log.i(TAG, "SdServer.onDestroy - cancelling notification");
            mNM.cancel(NOTIFICATION_ID);


            // stop this service.
            Log.d(TAG, "onDestroy(): calling stopSelf()");
            Log.i(TAG, "SdServer.onDestroy() - stopping self");
            stopSelf();

        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy() - " + e.toString());
            Log.i(TAG, "SdServer.onDestroy() -error " + e.toString());
        }

        // Stop LogManager on a background thread — its requestShutdown() can block for up to
        // 5 seconds waiting for an in-flight upload, which freezes the main thread and triggers
        // "Skipped N frames" and (worse) lets Android classify the app as background before the
        // service restart, causing startForegroundService() to be denied.
        if (mLm != null) {
            final LogManager lmToStop = mLm;
            mLm = null;  // clear the reference immediately so nothing else uses it
            Log.d(TAG, "Closing Down Log Manager (async)");
            new Thread(() -> {
                try {
                    lmToStop.stop();
                    LogManager.close();
                    Log.d(TAG, "onDestroy(): LogManager stopped on background thread");
                } catch (Exception e) {
                    Log.e(TAG, "onDestroy(): error stopping LogManager: " + e.getMessage());
                }
            }, "LogManager-shutdown").start();
        }

        super.onDestroy();

    }

    /**
     * Called when the user removes the task from the recent-apps list (swipes it away).
     * We treat this as a planned shutdown so START_STICKY does not restart unnecessarily.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved(): user removed task - marking as planned shutdown");
        setPlannedShutdown(getApplicationContext());
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Call this BEFORE stopping the service via a user-initiated "Exit" so that
     * the START_STICKY restart (if it happens) is not treated as a crash.
     * This is a static helper so it can be called from OsdUtil / activities without
     * a reference to the running SdServer instance.
     */
    public static void setPlannedShutdown(Context context) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    context.getApplicationContext());
            Log.i(TAG, "setPlannedShutdown(): writing planned shutdown flag");
            prefs.edit()
                    .putString(PREF_SHUTDOWN_REASON, SHUTDOWN_REASON_PLANNED)
                    .putString(PREF_SHUTDOWN_TIME,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                    java.util.Locale.getDefault()).format(new java.util.Date()))
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "setPlannedShutdown(): error - " + e.getMessage());
        }
    }

    /**
     * Create the single silent notification channel used for the foreground service status
     * display.  Sound is played independently via MediaPlayer — not through channels.
     */
    private void createNotificationChannels() {
        NotificationChannel okChannel = new NotificationChannel(
                NOTCH_OK, "OSD Status", NotificationManager.IMPORTANCE_DEFAULT);
        okChannel.setDescription("OpenSeizureDetector status notification");
        okChannel.setSound(null, null);
        okChannel.enableVibration(false);
        mNM.createNotificationChannel(okChannel);
        Log.i(TAG, "createNotificationChannels() - created silent status channel " + NOTCH_OK);
    }

    /**
     * Stop and release any currently-playing MediaPlayer instance.
     */
    private void stopMp3() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                mMediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "stopMp3() - error releasing MediaPlayer: " + e.getMessage());
            }
            mMediaPlayer = null;
        }
    }

    /**
     * Play an MP3 sound: uses the user-selected content URI if non-empty, otherwise falls back
     * to the bundled res/raw/ resource identified by rawResName.
     * Audio attributes are set to USAGE_ALARM so the phone's alarm volume is used and the
     * sound plays even in DND/silent modes (subject to user's DND alarm exception settings).
     */
    private void playMp3(String userUriStr, String rawResName) {
        stopMp3();  // stop any previously playing sound first
        try {
            Uri soundUri;
            if (userUriStr != null && !userUriStr.isEmpty()) {
                soundUri = Uri.parse(userUriStr);
                Log.i(TAG, "playMp3() - using user URI: " + soundUri);
            } else {
                soundUri = Uri.parse("android.resource://" + getPackageName() + "/raw/" + rawResName);
                Log.i(TAG, "playMp3() - using bundled sound: " + soundUri);
            }
            mMediaPlayer = new android.media.MediaPlayer();
            mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mMediaPlayer.setDataSource(getApplicationContext(), soundUri);
            mMediaPlayer.setOnPreparedListener(android.media.MediaPlayer::start);
            mMediaPlayer.setOnCompletionListener(mp -> stopMp3());
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "playMp3() - MediaPlayer error what=" + what + " extra=" + extra);
                stopMp3();
                return true;
            });
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "playMp3() - failed to start MediaPlayer: " + e.getMessage());
            stopMp3();
        }
    }

    /**
     * Show a notification while this service is running.
     */
    private void showNotification(int alarmLevel) {
        // Don't show notifications if service is being destroyed
        if (mIsDestroying) {
            Log.v(TAG, "showNotification - service is destroying, not showing notification");
            return;
        }

        Log.v(TAG, "showNotification() - alarmLevel=" + alarmLevel);

        // Choose icon, title, and the correct channel ID for this alarm level.
        // On Android 8+, sound is controlled entirely by the channel — setSound() on the
        // notification builder is ignored. Each channel was pre-configured with its sound in
        // createNotificationChannels(). We simply post to the right channel.
        // When mMp3Alarm is false, or mCancelAudible is set, we post to the silent OK channel
        // so that no sound plays even if the level is WARNING/ALARM/FAULT.
        int iconId;
        String titleStr;
        String channelId;

        switch (alarmLevel) {
            case 1:
                iconId = R.drawable.star_of_life_yellow_24x24;
                titleStr = getString(R.string.Warning);
                channelId = NOTCH_OK;
                break;
            case 2:
                iconId = R.drawable.star_of_life_red_24x24;
                titleStr = getString(R.string.Alarm);
                channelId = NOTCH_OK;
                break;
            case -1:
                iconId = R.drawable.star_of_life_fault_24x24;
                titleStr = getString(R.string.Fault);
                channelId = NOTCH_OK;
                break;
            default: // case 0 and anything else
                iconId = R.drawable.star_of_life_24x24;
                titleStr = getString(R.string.okBtnTxt);
                channelId = NOTCH_OK;
                break;
        }

        Log.i(TAG, "showNotification - alarmLevel=" + alarmLevel
                + " mMp3Alarm=" + mMp3Alarm
                + " mCancelAudible=" + mCancelAudible);

        Intent i = new Intent(getApplicationContext(), MainActivity2.class);
        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent =
                PendingIntent.getActivity(this,
                        0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        StringBuilder contentSb = new StringBuilder();
        if (mPhoneAlarm) {
            contentSb.append("Phone Call Alarm Active");
        } else if (mSMSAlarm) {
            contentSb.append(getString(R.string.sms_location_alarm_active));
        }
        if (mDataShareMsg != null && !mDataShareMsg.isEmpty()) {
            if (contentSb.length() > 0) contentSb.append("\n");
            contentSb.append(mDataShareMsg);
        }
        String contentStr = contentSb.toString();
        if (contentStr.isEmpty()) contentStr = null;

        // Determine whether anything actually changed. If not, skip notify() entirely —
        // without setOnlyAlertOnce(), calling notify() on the same channel would re-trigger sound.
        boolean alarmLevelChanged = (alarmLevel != mCurrentNotificationAlarmLevel);
        boolean contentChanged = !java.util.Objects.equals(contentStr, mCurrentNotificationContent);
        if (!alarmLevelChanged && !contentChanged) {
            Log.v(TAG, "showNotification - nothing changed, skipping notify()");
            return;
        }

        // When the alarm level changes, cancel the existing notification first then re-post to
        // the new channel. Android does not allow moving a notification between channels by
        // updating it — it must be cancelled and re-posted.
        if (alarmLevelChanged) {
            Log.i(TAG, "showNotification - alarm level changed from "
                    + mCurrentNotificationAlarmLevel + " to " + alarmLevel + ", cancelling old notification");
            mNM.cancel(NOTIFICATION_ID);
        }

        // Build with the channel for this alarm level. A new Builder is created each time because
        // the channel ID is baked in at construction and cannot be changed afterwards.
        // We do NOT use setOnlyAlertOnce() here — sound suppression is handled by only posting
        // to a sound-enabled channel when the alarm level actually changes (see channelId selection
        // above and the early-return guard above). setOnlyAlertOnce(true) sets the ONLY_ALERT_ONCE
        // flag which causes Android/Samsung to suppress sound even on freshly-posted notifications.
        mNotification = new NotificationCompat.Builder(this, channelId)
                .setContentIntent(contentIntent)
                .setSmallIcon(iconId)
                .setColor(0x00ffffff)
                .setAutoCancel(false)
                .setContentTitle(titleStr)
                .setContentText(contentStr)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentStr))
                .build();
        mNM.notify(NOTIFICATION_ID, mNotification);
        mCurrentNotificationAlarmLevel = alarmLevel;
        mCurrentNotificationContent = contentStr;
    }

    // Show the main activity on the user's screen.
    private void showMainActivity() {
        Log.i(TAG, "showMainActivity()");
        Log.i(TAG, "SdServer.showMainActivity()");

        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        // getRunningTasks is deprecated but still works for detecting our own app's state
        @SuppressWarnings("deprecation")
        List<ActivityManager.RunningTaskInfo> runningTaskInfo = manager.getRunningTasks(1);

        if (runningTaskInfo != null && runningTaskInfo.size() > 0) {
            ComponentName componentInfo = runningTaskInfo.get(0).topActivity;

            if (componentInfo.getPackageName().equals("uk.org.openseizuredetector")) {
                Log.i(TAG, "showMainActivity(): OpenSeizureDetector Activity is already shown on top - not doing anything");
                Log.i(TAG, "SdServer.showMainActivity - Activity is already shown on top, not doing anything");
            } else {
                Log.i(TAG, "showMainActivity(): Showing Main Activity");
                Intent i;
                i = new Intent(getApplicationContext(), MainActivity2.class);
                i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(i);
            }
        } else {
            mUtil.showToast("OpenSeizureDetector: showMainActvity Failed to Display Activity");
            Log.e(TAG, "OpenSeizureDetector: showMainActvity Failed to Display Activity");
        }
    }

    public void raiseManualAlarm() {
        Log.i(TAG, "raiseManualAlarm()");
        SdData sdData = mSdData;
        sdData.alarmState = AlarmState.MANUAL;
        onSdDataReceived(sdData);
    }

    /**
     * Process the data received from the SdData source.  On exit, the mSdData structure is populated with
     * the appropriate data.
     *
     * @param sdData
     */
    public void onSdDataReceived(SdData sdData) {
        // Track data receipt for watchdog using the data timestamp (not wall-clock now)
        if (sdData != null && sdData.dataTimeMillis > 0) {
            mLastDataReceivedMillis = sdData.dataTimeMillis;
            mHasReceivedData = true;
        }

        Log.v(TAG, "onSdDataReceived() - " + sdData.toString());
        Log.v(TAG, "onSdDataReceived(), sdData.fallAlarmStanding=" + sdData.fallAlarmStanding);

        // Enhanced logging for diagnostics
        Log.i(TAG, "DATA_RX: alarmState=" + sdData.alarmState +
                ", HR=" + sdData.mHR +
                ", thread=" + Thread.currentThread().getName());

        // Process data through SeizureDetector to run all seizure detection algorithm.
        if (mSeizureDetector != null) {
            if (mSdDataSourceName.equals("Network")) {
                Log.i(TAG, "onSdDataReceived() called with mSdDataSourceName == Network - not processing data, just going straight to alarm");
            } else {
                if (sdData.alarmState == AlarmState.MANUAL) {
                    Log.i(TAG, "onSdDataReceived() called with AlarmState == MANUAL - not processing data, just going straight to alarm");
                } else {
                    int alarmState = mSeizureDetector.processData(sdData);
                    sdData.alarmState = alarmState;
                    Log.v(TAG, "onSdDataReceived() - SeizureDetector returned alarmState=" + alarmState);
                    Log.i(TAG, "SeizureDetector: alarmState=" + alarmState);
                }
            }
        } else{
            if (mIsDestroying) {
                Log.e(TAG, "onSdDataReceived() - received data after shutting down seizuredetector - ignoring");
            } else {
                Log.e(TAG, "onSdDataReceived() - mSeizureDetector is null!");
                Log.i(TAG, "ERROR: SeizureDetector is null in onSdDataReceived()");
                mUtil.showToast("ERROR- Seizure Detector Not Initialised - this should not happen!");
            }
        }

        // Apply mute check (from either watch button or phone UI button)
        muteCheck(sdData);

        // Check phone battery level — raise a fault if below threshold so the user is alerted
        // before the phone dies silently and monitoring stops.
        if (mPhoneBatteryAlarmActive) {
            int battLevel = getPhoneBatteryLevel();
            Log.v(TAG, "onSdDataReceived() - phone battery level=" + battLevel + "%");
            if (battLevel >= 0 && battLevel <= mPhoneBatteryAlarmThreshold) {
                Log.w(TAG, "PHONE_BATTERY_LOW: battLevel=" + battLevel
                        + "% <= threshold=" + mPhoneBatteryAlarmThreshold + "%");
                sdData.mPhoneBatteryFaultStanding = true;
            } else {
                sdData.mPhoneBatteryFaultStanding = false;
            }
        } else {
            sdData.mPhoneBatteryFaultStanding = false;
        }

        boolean hasFault = (sdData.alarmState == AlarmState.FAULT
                || sdData.alarmState == AlarmState.NETFAULT
                || sdData.mHRFaultStanding
                || sdData.mHrFrozenFaultStanding
                || sdData.mPhoneBatteryFaultStanding);
        if (hasFault) {
            // Force fault state to override any alarm/warning state handling.
            sdData.alarmState = AlarmState.FAULT;
            sdData.alarmPhrase = "FAULT";
            sdData.alarmStanding = false;
            sdData.fallAlarmStanding = false;

            String faultReason = "";
            if (sdData.alarmState == AlarmState.FAULT) faultReason += "Data Source Fault. ";
            if (sdData.alarmState == AlarmState.NETFAULT) faultReason += "Network Fault. ";
            if (sdData.mHRFaultStanding) faultReason += "HR Fault. ";
            if (sdData.mHrFrozenFaultStanding) faultReason += "HR Frozen. ";
            if (sdData.mPhoneBatteryFaultStanding) {
                faultReason += "Phone battery low (" + getPhoneBatteryLevel() + "%). ";
            }

            sdData.faultCause = faultReason.trim();
        }

        if (sdData.alarmState == AlarmState.OK) {
            if ((!mLatchAlarms) ||
                    (mLatchAlarms &&
                            (!mSdData.alarmStanding && !mSdData.fallAlarmStanding))) {
                sdData.alarmPhrase = "OK";
                sdData.alarmStanding = false;
                sdData.fallAlarmStanding = false;
                showNotification(0);
            }
        }
        // Handle manual mute from watch buttons.
        if (sdData.alarmState == AlarmState.MUTE) {
            sdData.alarmPhrase = "MUTE";
            sdData.alarmStanding = false;
            sdData.fallAlarmStanding = false;
            showNotification(0);
        }
        // Handle warning alarm state
        if (sdData.alarmState == AlarmState.WARNING) {
            if ((!mLatchAlarms) ||
                    (mLatchAlarms &&
                            (!mSdData.alarmStanding && !mSdData.fallAlarmStanding))) {
                sdData.alarmPhrase = "WARNING";
                sdData.alarmStanding = false;
                sdData.fallAlarmStanding = false;
            }
            if (mLogAlarms) {
                Log.v(TAG, "WARNING - Logging to SD Card");
                //writeAlarmToSD();
            } else {
                Log.v(TAG, "WARNING");
            }
            warningBeep();
            showNotification(1);
        }
        // respond to normal alarms and manual alarms
        if ((sdData.alarmState == AlarmState.ALARM) || (sdData.alarmState == AlarmState.MANUAL)) {
            sdData.alarmPhrase = "ALARM";
            sdData.alarmStanding = true;
            if (mLogAlarms) {
                Log.v(TAG, "***ALARM*** - Logging to SD Card");
                //writeAlarmToSD();
            } else {
                Log.v(TAG, "***ALARM***");
            }
            // Make alarm beep tone
            alarmBeep();
            showNotification(2);
            // Display MainActvity
            // Only show MainActivity if user preference is enabled (default true)
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean displayMain = prefs.getBoolean("DisplayMainActivityOnAlarm", true);
                if (displayMain) {
                    showMainActivity();
                } else {
                    Log.v(TAG, "DisplayMainActivityOnAlarm preference is false - not showing MainActivity");
                }
            } catch (Exception ex) {
                Log.w(TAG, "Problem checking DisplayMainActivityOnAlarm preference, defaulting to show: " + ex.toString());
                showMainActivity();
            }
            // Send SMS Alarm.
            if (mSMSAlarm) {
                long tnowMillis = System.currentTimeMillis();
                // limit SMS alarms to one per minute
                if ((tnowMillis - mSMSTimeMillis) > 60000) {
                    sendSMSAlarm();
                    sendPhoneAlarm();
                    mSMSTimeMillis = tnowMillis;
                } else {
                    //mUtil.showToast(getString(R.string.SMSAlarmAlreadySentMsg));
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                //mUtil.showToast(getString(R.string.SMSAlarmDisabledNotSendingMsg));
                Log.v(TAG, "mSMSAlarm is false - not sending");
            }
            Log.v(TAG, "calling startLatchTimer()");
            startLatchTimer();
        }
        // Handle fall alarm
        Log.v(TAG, "sdData.fallAlarmStanding=" + sdData.fallAlarmStanding);
        if ((sdData.alarmState == AlarmState.FALL) || (sdData.fallAlarmStanding)) {
            sdData.alarmPhrase = "FALL";
            sdData.fallAlarmStanding = true;
            if (mLogAlarms) {
                Log.v(TAG, "***FALL*** - Logging to SD Card");
                //writeAlarmToSD();
                showNotification(2);
            } else {
                Log.v(TAG, "***FALL***");
            }
            // Make alarm beep tone
            alarmBeep();
            // Display MainActvity
            // Only show MainActivity if user preference is enabled (default true)
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean displayMain = prefs.getBoolean("DisplayMainActivityOnAlarm", true);
                if (displayMain) {
                    showMainActivity();
                } else {
                    Log.v(TAG, "DisplayMainActivityOnAlarm preference is false - not showing MainActivity");
                }
            } catch (Exception ex) {
                Log.w(TAG, "Problem checking DisplayMainActivityOnAlarm preference, defaulting to show: " + ex.toString());
                showMainActivity();
            }
            // Send SMS Alarm.
            if (mSMSAlarm) {
                long tnowMillis = System.currentTimeMillis();
                // limit SMS alarms to one per minute
                if ((tnowMillis - mSMSTimeMillis) > 60000) {
                    sendSMSAlarm();
                    mSMSTimeMillis = tnowMillis;
                } else {
                    //mUtil.showToast(getString(R.string.SMSAlarmAlreadySentMsg));
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                //mUtil.showToast(getString(R.string.msmsalarm_false_msg));
                Log.v(TAG, "mSMSAlarm is false - not sending");
            }

        }
        // Handle heart rate alarm
        if ((sdData.mHRAlarmActive) && (sdData.mHRAlarmStanding)) {
            sdData.alarmPhrase = "HR ABNORMAL";
            // Make alarm beep tone
            alarmBeep();
            showNotification(2);
            // Display MainActvity
            // Only show MainActivity if user preference is enabled (default true)
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean displayMain = prefs.getBoolean("DisplayMainActivityOnAlarm", true);
                if (displayMain) {
                    showMainActivity();
                } else {
                    Log.v(TAG, "DisplayMainActivityOnAlarm preference is false - not showing MainActivity");
                }
            } catch (Exception ex) {
                Log.w(TAG, "Problem checking DisplayMainActivityOnAlarm preference, defaulting to show: " + ex.toString());
                showMainActivity();
            }
            // Send SMS Alarm.
            if (mSMSAlarm) {
                long tnowMillis = System.currentTimeMillis();
                // limit SMS alarms to one per minute
                if ((tnowMillis - mSMSTimeMillis) > 60000) {
                    sendSMSAlarm();
                    mSMSTimeMillis = tnowMillis;
                } else {
                    //mUtil.showToast(getString(R.string.SMSAlarmAlreadySentMsg));
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                //mUtil.showToast(getString(R.string.SMSAlarmDisabledNotSendingMsg));
                Log.v(TAG, "mSMSAlarm is false - not sending");
            }
        }

        // Handle Oxygen Saturation alarm
        if ((sdData.mO2SatAlarmActive) && (sdData.mO2SatAlarmStanding)) {
            sdData.alarmPhrase = "Oxygen Saturation ABNORMAL";
            if (mLogAlarms) {
                Log.v(TAG, "***OXYGEN SATURATION*** - Logging to SD Card");
                //writeAlarmToSD();
            } else {
                Log.v(TAG, "***OXYGEN SATURATION***");
            }
            // Make alarm beep tone
            alarmBeep();
            showNotification(2);
            // Display MainActvity
            // Only show MainActivity if user preference is enabled (default true)
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean displayMain = prefs.getBoolean("DisplayMainActivityOnAlarm", true);
                if (displayMain) {
                    showMainActivity();
                } else {
                    Log.v(TAG, "DisplayMainActivityOnAlarm preference is false - not showing MainActivity");
                }
            } catch (Exception ex) {
                Log.w(TAG, "Problem checking DisplayMainActivityOnAlarm preference, defaulting to show: " + ex.toString());
                showMainActivity();
            }
            // Send SMS Alarm.
            if (mSMSAlarm) {
                long tnowMillis = System.currentTimeMillis();
                // limit SMS alarms to one per minute
                if ((tnowMillis - mSMSTimeMillis) > 60000) {
                    sendSMSAlarm();
                    mSMSTimeMillis = tnowMillis;
                } else {
                    //mUtil.showToast(getString(R.string.sms_alarm_already_sent_msg));
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                //mUtil.showToast(getString(R.string.SMSAlarmDisabledNotSendingMsg));
                Log.v(TAG, "mSMSAlarm is false - not sending");
            }
        }

        // Fault
        if (hasFault) {
            sdData.alarmPhrase = "FAULT";
            sdData.alarmStanding = false;
            sdData.fallAlarmStanding = false;
            showNotification(1);
        } else {
            stopFaultTimer();
        }
        mSdData = sdData;
        mSdData.dataSourceName = mSdDataSourceName;
        mSdData.phoneAppVersion = mUtil.getAppVersionName();

        if (webServer != null) webServer.setSdData(mSdData);
        Log.v(TAG, "onSdDataReceived() - setting mSdData to " + mSdData.toString());

        // Add current data point to the persistent history (SdDataHistory persists across SdData replacements).
        // fallAlgState is set by SeizureDetector from the SdAlgFall return value, so comparing it to
        // AlarmState.ALARM is the canonical way to check whether a fall was detected this period
        // (no separate mFallDetected field is needed on SdData).
        if (mSdDataHistory != null) {
            mSdDataHistory.addDataPoint(sdData.batteryPc, sdData.phoneBatteryPc,
                    sdData.watchSignalStrength, sdData.mPseizure,
                    sdData.mAccelMagStdDev, sdData.mHR,
                    sdData.mlModelProbs,
                    sdData.mFallWindowMin, sdData.mFallWindowMax,
                    sdData.fallAlgState == AlarmState.ALARM);
        }

        if (mLm != null) mLm.updateSdData(mSdData);

        logData();

        if (mSdData.alarmState == AlarmState.MANUAL) {
            Log.i(TAG, "onSdDataReceived() called with AlarmState == MANUAL - resetting back to WARNING status");
            mSdData.alarmState = AlarmState.WARNING;
        }
    }

    // Called by SdDataSource when a fault condition is detected.
    public void onSdDataFault(SdData sdData) {
        if (mIsDestroying) {
            Log.i(TAG,"onSdDataFault called while shutting down system - ignoring");
            return;
        }
        Log.e(TAG, "*** onSdDataFault() CALLED - FAULT DETECTED ***");
        Log.i(TAG, "FAULT_DETECTED: onSdDataFault called, alarmState=" + sdData.alarmState +
                                ", thread=" + Thread.currentThread().getName());

        Log.v(TAG, "onSdDataFault()");
        mSdData = sdData;
        mSdData.alarmState = AlarmState.FAULT;  // set fault alarm state.

        // Ensure faultCause has something useful if not already set by datasource
        if (mSdData.faultCause == null || mSdData.faultCause.isEmpty()) {
             mSdData.faultCause = "Data Source Fault";
        }

        mSdData.alarmPhrase = "FAULT";
        mSdData.alarmStanding = false;
        mSdData.fallAlarmStanding = false;
        stopLatchTimer();
        if (mLm != null) {
            mLm.updateSdData(mSdData);
        }
        if (webServer != null) webServer.setSdData(mSdData);

        // We only take action to warn the user and re-start the data source to attempt to fix it
        // ourselves if we have been in a fault condition for a while - signified by the mFaultTimerCompleted
        // flag.
        if (mFaultTimerCompleted) {
            Log.e(TAG, "FAULT: Timer completed - emitting fault warning");
            Log.i(TAG, "FAULT: Timer completed - calling faultWarningBeep()");
            faultWarningBeep();
            // Re-start the data source to attempt recovery
            // Only restart if:
            // 1. Not already in progress
            // 2. Last restart was > 60 seconds ago (prevent rapid restart loops)
            long timeSinceLastRestart = System.currentTimeMillis() - mLastDatasourceRestartMillis;
            if (!mDataSourceRestartInProgress && timeSinceLastRestart > 60000) {
                Log.w(TAG, "FAULT: Attempting to restart data source to recover from fault");
                Log.i(TAG, "FAULT: Restarting datasource to recover");
                mDataSourceRestartInProgress = true;
                mLastDatasourceRestartMillis = System.currentTimeMillis();

                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            Log.w(TAG, "FAULT: Stopping data source for restart");
                            Log.i(TAG, "FAULT: Stopping datasource");
                            if (mSdDataSource != null) {
                                mSdDataSource.stop();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "FAULT: Error stopping datasource: " + e.getMessage());
                            Log.i(TAG, "FAULT: Error stopping datasource: " + e.getMessage());
                        }

                        mHandler.postDelayed(new Runnable() {
                            public void run() {
                                try {
                                    Log.w(TAG, "FAULT: Restarting data source");
                                    Log.i(TAG, "FAULT: Restarting datasource");
                                    if (mSdDataSource != null) {
                                        mSdDataSource.start();
                                        mLastDataReceivedMillis = System.currentTimeMillis(); // Reset watchdog timer
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "FAULT: Error restarting datasource: " + e.getMessage());
                                    Log.i(TAG, "FAULT: Error restarting datasource: " + e.getMessage());
                                } finally {
                                    mDataSourceRestartInProgress = false;
                                }
                            }
                        }, 3000); // Wait 3 seconds before restart
                    }
                }, 1000); // Wait 1 second before stopping
            } else {
                if (mDataSourceRestartInProgress) {
                    Log.w(TAG, "FAULT: Datasource restart already in progress, skipping");
                    Log.i(TAG, "FAULT: Datasource restart already in progress");
                } else {
                    Log.w(TAG, "FAULT: Last restart was " + (timeSinceLastRestart/1000) + "s ago, skipping to prevent restart loop");
                    Log.i(TAG, "FAULT: Skipping restart (too soon - " + (timeSinceLastRestart/1000) + "s)");
                }
            }
        } else {
            startFaultTimer();
            Log.v(TAG, "onSdDataFault() - starting Fault Timer");
            Log.i(TAG, "onSdDataFault() - starting Fault Timer");
        }

        showNotification(-1);
    }

    /* from http://stackoverflow.com/questions/12154940/how-to-make-a-beep-in-android */

    /**
     * beep for duration milliseconds, using tone generator
     */
    private void beep(int duration) {
        if (mToneGenerator != null) {
            mToneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, duration);
            Log.v(TAG, "beep()");
        } else {
            mUtil.showToast(getString(R.string.PleaseForceStopOSDorRebootMsg));
            Log.v(TAG, "beep() - Warming mToneGenerator is null - not beeping!!!");
            Log.i(TAG, "SdServer.beep() - mToneGenerator is null???");
        }
    }

    /**
     * Returns the current phone battery level as a percentage (0–100),
     * or -1 if it cannot be determined.
     * Uses the sticky ACTION_BATTERY_CHANGED broadcast — no persistent registration needed.
     */
    private int getPhoneBatteryLevel() {
        try {
            Intent batteryStatus = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus == null) return -1;
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return -1;
            return (int) ((level / (float) scale) * 100);
        } catch (Exception e) {
            Log.e(TAG, "getPhoneBatteryLevel() - exception: " + e.getMessage());
            return -1;
        }
    }

    /*
     * beep, provided mAudibleAlarm is set
     */
    public void faultWarningBeep() {
        if (mCancelAudible) {
            Log.v(TAG, "faultWarningBeep() - CancelAudible Active - silent beep...");
        } else {
            if (mAudibleFaultWarning) {
                if (mMp3Alarm) {
                    Log.i(TAG, "SdServer.faultWarningBeep() - playing MP3");
                    playMp3(mMp3FaultUri, "fault");
                } else {
                    beep(10);
                }
                Log.v(TAG, "faultWarningBeep()");
                Log.i(TAG, "SdServer.faultWarningBeep() - beeping");
            } else {
                Log.v(TAG, "faultWarningBeep() - silent...");
            }
        }
    }


    /*
     * beep, provided mAudibleAlarm is set
     */
    public void alarmBeep() {
        if (mCancelAudible) {
            Log.v(TAG, "alarmBeep() - CancelAudible Active - silent beep...");
        } else {
            if (mAudibleAlarm) {
                if (mMp3Alarm) {
                    Log.i(TAG, "SdServer.alarmBeep() - playing MP3");
                    playMp3(mMp3AlarmUri, "alarm");
                } else {
                    beep(3000);
                }
                Log.v(TAG, "alarmBeep()");
                Log.i(TAG, "SdServer.alarmBeep() - beeping");
            } else {
                Log.v(TAG, "alarmBeep() - silent...");
            }
        }
    }

    /*
     * beep, provided mAudibleWarning is set
     */
    public void warningBeep() {
        if (mCancelAudible) {
            Log.v(TAG, "warningBeep() - CancelAudible Active - silent beep...");
        } else {
            if (mAudibleWarning) {
                if (mMp3Alarm) {
                    Log.i(TAG, "SdServer.warningBeep() - playing MP3");
                    playMp3(mMp3WarningUri, "warning");
                } else {
                    beep(100);
                }
                Log.v(TAG, "warningBeep()");
                Log.i(TAG, "SdServer.warningBeep() - beeping");
            } else {
                Log.v(TAG, "warningBeep() - silent...");
            }
        }
    }


    /**
     * Sends SMS Alarms to the telephone numbers specified in mSMSNumbers[]
     * Attempts to find a better location, and sends a second SMS after location search
     * complete (onLocationReceived()).
     */
    public void sendSMSAlarm() {
        AlertDialog ad;
        if (mSMSAlarm) {
            if (!mCancelAudible) {
                startSmsTimer();
            } else {
                Log.i(TAG, "sendSMSAlarm() - Cancel Audible Active - not sending SMS");
                //mUtil.showToast(getString(R.string.cancel_audible_not_sending_sms));
            }
        } else {
            Log.i(TAG, "sendSMSAlarm() - SMS Alarms Disabled - not doing anything!");
            //mUtil.showToast(getString(R.string.sms_alarms_disabled));
        }
        if (mPhoneAlarm) {
            if (!mCancelAudible) {
                Log.i(TAG, "sendSMSAlarm() - Sending Phone Alarm Broadcast");
                sendPhoneAlarm();
            } else {
                Log.i(TAG, "sendSMSAlarm() - Cancel Audible Active - not making Phone Call");
                //mUtil.showToast(getString(R.string.cancel_audible_not_sending_sms));
            }
        } else {
            Log.i(TAG, "sendSMSAlarm() - Phone Alarms Disabled - not doing anything!");
            //mUtil.showToast(getString(R.string.phone_alarm_disabled));
        }
    }

    /**
     * Sends SMS Alarms to the telephone numbers specified in mSMSNumbers[]
     * Attempts to find a better location, and sends a second SMS after location search
     * complete (onLocationReceived()).
     */
    public void sendFalseAlarmSMS() {
        AlertDialog ad;
        if (mSMSAlarm) {
            if (!mCancelAudible) {
                Log.i(TAG, "sendFalseAlarmsSMS() - Sending to " + mSMSNumbers.length + " Numbers");
                Log.i(TAG, "SdServer.sendFalseAlarmsSMS()");
                Calendar cal = Calendar.getInstance();
                String dateStr = String.format("%02d:%02d:%02d %02d/%02d/%04d",
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        cal.get(Calendar.SECOND),
                        cal.get(Calendar.DAY_OF_MONTH),
                        cal.get(Calendar.MONTH) + 1,
                        cal.get(Calendar.YEAR));
                String shortUuidStr = mUuidStr.substring(mUuidStr.length() - 6);

                // SmsManager sm = SmsManager.getDefault();
                for (int i = 0; i < mSMSNumbers.length; i++) {
                    Log.i(TAG, "sendFalseAlarmsSMS() - Sending to " + mSMSNumbers[i]);
                    //sendSMS(new String(mSMSNumbers[i]), mSMSFalseAlarmMsgStr + " - " + dateStr + " " + shortUuidStr);
                    try {
                        SmsManager sm = this.getSystemService(SmsManager.class);
                        if (sm != null) {
                            sm.sendTextMessage(mSMSNumbers[i], null, mSMSFalseAlarmMsgStr + " - " + dateStr + " " + shortUuidStr,
                                    null, null);
                        } else {
                            Log.e(TAG, "sendFalseAlarmsSMS - SmsManager is null");
                            mUtil.showToast(getString(R.string.failed_to_send_sms));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "sendFalseAlarmsSMS - Failed to send SMS Message");
                        mUtil.showToast(getString(R.string.failed_to_send_sms));
                    }

                }

            } else {
                Log.i(TAG, "sendFalseAlarmsSMS() - Cancel Audible Active - not sending SMS");
                //mUtil.showToast(getString(R.string.cancel_audible_not_sending_sms));
            }
        } else {
            Log.i(TAG, "sendFalseAlarmsSMS() - SMS Alarms Disabled - not doing anything!");
            //mUtil.showToast(getString(R.string.sms_alarms_disabled));
        }
    }



    /**
     * smsCanelClickListener - onClickListener for the SMS cancel dialog box.   If the
     * negative button is pressed, it cancels the SMS timer to prevent the SMS being sent.
     */
    DialogInterface.OnClickListener smsCancelClickListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            switch (which) {
                case DialogInterface.BUTTON_POSITIVE:
                    Log.v(TAG, "smsCancelClickListener - Positive button");
                    //Yes button clicked
                    break;

                case DialogInterface.BUTTON_NEGATIVE:
                    Log.v(TAG, "smsCancelClickListener - Negative button");
                    //No button clicked
                    break;
            }
        }
    };


    /*
     * Start the timer that will send and SMS alert after a given period.
     */
    private void startSmsTimer() {
        if (mSmsTimer != null) {
            Log.v(TAG, "startSmsTimer -timer already running - cancelling it");
            mSmsTimer.cancel();
            mSmsTimer = null;
        }
        Log.v(TAG, "startSmsTimer() - starting SmsTimer");
        runOnUiThread(new Runnable() {
            public void run() {
                mSmsTimer =
                        new SmsTimer(mSmsTimerSecs * 1000, 1000);
                mSmsTimer.start();
            }
        });
    }


    /*
     * Cancel the SMS timer to prevent the SMS message being sent..
     */
    public void stopSmsTimer() {
        if (mSmsTimer != null) {
            Log.v(TAG, "stopSmsTimer(): cancelling Sms timer");
            mSmsTimer.cancel();
            mSmsTimer = null;
        }
    }


    /*
     * Start the timer that will automatically re-set a latched alarm after a given period.
     */
    private void startLatchTimer() {
        if (mLatchAlarms) {
            if (mLatchAlarmTimer != null) {
                Log.i(TAG, "startLatchTimer -timer already running - cancelling it");
                mLatchAlarmTimer.cancel();
                mLatchAlarmTimer = null;
            }
            Log.i(TAG, "startLatchTimer() - starting alarm latch release timer to time out in " + mLatchAlarmPeriod + " sec");
            // set timer to timeout after mLatchAlarmPeriod, and Tick() function to be called every second.
            // We need to start the timer on the UI thread to get it to work for some reason - I don't know why!
            runOnUiThread(new Runnable() {
                public void run() {
                    mLatchAlarmTimer =
                            new LatchAlarmTimer(mLatchAlarmPeriod * 1000, 1000);
                    mLatchAlarmTimer.start();
                }
            });
        } else {
            Log.v(TAG, "startLatchTimer() - Latch Alarms disabled - not doing anything");
        }
    }

    /*
     * Cancel the automatic de-latch timer - called from onDestroy, or if the AcceptAlarm button is pressed.
     */
    private void stopLatchTimer() {
        if (mLatchAlarmTimer != null) {
            Log.v(TAG, "stopLatchTimer(): cancelling LatchAlarm timer");
            mLatchAlarmTimer.cancel();
            mLatchAlarmTimer = null;
        }
    }

    /**
     * set the alarm standing flags to false to allow alarm phase to reset to current value.
     */
    public void acceptAlarm() {
        Log.i(TAG, "acceptAlarm()");
        mSdData.alarmStanding = false;
        mSdData.fallAlarmStanding = false;
        mSdDataSource.acceptAlarm();
        stopLatchTimer();
    }


    public void cancelAudible() {
        // Start timer to remove the cancel audible flag
        // after the required period.
        if (mCancelAudibleTimer != null) {
            Log.i(TAG, "cancelAudible(): cancel audible timer already running - cancelling it.");
            mCancelAudibleTimer.cancel();
            mCancelAudibleTimer = null;
            mCancelAudible = false;
        } else {
            Log.i(TAG, "cancelAudible(): starting cancel audible timer");
            mCancelAudible = true;
            // Initialize the time remaining to the full duration before starting the timer
            // so the UI displays the correct value immediately
            mCancelAudibleTimeRemaining = mCancelAudiblePeriod * 60;
            mCancelAudibleTimer =
                    // conver to ms.
                    new CancelAudibleTimer(mCancelAudiblePeriod * 60 * 1000, 1000);
            mCancelAudibleTimer.start();
        }
    }

    public boolean isAudibleCancelled() {
        return mCancelAudible;
    }

    public long cancelAudibleTimeRemaining() {
        return mCancelAudibleTimeRemaining;
    }

    public boolean isLatchAlarms() {
        return mLatchAlarms;
    }


    /**
     * Start the web server (on port 8080), and register for network connectivity events so we can log
     * problems.
     */
    protected void startWebServer() {
        Log.i(TAG, "startWebServer()");
        Log.i(TAG, "SdServer.Start Web Server.");
        if (webServer == null) {
            webServer = new SdWebServer(getApplicationContext(), mSdData, this);
            try {
                webServer.start();
            } catch (IOException ioe) {
                Log.e(TAG, "startWebServer(): Error: " + ioe.toString());
            }
            Log.i(TAG, "startWebServer(): Web server initialized.");
        } else {
            Log.i(TAG, "startWebServer(): server already running???");
        }


    }

    /**
     * Stop the web server - FIXME - doesn't seem to do anything!
     * And de-register for network connectivity events.
     */
    protected void stopWebServer() {
        Log.i(TAG, "SdServer.stopWebServer()");
        if (webServer != null) {
            webServer.stop();
            if (webServer == null) {
                Log.i(TAG, "stopWebServer() - server null - server died ok");
                Log.v(TAG, "stopWebServer() - server null - server died ok");
            } else {
                if (webServer.isAlive()) {
                    Log.w(TAG, "stopWebServer() - server still alive???");
                    Log.i(TAG, "stopWebServer() - server still alive???");
                } else {
                    Log.i(TAG, "stopWebServer() - server died ok");
                    Log.v(TAG, "stopWebServer() - server died ok");
                }
            }
            //webServer = null;
        }
    }

    private class NetworkBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(TAG, "NetworkBroadCastReceiver.onReceive");
            Log.i(TAG, "NetworkBroadcastReceiver.onReceive(): Network State Changed" + intent.getAction());
            //mUtil.showToast("Network State Changed" + intent.getAction());

            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            boolean isConnected = false;
            boolean isWiFi = false;

            if (cm != null) {
                android.net.Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                    if (capabilities != null) {
                        isConnected = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        isWiFi = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                    }
                }
            }

            if (isConnected) {
                if (!isWiFi) {
                    Log.v(TAG, "NetworkBroadcastReceiver - no Wifi Connection");
                    Log.i(TAG, "Network State Changed - no Wifi Connection");
                    //mUtil.showToast(getString(R.string.no_wifi_connection));
                    // If mobile data logging is enabled, trigger upload
                    if (mLogDataRemoteMobile && mLm != null) {
                        Log.i(TAG, "NetworkBroadcastReceiver - Mobile data connected and remote mobile logging enabled - triggering upload");
                        Log.i(TAG, "NetworkBroadcastReceiver - triggering upload on mobile data");
                        mLm.triggerImmediateUpload();
                    }
                } else {
                    Log.v(TAG, "NetworkBroadcastReceiver - Wifi Connected");
                    Log.i(TAG, "Network State Changed - Wifi Connected");
                    //mUtil.showToast("Network State Changed - Wifi Connected");
                    // Trigger immediate upload when WiFi becomes available
                    if (mLm != null) {
                        Log.i(TAG, "NetworkBroadcastReceiver - WiFi connected - triggering upload");
                        Log.i(TAG, "NetworkBroadcastReceiver - triggering upload on WiFi connection");
                        mLm.triggerImmediateUpload();
                    }
                }
            } else {
                Log.v(TAG, "NetworkBroadcastReceiver - No Active Network");
                Log.i(TAG, "Network State Changed - No Active Network");
                //mUtil.showToast(getString(R.string.no_active_network));
            }
        }
    }


    /**
     * Log data to SD card if mLogData is set in preferences.
     */
    public void logData() {
        if (mLogData) {
            if (mLm != null) {
                Log.v(TAG, "logData() - writing data to Database");
                //writeToSD();
                mLm.writeDatapointToLocalDb(mSdData, mSdDataHistory);
            } else {
                Log.e(TAG, "logData() - mLm is null - this should not happen");
            }
        }
    }


    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/prefs.xml
     */
    public void updatePrefs() {
        Log.i(TAG, "SdServer.updatePrefs()");

        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        try {
            mSdDataSourceName = SP.getString("DataSource", "SET_FROM_XML");
            Log.d(TAG, "updatePrefs() - DataSource = " + mSdDataSourceName);
            mLatchAlarms = SP.getBoolean("LatchAlarms", false);
            Log.d(TAG, "updatePrefs() - mLatchAlarms = " + mLatchAlarms);
            // Parse the LatchAlarmPeriod setting.
            try {
                String latchAlarmPeriodStr = SP.getString("LatchAlarmTimerPeriod", "SET_FROM_XML");
                mLatchAlarmPeriod = Integer.parseInt(latchAlarmPeriodStr);
                Log.d(TAG, "updatePrefs() - mLatchAlarmTimerPeriod = " + mLatchAlarmPeriod);
            } catch (Exception ex) {
                Log.w(TAG, "updatePrefs() - Problem with LatchAlarmTimerPeriod preference!");
                mUtil.showToast(getString(R.string.problem_parsing_preferences));
            }
            mAudibleFaultWarning = SP.getBoolean("AudibleFaultWarning", true);
            Log.d(TAG, "updatePrefs() - mAuidbleFaultWarning = " + mAudibleFaultWarning);
            // Parse the faultTimer period setting.
            try {
                String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "SET_FROM_XML");
                mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
                Log.d(TAG, "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
            } catch (Exception ex) {
                Log.w(TAG, "updatePrefs() - Problem with FaultTimerPeriod preference!");
                mUtil.showToast(getString(R.string.problem_parsing_preferences));
            }

            // Phone battery low-battery fault preferences
            mPhoneBatteryAlarmActive = SP.getBoolean("PhoneBatteryAlarmActive", true);
            Log.d(TAG, "updatePrefs() - mPhoneBatteryAlarmActive = " + mPhoneBatteryAlarmActive);
            try {
                String battThreshStr = SP.getString("PhoneBatteryAlarmThreshold", "5");
                mPhoneBatteryAlarmThreshold = Integer.parseInt(battThreshStr);
                Log.d(TAG, "updatePrefs() - mPhoneBatteryAlarmThreshold = " + mPhoneBatteryAlarmThreshold);
            } catch (Exception ex) {
                Log.w(TAG, "updatePrefs() - Problem with PhoneBatteryAlarmThreshold preference, using default 5%");
                mPhoneBatteryAlarmThreshold = 5;
            }

            mAudibleAlarm = SP.getBoolean("AudibleAlarm", true);
            Log.d(TAG, "updatePrefs() - mAudibleAlarm = " + mAudibleAlarm);
            mAudibleWarning = SP.getBoolean("AudibleWarning", true);
            Log.d(TAG, "updatePrefs() - mAudibleWarning = " + mAudibleWarning);
            mMp3Alarm = SP.getBoolean("UseMp3Alarm", false);
            Log.d(TAG, "updatePrefs() - mMp3Alarm = " + mMp3Alarm);
            // User-selected MP3 URIs — empty string means "use bundled sound"
            mMp3WarningUri = SP.getString("Mp3WarningUri", "");
            mMp3AlarmUri   = SP.getString("Mp3AlarmUri",   "");
            mMp3FaultUri   = SP.getString("Mp3FaultUri",   "");
            Log.d(TAG, "updatePrefs() - Mp3WarningUri=" + mMp3WarningUri
                    + " Mp3AlarmUri=" + mMp3AlarmUri
                    + " Mp3FaultUri=" + mMp3FaultUri);
            // Recreate notification channels so the new sound URIs take effect immediately.
            createNotificationChannels();

            mSMSAlarm = PreferenceUtils.getBooleanFromXml(SP, "SMSAlarm");
            Log.d(TAG, "updatePrefs() - mSMSAlarm = " + mSMSAlarm);
            mPhoneAlarm = SP.getBoolean("PhoneCallAlarm", false);
            String SMSNumberStr = SP.getString("SMSNumbers", "SET_FROM_XML");
            mSMSNumbers = SMSNumberStr.split(",");
            Log.d(TAG, "updatePrefs() - SMSNumberStr = " + SMSNumberStr);
            Log.d(TAG, "updatePrefs() - mSMSNumbers = " + mSMSNumbers);
            mSMSMsgStr = SP.getString("SMSMsg", "SET_FROM_XML");
            Log.d(TAG, "updatePrefs() - SMSMsgStr = " + mSMSMsgStr);
            mSMSFalseAlarmMsgStr = SP.getString("SMSFalseAlarmMsg", "SET_FROM_XML");

            String smsDelayPeriodStr = SP.getString("SMSDelayPeriod", "SET_FROM_XML");
            mSmsTimerSecs = Integer.parseInt(smsDelayPeriodStr);
            Log.d(TAG,"updatePrefs() - mSmsTimerSecs = "+mSmsTimerSecs);

            mLogAlarms = SP.getBoolean("LogAlarms", true);
            Log.d(TAG, "updatePrefs() - mLogAlarms = " + mLogAlarms);
            //mLogData = SP.getBoolean("LogData", true);
            mLogData = true;
            Log.d(TAG, "updatePrefs() - mLogData = " + mLogData);
            //mLogDataRemote = SP.getBoolean("LogDataRemote", false);
            mLogDataRemote = true;
            Log.d(TAG, "updatePrefs() - mLogDataRemote = " + mLogDataRemote);
            mLogDataRemoteMobile = PreferenceUtils.getBooleanFromXml(SP, "LogDataRemoteMobile");
            Log.d(TAG, "updatePrefs() - mLogDataRemoteMobile = " + mLogDataRemoteMobile);
            mLogNDA = PreferenceUtils.getBooleanFromXml(SP, "LogNDA");
            Log.d(TAG, "updatePrefs() - mLogNDA = " + mLogNDA);
            mAuthToken = SP.getString("webApiAuthToken", null);
            Log.d(TAG, "updatePrefs() - mAuthToken = " + mAuthToken);

            String prefVal;
            prefVal = SP.getString("EventDurationSec", "SET_FROM_XML");
            mEventDuration = Integer.parseInt(prefVal);
            Log.d(TAG, "mEventDuration=" + mEventDuration);

            mAutoPruneDb = PreferenceUtils.getBooleanFromXml(SP, "AutoPruneDb");
            Log.d(TAG, "mAutoPruneDb=" + mAutoPruneDb);

            prefVal = SP.getString("DataRetentionPeriod", "SET_FROM_XML");
            mDataRetentionPeriod = Integer.parseInt(prefVal);
            Log.d(TAG, "mDataRetentionPeriod=" + mDataRetentionPeriod);
            Log.d(TAG, "mRemoteLogPeriod=" + mRemoteLogPeriod);

            mOSDUrl = SP.getString("OSDUrl", "SET_FROM_XML");
            Log.d(TAG, "updatePrefs() - mOSDUrl = " + mOSDUrl);

            // ML Model Update Check Period
            prefVal = SP.getString("MlModelUpdateCheckPeriod", "SET_FROM_XML");
            long newPeriod = Long.parseLong(prefVal);
            if (newPeriod != mModelUpdateCheckPeriod) {
                mModelUpdateCheckPeriod = newPeriod;
                // Restart the timer with new period
                stopModelUpdateTimer();
                if (mModelUpdateCheckPeriod > 0) {
                    startModelUpdateTimer();
                }
            }
            Log.d(TAG, "updatePrefs() - mModelUpdateCheckPeriod = " + mModelUpdateCheckPeriod);

        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - Problem parsing preferences!" + ex.toString());
            Log.e(TAG, "SdServer.updatePrefs() - Error " + ex.toString());
            mUtil.showToast(getString(R.string.problem_parsing_preferences));
            faultWarningBeep();
        }
    }


    public void sendPhoneAlarm() {
        /**
         * Use the separate OpenSeizureDetector Dialler app to generate a phone call alarm to the numbers selected for SMS Alarms.
         */
        Log.i(TAG, "sendPhoneAlarm() - sending broadcast intent");
        Intent intent = new Intent();
        intent.setAction("uk.org.openseizuredetector.dialler.ALARM");
        intent.putExtra("NUMBERS", mSMSNumbers);
        sendBroadcast(intent);
    }

    /**
     * muteCheck() - Check if mute is active from either source:
     * 1. Watch button: mMute value received from watch
     * 2. Phone UI button: mCancelAudible flag set by user clicking mute button
     *
     * If muted, sets alarmState to 6 (MUTE state)
     */
    public void muteCheck(SdData sdData) {
        if (sdData == null) return;

        // Check watch button mute (from the watch JSON data)
        if (sdData.mMute != 0) {
            Log.i(TAG, "muteCheck() - Mute active from watch button");
            sdData.alarmState = AlarmState.MUTE;  // MUTE state
            Log.i(TAG, "muteCheck() - cancelling SmSTimer to prevent alert being sent");
            stopSmsTimer();
            return;
        }

        // Check phone UI button mute (from mCancelAudible)
        if (mCancelAudible) {
            Log.i(TAG, "muteCheck() - Mute active from phone UI button");
            sdData.alarmState = AlarmState.MUTE;  // MUTE state
            Log.i(TAG, "muteCheck() - cancelling SmSTimer to prevent alert being sent");
            stopSmsTimer();
            return;
        }
    }

    /**
     * muteAlarm() - Public method for UI to call when user clicks mute button on phone
     * Sets mCancelAudible flag to activate mute
     */
    public void muteAlarm() {
        Log.i(TAG, "muteAlarm() - User clicked mute on phone UI");
        mCancelAudible = true;
        // The mute will be applied on next data reception via muteCheck()
    }


    /*
     * Wait a given time, then send an SMS alert - the idea is to give the user time to cancel the
     * alert if necessary.
     */
    public class SmsTimer extends CountDownTimer implements SdLocationReceiver {
        public long mTimeLeft = -1;

        public SmsTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        // called after startTime ms.
        @Override
        public void onFinish() {
            Log.i(TAG, "SmsTimer.onFinish()");
            mTimeLeft = 0;
            if (mLocationFinder != null) {
                mLocationFinder.getLocation(this);
                Location loc = mLocationFinder.getLastLocation();
                if (loc != null) {
                    mUtil.showToast(getString(R.string.send_sms_last_location)
                            + loc.getLongitude() + ","
                            + loc.getLatitude());
                } else {
                    Log.i(TAG, "SmsTimer.onFinish() - Last Location is Null so sending first SMS without location.");
                }
            } else {
                Log.e(TAG, "SmsTimer.onFinish - mLocationFinder is null - this should not happen!");
                mUtil.showToast(getString(R.string.mLocationFinder_is_null_msg));
            }
            Log.i(TAG, "SmsTimer.onFinish() - Sending to " + mSMSNumbers.length + " Numbers");
            Log.i(TAG, "SdServer.SmsTimer.onFinish()");
            Calendar cal = Calendar.getInstance();
            String dateStr = String.format("%02d:%02d:%02d %02d/%02d/%04d",
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    cal.get(Calendar.SECOND),
                    cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.YEAR));
            String shortUuidStr = mUuidStr.substring(mUuidStr.length() - 6);

            // SmsManager sm = this.getSystemService(SmsManager.class);
            for (int i = 0; i < mSMSNumbers.length; i++) {
                Log.i(TAG, "SmsTimer.onFinish() - Sending to " + mSMSNumbers[i]);
                sendSMS(new String(mSMSNumbers[i]), mSMSMsgStr + " - " + dateStr + " " + shortUuidStr);
            }
        }

        // Called every 'interval' ms.
        @Override
        public void onTick(long timeRemaining) {
            Log.d(TAG, "SmsTimer.onTick() - time remaining = " + timeRemaining / 1000 + " sec");
            // The MainActivity screen picks up mTimeLeft to update the screen.
            mTimeLeft = timeRemaining;
            alarmBeep();
        }

        /**
         * onSdLocationReceived - called with the best estimate location after mLocationReceiver times out.
         */
        private void sendSMS(String phoneNo, String msgStr) {
            Log.i(TAG, "sendSMS() - Sending to " + phoneNo);
            try {
                SmsManager sm = getSystemService(SmsManager.class);
                if (sm != null) {
                    sm.sendTextMessage(phoneNo, null, msgStr,
                            null, null);
                } else {
                    Log.e(TAG, "sendSMS - SmsManager is null");
                    mUtil.showToast(getString(R.string.failed_to_send_sms));
                }
            } catch (Exception e) {
                Log.e(TAG, "sendSMS - Failed to send SMS Message");
                Log.e(TAG, e.toString());
                mUtil.showToast(getString(R.string.failed_to_send_sms));
            }
        }

        private void sendSMSIntent(String phoneNo, String msgStr) {
            Log.i(TAG, "sendSMSIntent() - Sending to " + phoneNo);
            // sm.sendTextMessage(mSMSNumbers[i], null, mSMSMsgStr + " - " + dateStr, null, null);
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("smsto:")); //, HTTP.PLAIN_TEXT_TYPE);
            intent.putExtra("sms_body", msgStr);
            intent.putExtra("address", phoneNo);
            if (intent.resolveActivity(getPackageManager()) != null) {
                Log.i(TAG, "sendSMSIntent() - Starting Activity to send SMS....");
                startActivity(intent);
            } else {
                Log.e(TAG, "sendSMSIntent() - Failed to send SMS - can not find activity do do it");
            }

        }

        @Override
        public void onSdLocationReceived(Location ll) {
            if (ll == null) {
                //mUtil.showToast("onSdLocationReceived() - NULL LOCATION RECEIVED");
                Log.w(TAG, "onSdLocationReceived() - NULL LOCATION RECEIVED");
            } else {
                //mUtil.showToast("onSdLocationReceived() - found location" + ll.toString());
                Log.i(TAG, "onSdLocationReceived() - found location" + ll.toString());
                if (mSMSAlarm) {
                    Log.i(TAG, "onSdLocationReceived() - Sending SMS to " + mSMSNumbers.length + " Numbers");
                    Log.i(TAG, "SdServer.sendSMSAlarm()");
                    Calendar cal = Calendar.getInstance();
                    String dateStr = String.format("%02d:%02d:%02d %02d/%02d/%04d",
                            cal.get(Calendar.HOUR_OF_DAY),
                            cal.get(Calendar.MINUTE),
                            cal.get(Calendar.SECOND),
                            cal.get(Calendar.DAY_OF_MONTH),
                            cal.get(Calendar.MONTH) + 1,
                            cal.get(Calendar.YEAR));
                    NumberFormat df = new DecimalFormat("#0.000");
                    String geoUri = "<a href='geo:"
                            + df.format(ll.getLatitude()) + "," + df.format(ll.getLongitude())
                            + ";u=" + df.format(ll.getAccuracy()) + "'>here</a>";
                    String googleUrl = "https://www.google.com/maps/place?q="
                            + ll.getLatitude() + "%2C" + ll.getLongitude();
                    String shortUuidStr = mUuidStr.substring(mUuidStr.length() - 6);

                    String messageStr = mSMSMsgStr + " - " +
                            dateStr + " - " + googleUrl + " " + shortUuidStr;
                    Log.i(TAG, "onSdLocationReceived() - Message is " + messageStr);
                    //mUtil.showToast(messageStr);
                    for (int i = 0; i < mSMSNumbers.length; i++) {
                        Log.i(TAG, "onSdLocationReceived() - Sending to " + mSMSNumbers[i]);
                        sendSMS(mSMSNumbers[i], messageStr);
                    }
                } else {
                    Log.i(TAG, "sendSMSAlarm() - SMS Alarms Disabled - not doing anything!");
                    //mUtil.showToast(getString(R.string.sms_alarms_disabled));
                }

            }
        }


    }


    /*
     * Latch alarm in alarm state for a given period (mLatchAlarmPeriod seconds) after the alarm is raised.
     * This is to ensure multiple Alarm annunciations even if only a single Alarm signal is received.
     */
    private class LatchAlarmTimer extends CountDownTimer {
        public LatchAlarmTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        // called after startTime ms.
        @Override
        public void onFinish() {
            Log.i(TAG, "LatchAlarmTimer.onFinish()");
            // Do the equivalent of accept alarm push button.
            acceptAlarm();
        }

        // Called every 'interval' ms.
        @Override
        public void onTick(long timeRemaining) {
            Log.v(TAG, "LatchAlarmTimer.onTick() - time remaining = " + timeRemaining / 1000 + " sec");
            alarmBeep();
        }
    }

    /*
     * Temporary cancel audible alarms, for the period specified by the
     * CancelAudiblePeriod setting.
     */
    private class CancelAudibleTimer extends CountDownTimer {
        public CancelAudibleTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onFinish() {
            mCancelAudible = false;
            Log.v(TAG, "mCancelAudibleTimer - removing cancelAudible flag");
        }

        @Override
        public void onTick(long msRemaining) {
            mCancelAudibleTimeRemaining = msRemaining / 1000;
            Log.v(TAG, "mCancelAudibleTimer - onTick() - Time Remaining = "
                    + mCancelAudibleTimeRemaining);
        }

    }

    /**
     * Start the fault timer that is used to require a fault to remain
     * standing for a period before raising fault beeps.
     */
    public void startFaultTimer() {
        if (mFaultTimer != null) {
            Log.v(TAG, "startFaultTimer(): fault timer already running - not doing anything.");
            Log.i(TAG, "startFaultTimer() - fault timer already running");
        } else {
            Log.v(TAG, "startFaultTimer(): starting fault timer.");
            Log.i(TAG, "startFaultTimer() - starting fault timer");
            runOnUiThread(new Runnable() {
                public void run() {
                    mFaultTimerCompleted = false;
                    mFaultTimer =
                            // convert to ms.
                            new FaultTimer(mFaultTimerPeriod * 1000, 1000);
                    mFaultTimer.start();
                }
            });
        }
    }

    public void stopFaultTimer() {
        if (mFaultTimer != null) {
            //Log.v(TAG, "stopFaultTimer(): fault timer already running - cancelling it.");
            Log.i(TAG, "stopFaultTimer() - stopping fault timer");
            mFaultTimer.cancel();
            mFaultTimer = null;
            mFaultTimerCompleted = false;
        } else {
            //Log.v(TAG, "stopFaultTimer(): fault timer not running - not doing anything.");
            //Log.i(TAG, "stopFaultTimer() - fault timer not running");
        }
    }


    /**
     * Inhibit fault alarm initiation for a period to avoid spurious warning
     * beeps caused by short term network interruptions.
     */
    private class FaultTimer extends CountDownTimer {
        public long mFaultTimerRemaining = 0;

        public FaultTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onFinish() {
            try {
                mFaultTimerCompleted = true;
                Log.v(TAG, "mFaultTimer - removing mFaultTimerRunning flag");
                Log.d(TAG, "FaultTimer.onFinish()");
            } catch (Exception e) {
                Log.e(TAG, "Error in FaultTimer.onFinish()", e);
                mUtil.writeExceptionLog("FaultTimer", "onFinish", e);
            }
        }

        @Override
        public void onTick(long msRemaining) {
            try {
                mFaultTimerRemaining = msRemaining / 1000;
                Log.v(TAG, "mFaultTimer - onTick() - Time Remaining = " + mFaultTimerRemaining);

                // Log memory status every minute (every 60 ticks at 1-second interval)
                if (mFaultTimerRemaining % 60 == 0) {
                    mUtil.writeMemoryLog("Watchdog heartbeat");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FaultTimer.onTick()", e);
                mUtil.writeExceptionLog("FaultTimer", "onTick", e);
            }
        }

    }


    /**
     * Start the events timer.
     */
    public void startEventsTimer() {
        if (mEventsTimer != null) {
            Log.v(TAG, "startEventsTimer(): timer already running - not doing anything.");
            Log.i(TAG, "startEventsTimer() - timer already running");
        } else {
            Log.v(TAG, "startEventsTimer(): starting timer.");
            Log.i(TAG, "startEventsTimer() - starting timer");
            runOnUiThread(new Runnable() {
                public void run() {
                    mEventsTimer =
                            // Run every 10 sec (convert to ms.)
                            new CheckEventsTimer(mEventsTimerPeriod * 1000, 1000);
                    mEventsTimer.mIsRunning = true;
                    mEventsTimer.start();
                }
            });
        }
    }

    public void stopEventsTimer() {
        if (mEventsTimer != null) {
            Log.v(TAG, "stopEventsTimer(): timer already running - cancelling it.");
            Log.i(TAG, "stopEventsTimer() - stopping timer, setting mIsRunning to false");
            mEventsTimer.mIsRunning = false;
            mEventsTimer.cancel();
            //mEventsTimer = null;
        } else {
            Log.v(TAG, "stopEventsTimer(): timer not running - not doing anything.");
        }
    }


    private void checkEvents() {
        // Retrieve events from remote database
        if (mLm == null) {
            Log.w(TAG, "checkEvents() - mLm is null, skipping");
            return;
        }
        if (mLm.mWac.getEvents((JSONObject remoteEventsObj) -> {
            Log.v(TAG, "checkEvents.getEvents.Callback()");
            Boolean haveUnvalidatedEvent = false;
            if (remoteEventsObj == null) {
                Log.e(TAG, "checkEvents.getEvents.Callback():  Error Retrieving events");
            } else {
                try {
                    JSONArray eventsArray = remoteEventsObj.getJSONArray("events");
                    // A bit of a hack to display in reverse chronological order
                    for (int i = eventsArray.length() - 1; i >= 0; i--) {
                        JSONObject eventObj = eventsArray.getJSONObject(i);
                        String typeStr = eventObj.getString("type");
                        //Log.v(TAG,"CheckEventsTimer: id="+id+", typeStr="+typeStr);
                        if (typeStr.equals("null") || typeStr.equals("")) {
                            haveUnvalidatedEvent = true;
                            //Log.v(TAG,"CheckEventsTimer:setting firstUnvalidatedEvent to "+firstUnvalidatedEvent);
                        }
                    }
                    Log.v(TAG, "checkEvents() - haveUnvalidatedEvent = " +
                            haveUnvalidatedEvent);

                    if (haveUnvalidatedEvent) {
                        mDataShareMsg = getString(R.string.please_confirm_seizure_events);
                    } else {
                        mDataShareMsg = null;
                    }

                    // Update the main notification
                    showNotification(mCurrentNotificationAlarmLevel);

                } catch (JSONException e) {
                    Log.e(TAG, "CheckEventsTimer.onFinish(): Error Parsing remoteEventsObj: " + e.getMessage());
                    //mUtil.showToast("Error Parsing remoteEventsObj - this should not happen!!!");
                }
            }
        })) {
            Log.v(TAG, "CheckEventsTimer() - requested events");
        } else {
            Log.v(TAG, "CheckEventsTimer() - Not Logged In");
            mDataShareMsg = getString(R.string.login_to_osdapi);

            // Update the main notification
            showNotification(mCurrentNotificationAlarmLevel);
        }

    }

    /**
     * Periodically check if we have unvalidated events in the remote database.
     * Show a notification if we do.
     */
    private class CheckEventsTimer extends CountDownTimer {
        public boolean mIsRunning = true;

        public CheckEventsTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onFinish() {
            try {
                Log.v(TAG, "CheckEventsTimer.onFinish()");
                Log.d(TAG, "CheckEventsTimer.onFinish()");
                checkEvents();
                if (mIsRunning) {
                    // Restart this timer.
                    Log.v(TAG, "CheckEventsTimer.onFinish() - mIsRunning is true, so re-starting timer");
                    start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in CheckEventsTimer.onFinish()", e);
                mUtil.writeExceptionLog("CheckEventsTimer", "onFinish", e);
            }
        }

        @Override
        public void onTick(long msRemaining) {
            // Silent operation during tick
        }
    }


    /**
     * Start the ML model update check timer
     */
    public void startModelUpdateTimer() {
        if (mModelUpdateTimer != null) {
            Log.v(TAG, "startModelUpdateTimer(): timer already running - cancelling it.");
            stopModelUpdateTimer();
        }
        if (mModelUpdateCheckPeriod > 0) {
            Log.v(TAG, "startModelUpdateTimer(): starting timer.");
            Log.i(TAG, "startModelUpdateTimer() - starting timer");
            runOnUiThread(new Runnable() {
                public void run() {
                    mModelUpdateTimer =
                            // Run every configured period (convert to ms.)
                            new CheckModelUpdateTimer(mModelUpdateCheckPeriod * 1000, 1000);
                    mModelUpdateTimer.mIsRunning = true;
                    mModelUpdateTimer.start();
                }
            });
        }
    }

    public void stopModelUpdateTimer() {
        if (mModelUpdateTimer != null) {
            Log.v(TAG, "stopModelUpdateTimer(): timer already running - cancelling it.");
            Log.i(TAG, "stopModelUpdateTimer() - stopping timer, setting mIsRunning to false");
            mModelUpdateTimer.mIsRunning = false;
            mModelUpdateTimer.cancel();
            mModelUpdateTimer = null;
        } else {
            Log.v(TAG, "stopModelUpdateTimer(): timer not running - not doing anything.");
        }
    }

    private void checkForModelUpdate() {
        Log.v(TAG, "checkForModelUpdate()");
        if (mMlModelManager == null) {
            Log.w(TAG, "checkForModelUpdate(): mMlModelManager is null");
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Store last check time
        prefs.edit().putLong("LastModelUpdateCheck", System.currentTimeMillis()).apply();

        mMlModelManager.checkForModelUpdate(prefs, new MlModelManager.ModelUpdateCallback() {
            @Override
            public void onUpdateAvailable(JSONObject recommendedModel) {
                Log.i(TAG, "checkForModelUpdate(): Update available");
                runOnUiThread(() -> showModelUpdateDialog(recommendedModel));
            }

            @Override
            public void onNoUpdate() {
                Log.d(TAG, "checkForModelUpdate(): No update available");
            }
        });
    }

    /**
     * Show a dialog to prompt the user to download a new recommended ML model
     */
    private void showModelUpdateDialog(JSONObject recommendedModel) {
        try {
            String modelName = recommendedModel.optString("name", "Unknown");
            String message = getString(R.string.ml_model_update_available_message, modelName);

            AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog));
            builder.setTitle(R.string.ml_model_update_available_title);
            builder.setMessage(message);
            builder.setCancelable(true);

            // Download Now button
            builder.setPositiveButton(R.string.ml_model_update_download_button, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Log.i(TAG, "User chose to download new model");
                    downloadModelUpdate(recommendedModel);
                }
            });

            // Remind Me Later button
            builder.setNeutralButton(R.string.ml_model_update_later_button, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Log.i(TAG, "User chose to be reminded later");
                    dialog.dismiss();
                }
            });

            // Skip This Version button
            builder.setNegativeButton(R.string.ml_model_update_skip_button, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Log.i(TAG, "User chose to skip this model version");
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SdServer.this);
                    prefs.edit().putString("SkippedModelName", recommendedModel.optString("name")).apply();
                    dialog.dismiss();
                }
            });

            AlertDialog dialog = builder.create();
            // Make the dialog show over other apps (required for background service)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                dialog.getWindow().setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "showModelUpdateDialog(): Error showing dialog: " + e.getMessage());
        }
    }

    /**
     * Download and install a new ML model
     */
    private void downloadModelUpdate(JSONObject modelInfo) {
        try {
            String inputFmtStr = modelInfo.optString("input_format", "1d_mag");
            int inputSize = modelInfo.optInt("input_size", 125);
            String framework = modelInfo.optString("framework", "tflite");

            mMlModelManager.downloadAndInstallModel(modelInfo, (ok, file) -> {
                if (!ok || file == null) {
                    Log.e(TAG, "downloadModelUpdate(): Failed to download model");
                    mUtil.showToast(getString(R.string.ml_model_download_failed));
                    return;
                }

                // Store the model details in shared preferences
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit()
                        .putString("CnnModelFile", file.getAbsolutePath())
                        .putString("CnnModelName", modelInfo.optString("name"))
                        .putString("CnnInputFormatStr", inputFmtStr)
                        .putInt("CnnInputSize", inputSize)
                        .putString("CnnFramework", framework)
                        .putString("CnnModelId", modelInfo.optString("name"))
                        .remove("SkippedModelName")  // Clear any skipped version
                        .apply();

                Log.i(TAG, "downloadModelUpdate(): Model downloaded and installed successfully");
                mUtil.showToast(getString(R.string.ml_model_download_success));
            });

        } catch (Exception e) {
            Log.e(TAG, "downloadModelUpdate(): Error: " + e.getMessage());
            mUtil.showToast(getString(R.string.ml_model_download_failed));
        }
    }

    /**
     * Periodically check if a new recommended ML model is available
     */
    private class CheckModelUpdateTimer extends CountDownTimer {
        public boolean mIsRunning = true;

        public CheckModelUpdateTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onFinish() {
            Log.v(TAG, "CheckModelUpdateTimer.onFinish()");
            checkForModelUpdate();
            if (mIsRunning) {
                // Restart this timer.
                Log.v(TAG, "CheckModelUpdateTimer.onFinish() - mIsRunning is true, so re-starting timer");
                start();
            }
        }

        @Override
        public void onTick(long msRemaining) {
        }
    }

    /**
     * Watchdog thread to detect silent failures
     * Runs in background and checks if data is still being received
     * If no data for 2 minutes, forces a fault condition
     */
    private class WatchdogThread extends Thread {
        private volatile boolean mRunning = true;

        public void shutdown() {
            mRunning = false;
            interrupt();
        }

        @Override
        public void run() {
            Log.i(TAG, "WatchdogThread: Starting watchdog");
            Log.i(TAG, "WATCHDOG: Thread started");

            while (mRunning && !mIsDestroying) {
                try {
                    Thread.sleep(30000); // Check every 30 seconds

                    long now = System.currentTimeMillis();
                    long lastDataMillis = mHasReceivedData ? mLastDataReceivedMillis : mServiceStartMillis;
                    long timeSinceLastData = now - lastDataMillis;
                    long timeSinceLastHeartbeat = now - mLastWatchdogHeartbeatMillis;
                    long timeSinceLastFault = now - mLastWatchdogFaultMillis;

                    // Log heartbeat every 60 seconds
                    if (timeSinceLastHeartbeat > 60000) {
                        String threadState = "UNKNOWN";
                        Thread mainThread = Looper.getMainLooper().getThread();
                        if (mainThread != null) {
                            threadState = mainThread.getState().toString();
                        }

                        String heartbeatMsg = String.format(
                            "alive, lastData=%ds ago, mainThread=%s, faultTimer=%s",
                            timeSinceLastData/1000, threadState,
                            (mFaultTimer != null ? "running" : "stopped")
                        );

                        Log.v(TAG, "WATCHDOG_HB: " + heartbeatMsg);

                        // Log memory status alongside heartbeat for trend analysis
                        mUtil.writeMemoryLog("Watchdog heartbeat");

                        mLastWatchdogHeartbeatMillis = now;
                    }

                    // Check for data timeout (2 minutes with no data)
                    if (lastDataMillis > 0 && timeSinceLastData > 120000 && timeSinceLastFault > 120000) {
                        Log.e(TAG, "WATCHDOG: NO DATA FOR " + (timeSinceLastData/1000) + " SECONDS - FORCING FAULT");
                        Log.v(TAG, String.format(
                            "WATCHDOG_FAULT: No data for %d seconds - forcing fault state",
                            timeSinceLastData/1000
                        ));

                        // Force fault on main thread
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                SdData faultData = mSdData;
                                if (faultData == null) {
                                    faultData = new SdData();
                                }
                                faultData.alarmState = AlarmState.FAULT; // Fault
                                faultData.alarmPhrase = "WATCHDOG: No Data";
                                onSdDataFault(faultData);
                            }
                        });

                        // Reset timer to avoid rapid re-triggering
                        mLastDataReceivedMillis = now;
                        mHasReceivedData = true;
                    }

                } catch (InterruptedException e) {
                    if (mRunning) {
                        Log.w(TAG, "WatchdogThread: Interrupted");
                        Log.i(TAG, "WATCHDOG: Thread interrupted");
                    }
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "WatchdogThread: Exception in watchdog loop", e);
                    mUtil.writeExceptionLog("WatchdogThread", "run", e);
                }
            }

            Log.i(TAG, "WatchdogThread: Exiting");
            Log.i(TAG, "WATCHDOG: Thread exiting");
        }
    }
}
