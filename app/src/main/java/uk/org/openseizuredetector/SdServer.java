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

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.support.v7.app.NotificationCompat;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import java.util.Timer;
import java.util.TimerTask;
import java.io.*;
import java.util.*;
import java.util.StringTokenizer;

import android.text.format.Time;

import org.json.JSONObject;
import org.json.JSONArray;



/**
 * Based on example at:
 * http://stackoverflow.com/questions/14309256/using-nanohttpd-in-android
 * and
 * http://developer.android.com/guide/components/services.html#ExtendingService
 */
public class SdServer extends Service implements SdDataReceiver {
    // Notification ID
    private int NOTIFICATION_ID = 1;

    private NotificationManager mNM;

    private SdWebServer webServer = null;
    private final static String TAG = "SdServer";
    private Timer dataLogTimer = null;
    private CancelAudibleTimer mCancelAudibleTimer = null;
    private int mCancelAudiblePeriod = 10;  // Cancel Audible Period in minutes
    private long mCancelAudibleTimeRemaining = 0;
    private HandlerThread thread;
    private WakeLock mWakeLock = null;
    public SdDataSource mSdDataSource;
    public SdData mSdData;
    private String mSdDataSourceName = "undefined";  // The name of the data soruce specified in the preferences.
    private boolean mLatchAlarms = false;
    private boolean mCancelAudible = false;
    public boolean mAudibleAlarm = false;
    private boolean mAudibleWarning = false;
    private boolean mAudibleFaultWarning = false;
    private boolean mSMSAlarm = false;
    private String[] mSMSNumbers;
    private String mSMSMsgStr = "default SMS Message";
    public Time mSMSTime = null;  // last time we sent an SMS Alarm (limited to one per minute)
    private boolean mLogAlarms = true;
    private boolean mLogData = false;
    private File mOutFile;
    private OsdUtil mUtil;

    private final IBinder mBinder = new SdBinder();

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
        mSdData = new SdData();
        Log.v(TAG, "SdServer Created");
    }


    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "sdServer.onBind()");
        return mBinder;
    }


    /**
     * onCreate() - called when services is created.  Starts message
     * handler process to listen for messages from other processes.
     */
    @Override
    public void onCreate() {
        Log.v(TAG, "onCreate()");

        mUtil = new OsdUtil(getApplicationContext());

        // Create a wake lock, but don't use it until the service is started.
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyWakelockTag");
    }

    /**
     * onStartCommand - start the web server and the message loop for
     * communications with other processes.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand() - SdServer service starting");

        // Update preferences.
        Log.v(TAG, "onStartCommand() - calling updatePrefs()");
        updatePrefs();

        Log.v(TAG, "onStartCommand: Datasource =" + mSdDataSourceName);
        switch (mSdDataSourceName) {
            case "Pebble":
                Log.v(TAG,"Selecting Pebble DataSource");
                mSdDataSource = new SdDataSourcePebble(this.getApplicationContext(), this);
                break;
            case "Network":
                Log.v(TAG, "Selecting Network DataSource");
                mSdDataSource = new SdDataSourceNetwork(this.getApplicationContext(),this);
                break;
            default:
                Log.v(TAG, "Datasource " + mSdDataSourceName + " not recognised - Exiting");
                mUtil.showToast("Datasource " + mSdDataSourceName + " not recognised - Exiting");
                return 1;
        }
        mSdDataSource.start();


        // Display a notification icon in the status bar of the phone to
        // show the service is running.
        Log.v(TAG, "showing Notification");
        showNotification();

        // Record last time we sent an SMS so we can limit rate of SMS
        // sending to one per minute.
        mSMSTime = new Time(Time.getCurrentTimezone());



        // Start timer to log data regularly..
        if (dataLogTimer == null) {
            Log.v(TAG, "onCreate(): starting dataLog timer");
            dataLogTimer = new Timer();
            dataLogTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    logData();
                }
            }, 0, 1000 * 60);
        } else {
            Log.v(TAG, "onCreate(): dataLog timer already running.");
        }


        // Start the web server
        startWebServer();

        // Apply the wake-lock to prevent CPU sleeping (very battery intensive!)
        if (mWakeLock != null) {
            mWakeLock.acquire();
            Log.v(TAG, "Applied Wake Lock to prevent device sleeping");
        } else {
            Log.d(TAG, "mmm...mWakeLock is null, so not aquiring lock.  This shouldn't happen!");
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.v(TAG, "onDestroy(): SdServer Service stopping");
        // release the wake lock to allow CPU to sleep and reduce
        // battery drain.
        if (mWakeLock != null) {
            try {
                mWakeLock.release();
                Log.v(TAG, "Released Wake Lock to allow device to sleep.");
            } catch (Exception e) {
                Log.e(TAG, "Error Releasing Wakelock - " + e.toString());
                mUtil.showToast("Error Releasing Wakelock");
            }
        } else {
            Log.d(TAG, "mmm...mWakeLock is null, so not releasing lock.  This shouldn't happen!");
        }

        mSdDataSource.stop();

        // Stop the data update timer
        if (mCancelAudibleTimer !=null) {
            Log.v(TAG,"stop(): cancelling Cancel_Audible timer");
            mCancelAudibleTimer.cancel();
            //mCancelAudibleTimer.purge();
            mCancelAudibleTimer = null;
        }


        try {
            // Cancel the notification.
            Log.v(TAG, "onDestroy(): cancelling notification");
            mNM.cancel(NOTIFICATION_ID);
            // Stop web server
            Log.v(TAG, "onDestroy(): stopping web server");
            stopWebServer();
            // stop this service.
            Log.v(TAG, "onDestroy(): calling stopSelf()");
            stopSelf();

        } catch (Exception e) {
            Log.v(TAG, "Error in onDestroy() - " + e.toString());
        }
    }


    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        Log.v(TAG, "showNotification()");
        Intent i = new Intent(getApplicationContext(),MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent = PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        Notification notification = builder.setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.star_of_life_24x24)
                .setTicker("OpenSeizureDetector")
                .setAutoCancel(false)
                .setContentTitle("OpenSeizureDetector")
                .setContentText("osdtext")
                .build();

        mNM = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNM.notify(NOTIFICATION_ID, notification);
    }


    /**
     * Process the data received from the SdData source.
     * @param sdData
     */
    public void onSdDataReceived(SdData sdData) {
        Log.v(TAG, "onSdDataReceived() - "+sdData.toString());
        if (sdData.alarmState == 0) {
            if ((!mLatchAlarms) ||
                    (mLatchAlarms &&
                            (!sdData.alarmStanding && !sdData.fallAlarmStanding))) {
                sdData.alarmPhrase = "OK";
                sdData.alarmStanding = false;
                sdData.fallAlarmStanding = false;
            }
        }
        if (sdData.alarmState == 1) {
            if ((!mLatchAlarms) ||
                    (mLatchAlarms &&
                            (!sdData.alarmStanding && !sdData.fallAlarmStanding))) {
                sdData.alarmPhrase = "WARNING";
                sdData.alarmStanding = false;
                sdData.fallAlarmStanding = false;
            }
            if (mLogAlarms) {
                Log.v(TAG, "WARNING - Logging to SD Card");
                writeAlarmToSD();
                logData();
            } else {
                Log.v(TAG, "WARNING");
            }
            warningBeep();
        }
        if ((sdData.alarmState == 2) || (sdData.alarmStanding)) {
            sdData.alarmPhrase = "ALARM";
            sdData.alarmStanding = true;
            if (mLogAlarms) {
                Log.v(TAG, "***ALARM*** - Logging to SD Card");
                writeAlarmToSD();
                logData();
            } else {
                Log.v(TAG, "***ALARM***");
            }
            // Make alarm beep tone
            alarmBeep();
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
                }
            }
        }
        if ((sdData.alarmState == 3) || (sdData.fallAlarmStanding)) {
            sdData.alarmPhrase = "FALL";
            sdData.fallAlarmStanding = true;
            if (mLogAlarms) {
                Log.v(TAG, "***FALL*** - Logging to SD Card");
                writeAlarmToSD();
                logData();
            } else {
                Log.v(TAG, "***FALL***");
            }
            // Make alarm beep tone
            alarmBeep();
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
                }
            }

        }
        // Fault
        if ((sdData.alarmState == 4)) {
            sdData.alarmPhrase = "FAULT";
            faultWarningBeep();
        }
        mSdData = sdData;
        if (webServer!=null) webServer.setSdData(mSdData);
        Log.v(TAG,"onSdDataReceived() - setting mSdData to "+mSdData.toString());
    }

    // Called by SdDataSource when a fault condition is detected.
    public void onSdDataFault(SdData sdData) {
        Log.v(TAG,"onSdDataFault()");
        mSdData = sdData;
        mSdData.alarmState = 4;  // set fault alarm state.
        if (webServer!=null) webServer.setSdData(mSdData);
        if (mAudibleFaultWarning) {
            faultWarningBeep();
        }
    }

    /* from http://stackoverflow.com/questions/12154940/how-to-make-a-beep-in-android */

    /**
     * beep for duration miliseconds, but only if mAudibleAlarm is set.
     */
    private void beep(int duration) {
        ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, duration);
        Log.v(TAG, "beep()");
    }

    /*
     * beep, provided mAudibleAlarm is set
     */
    public void faultWarningBeep() {
        if (mCancelAudible) {
            Log.v(TAG, "faultWarningBeep() - CancelAudible Active - silent beep...");
        } else {
            if (mAudibleFaultWarning) {
                beep(10);
                Log.v(TAG, "faultWarningBeep()");
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
                beep(1000);
                Log.v(TAG, "alarmBeep()");
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
                beep(100);
                Log.v(TAG, "warningBeep()");
            } else {
                Log.v(TAG, "warningBeep() - silent...");
            }
        }
    }


    /**
     * Sends SMS Alarms to the telephone numbers specified in mSMSNumbers[]
     */
    public void sendSMSAlarm() {
        if (mSMSAlarm) {
            Log.v(TAG, "sendSMSAlarm() - Sending to " + mSMSNumbers.length + " Numbers");
            Time tnow = new Time(Time.getCurrentTimezone());
            tnow.setToNow();
            String dateStr = tnow.format("%Y-%m-%d %H-%M-%S");
            SmsManager sm = SmsManager.getDefault();
            for (int i = 0; i < mSMSNumbers.length; i++) {
                Log.v(TAG, "sendSMSAlarm() - Sending to " + mSMSNumbers[i]);
                sm.sendTextMessage(mSMSNumbers[i], null, mSMSMsgStr + " - " + dateStr, null, null);
            }
        } else {
            Log.v(TAG, "sendSMSAlarm() - SMS Alarms Disabled - not doing anything!");
            Toast toast = Toast.makeText(getApplicationContext(),
                    "SMS Alarms Disabled - not doing anything!",
                    Toast.LENGTH_SHORT);
            toast.show();
        }
    }


    /**
     * set the alarm standing flags to false to allow alarm phase to reset to current value.
     */
    public void acceptAlarm() {
        Log.v(TAG, "acceptAlarm()");
        mSdData.alarmStanding = false;
        mSdData.fallAlarmStanding = false;
    }


    public void cancelAudible() {
        // Start timer to remove the cancel audible flag
        // after the required period.
        if (mCancelAudibleTimer!=null) {
            Log.v(TAG,"onCreate(): cancel audible timer already running - cancelling it.");
            mCancelAudibleTimer.cancel();
            mCancelAudibleTimer = null;
            mCancelAudible = false;
        } else {
            Log.v(TAG,"cancelAudible(): starting cancel audible timer");
            mCancelAudible = true;
            mCancelAudibleTimer =
                    // conver to ms.
                    new CancelAudibleTimer(mCancelAudiblePeriod*60*1000,1000);
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
     * Start the web server (on port 8080)
     */
    protected void startWebServer() {
        Log.v(TAG, "startWebServer()");
        if (webServer == null) {
            webServer = new SdWebServer(getApplicationContext(),getDataStorageDir(),mSdData);
            try {
                webServer.start();
            } catch (IOException ioe) {
                Log.w(TAG, "startWebServer(): Error: " + ioe.toString());
            }
            Log.w(TAG, "startWebServer(): Web server initialized.");
        } else {
            Log.v(TAG, "startWebServer(): server already running???");
        }
    }

    /**
     * Stop the web server - FIXME - doesn't seem to do anything!
     */
    protected void stopWebServer() {
        Log.v(TAG, "stopWebServer()");
        if (webServer != null) {
            webServer.stop();
            if (webServer.isAlive()) {
                Log.v(TAG, "stopWebServer() - server still alive???");
            } else {
                Log.v(TAG, "stopWebServer() - server died ok");
            }
            webServer = null;
        }
    }

    /**
     * Log data to SD card if mLogData is set in preferences.
     */
    public void logData() {
        if (mLogData) {
            Log.v(TAG, "logData() - writing data to SD Card");
            writeToSD();
        }
    }


    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/prefs.xml
     */
    public void updatePrefs() {
        Log.v(TAG, "updatePrefs()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        try {
            mSdDataSourceName = SP.getString("DataSource","undefined");
            Log.v(TAG,"updatePrefs() - DataSource = "+mSdDataSourceName);
            mLatchAlarms = SP.getBoolean("LatchAlarms", false);
            Log.v(TAG, "updatePrefs() - mLatchAlarms = " + mLatchAlarms);
            mAudibleFaultWarning = SP.getBoolean("AudibleFaultWarning", true);
            Log.v(TAG, "updatePrefs() - mAuidbleFaultWarning = " + mAudibleFaultWarning);
            mAudibleAlarm = SP.getBoolean("AudibleAlarm", true);
            Log.v(TAG, "updatePrefs() - mAuidbleAlarm = " + mAudibleAlarm);
            mAudibleWarning = SP.getBoolean("AudibleWarning", true);
            Log.v(TAG, "updatePrefs() - mAuidbleWarning = " + mAudibleWarning);
            mSMSAlarm = SP.getBoolean("SMSAlarm", false);
            Log.v(TAG, "updatePrefs() - mSMSAlarm = " + mSMSAlarm);
            String SMSNumberStr = SP.getString("SMSNumbers", "");
            mSMSNumbers = SMSNumberStr.split(",");
            mSMSMsgStr = SP.getString("SMSMsg", "Seizure Detected!!!");
            Log.v(TAG, "updatePrefs() - SMSNumberStr = " + SMSNumberStr);
            Log.v(TAG, "updatePrefs() - mSMSNumbers = " + mSMSNumbers);
            mLogAlarms = SP.getBoolean("LogAlarms", true);
            Log.v(TAG, "updatePrefs() - mLogAlarms = " + mLogAlarms);
            mLogData = SP.getBoolean("LogData", false);
            Log.v(TAG, "updatePrefs() - mLogData = " + mLogData);

        } catch (Exception ex) {
            Log.v(TAG, "updatePrefs() - Problem parsing preferences!");
            Toast toast = Toast.makeText(getApplicationContext(), "Problem Parsing Preferences - Something won't work - Please go back to Settings and correct it!", Toast.LENGTH_SHORT);
            toast.show();
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
        // Get the directory for the user's public pictures directory.
        File file =
                new File(Environment.getExternalStorageDirectory()
                        , "OpenSeizureDetector");
        if (!file.mkdirs()) {
            Log.e(TAG, "Directory not created");
        }
        return file;
    }

    /**
     * Write data to SD card alarm log
     */
    public void writeAlarmToSD() {
        writeToSD(true);
    }

    /**
     * Write to data log file on SD Card
     */
    public void writeToSD() {
        writeToSD(false);
    }

    /**
     * Write data to SD card - writes to data log file unless alarm=true,
     * in which case writes to alarm log file.
     */
    public void writeToSD(boolean alarm) {
        Log.v(TAG, "writeToSD(" + alarm + ")");
        Time tnow = new Time(Time.getCurrentTimezone());
        tnow.setToNow();
        String dateStr = tnow.format("%Y-%m-%d");

        // Select filename depending on 'alarm' parameter.
        String fname;
        if (alarm)
            fname = "AlarmLog";
        else
            fname = "DataLog";

        fname = fname + "_" + dateStr + ".txt";
        // Open output directory on SD Card.
        if (isExternalStorageWritable()) {
            try {
                FileWriter of = new FileWriter(getDataStorageDir().toString()
                        + "/" + fname, true);
                if (mSdData != null) {
                    Log.v(TAG, "writing mSdData.toString()");
                    of.append(mSdData.toString() + "\n");
                }
                of.close();
            } catch (Exception ex) {
                Log.e(TAG, "writeAlarmToSD - error " + ex.toString());
            }
        } else {
            Log.e(TAG, "ERROR - Can not Write to External Folder");
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
            Log.v(TAG,"mCancelAudibleTimer - removing cancelAudible flag");
        }
        @Override
        public void onTick(long msRemaining) {
            mCancelAudibleTimeRemaining = msRemaining/1000;
            Log.v(TAG,"mCancelAudibleTimer - onTick() - Time Remaining = "
                    + mCancelAudibleTimeRemaining);
        }

    }



}
