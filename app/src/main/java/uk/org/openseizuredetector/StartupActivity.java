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
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.PreferenceManager;
import android.text.Html;
import android.text.SpannableString;
import android.text.util.Linkify;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;

import com.rohitss.uceh.UCEHandler;

import java.util.Arrays;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/**
 * StartupActivity is shown on app start-up.  It starts the SdServer background service and waits
 * for it to start and to receive data and settings from the seizure detector before exiting and
 * starting the main activity.
 */
public class StartupActivity extends AppCompatActivity {
    private static String TAG = "StartupActivity";
    private int okColour = Color.BLUE;
    private int warnColour = Color.MAGENTA;
    private int alarmColour = Color.RED;
    private int okTextColour = Color.WHITE;
    private int warnTextColour = Color.BLACK;
    private int alarmTextColour = Color.BLACK;


    private OsdUtil mUtil;
    private Timer mUiTimer;
    private SdServiceConnection mConnection;
    private boolean mStartedMainActivity = false;
    private boolean mDialogDisplayed = false;
    private Handler mHandler = new Handler();   // used to update ui from mUiTimer
    private boolean mUsingPebbleDataSource = true;
    private String mPebbleAppPackageName = null;
    private boolean mBatteryOptDialogDisplayed = false;
    private AlertDialog mBatteryOptDialog;
    private boolean mLocationPermissions1Requested;
    private boolean mLocationPermissions2Requested;
    private boolean mSmsPermissionsRequested;
    private boolean mPermissionsRequested;

    private SharedPreferences SP = null;
    private SdData localSdData;

    public final String[] REQUIRED_PERMISSIONS = {
            //Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.WAKE_LOCK,
    };


    public final String[] SMS_PERMISSIONS_1 = {
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
    };

    public final String[] LOCATION_PERMISSIONS_1 = {
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            //Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
    };

    // Additional permission required by Android 10 (API 29) and higher.
    public final String[] LOCATION_PERMISSIONS_2 = {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    };
    private long lastPress;
    private Toast backpressToast;
    private boolean activateStopByBack;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "onCreate()");
        setContentView(R.layout.startup_activity);

        // Set our custom uncaught exception handler to report issues.
        //Thread.setDefaultUncaughtExceptionHandler(new OsdUncaughtExceptionHandler(StartupActivity.this));
        new UCEHandler.Builder(this)
                .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                .build();

        // Read the default settings from the xml preferences files, so we do
        // not have to use the hard coded ones in the java files.
        PreferenceManager.setDefaultValues(this, R.xml.alarm_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.general_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.network_datasource_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.pebble_datasource_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.seizure_detector_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.network_passive_datasource_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.logging_prefs, true);

        mHandler = new Handler();
        mUtil = new OsdUtil(this, mHandler);
        mUtil.writeToSysLogFile("");
        mUtil.writeToSysLogFile("*******************************");
        mUtil.writeToSysLogFile("* StartUpActivity Started     *");
        mUtil.writeToSysLogFile("*******************************");

        // Force the screen to stay on when the app is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        Button b;

        b = (Button) findViewById(R.id.settingsButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "settings button clicked");
                try {
                    mUtil.writeToSysLogFile("Starting Settings Activity");
                    Intent intent = new Intent(
                            StartupActivity.this,
                            PrefActivity.class);
                    startActivity(intent);
                } catch (Exception ex) {
                    Log.v(TAG, "exception starting settings activity " + ex.toString() + " " + Arrays.toString(Thread.currentThread().getStackTrace()), ex);
                    mUtil.writeToSysLogFile("ERROR Starting Settings Activity");
                }

            }
        });


        b = (Button) findViewById(R.id.installOsdAppButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "install Osd Watch App button clicked");
                mUtil.writeToSysLogFile("Installing Watch App");
                if (Objects.nonNull(mConnection))
                    if (Objects.nonNull(mConnection.mSdServer))
                        if (Objects.nonNull(mConnection.mSdServer.mSdDataSource))
                            mConnection.mSdServer.mSdDataSource.installWatchApp();
            }
        });
        if (Objects.isNull(mConnection)) {
            mConnection = new SdServiceConnection(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart()");
        mUtil.writeToSysLogFile("StartupActivity.onStart()");
        TextView tv;

        String versionName = mUtil.getAppVersionName();
        tv = (TextView) findViewById(R.id.appNameTv);
        tv.setText("OpenSeizureDetector V" + versionName);

        // Display the DataSource name
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(this);
        ;
        String dataSourceName = SP.getString("DataSource", "Pebble");
        tv = (TextView) findViewById(R.id.dataSourceTextView);
        tv.setText(String.format("%s = %s", getString(R.string.DataSource), dataSourceName));

        if (mUtil.isServerRunning()) {
            Log.i(TAG, "onStart() - server running - stopping it - isServerRunning=" + mUtil.isServerRunning());
            mUtil.writeToSysLogFile("StartupActivity.onStart() - server already running - stopping it.");
            mUtil.stopServer();
        } else {
            Log.i(TAG, "onStart() - server not running - isServerRunning=" + mUtil.isServerRunning());
        }
        // Wait 0.1 second to give the server chance to shutdown in case we have just shut it down below, then start it
        mHandler.postDelayed(()-> {
                mUtil.writeToSysLogFile("StartupActivity.onStart() - starting server after delay - isServerRunning=" + mUtil.isServerRunning());
                Log.i(TAG, "onStart() - starting server after delay -isServerRunning=" + mUtil.isServerRunning());
                mUtil.startServer();
                // Bind to the service.
                Log.i(TAG, "onStart() - binding to server");
                mUtil.writeToSysLogFile("StartupActivity.onStart() - binding to server");
                serverStatusRunnable.run();
                if (Objects.isNull(mConnection))
                    mConnection = new SdServiceConnection(StartupActivity.this);
                if (!mConnection.mBound) {
                    mUtil.bindToServer(StartupActivity.this, mConnection);
                }
                connectUiLiveDataRunner();
        }, (long)OsdUtil.convertTimeUnit(1.0, TimeUnit.SECONDS,TimeUnit.MILLISECONDS));

        // Check power management settings
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            Log.i(TAG, "Power Management OK - we are ignoring Battery Optimizations");
            mBatteryOptDialogDisplayed = false;
        } else {
            boolean preventBatteryOptWarning = SP.getBoolean("PreventBatteryOptWarning", false);
            if (preventBatteryOptWarning) {
                Log.i(TAG, "PreventBatteryOptWarning is true, so not displaying battery optimisation dialog");
            } else {
                Log.e(TAG, "Power Management Problem - not ignoring Battery Optimisations");
                //mUtil.showToast("WARNING - Phone is Optimising OpenSeizureDetector Battery Usage - this is likely to prevent it working correctly when running on battery!");
                if (!mBatteryOptDialogDisplayed) showBatteryOptimisationWarningDialog();
            }
        }


        // Check to see if this is the first time the app has been run, and display welcome dialog if it is.
        checkFirstRun();

        // start timer to refresh user interface every second.
        mUiTimer = new Timer();
        mUiTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mHandler.post(serverStatusRunnable);
                //updateServerStatus();
            }
        }, 0, 2000);

    }

    void connectUiLiveDataRunner(){
        Log.i(TAG,"Connecting mConnection.mSdServer.uiLiveData");
        if (Objects.nonNull(mConnection)) {
            if (mConnection.mBound && Objects.nonNull(mConnection.mSdServer) && !this.isFinishing() && !this.isDestroyed()) {
                if (!mConnection.mSdServer.uiLiveData.isListeningInContext(StartupActivity.this)) {
                    mConnection.mSdServer.uiLiveData.observe(StartupActivity.this, StartupActivity.this::onChangedObserver);
                    mConnection.mSdServer.uiLiveData.observeForever(StartupActivity.this::onChangedObserver);
                    mConnection.mSdServer.uiLiveData.addToListening(StartupActivity.this);
                    serverStatusRunnable.run();
                    return;
                }
            }else if ((!mConnection.mBound || Objects.isNull(mConnection.mSdServer)) && !this.isFinishing() && !this.isDestroyed()) {
                mHandler.postDelayed(this::connectUiLiveDataRunner,100);
            }
        }
        Log.i(TAG,"Letting go connect request");
    }

    /**
     * onChangedObserver is responsible for handling LiveData changed event
     * (this.postValue(mSdData)
     * result here is (SdData) from Object o.
     * Source event line: AWSdService:ServiceLiveData:signalChangedData()
     */
    private void onChangedObserver(Object o) {
        try {
            localSdData = (SdData) o;
            serverStatusRunnable.run();
        } catch (Exception e) {
            Log.e(getClass().getName(), "onChangedObserver: error: ", e);
        }
    }


    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop() - unbinding from server");
        mUtil.writeToSysLogFile("StartupActivity.onStop() - unbinding from server");
        if (Objects.nonNull(mConnection)) {
            if (mConnection.mBound) {
                if (Objects.nonNull(mConnection.mSdServer)) {

                    if (mConnection.mSdServer.mBound) {
                        mConnection.mSdServer.parentContext = null;
                        if (Objects.nonNull(mConnection.mSdServer.mWearNodeUri)) {
                            if (Objects.isNull(SP))
                                SP = PreferenceManager
                                        .getDefaultSharedPreferences(StartupActivity.this);

                            SharedPreferences.Editor editor = SP.edit();
                            editor.putString(Constants.GLOBAL_CONSTANTS.intentReceiver, mConnection.mSdServer.mWearNodeUri);
                            editor.apply();
                        }
                    }
                    if (Objects.nonNull(mConnection.mSdServer.uiLiveData))
                        if (mConnection.mSdServer.uiLiveData.hasActiveObservers())
                            mConnection.mSdServer.uiLiveData.removeObserver(StartupActivity.this::onChangedObserver);
                }
                mUtil.unbindFromServer(StartupActivity.this, mConnection);
            }
        }
        mConnection = null;

        if (isFinishing())
            if (mUtil.isServerRunning() && false)
                mUtil.stopServer();

        if (Objects.nonNull(mUiTimer)) mUiTimer.cancel();
    }
    @Override
    public void onBackPressed() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPress > 5000) {
                backpressToast = Toast.makeText(getBaseContext(), "Press back again to exit", Toast.LENGTH_LONG);
                backpressToast.show();
                lastPress = currentTime;
            } else {
                Log.d(TAG, "onBackPressed: initiating shutdown");
                if (backpressToast != null) backpressToast.cancel();
                activateStopByBack = true;
                if (Objects.nonNull(mConnection))
                    if (mConnection.mBound)
                        mUtil.unbindFromServer(StartupActivity.this, mConnection);
                if (mUtil.isServerRunning())
                    mUtil.stopServer();
                mHandler.postDelayed(StartupActivity.this::finishAffinity, 100);
                super.onBackPressed();
            }
        } catch (Exception e) {
            Log.e(TAG, "onBackPressed() Error thrown while processing.");
        }
    }



    /*
     * serverStatusRunnable - called by updateServerStatus - updates the
     * user interface to reflect the current status received from the server.
     * If everything is ok, we close this activity and open the main user interface
     * activity.
     */
    final Runnable serverStatusRunnable = new Runnable() {
        public void run() {
            Boolean allOk = true;
            TextView tv;
            ProgressBar pb;
            boolean smsAlarmsActive = true;
            boolean phoneAlarmsActive = true;

            Log.v(TAG, "serverStatusRunnable()");
            SharedPreferences SP = PreferenceManager
                    .getDefaultSharedPreferences(getBaseContext());
            smsAlarmsActive = SP.getBoolean("SMSAlarm", false);
            phoneAlarmsActive = SP.getBoolean("PhoneCallAlarm", false);

            // Check power management settings
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                Log.i(TAG, "Power Management OK - we are ignoring Battery Optimizations");
                if (mBatteryOptDialogDisplayed) {
                    mBatteryOptDialog.cancel();
                    mBatteryOptDialogDisplayed = false;
                }
            }

            // Settings ok
            tv = (TextView) findViewById(R.id.textItem1);
            pb = (ProgressBar) findViewById(R.id.progressBar1);
            if (arePermissionsOK()) {
                if (smsAlarmsActive && !areSMSPermissions1OK()) {
                    Log.i(TAG, "SMS permissions NOT OK");
                    tv.setText(getString(R.string.SmsPermissionWarning));
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    //pb.setIndeterminateDrawable(AppCompatResources.getDrawable(StartupActivity.this,R.drawable.start_server));
                    //pb.setProgressDrawable(AppCompatResources.getDrawable(StartupActivity.this,R.drawable.start_server));
                    requestSMSPermissions();
                    allOk = false;
                } else if (smsAlarmsActive && !areLocationPermissions1OK()) {
                    Log.i(TAG, "Location permissions NOT OK");
                    tv.setText(getString(R.string.SmsPermissionWarning));
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    requestLocationPermissions1();
                    allOk = false;
                } else if (smsAlarmsActive && !areLocationPermissions2OK()) {
                    Log.i(TAG, "Location permissions2 NOT OK");
                    tv.setText(getString(R.string.SmsPermissionWarning));
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    requestLocationPermissions2();
                    allOk = false;
                } else {
                    tv.setText(getString(R.string.AppPermissionsOk));
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    pb.setIndeterminateDrawable(AppCompatResources.getDrawable(StartupActivity.this,R.drawable.start_server));
                    pb.setProgressDrawable(AppCompatResources.getDrawable(StartupActivity.this,R.drawable.start_server));
                }
            } else {
                tv.setText(getString(R.string.AppPermissionsWarning));
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
                requestPermissions(StartupActivity.this);
            }

            // If phone alarms are selected, we need to have the uk.org.openseizuredetector.dialler package installed to do the actual dialling.
            if (phoneAlarmsActive && !mUtil.isPackageInstalled("uk.org.openseizuredetector.dialler")) {
                tv.setText(getText(R.string.DiallerNotInstalledWarning));
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminateDrawable(AppCompatResources.getDrawable(StartupActivity.this,R.drawable.start_server));
                pb.setProgressDrawable(AppCompatResources.getDrawable(StartupActivity.this,R.drawable.start_server));
                allOk = false;
            }
            if (Objects.isNull(mConnection))
            {
                allOk = false;
            }else {
                // Are we Bound to the Service
                tv = (TextView) findViewById(R.id.textItem2);
                pb = (ProgressBar) findViewById(R.id.progressBar2);
                if (mConnection.mBound) {
                    tv.setText(getString(R.string.BoundToServiceOk));
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    pb.setIndeterminateDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                    pb.setProgressDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                } else {
                    tv.setText(getString(R.string.BindingToService));
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    pb.setIndeterminate(true);
                    allOk = false;
                }

                // Is Watch Connected?
                tv = (TextView) findViewById(R.id.textItem3);
                pb = (ProgressBar) findViewById(R.id.progressBar3);
                if (mConnection.watchConnected()) {
                    tv.setText(getString(R.string.WatchConnectedOk));
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    pb.setIndeterminateDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                    pb.setProgressDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                } else {
                    tv.setText(getString(R.string.WatchNotConnected));
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    pb.setIndeterminate(true);
                    allOk = false;
                }


                // Do we have seizure detector data?
                tv = (TextView) findViewById(R.id.textItem5);
                pb = (ProgressBar) findViewById(R.id.progressBar5);
                if (mConnection.hasSdData()) {
                    tv.setText(getString(R.string.SeizureDetectorDataReceived));
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    pb.setIndeterminateDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                    pb.setProgressDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                } else {
                    tv.setText(getString(R.string.WaitingForSeizureDetectorData));
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    pb.setIndeterminate(true);
                    allOk = false;
                }


                // Do we have seizure detector settings yet?
                tv = (TextView) findViewById(R.id.textItem6);
                pb = (ProgressBar) findViewById(R.id.progressBar6);
                if (mConnection.hasSdSettings()) {
                    tv.setText(getString(R.string.SeizureDetectorSettingsReceived));
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    pb.setIndeterminateDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                    pb.setProgressDrawable(AppCompatResources.getDrawable(StartupActivity.this, R.drawable.start_server));
                } else {
                    tv.setText(getString(R.string.WaitingForSeizureDetectorSettings));
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    pb.setIndeterminate(true);
                    allOk = false;
                }

            }
            // If all the parameters are ok, close this activity and open the main
            // user interface activity instead.
            if (allOk) {
                if (!mDialogDisplayed && !mBatteryOptDialogDisplayed) {
                    if (!mStartedMainActivity) {
                        Log.i(TAG, "serverStatusRunnable() - starting main activity...");
                        mUtil.writeToSysLogFile("StartupActivity.serverStatusRunnable - all checks ok - starting main activity.");
                        try {
                            Boolean useNewUi = SP.getBoolean("UseNewUi", false);
                            Intent intent;
                            if (useNewUi) {
                                intent = new Intent(
                                        StartupActivity.this,
                                        MainActivity2.class);
                            } else {
                                intent = new Intent(
                                        StartupActivity.this,
                                        MainActivity.class);
                            }
                            intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(intent);
                            mStartedMainActivity = true;
                            finish();
                        } catch (Exception ex) {
                            mStartedMainActivity = false;
                            Log.e(TAG, "exception starting main activity " + ex.toString() + " " + Arrays.toString(Thread.currentThread().getStackTrace()), ex);
                            mUtil.writeToSysLogFile("StartupActivity.serverStatusRunnable - exception starting main activity " + ex.getMessage() + "\n" +
                                    Arrays.toString(Thread.currentThread().getStackTrace()));
                        }
                    } else {
                        Log.v(TAG, "allOk, but already started MainActivity so not doing anything");
                        mUtil.writeToSysLogFile("StartupActivity.serverStatusRunnable - allOk, but already started MainActivity so not doing anything");
                    }
                } else {
                    Log.v(TAG, "allok, but dialog displayted so not starting MainActivity");
                }
            }
        }
    };

    /**
     * getVersionName - returns the version name (e.g. 2.3.2) for this application.
     *
     * @param context
     * @param cls     - a class from which to determine the version mame.
     * @return the string version name specified in AndroidManifest.xml
     */
    public static String getVersionName(Context context, Class cls) {
        try {
            ComponentName comp = new ComponentName(context, cls);
            PackageInfo pinfo = context.getPackageManager().getPackageInfo(
                    comp.getPackageName(), 0);
            return "Version: " + pinfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            Log.e(TAG, "getVersionName Exception - " + e.toString(), e);
            return null;
        }
    }

    /**
     * checkFirstRun - checks to see if this is the first run of the app after installation or upgrade.
     * if it is, the relevant dialog message is displayed.  If not, the routine just exists so start-up can continue.
     */
    public void checkFirstRun() {
        String storedVersionName = "";
        String versionName;
        AlertDialog UpdateDialog;
        AlertDialog FirstRunDialog;
        SharedPreferences prefs;
        Log.i(TAG, "checkFirstRun()");
        versionName = this.getVersionName(StartupActivity.this, StartupActivity.class);
        prefs = PreferenceManager.getDefaultSharedPreferences(StartupActivity.this);
        storedVersionName = (prefs.getString("AppVersionName", null));
        Log.v(TAG, "storedVersionName=" + storedVersionName + ", versionName=" + versionName);

        // CHeck for new installation
        //storedVersionName = null;  // FIXME Force first run dialog for easier testing ****************************
        if (storedVersionName == null || storedVersionName.length() == 0) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                    this);
            final String s = new String(
                    getString(R.string.FirstRunDlgMsg));
            alertDialogBuilder
                    .setTitle(getString(R.string.FirstRunDlgTitle))
                    .setMessage(Html.fromHtml(s))
                    .setCancelable(false)
                    .setNeutralButton(getString(R.string.closeBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            mDialogDisplayed = false;
                            //MainActivity.this.finish();
                        }
                    })
                    .setPositiveButton(R.string.privacy_policy, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            mDialogDisplayed = false;
                            String url = OsdUtil.PRIVACY_POLICY_URL;
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(url));
                            startActivity(i);
                            dialog.cancel();
                            mDialogDisplayed = false;
                        }
                    })
                    .setNegativeButton(R.string.data_sharing, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            mDialogDisplayed = false;
                            String url = OsdUtil.DATA_SHARING_URL;
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(url));
                            startActivity(i);
                            dialog.cancel();
                            mDialogDisplayed = false;
                        }
                    })
            ;
            FirstRunDialog = alertDialogBuilder.create();
            Log.i(TAG, "Displaying First Run Dialog");
            FirstRunDialog.show();
            mDialogDisplayed = true;
        } else if (!storedVersionName.equals(versionName)) {
            // Check for update of installed application
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                    this);
            final String s = new String(
                    getString(R.string.UpgradeMsg) + getString(R.string.changelog)
            );

            alertDialogBuilder
                    .setTitle(getString(R.string.UpdateDialogTitleTxt))
                    .setMessage(Html.fromHtml(s))
                    .setCancelable(false)
                    .setNeutralButton(getString(R.string.closeBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            mDialogDisplayed = false;
                            //MainActivity.this.finish();
                        }
                    })
                    .setPositiveButton(R.string.privacy_policy, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            mDialogDisplayed = false;
                            String url = OsdUtil.PRIVACY_POLICY_URL;
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(url));
                            startActivity(i);
                            dialog.cancel();
                            mDialogDisplayed = false;
                        }
                    })
                    .setNegativeButton(R.string.data_sharing, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            mDialogDisplayed = false;
                            String url = OsdUtil.DATA_SHARING_URL;
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(url));
                            startActivity(i);
                            dialog.cancel();
                            mDialogDisplayed = false;
                        }
                    });
            UpdateDialog = alertDialogBuilder.create();
            Log.i(TAG, "Displaying Update Dialog");
            UpdateDialog.show();
            mDialogDisplayed = true;
        } else {
            Log.v(TAG, "App has already been run - not showing dialog.");
        }
        Log.i(TAG, "Setting Stored AppVersionName to" + versionName);
        prefs.edit().putString("AppVersionName", versionName).commit();
    }

    private void showBatteryOptimisationWarningDialog() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                this);
        final SpannableString s = new SpannableString(
                getString(R.string.battery_usage_optimisation_dialog_text)
        );
        // This makes the links display as links, but they do not respond to clicks for some reason...
        Linkify.addLinks(s, Linkify.ALL);
        alertDialogBuilder
                .setTitle(R.string.battery_usage_optimisation_dialog_title)
                .setMessage(s)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.okBtnTxt), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                        mBatteryOptDialogDisplayed = false;
                    }
                });
        mBatteryOptDialog = alertDialogBuilder.create();
        Log.i(TAG, "Displaying Update Dialog");
        mBatteryOptDialog.show();
        mBatteryOptDialogDisplayed = true;
    }

    /*****************************************************************************/
    public boolean arePermissionsOK() {
        boolean allOk = true;
        Log.v(TAG, "arePermissionsOK");
        for (int i = 0; i < REQUIRED_PERMISSIONS.length; i++) {
            if (ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSIONS[i])
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, REQUIRED_PERMISSIONS[i] + " Permission Not Granted");
                allOk = false;
            }
        }

        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 1);
            ActivityCompat.requestPermissions((Activity) this,
                    new String[]{Manifest.permission.BODY_SENSORS},
            Constants.GLOBAL_CONSTANTS.PERMISSION_REQUEST_BODY_SENSORS);

        } else {
            Log.d(TAG, "ALREADY GRANTED");
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            ActivityCompat.requestPermissions((Activity) this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    Constants.GLOBAL_CONSTANTS.PERMISSION_REQUEST_ACCESS_FINE_LOCATION
            );

        } else {
            Log.d(TAG, "ALREADY GRANTED");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            if (checkSelfPermission(Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND}, 1);
                ActivityCompat.requestPermissions((Activity) this,
                        new String[]{Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND},
                        Constants.GLOBAL_CONSTANTS.PERMISSION_REQUEST_START_FOREGROUND_SERVICES_FROM_BACKGROUND
                );

            } else {
                Log.d(TAG, "ALREADY GRANTED");
            }
        return allOk;
    }

    public boolean areSMSPermissions1OK() {
        boolean allOk = true;
        Log.v(TAG, "areSMSPermissions1 OK()");
        for (int i = 0; i < SMS_PERMISSIONS_1.length; i++) {
            if (ContextCompat.checkSelfPermission(this, SMS_PERMISSIONS_1[i])
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "areSMSPermissions1OK: " + SMS_PERMISSIONS_1[i] + " Permission Not Granted");
                allOk = false;
            }
        }
        return allOk;
    }


    public boolean areLocationPermissions1OK() {
        boolean allOk = true;
        Log.v(TAG, "areLocationPermissions1 OK()");
        for (int i = 0; i < LOCATION_PERMISSIONS_1.length; i++) {
            if (ContextCompat.checkSelfPermission(this, LOCATION_PERMISSIONS_1[i])
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, LOCATION_PERMISSIONS_1[i] + " Permission Not Granted");
                allOk = false;
            }
        }
        return allOk;
    }

    public boolean areLocationPermissions2OK() {
        boolean allOk = true;
        Log.v(TAG, "areSMSPermissions2OK() - SDK=" + android.os.Build.VERSION.SDK_INT);
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Log.d(TAG, "areLocationPermission2OK() - SDK <29 (Android 10) so  permission not required");
            allOk = true;
        } else {
            for (int i = 0; i < LOCATION_PERMISSIONS_2.length; i++) {
                if (ContextCompat.checkSelfPermission(this, LOCATION_PERMISSIONS_2[i])
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, LOCATION_PERMISSIONS_2[i] + " Permission Not Granted");
                    allOk = false;
                }
            }
        }
        return allOk;
    }

    public void requestPermissions(AppCompatActivity activity) {
        if (mPermissionsRequested) {
            Log.i(TAG, "requestPermissions() - request already sent - not doing anything");
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

    public void requestSMSPermissions() {
        if (mSmsPermissionsRequested) {
            Log.i(TAG, "requestSMSPermissions() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestSMSPermissions() - requesting permissions");
            mSmsPermissionsRequested = true;
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                    this);
            alertDialogBuilder
                    .setTitle(R.string.permissions_required)
                    .setMessage(R.string.sms_permissions_rationale_1)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.okBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            Log.i(TAG, "requestSMSPermissions(): Launching ActivityCompat.requestPermissions()");
                            ActivityCompat.requestPermissions(StartupActivity.this,
                                    SMS_PERMISSIONS_1,
                                    45);
                        }
                    })
                    .setNegativeButton(getString(R.string.cancelBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    }).create().show();
        }
    }


    public void requestLocationPermissions1() {
        if (mLocationPermissions1Requested) {
            Log.i(TAG, "requestLocationPermissions1() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestLocationPermissions1() - requesting permissions");
            mLocationPermissions1Requested = true;
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                    this);
            alertDialogBuilder
                    .setTitle(R.string.permissions_required)
                    .setMessage(R.string.location_permissions_rationale_1)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.okBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            Log.i(TAG, "requestLocationPermissions1(): Launching ActivityCompat.requestPermissions()");
                            ActivityCompat.requestPermissions(StartupActivity.this,
                                    LOCATION_PERMISSIONS_1,
                                    43);
                        }
                    })
                    .setNegativeButton(getString(R.string.cancelBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    })
                    .create().show();
        }
    }

    public void requestLocationPermissions2() {
        if (mLocationPermissions2Requested) {
            Log.i(TAG, "requestSMSPermissions2() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestSMSPermissions2() - requesting permissions");
            mLocationPermissions2Requested = true;

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                    this);
            alertDialogBuilder
                    .setTitle(R.string.permissions_required)
                    .setMessage(R.string.location_permissions_2_rationale)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.okBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            Log.i(TAG, "requestSMSPermissions(): Launching ActivityCompat.requestPermissions()");
                            ActivityCompat.requestPermissions(StartupActivity.this,
                                    LOCATION_PERMISSIONS_2,
                                    44);
                        }
                    })
                    .setNegativeButton(getString(R.string.cancelBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    }).create().show();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.i(TAG, "onRequestPermissionsResult - Permission" + permissions + " = " + grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int i = 0; i < permissions.length; i++) {
            Log.i(TAG, "Permission " + permissions[i] + " = " + grantResults[i]);
        }
    }


}
