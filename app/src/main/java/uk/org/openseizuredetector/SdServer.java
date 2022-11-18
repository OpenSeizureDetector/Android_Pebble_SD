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

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.telephony.SmsManager;
import android.text.format.Time;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationCompat;

import com.rohitss.uceh.UCEHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Timer;

/**
 * Based on example at:
 * http://stackoverflow.com/questions/14309256/using-nanohttpd-in-android
 * and
 * http://developer.android.com/guide/components/services.html#ExtendingService
 */
public class SdServer extends Service implements SdDataReceiver {
    private String mUuidStr = "0f675b21-5a36-4fe7-9761-fd0c691651f3";  // UUID to Identify OSD.

    // Notification ID
    private final int NOTIFICATION_ID = 1;
    private final int EVENT_NOTIFICATION_ID = 2;
    private final int DATASHARE_NOTIFICATION_ID = 3;
    private String mNotChId = "OSD Notification Channel";
    private CharSequence mNotChName = "OSD Notification Channel";
    private String mNotChDesc = "OSD Notification Channel Description";
    private String mEventNotChId = "OSD Event Notification Channel";
    private CharSequence mEventNotChName = "OSD Event Notification Channel";
    private String mEventNotChDesc = "OSD Event Notification Channel Description";

    private NotificationManager mNM;
    private NotificationCompat.Builder mNotificationBuilder;
    private Notification mNotification;
    private SdWebServer webServer = null;
    private final static String TAG = "SdServer";
    private Timer dataLogTimer = null;
    private CancelAudibleTimer mCancelAudibleTimer = null;
    private int mCancelAudiblePeriod = 10;  // Cancel Audible Period in minutes
    private long mCancelAudibleTimeRemaining = 0;
    private FaultTimer mFaultTimer = null;
    private CheckEventsTimer mEventsTimer = null;
    private int mFaultTimerPeriod = 30;  // Fault Timer Period in sec
    private boolean mFaultTimerCompleted = false;

    private HandlerThread thread;
    private WakeLock mWakeLock = null;
    private LocationFinder mLocationFinder = null;
    public SdDataSource mSdDataSource;
    public SdData mSdData = null;
    public String mSdDataSourceName = "undefined";  // The name of the data soruce specified in the preferences.
    private boolean mLatchAlarms = false;
    private int mLatchAlarmPeriod = 0;
    private LatchAlarmTimer mLatchAlarmTimer = null;
    private boolean mCancelAudible = false;
    public boolean mAudibleAlarm = false;   // set to public because it is accessed by MainActivity
    private boolean mAudibleWarning = false;
    private boolean mAudibleFaultWarning = false;
    private boolean mMp3Alarm = false;
    private boolean mPhoneAlarm = false;
    private boolean mSMSAlarm = false;
    private String[] mSMSNumbers;
    private String mSMSMsgStr = "default SMS Message";
    public Time mSMSTime = null;  // last time we sent an SMS Alarm (limited to one per minute)
    public SmsTimer mSmsTimer = null;  // Timer to wait 10 seconds before sending an alert to give the user chance to cancel it.
    private AlertDialog.Builder mSMSAlertDialog;   // Dialog shown during countdown to sending SMS.

    // Data Logging Parameters
    private boolean mLogAlarms = true;
    public boolean mLogData = false;
    public boolean mLogDataRemote = false;
    public boolean mLogDataRemoteMobile = false;
    public boolean mLogNDA = false;

    private String mAuthToken = null;
    private long mEventsTimerPeriod = 60; // Number of seconds between checks to see if there are unvalidated remote events.
    private long mEventDuration = 120;   // event duration in seconds - uploads datapoints that cover this time range centred on the event time.
    public long mDataRetentionPeriod = 1; // Prunes the local db so it only retains data younger than this duration (in days)
    private long mRemoteLogPeriod = 6; // Period in seconds between uploads to the remote server.
    private long mAutoPrunePeriod = 3600;  // Prune the database every hour
    private boolean mAutoPruneDb;

    private String mOSDUrl = "";

    private OsdUtil mUtil;
    private Handler mHandler;
    private ToneGenerator mToneGenerator;

    private NetworkBroadcastReceiver mNetworkBroadcastReceiver;

    private final IBinder mBinder = new SdBinder();

    public LogManager mLm;

    /**
     * class to handle binding the MainApp activity to this service
     * so it can access mSdData.
     */
    public class SdBinder extends Binder {
        SdServer getService() {
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
        mHandler = new Handler();
        mSdData = new SdData();
        mToneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);

        mUtil = new OsdUtil(getApplicationContext(), mHandler);
        mUtil.writeToSysLogFile("SdServer.onCreate()");

        // Set our custom uncaught exception handler to report issues.
        //Thread.setDefaultUncaughtExceptionHandler(
        //        new OsdUncaughtExceptionHandler(SdServer.this));
        new UCEHandler.Builder(this)
                .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                .build();
        //int i = 5/0;  // Force exception to test handler.


        // Create a wake lock, but don't use it until the service is started.
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "OSD:WakeLock");
    }

    /**
     * onStartCommand - start the web server and the message loop for
     * communications with other processes.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand() - SdServer service starting");
        mUtil.writeToSysLogFile("SdServer.onStartCommand()");

        // Update preferences.
        Log.v(TAG, "onStartCommand() - calling updatePrefs()");
        updatePrefs();

        Log.v(TAG, "onStartCommand: Datasource =" + mSdDataSourceName + ", phoneAppVersion="+mUtil.getAppVersionName());
        mSdData.dataSourceName = mSdDataSourceName;
        mSdData.phoneAppVersion = mUtil.getAppVersionName();
        switch (mSdDataSourceName) {
            case "Pebble":
                Log.v(TAG, "Selecting Pebble DataSource");
                mUtil.writeToSysLogFile("SdServer.onStartCommand() - creating SdDataSourcePebble");
                mSdDataSource = new SdDataSourcePebble(this.getApplicationContext(), mHandler, this);
                break;
            case "AndroidWear":
                Log.v(TAG, "Selecting Android Wear DataSource");
                mUtil.writeToSysLogFile("SdServer.onStartCommand() - creating SdDataSourceAw");
                mSdDataSource = new SdDataSourceAw(this.getApplicationContext(), mHandler, this);
                break;
            case "Network":
                Log.v(TAG, "Selecting Network DataSource");
                mUtil.writeToSysLogFile("SdServer.onStartCommand() - creating SdDataSourceNetwork");
                mSdDataSource = new SdDataSourceNetwork(this.getApplicationContext(), mHandler, this);
                Log.i(TAG,"Disabling remote logging when using network data source");
                mLogDataRemote = false;
                break;
            case "Garmin":
                Log.v(TAG, "Selecting Garmin DataSource");
                mUtil.writeToSysLogFile("SdServer.onStartCommand() - creating SdDataSourceGarmin");
                mSdDataSource = new SdDataSourceGarmin(this.getApplicationContext(), mHandler, this);
                break;
            case "BLE":
                Log.v(TAG, "Selecting BLE DataSource");
                mUtil.writeToSysLogFile("SdServer.onStartCommand() - creating SdDataSourceBLE");
                mSdDataSource = new SdDataSourceBLE(this.getApplicationContext(), mHandler, this);
                break;
            case "Phone":
                Log.v(TAG, "Selecting Phone Sensor DataSource");
                mUtil.writeToSysLogFile("SdServer.onStartCommand() - creating SdDataSourcePhone");
                mSdDataSource = new SdDataSourcePhone(this.getApplicationContext(), mHandler, this);
                break;
            default:
                Log.e(TAG, "Datasource " + mSdDataSourceName + " not recognised - Defaulting to Phone");
                //mUtil.writeToSysLogFile("SdServer.onStartCommand() - Datasource " + mSdDataSourceName + " not recognised - exiting");
                mUtil.showToast(getString(R.string.DatasourceTitle) + " " + mSdDataSourceName + getString(R.string.DefaultingToPhoneMsg));
                mUtil.writeToSysLogFile("SdServer.onStartCommand() - creating SdDataSourcePhone");
                mSdDataSource = new SdDataSourcePhone(this.getApplicationContext(), mHandler, this);
        }

        // Create our log manager.
        mLm = new LogManager(this, mLogDataRemote, mLogDataRemoteMobile, mAuthToken, mEventDuration,
                mRemoteLogPeriod, mLogNDA ,mAutoPruneDb, mDataRetentionPeriod, mSdData);

        if (mSMSAlarm) {
            Log.v(TAG, "Creating LocationFinder");
            mLocationFinder = new LocationFinder(getApplicationContext());
        }
        mUtil.writeToSysLogFile("SdServer.onStartCommand() - starting SdDataSource");
        mSdDataSource.start();

        // Initialise Notification channel for API level 26 and over
        // from https://stackoverflow.com/questions/44443690/notificationcompat-with-api-26
        mNM = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationBuilder = new NotificationCompat.Builder(this, mNotChId);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(mNotChId,
                    mNotChName,
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(mNotChDesc);
            mNM.createNotificationChannel(channel);
        }


        // Display a notification icon in the status bar of the phone to
        // show the service is running.
        if (Build.VERSION.SDK_INT >= 26) {
            Log.v(TAG, "showing Notification and calling startForeground (Android 8 and higher)");
            mUtil.writeToSysLogFile("SdServer.onStartCommand() - showing Notification and calling startForeground (Android 8 and higher)");
            showNotification(0);
            startForeground(NOTIFICATION_ID, mNotification);
        } else {
            Log.v(TAG, "showing Notification");
            mUtil.writeToSysLogFile("SdServer.onStartCommand() - showing Notification");
            showNotification(0);
        }
        // Record last time we sent an SMS so we can limit rate of SMS
        // sending to one per minute.   We set it to one minute ago (60000 milliseconds)
        mSMSTime = new Time(Time.getCurrentTimezone());
        mSMSTime.set(mSMSTime.toMillis(false) - 60000);


        // Start timer to log data regularly..
        if (dataLogTimer == null) {
            Log.v(TAG, "onStartCommand(): starting dataLog timer");
            mUtil.writeToSysLogFile("SdServer.onStartCommand() - starting dataLog timer");
            /*dataLogTimer = new Timer();
            dataLogTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Log.v(TAG,"dataLogTimer.run()");
                    logData();
                }
            }, 0, 1000 * 60);
            */
        } else {
            Log.v(TAG, "onStartCommand(): dataLog timer already running.");
            mUtil.writeToSysLogFile("SdServer.onStartCommand() - dataLog timer already running???");
        }

        if (mLogDataRemote) {
            startEventsTimer();
        }


        // Start the web server
        mUtil.writeToSysLogFile("SdServer.onStartCommand() - starting web server");
        startWebServer();

        // Apply the wake-lock to prevent CPU sleeping (very battery intensive!)
        if (mWakeLock != null) {
            mWakeLock.acquire();
            Log.v(TAG, "Applied Wake Lock to prevent device sleeping");
            mUtil.writeToSysLogFile("SdServer.onStartCommand() - applying wake lock");
        } else {
            Log.d(TAG, "mmm...mWakeLock is null, so not aquiring lock.  This shouldn't happen!");
            mUtil.writeToSysLogFile("SdServer.onStartCommand() - mWakeLock is not null - this shouldn't happen???");
        }

        checkEvents();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy(): SdServer Service stopping");
        mUtil.writeToSysLogFile("SdServer.onDestroy() - releasing wakelock");
        // release the wake lock to allow CPU to sleep and reduce
        // battery drain.
        if (mWakeLock != null) {
            try {
                mWakeLock.release();
                Log.d(TAG, "Released Wake Lock to allow device to sleep.");
            } catch (Exception e) {
                Log.e(TAG, "Error Releasing Wakelock - " + e.toString());
                mUtil.writeToSysLogFile("SdServer.onDestroy() - Error releasing wakelock.");
                mUtil.showToast(getString(R.string.ErrorReleasingWakelockMsg));
            }
        } else {
            Log.d(TAG, "mmm...mWakeLock is null, so not releasing lock.  This shouldn't happen!");
            mUtil.writeToSysLogFile("SdServer.onDestroy() - mWakeLock is null so not releasing lock - this Shouldn't happen???");
        }

        if (mSdDataSource != null) {
            Log.d(TAG, "stopping mSdDataSource");
            mUtil.writeToSysLogFile("SdServer.onDestroy() - stopping mSdDataSource");
            mSdDataSource.stop();
        } else {
            Log.e(TAG, "ERROR - mSdDataSource is null - why????");
            mUtil.writeToSysLogFile("SdServer.onDestroy() - mSdDataSource is null - why???");
        }

        // Stop the Cancel Audible timer
        if (mCancelAudibleTimer != null) {
            Log.d(TAG, "onDestroy(): cancelling Cancel_Audible timer");
            mCancelAudibleTimer.cancel();
            //mCancelAudibleTimer.purge();
            mCancelAudibleTimer = null;
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

        if (mLm != null) {
            Log.d(TAG, "Closing Down Log Manager");
            mLm.stop();
            mLm.close();
        }

        try {
            // Stop web server
            Log.d(TAG, "onDestroy(): stopping web server");
            mUtil.writeToSysLogFile("SdServer.onDestroy() - stopping Web Server");
            stopWebServer();

            mUtil.writeToSysLogFile("SdServer.onDestroy() - releasing mToneGenerator");
            mToneGenerator.release();
            mToneGenerator = null;

            this.stopForeground(true);
            // Cancel the notification.
            Log.d(TAG, "onDestroy(): cancelling notification");
            mUtil.writeToSysLogFile("SdServer.onDestroy - cancelling notification");
            mNM.cancel(NOTIFICATION_ID);
            mNM.cancel(EVENT_NOTIFICATION_ID);
            mNM.cancel(DATASHARE_NOTIFICATION_ID);


            // stop this service.
            Log.d(TAG, "onDestroy(): calling stopSelf()");
            mUtil.writeToSysLogFile("SdServer.onDestroy() - stopping self");
            stopSelf();

        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy() - " + e.toString());
            mUtil.writeToSysLogFile("SdServer.onDestroy() -error " + e.toString());
        }



        super.onDestroy();

    }


    /**
     * Show a notification while this service is running.
     */
    private void showNotification(int alarmLevel) {
        Log.v(TAG, "showNotification() - alarmLevel=" + alarmLevel);
        int iconId;
        String titleStr;
        Uri soundUri = null;
        switch (alarmLevel) {
            case 0:
                iconId = R.drawable.star_of_life_24x24;
                titleStr = getString(R.string.okBtnTxt);
                soundUri = null;
                break;
            case 1:
                iconId = R.drawable.star_of_life_yellow_24x24;
                titleStr = getString(R.string.Warning);
                if (mAudibleWarning)
                    soundUri = Uri.parse("android.resource://" + getPackageName() + "/raw/warning");
                break;
            case 2:
                iconId = R.drawable.star_of_life_red_24x24;
                titleStr = getString(R.string.Alarm);
                if (mAudibleAlarm)
                    soundUri = Uri.parse("android.resource://" + getPackageName() + "/raw/alarm");
                break;
            case -1:
                iconId = R.drawable.star_of_life_fault_24x24;
                titleStr = getString(R.string.Fault);
                if (mAudibleFaultWarning)
                    soundUri = Uri.parse("android.resource://" + getPackageName() + "/raw/fault");
                break;
            default:
                iconId = R.drawable.star_of_life_24x24;
                soundUri = null;
                titleStr = getString(R.string.okBtnTxt);
        }

        if (mCancelAudible) {
            Log.v(TAG, "ShowNotification - Not beeping because mCancelAudible set");
            soundUri = null;
        }

        Intent i = new Intent(getApplicationContext(), MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent =
                PendingIntent.getActivity(this,
                        0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        String smsStr;
        if (mSMSAlarm) {
            smsStr = getString(R.string.sms_location_alarm_active);
        } else {
            smsStr = getString(R.string.sms_location_alarm_disabled);
        }
        if (mPhoneAlarm) {
            smsStr = "Phone Call Alarm Active";
        }
        if (mNotificationBuilder != null) {
            mNotification = mNotificationBuilder.setContentIntent(contentIntent)
                    .setSmallIcon(iconId)
                    .setColor(0x00ffffff)
                    .setAutoCancel(false)
                    .setContentTitle(titleStr)
                    .setContentText(smsStr)
                    .setOnlyAlertOnce(true)
                    .build();
            if (mMp3Alarm) {
                if (soundUri != null) {
                    Log.v(TAG, "showNotification - setting Notification Sound to " + soundUri.toString());
                    mNotificationBuilder.setSound(soundUri);
                }
            }
            mNM.notify(NOTIFICATION_ID, mNotification);
        } else {
            Log.i(TAG, "showNotification() - notification builder is null, so not showing notification.");
        }
    }

    // Show the main activity on the user's screen.
    private void showMainActivity() {
        Log.i(TAG, "showMainActivity()");
        mUtil.writeToSysLogFile("SdServer.showMainActivity()");

        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> runningTaskInfo = manager.getRunningTasks(1);
        ComponentName componentInfo = runningTaskInfo.get(0).topActivity;

        if (componentInfo.getPackageName().equals("uk.org.openseizuredetector")) {
            Log.i(TAG, "showMainActivity(): OpenSeizureDetector Activity is already shown on top - not doing anything");
            mUtil.writeToSysLogFile("SdServer.showMainActivity - Activity is already shown on top, not doing anything");
        } else {
            Log.i(TAG, "showMainActivity(): Showing Main Activity");
            Intent i = new Intent(getApplicationContext(), MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            this.startActivity(i);
        }
    }

    public void raiseManualAlarm() {
        Log.d(TAG, "raiseManualAlarm()");
        SdData sdData = mSdData;
        sdData.alarmState = 5;
        onSdDataReceived(sdData);
    }

    /**
     * Process the data received from the SdData source.  On exit, the mSdData structure is populated with
     * the appropriate data.
     *
     * @param sdData
     */
    public void onSdDataReceived(SdData sdData) {
        Log.v(TAG, "onSdDataReceived() - " + sdData.toString());
        Log.v(TAG, "onSdDataReceived(), sdData.fallAlarmStanding=" + sdData.fallAlarmStanding);

        if (sdData.alarmState == 0) {
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
        if (sdData.alarmState == 6) {
            sdData.alarmPhrase = "MUTE";
            sdData.alarmStanding = false;
            sdData.fallAlarmStanding = false;
            showNotification(0);
        }
        // Handle warning alarm state
        if (sdData.alarmState == 1) {
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
        // respond to normal alarms (2) and manual alarms (5)
        if ((sdData.alarmState == 2) || (sdData.alarmState == 5)) {
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
            showMainActivity();
            // Send SMS Alarm.
            if (mSMSAlarm) {
                Time tnow = new Time(Time.getCurrentTimezone());
                tnow.setToNow();
                // limit SMS alarms to one per minute
                if ((tnow.toMillis(false)
                        - mSMSTime.toMillis(false))
                        > 60000) {
                    sendSMSAlarm();
                    sendPhoneAlarm();
                    mSMSTime = tnow;
                } else {
                    mUtil.showToast(getString(R.string.SMSAlarmAlreadySentMsg));
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                mUtil.showToast(getString(R.string.SMSAlarmDisabledNotSendingMsg));
                Log.v(TAG, "mSMSAlarm is false - not sending");
            }

            startLatchTimer();
        }
        // Handle fall alarm
        Log.v(TAG, "sdData.fallAlarmStanding=" + sdData.fallAlarmStanding);
        if ((sdData.alarmState == 3) || (sdData.fallAlarmStanding)) {
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
            showMainActivity();
            // Send SMS Alarm.
            if (mSMSAlarm) {
                Time tnow = new Time(Time.getCurrentTimezone());
                tnow.setToNow();
                // limit SMS alarms to one per minute
                if ((tnow.toMillis(false)
                        - mSMSTime.toMillis(false))
                        > 60000) {
                    sendSMSAlarm();
                    mSMSTime = tnow;
                } else {
                    mUtil.showToast(getString(R.string.SMSAlarmAlreadySentMsg));
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                mUtil.showToast("mSMSAlarm is false - not sending");
                Log.v(TAG, "mSMSAlarm is false - not sending");
            }

        }
        // Handle heart rate alarm
        if ((sdData.mHRAlarmActive) && (sdData.mHRAlarmStanding)) {
            sdData.alarmPhrase = "HR ABNORMAL";
            if (mLogAlarms) {
                Log.v(TAG, "***HEART RATE*** - Logging to SD Card");
                //writeAlarmToSD();
            } else {
                Log.v(TAG, "***HEART RATE***");
            }
            // Make alarm beep tone
            alarmBeep();
            showNotification(2);
            // Display MainActvity
            showMainActivity();
            // Send SMS Alarm.
            if (mSMSAlarm) {
                Time tnow = new Time(Time.getCurrentTimezone());
                tnow.setToNow();
                // limit SMS alarms to one per minute
                if ((tnow.toMillis(false)
                        - mSMSTime.toMillis(false))
                        > 60000) {
                    sendSMSAlarm();
                    mSMSTime = tnow;
                } else {
                    mUtil.showToast("SMS Alarm already sent - not re-sending");
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                mUtil.showToast(getString(R.string.SMSAlarmDisabledNotSendingMsg));
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
            showMainActivity();
            // Send SMS Alarm.
            if (mSMSAlarm) {
                Time tnow = new Time(Time.getCurrentTimezone());
                tnow.setToNow();
                // limit SMS alarms to one per minute
                if ((tnow.toMillis(false)
                        - mSMSTime.toMillis(false))
                        > 60000) {
                    sendSMSAlarm();
                    mSMSTime = tnow;
                } else {
                    mUtil.showToast("SMS Alarm already sent - not re-sending");
                    Log.v(TAG, "SMS Alarm already sent - not re-sending");
                }
            } else {
                mUtil.showToast(getString(R.string.SMSAlarmDisabledNotSendingMsg));
                Log.v(TAG, "mSMSAlarm is false - not sending");
            }
        }


        // Fault
        if ((sdData.alarmState) == 4 || (sdData.alarmState == 7) || (sdData.mHRFaultStanding)) {
            sdData.alarmPhrase = "FAULT";
            //writeAlarmToSD();
            faultWarningBeep();
            showNotification(-1);
        } else {
            stopFaultTimer();
        }
        mSdData = sdData;
        mSdData.dataSourceName = mSdDataSourceName;
        mSdData.phoneAppVersion = mUtil.getAppVersionName();

        if (webServer != null) webServer.setSdData(mSdData);
        Log.v(TAG, "onSdDataReceived() - setting mSdData to " + mSdData.toString());
        mLm.updateSdData(mSdData);

        logData();
    }


    // Called by SdDataSource when a fault condition is detected.
    public void onSdDataFault(SdData sdData) {
        Log.v(TAG, "onSdDataFault()");
        mSdData = sdData;
        mSdData.alarmState = 4;  // set fault alarm state.
        mSdData.alarmPhrase = "FAULT";
        mSdData.alarmStanding = false;
        if (webServer != null) webServer.setSdData(mSdData);
        // We only take action to warn the user and re-start the data source to attempt to fix it
        // ourselves if we have been in a fault condition for a while - signified by the mFaultTimerCompleted
        // flag.
        if (mFaultTimerCompleted) {
            faultWarningBeep();
            //mSdDataSource.stop();
            //mHandler.postDelayed(new Runnable() {
            //    public void run() {
            //        mSdDataSource.start();
            //    }
            //}, 190);
        } else {
            startFaultTimer();
            Log.v(TAG, "onSdDataFault() - starting Fault Timer");
            mUtil.writeToSysLogFile("onSdDataFault() - starting Fault Timer");
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
            mUtil.writeToSysLogFile("SdServer.beep() - mToneGenerator is null???");
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
                    Log.v(TAG, "Not making MP3 fault beep - handled by notification");
                } else {
                    beep(10);
                }
                Log.v(TAG, "faultWarningBeep()");
                mUtil.writeToSysLogFile("SdServer.faultWarningBeep() - beeping");
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
                    Log.v(TAG, "Not making MP3 alarm beep - handled by ShowNotification");
                } else {
                    beep(3000);
                }
                Log.v(TAG, "alarmBeep()");
                mUtil.writeToSysLogFile("SdServer.alarmBeep() - beeping");
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
                    Log.v(TAG, "not making MP3 alarm beep - handled by showNotification");
                } else {
                    beep(100);
                }
                Log.v(TAG, "warningBeep()");
                mUtil.writeToSysLogFile("SdServer.warningBeep() - beeping");
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
                mUtil.showToast(getString(R.string.cancel_audible_not_sending_sms));
            }
        } else {
            Log.i(TAG, "sendSMSAlarm() - SMS Alarms Disabled - not doing anything!");
            mUtil.showToast(getString(R.string.sms_alarms_disabled));
        }
        if (mPhoneAlarm) {
            if (!mCancelAudible) {
                Log.i(TAG, "sendSMSAlarm() - Sending Phone Alarm Broadcast");
                sendPhoneAlarm();
            } else {
                Log.i(TAG, "sendSMSAlarm() - Cancel Audible Active - not making Phone Call");
                mUtil.showToast(getString(R.string.cancel_audible_not_sending_sms));
            }
        } else {
            Log.i(TAG, "sendSMSAlarm() - Phone Alarms Disabled - not doing anything!");
            //mUtil.showToast(getString(R.string.phone_alarm_disabled));
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
                        new SmsTimer(10 * 1000, 1000);
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
                Log.v(TAG, "startLatchTimer -timer already running - cancelling it");
                mLatchAlarmTimer.cancel();
                mLatchAlarmTimer = null;
            }
            Log.v(TAG, "startLatchTimer() - starting alarm latch release timer to time out in " + mLatchAlarmPeriod + " sec");
            // set timer to timeout after mLatchAlarmPeriod, and Tick() function to be called every second.
            mLatchAlarmTimer =
                    new LatchAlarmTimer(mLatchAlarmPeriod * 1000, 1000);
            mLatchAlarmTimer.start();
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
        mUtil.writeToSysLogFile("SdServer.Start Web Server.");
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

        mNetworkBroadcastReceiver = new NetworkBroadcastReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        //filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        getApplicationContext().registerReceiver(mNetworkBroadcastReceiver, filter);
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
                mUtil.writeToSysLogFile("stopWebServer() - server null - server died ok");
                Log.v(TAG, "stopWebServer() - server null - server died ok");
            } else {
                if (webServer.isAlive()) {
                    Log.w(TAG, "stopWebServer() - server still alive???");
                    mUtil.writeToSysLogFile("stopWebServer() - server still alive???");
                } else {
                    mUtil.writeToSysLogFile("stopWebServer() - server died ok");
                    Log.v(TAG, "stopWebServer() - server died ok");
                }
            }
            //webServer = null;
        }
        mUtil.writeToSysLogFile("unregisterig network broadcast receiver");
        Log.v(TAG, "unregistering network broadcast receiver");
        getApplicationContext().unregisterReceiver(mNetworkBroadcastReceiver);
    }

    private class NetworkBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(TAG, "NetworkBroadCastReceiver.onReceive");
            mUtil.writeToSysLogFile("NetworkBroadcastReceiver.onReceive(): Network State Changed" + intent.getAction());
            //mUtil.showToast("Network State Changed" + intent.getAction());

            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            NetworkInfo activeNetwork = null;
            try {
                activeNetwork = cm.getActiveNetworkInfo();
            } catch (Exception e) {
                Log.e(TAG, "NetworkBroadcastReceiver - failed to retrieve active network info");
                mUtil.writeToSysLogFile("NetworkBroadcastReceiver - failed to retrieve active network info");
                Log.e(TAG, e.toString());
            }
            boolean isConnected = activeNetwork != null &&
                    activeNetwork.isConnectedOrConnecting();
            if (isConnected) {
                boolean isWiFi = activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
                if (!isWiFi) {
                    Log.v(TAG, "NetworkBroadcastReceiver - no Wifi Connection");
                    mUtil.writeToSysLogFile("Network State Changed - no Wifi Connection");
                    mUtil.showToast(getString(R.string.no_wifi_connection));
                } else {
                    Log.v(TAG, "NetworkBroadcastReceiver - Wifi Connected");
                    mUtil.writeToSysLogFile("Network State Changed - Wifi Connected");
                    //mUtil.showToast("Network State Changed - Wifi Connected");
                }
            } else {
                Log.v(TAG, "NetworkBroadcastReceiver - No Active Network");
                mUtil.writeToSysLogFile("Network State Changed - No Active Network");
                mUtil.showToast(getString(R.string.no_active_network));
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
                mLm.writeDatapointToLocalDb(mSdData);
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
        Log.i(TAG, "updatePrefs()");
        mUtil.writeToSysLogFile("SdServer.updatePrefs()");

        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        try {
            mSdDataSourceName = SP.getString("DataSource", "Pebble");
            Log.v(TAG, "updatePrefs() - DataSource = " + mSdDataSourceName);
            mUtil.writeToSysLogFile("updatePrefs() - DataSource = " + mSdDataSourceName);
            mLatchAlarms = SP.getBoolean("LatchAlarms", false);
            Log.v(TAG, "updatePrefs() - mLatchAlarms = " + mLatchAlarms);
            mUtil.writeToSysLogFile("updatePrefs() - mLatchAlarms = " + mLatchAlarms);
            // Parse the LatchAlarmPeriod setting.
            try {
                String latchAlarmPeriodStr = SP.getString("LatchAlarmTimerPeriod", "30");
                mLatchAlarmPeriod = Integer.parseInt(latchAlarmPeriodStr);
                Log.v(TAG, "updatePrefs() - mLatchAlarmTimerPeriod = " + mLatchAlarmPeriod);
                mUtil.writeToSysLogFile("updatePrefs() - mLatchAlarmTimerPeriod = " + mLatchAlarmPeriod);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with LatchAlarmTimerPeriod preference!");
                mUtil.writeToSysLogFile("updatePrefs() - Problem with LatchAlarmTimerPeriod preference!");
                mUtil.showToast(getString(R.string.problem_parsing_preferences));
            }
            mAudibleFaultWarning = SP.getBoolean("AudibleFaultWarning", true);
            Log.v(TAG, "updatePrefs() - mAuidbleFaultWarning = " + mAudibleFaultWarning);
            mUtil.writeToSysLogFile("updatePrefs() - mAuidbleFaultWarning = " + mAudibleFaultWarning);
            // Parse the faultTimer period setting.
            try {
                String faultTimerPeriodStr = SP.getString("FaultTimerPeriod", "30");
                mFaultTimerPeriod = Integer.parseInt(faultTimerPeriodStr);
                Log.v(TAG, "updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
                mUtil.writeToSysLogFile("updatePrefs() - mFaultTimerPeriod = " + mFaultTimerPeriod);
            } catch (Exception ex) {
                Log.v(TAG, "updatePrefs() - Problem with FaultTimerPeriod preference!");
                mUtil.writeToSysLogFile("updatePrefs() - Problem with FaultTimerPeriod preference!");
                mUtil.showToast(getString(R.string.problem_parsing_preferences));
            }

            mAudibleAlarm = SP.getBoolean("AudibleAlarm", true);
            Log.v(TAG, "updatePrefs() - mAuidbleAlarm = " + mAudibleAlarm);
            mUtil.writeToSysLogFile("updatePrefs() - mAuidbleAlarm = " + mAudibleAlarm);
            mAudibleWarning = SP.getBoolean("AudibleWarning", true);
            Log.v(TAG, "updatePrefs() - mAuidbleWarning = " + mAudibleWarning);
            mUtil.writeToSysLogFile("updatePrefs() - mAuidbleWarning = " + mAudibleWarning);
            mMp3Alarm = SP.getBoolean("UseMp3Alarm", false);
            Log.v(TAG, "updatePrefs() - mMp3Alarm = " + mMp3Alarm);
            mUtil.writeToSysLogFile("updatePrefs() - mMp3Alarm = " + mMp3Alarm);

            mSMSAlarm = SP.getBoolean("SMSAlarm", false);
            Log.v(TAG, "updatePrefs() - mSMSAlarm = " + mSMSAlarm);
            mUtil.writeToSysLogFile("updatePrefs() - mSMSAlarm = " + mSMSAlarm);
            mPhoneAlarm = SP.getBoolean("PhoneCallAlarm", false);
            Log.v(TAG, "updatePrefs() - mSMSAlarm = " + mSMSAlarm);
            mUtil.writeToSysLogFile("updatePrefs() - mSMSAlarm = " + mSMSAlarm);
            String SMSNumberStr = SP.getString("SMSNumbers", "");
            mSMSNumbers = SMSNumberStr.split(",");
            mSMSMsgStr = SP.getString("SMSMsg", "Seizure Detected!!!");
            Log.v(TAG, "updatePrefs() - SMSNumberStr = " + SMSNumberStr);
            mUtil.writeToSysLogFile("updatePrefs() - SMSNumberStr = " + SMSNumberStr);
            Log.v(TAG, "updatePrefs() - mSMSNumbers = " + mSMSNumbers);
            mUtil.writeToSysLogFile("updatePrefs() - mSMSNumbers = " + mSMSNumbers);
            mLogAlarms = SP.getBoolean("LogAlarms", true);
            Log.v(TAG, "updatePrefs() - mLogAlarms = " + mLogAlarms);
            mUtil.writeToSysLogFile("updatePrefs() - mLogAlarms = " + mLogAlarms);
            //mLogData = SP.getBoolean("LogData", true);
            mLogData = true;
            Log.v(TAG, "SdServer.updatePrefs() - mLogData = " + mLogData);
            mUtil.writeToSysLogFile("updatePrefs() - mLogData = " + mLogData);
            //mLogDataRemote = SP.getBoolean("LogDataRemote", false);
            mLogDataRemote = true;
            Log.v(TAG, "updatePrefs() - mLogDataRemote = " + mLogDataRemote);
            mUtil.writeToSysLogFile("updatePrefs() - mLogDataRemote = " + mLogDataRemote);
            mLogDataRemoteMobile = SP.getBoolean("LogDataRemoteMobile", false);
            Log.v(TAG, "updatePrefs() - mLogDataRemoteMobile = " + mLogDataRemoteMobile);
            mLogNDA = SP.getBoolean("LogNDA", false);
            Log.v(TAG, "updatePrefs() - mLogNDA = " + mLogNDA);
            mUtil.writeToSysLogFile("updatePrefs() - mLogDataRemoteMobile = " + mLogDataRemoteMobile);
            mAuthToken = SP.getString("webApiAuthToken", null);
            Log.v(TAG, "updatePrefs() - mAuthToken = " + mAuthToken);
            mUtil.writeToSysLogFile("updatePrefs() - mAuthToken = " + mAuthToken);

            String prefVal;
            prefVal = SP.getString("EventDurationSec", "300");
            mEventDuration = Integer.parseInt(prefVal);
            Log.v(TAG, "mEventDuration=" + mEventDuration);

            mAutoPruneDb = SP.getBoolean("AutoPruneDb", true);
            Log.v(TAG, "mAutoPruneDb=" + mAutoPruneDb);

            prefVal = SP.getString("DataRetentionPeriod", "28");
            mDataRetentionPeriod = Integer.parseInt(prefVal);
            Log.v(TAG, "mDataRetentionPeriod=" + mDataRetentionPeriod);

            //prefVal = SP.getString("RemoteLogPeriod", "60");
            //mRemoteLogPeriod = Integer.parseInt(prefVal);
            //mRemoteLogPeriod = 60;
            Log.v(TAG, "mRemoteLogPeriod=" + mRemoteLogPeriod);

            //mOSDUname = SP.getString("OSDUname", "<username>");
            //Log.v(TAG, "updatePrefs() - mOSDUname = " + mOSDUname);
            //mOSDPasswd = SP.getString("OSDPasswd", "<passwd>");
            //Log.v(TAG, "updatePrefs() - mOSDPasswd = " + mOSDPasswd);
            //mOSDWearerId = Integer.parseInt(SP.getString("OSDWearerId", "0"));
            //Log.v(TAG, "updatePrefs() - mOSDWearerId = " + mOSDWearerId);
            mOSDUrl = SP.getString("OSDUrl", "http://openseizuredetector.org.uk/webApi");
            Log.v(TAG, "updatePrefs() - mOSDUrl = " + mOSDUrl);
            mUtil.writeToSysLogFile("updatePrefs() - mOSDUrl = " + mOSDUrl);
        } catch (Exception ex) {
            Log.v(TAG, "updatePrefs() - Problem parsing preferences!");
            mUtil.writeToSysLogFile("SdServer.updatePrefs() - Error " + ex.toString());
            mUtil.showToast(getString(R.string.problem_parsing_preferences));
        }
    }


    public void sendPhoneAlarm() {
        /**
         * Use the separate OpenSeizureDetector Dialler app to generate a phone call alarm to the numbers selected for SMS Alarms.
         */
        Log.v(TAG, "sendPhoneAlarm() - sending broadcast intent");
        Intent intent = new Intent();
        intent.setAction("uk.org.openseizuredetector.dialler.ALARM");
        intent.putExtra("NUMBERS", mSMSNumbers);
        sendBroadcast(intent);
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
            Log.v(TAG, "SmsTimer.onFinish()");
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
                Log.e(TAG,"SmsTimer.onFinish - mLocationFinder is null - this should not happen!");
                mUtil.showToast("SmsTimer.onFinish - mLocationFinder is null - this should not happen! - Please report this issue!");
            }
            Log.i(TAG, "SmsTimer.onFinish() - Sending to " + mSMSNumbers.length + " Numbers");
            mUtil.writeToSysLogFile("SdServer.SmsTimer.onFinish()");
            Time tnow = new Time(Time.getCurrentTimezone());
            tnow.setToNow();
            String dateStr = tnow.format("%H:%M:%S %d/%m/%Y");
            String shortUuidStr = mUuidStr.substring(mUuidStr.length() - 6);

            // SmsManager sm = SmsManager.getDefault();
            for (int i = 0; i < mSMSNumbers.length; i++) {
                Log.i(TAG, "SmsTimer.onFinish() - Sending to " + mSMSNumbers[i]);
                sendSMS(new String(mSMSNumbers[i]), mSMSMsgStr + " - " + dateStr + " " + shortUuidStr);
            }
        }

        // Called every 'interval' ms.
        @Override
        public void onTick(long timeRemaining) {
            Log.v(TAG, "SmsTimer.onTick() - time remaining = " + timeRemaining / 1000 + " sec");
            // The MainActivity screen picks up mTimeLeft to update the screen.
            mTimeLeft = timeRemaining;
            alarmBeep();
        }

        /**
         * onSdLocationReceived - called with the best estimate location after mLocationReceiver times out.
         *
         */
        private void sendSMS(String phoneNo, String msgStr) {
            Log.i(TAG, "sendSMS() - Sending to " + phoneNo);
            try {
                SmsManager sm = SmsManager.getDefault();
                sm.sendTextMessage(phoneNo, null, msgStr,
                        null, null);
            } catch (Exception e) {
                Log.e(TAG, "sendSMS - Failed to send SMS Message");
                mUtil.writeToSysLogFile("sendSMS - Failed to send SMS Message");
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
                    mUtil.writeToSysLogFile("SdServer.sendSMSAlarm()");
                    Time tnow = new Time(Time.getCurrentTimezone());
                    tnow.setToNow();
                    String dateStr = tnow.format("%H:%M:%S %d/%m/%Y");
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
                    mUtil.showToast(messageStr);
                    for (int i = 0; i < mSMSNumbers.length; i++) {
                        Log.i(TAG, "onSdLocationReceived() - Sending to " + mSMSNumbers[i]);
                        sendSMS(new String(mSMSNumbers[i]), messageStr);
                    }
                } else {
                    Log.i(TAG, "sendSMSAlarm() - SMS Alarms Disabled - not doing anything!");
                    mUtil.showToast(getString(R.string.sms_alarms_disabled));
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
            Log.v(TAG, "LatchAlarmTimer.onFinish()");
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
            mUtil.writeToSysLogFile("startFaultTimer() - fault timer already running");
        } else {
            Log.v(TAG, "startFaultTimer(): starting fault timer.");
            mUtil.writeToSysLogFile("startFaultTimer() - starting fault timer");
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
            mUtil.writeToSysLogFile("stopFaultTimer() - stopping fault timer");
            mFaultTimer.cancel();
            mFaultTimer = null;
            mFaultTimerCompleted = false;
        } else {
            //Log.v(TAG, "stopFaultTimer(): fault timer not running - not doing anything.");
            //mUtil.writeToSysLogFile("stopFaultTimer() - fault timer not running");
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
            mFaultTimerCompleted = true;
            Log.v(TAG, "mFaultTimer - removing mFaultTimerRunning flag");
        }

        @Override
        public void onTick(long msRemaining) {
            mFaultTimerRemaining = msRemaining / 1000;
            Log.v(TAG, "mFaultTimer - onTick() - Time Remaining = "
                    + mFaultTimerRemaining);
        }

    }


    /**
     * Start the events timer.
     */
    public void startEventsTimer() {
        if (mEventsTimer != null) {
            Log.v(TAG, "startEventsTimer(): timer already running - not doing anything.");
            mUtil.writeToSysLogFile("startEventsTimer() - timer already running");
        } else {
            Log.v(TAG, "startEventsTimer(): starting timer.");
            mUtil.writeToSysLogFile("startEventsTimer() - starting timer");
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
            mUtil.writeToSysLogFile("stopEventsTimer() - stopping timer, setting mIsRunning to false");
            mEventsTimer.mIsRunning = false;
            mEventsTimer.cancel();
            //mEventsTimer = null;
        } else {
            Log.v(TAG, "stopEventsTimer(): timer not running - not doing anything.");
        }
    }


    private void checkEvents() {
        // Retrieve events from remote database
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
                        Log.v(TAG,"checkEvents() - showing event notification and cancelling datashare notification.");
                        showEventNotification();
                        mNM.cancel(DATASHARE_NOTIFICATION_ID);
                    } else {
                        Log.v(TAG,"checkEvents() - cancelling event and datashare notifications");
                        mNM.cancel(EVENT_NOTIFICATION_ID);
                        mNM.cancel(DATASHARE_NOTIFICATION_ID);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "CheckEventsTimer.onFinish(): Error Parsing remoteEventsObj: " + e.getMessage());
                    //mUtil.showToast("Error Parsing remoteEventsObj - this should not happen!!!");
                }
            }
        })) {
            Log.v(TAG, "CheckEventsTimer() - requested events");
        } else {
            Log.v(TAG, "CheckEventsTimer() - Not Logged In");
            mNM.cancel(EVENT_NOTIFICATION_ID);
            showDatashareNotification();
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
            Log.v(TAG, "CheckEventsTimer.onFinish()");
            checkEvents();
            if (mIsRunning) {
                // Restart this timer.
                Log.v(TAG, "CheckEventsTimer.onFinish() - mIsRunning is true, so re-starting timer");
                start();
            }
        }

        @Override
        public void onTick(long msRemaining) {
        }
    }

    /**
     * Show a notification to tell the user that we have unvalidated events.
     */
    private void showEventNotification() {
        Log.v(TAG, "showEventNotification()");
        int iconId;
        String titleStr;
        Uri soundUri = null;

        // Initialise Notification channel for API level 26 and over
        // from https://stackoverflow.com/questions/44443690/notificationcompat-with-api-26
        NotificationManager nM = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getApplicationContext(), mEventNotChId);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(mEventNotChId,
                    mEventNotChName,
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(mEventNotChDesc);
            nM.createNotificationChannel(channel);
        }

        iconId = R.drawable.datasharing_query_24x24;
        titleStr = getString(R.string.unvalidatedEventsTitle);

        Intent i = new Intent(getApplicationContext(), LogManagerControlActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.setAction("None");
        PendingIntent contentIntent =
                PendingIntent.getActivity(getApplicationContext(),
                        0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        String contentStr = getString(R.string.please_confirm_seizure_events);

        Notification notification = notificationBuilder.setContentIntent(contentIntent)
                .setSmallIcon(iconId)
                .setColor(0x00ffffff)
                .setAutoCancel(false)
                .setContentTitle(titleStr)
                .setContentText(contentStr)
                .setOnlyAlertOnce(true)
                .build();
        nM.notify(EVENT_NOTIFICATION_ID, notification);
    }


    /**
     * Show a notification asking the user to set-up data sharing.
     */
    private void showDatashareNotification() {
        Log.v(TAG, "showDatashareNotification()");
        int iconId;
        String titleStr;
        Uri soundUri = null;

        // Initialise Notification channel for API level 26 and over
        // from https://stackoverflow.com/questions/44443690/notificationcompat-with-api-26
        NotificationManager nM = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getApplicationContext(), mEventNotChId);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(mEventNotChId,
                    mEventNotChName,
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(mEventNotChDesc);
            nM.createNotificationChannel(channel);
        }

        iconId = R.drawable.datasharing_fault_24x24;
        titleStr = getString(R.string.datasharing_notification_title);

        Intent i = new Intent(getApplicationContext(), MainActivity.class);
        i.putExtra("action", "showDataSharingDialog");
        i.setAction("showDataSharingDialog");
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent =
                PendingIntent.getActivity(getApplicationContext(),
                        0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        Intent loginIntent = new Intent(getApplicationContext(), AuthenticateActivity.class);
        loginIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent loginPendingIntent =
                PendingIntent.getActivity(getApplicationContext(),
                        0, loginIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        String contentStr = getString(R.string.datasharing_notification_text);
        Notification notification = notificationBuilder
                .setContentIntent(contentIntent)
                .setSmallIcon(iconId)
                .setColor(0x00ffffff)
                .setAutoCancel(false)
                .setContentTitle(titleStr)
                .setContentText(contentStr)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.common_google_signin_btn_icon_dark, getString(R.string.login), loginPendingIntent)
                .setPriority(0)
                .build();
        nM.notify(DATASHARE_NOTIFICATION_ID, notification);
    }


}



