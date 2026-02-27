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
package uk.org.openseizuredetector.activity.startup;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.alg.MlModelManager;
import uk.org.openseizuredetector.client.SdServiceConnection;
import uk.org.openseizuredetector.utils.OsdUtil;
import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.util.Linkify;
import androidx.activity.OnBackPressedCallback;
import androidx.core.text.HtmlCompat;
import androidx.core.text.util.LinkifyCompat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rohitss.uceh.UCEHandler;

import org.json.JSONArray;

import java.util.Timer;
import java.util.TimerTask;

import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;
import uk.org.openseizuredetector.activity.main.MainActivity2;
import uk.org.openseizuredetector.activity.onboarding.OnboardingActivity;
import uk.org.openseizuredetector.activity.settings.PrefActivity;
/**
 * StartupActivity is shown on app start-up.  It starts the SdServer background service and waits
 * for it to start and to receive data and settings from the seizure detector before exiting and
 * starting the main activity.
 */
public class StartupActivity extends AppCompatActivity {
    private static String TAG = "StartupActivity";

    private OsdUtil mUtil;
    private Timer mUiTimer;
    private SdServiceConnection mConnection;
    private boolean mStartedMainActivity = false;
    private boolean mDialogDisplayed = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());   // used to update ui from mUiTimer
    private boolean mUsingPebbleDataSource = true;
    private String mPebbleAppPackageName = null;
    private boolean mBatteryOptDialogDisplayed = false;
    private AlertDialog mBatteryOptDialog;
    private boolean mBleDeviceConfigDialogDisplayed = false;  // Flag to prevent re-creating the BLE device config dialog
    private boolean mMlIncompatibilityDialogDisplayed = false;
    private boolean mLocationPermissions1Requested;
    private boolean mLocationPermissions2Requested;
    private boolean mSmsPermissionsRequested;
    private boolean mPermissionsRequested;
    private boolean mActivityPermissionsRequested = false;
    private boolean mBindInProgress = false;
    private boolean mIsShuttingDown = false;
    private boolean mServerStopRequested = false;


    public final String[] REQUIRED_PERMISSIONS = {
            //Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.POST_NOTIFICATIONS,
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

    private String[] BT_PERMISSIONS;
    private boolean mBTPermissionsRequested = false;
    private String mSdDataSourceName;
    private String mBleDeviceAddr;
    private String mBleDeviceName;

    private final int MODE_INIT = 0;
    private final int MODE_SHUTDOWN_SERVER = 1;
    private final int MODE_START_SERVER = 2;
    private final int MODE_CONNECT_SERVER = 3;
    private final int MODE_WATCH_RUNNING = 4;
    private final int MODE_SD_DATA_OK = 5;
    private int mMode = MODE_INIT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate()");
        mUtil = new OsdUtil(this, mHandler);
        mUtil.writeToSysLogFile("StartupActivity.onCreate()", "LIFECYCLE");
        mUtil.writeMemoryLog("StartupActivity.onCreate");

        // Check if this is the first run - if so, launch onboarding wizard
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean firstRunComplete = prefs.getBoolean("first_run_complete", false);

        //firstRunComplete = false;  // FIXME - forced to false for testing

        if (!firstRunComplete) {
            Log.i(TAG, "First run detected - launching onboarding wizard");
            mUtil.writeToSysLogFile("StartupActivity.onCreate - Launching onboarding", "LIFECYCLE");
            Intent onboardingIntent = new Intent(this, OnboardingActivity.class);
            startActivity(onboardingIntent);
            finish();
            return; // Exit onCreate early
        }

        setContentView(R.layout.startup_activity);


        // Centralised preference initialisation from XML files
        PrefActivity.initialiseDefaultValues(this, false);

        mHandler = new Handler(Looper.getMainLooper());
        mUtil = new OsdUtil(getApplicationContext(), mHandler);
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
                    Log.v(TAG, "exception starting settings activity " + ex.toString());
                    mUtil.writeToSysLogFile("ERROR Starting Settings Activity");
                }

            }
        });

        // Enable the "Install Watch App" button if we have the Pebble data source selected,
        // otherwise hide it.
        b = (Button) findViewById(R.id.installOsdAppButton);
        String dataSourceName = (prefs.getString("DataSource", "Phone"));
        if (dataSourceName.equals("Pebble")) {
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "install Osd Watch App button clicked");
                    if (mConnection.mSdServer.mSdDataSource != null) {
                        mUtil.writeToSysLogFile("Installing Watch App");
                        mConnection.mSdServer.mSdDataSource.installWatchApp();
                    } else {
                        mUtil.showToast("Error installing watch app - Datasource has not started - please see installation instructions on web site");
                        Log.v(TAG, "Displaying Installation Instructions");
                        try {
                            String url = "https://www.openseizuredetector.org.uk/?page_id=1894";
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(url));
                            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception ex) {
                            Log.i(TAG, "exception starting install watch app activity " + ex.toString());
                            mUtil.showToast("Error Displaying Installation Instructions - try http://www.openseizuredetector.org.uk/?page_id=1894 instead");
                        }
                    }
                }
            });
        } else {
            b.setVisibility(View.GONE);
        }

        b = (Button) findViewById(R.id.instructionsButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "instructions button clicked");
                try {
                    String url = "https://www.openseizuredetector.org.uk/?page_id=1894";
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ex) {
                    Log.v(TAG, "exception displaying instructions " + ex.toString());
                    mUtil.showToast("ERROR Displaying Instructions");
                }

            }
        });

        b = (Button) findViewById(R.id.troubleshootingButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "troubleshooting button clicked");
                try {
                    String url = "https://www.openseizuredetector.org.uk/?page_id=2235";
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ex) {
                    Log.v(TAG, "exception displaying troubleshooting " + ex.toString());
                    mUtil.showToast("ERROR Displaying Troubleshooting Tips");
                }

            }
        });


        // Connect to the background service
        mConnection = new SdServiceConnection(getApplicationContext());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
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
                .getDefaultSharedPreferences(getBaseContext());

        mSdDataSourceName = SP.getString("DataSource", "Pebble");
        mBleDeviceAddr = SP.getString("BLE_Device_Addr", "");
        mBleDeviceName = SP.getString("BLE_Device_Name", "");
        tv = (TextView) findViewById(R.id.dataSourceTextView);

        if (mSdDataSourceName.equals("BLE")) {
            tv.setText(String.format("%s = %s (%s - %s)", getString(R.string.DataSource), mSdDataSourceName, mBleDeviceName, mBleDeviceAddr));
        } else {
            tv.setText(String.format("%s = %s", getString(R.string.DataSource), mSdDataSourceName));
        }


        // Don't automatically stop/restart the server when returning from settings
        // The service will reload preferences when needed
        if (mUtil.isServerRunning()) {
            Log.i(TAG, "onStart() - server already running - will continue with existing service");
            mUtil.writeToSysLogFile("StartupActivity.onStart() - server already running");
            mMode = MODE_START_SERVER; // Continue to connection checks
        } else {
            mMode = MODE_START_SERVER;
            Log.i(TAG, "onStart() - server not running - isServerRunning=" + mUtil.isServerRunning());
        }

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

        // Check ML compatibility
        checkMlCompatibility();

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

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop() - unbinding from server");
        if (mUtil != null) {
            mUtil.writeToSysLogFile("StartupActivity.onStop() - unbinding from server");
            mUtil.unbindFromServer(getApplicationContext(), mConnection);
        }
        if (mUiTimer != null) {
            mUiTimer.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
        if (mUtil != null) {
            mUtil.writeToSysLogFile("StartupActivity.onDestroy()", "LIFECYCLE");
            mUtil.writeMemoryLog("StartupActivity.onDestroy");
        }

        // Cancel timers to prevent any background operations
        if (mUiTimer != null) {
            mUiTimer.cancel();
            mUiTimer = null;
        }

        // Remove any pending handler callbacks
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        Log.i(TAG, "onDestroy() - cleanup complete");
        if (mUtil != null) {
            mUtil.writeToSysLogFile("StartupActivity.onDestroy - cleanup complete", "LIFECYCLE");
        }
    }

    private void handleBackPressed() {
         Log.i(TAG, "onBackPressed() - user pressed back button");
         if (mUtil != null) {
             mUtil.writeToSysLogFile("StartupActivity.onBackPressed() - shutting down");
         }

        // Set shutdown flag to prevent any restart attempts
        mIsShuttingDown = true;

        // Cancel the UI timer immediately to prevent further checks
        if (mUiTimer != null) {
            mUiTimer.cancel();
            mUiTimer = null;
        }

        // Remove any pending handler callbacks
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        // If server is running, stop it with timeout protection
        if (mUtil != null && mUtil.isServerRunning()) {
            Log.i(TAG, "onBackPressed() - stopping server before exit");
            mUtil.writeToSysLogFile("StartupActivity.onBackPressed() - stopping server");

            mServerStopRequested = true;

            // Stop server in background thread with timeout
            new Thread(() -> {
                try {
                    mUtil.stopServer();
                    Log.i(TAG, "onBackPressed() - server stopped successfully");
                } catch (Exception e) {
                    Log.e(TAG, "onBackPressed() - error stopping server: " + e.getMessage());
                }

                // Finish activity on UI thread
                runOnUiThread(() -> {
                    Log.i(TAG, "onBackPressed() - finishing activity");
                    finish();
                });
            }).start();

            // Also set a timeout to ensure we exit even if stop hangs
            if (mHandler != null) {
                mHandler.postDelayed(() -> {
                    if (!isFinishing()) {
                        Log.w(TAG, "onBackPressed() - server stop timeout, forcing exit");
                        if (mUtil != null) {
                            mUtil.writeToSysLogFile("StartupActivity.onBackPressed() - timeout, forcing exit");
                        }
                        finish();
                    }
                }, 7000); // 7 seconds - slightly longer than BLE disconnect timeout
            }

        } else {
            // Server not running, just exit
            Log.i(TAG, "onBackPressed() - server not running, exiting immediately");
            finish();
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
            // Don't do anything if we're shutting down
            if (mIsShuttingDown) {
                Log.v(TAG, "serverStatusRunnable() - shutting down, skipping status check");
                return;
            }

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
                Log.i(TAG,"arePermissionsOK=true");
                Log.i(TAG,"mSdDataSourceName = "+ mSdDataSourceName);
                tv.setText(getString(R.string.AppPermissionsOk));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_text));
                pb.setIndeterminateDrawable(getCheckboxDrawable());
                pb.setProgressDrawable(getCheckboxDrawable());

                if (mSdDataSourceName.equals("BLE") || mSdDataSourceName.equals("BLE2")) {
                    if (!mUtil.areBtPermissionsOk()) {
                        Log.i(TAG, "Bluetooth permissions NOT OK");
                        tv.setText(getString(R.string.BTPermissionWarning));
                        tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                        tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                        requestBTPermissions();
                        allOk = false;
                    } else if (mBleDeviceAddr.equals("")) {
                        Log.i(TAG, "BLE data source selected, but no device address specified - showing dialog");
                        // Only show the dialog once - check flag to prevent multiple re-creations
                        if (!mBleDeviceConfigDialogDisplayed) {
                            mBleDeviceConfigDialogDisplayed = true;
                            showBleDeviceConfigDialog();
                        }
                        return;
                    }
                } else if (mSdDataSourceName.equals("Phone") && !mUtil.areActivityPermissionsOk()) {
                    // Activity permissions (ACTIVITY_RECOGNITION) only needed for Phone datasource
                    Log.i(TAG, "Activity permissions NOT OK for Phone datasource");
                    tv.setText(getString(R.string.ActivityPermissionWarning));
                    tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                    tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                    requestActivityPermissions();
                    allOk = false;

                } else if (smsAlarmsActive && !areSMSPermissions1OK()) {
                    Log.i(TAG, "SMS permissions NOT OK");
                    tv.setText(getString(R.string.SmsPermissionWarning));
                    tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                    tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                    requestSMSPermissions();
                    allOk = false;
                } else if (smsAlarmsActive && !areLocationPermissions1OK()) {
                    Log.i(TAG, "Location permissions NOT OK");
                    tv.setText(getString(R.string.SmsPermissionWarning));
                    tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                    tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                    requestLocationPermissions1();
                    allOk = false;
                } else if (smsAlarmsActive && !areLocationPermissions2OK()) {
                    Log.i(TAG, "Location permissions2 NOT OK");
                    tv.setText(getString(R.string.SmsPermissionWarning));
                    tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                    tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                    requestLocationPermissions2();
                    allOk = false;
                }
            } else {
                tv.setText(getString(R.string.AppPermissionsWarning));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_error_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_error_text));
                pb.setIndeterminate(true);
                allOk = false;
                requestPermissions(StartupActivity.this);
            }

            // If phone alarms are selected, we need to have the uk.org.openseizuredetector.dialler package installed to do the actual dialling.
            if (phoneAlarmsActive && !mUtil.isPackageInstalled("uk.org.openseizuredetector.dialler")) {
                tv.setText(getText(R.string.DiallerNotInstalledWarning));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_error_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_error_text));
                pb.setIndeterminateDrawable(getCheckboxDrawable());
                pb.setProgressDrawable(getCheckboxDrawable());
                allOk = false;
            }

            if (allOk) {
                tv = (TextView) findViewById(R.id.textItem1);
                pb = (ProgressBar) findViewById(R.id.progressBar1);

                // Check health foreground service permissions on Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !areHealthForegroundServicePermissionsOK()) {
                    Log.i(TAG, "health foreground service permissions not granted - requesting");
                    tv.setText("Requesting Permissions");
                    tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                    tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                    pb.setIndeterminateDrawable(getCheckboxDrawable());
                    pb.setProgressDrawable(getCheckboxDrawable());

                    // Request activity recognition permission (also covers health monitoring)
                    ActivityCompat.requestPermissions(StartupActivity.this,
                            new String[]{android.Manifest.permission.ACTIVITY_RECOGNITION},
                            1);
                    allOk = false;
                    return;
                }

                if (!mUtil.isServerRunning()) {
                    // Don't start server if we're shutting down or a stop was requested
                    if (mIsShuttingDown || mServerStopRequested) {
                        Log.i(TAG, "serverStatusRunnable() - shutdown in progress, not starting server");
                        return;
                    }

                    mUtil.writeToSysLogFile("StartupActivity.onStart() - starting server  - isServerRunning=" + mUtil.isServerRunning());
                    Log.i(TAG, "onStart() - starting server -isServerRunning=" + mUtil.isServerRunning());

                    // Clear the user_stopped_service flag since we're starting the service
                    SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getBaseContext());
                    prefs.edit().putBoolean("user_stopped_service", false).apply();

                    mUtil.startServer();
                    mBindInProgress = false;
                    allOk = false;
                    tv.setText("Starting Server");
                    tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                    tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                    pb.setIndeterminateDrawable(getCheckboxDrawable());
                    pb.setProgressDrawable(getCheckboxDrawable());
                    mMode = MODE_START_SERVER;
                } else {
                    // Don't bind or continue if shutting down
                    if (mIsShuttingDown || mServerStopRequested) {
                        Log.i(TAG, "serverStatusRunnable() - shutdown in progress, not binding to server");
                        return;
                    }

                    tv.setText(getString(R.string.ServerRunningOK));
                    tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_background));
                    tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_text));
                    pb.setIndeterminateDrawable(getCheckboxDrawable());
                    pb.setProgressDrawable(getCheckboxDrawable());
                    if (mBindInProgress) {
                        Log.i(TAG,"Waiting to bind to server");
                    } else {
                        Log.i(TAG, "ServerStatusRunnable() - not starting server - allOk=" + allOk + ", isServerRunning()=" + mUtil.isServerRunning());
                        // Bind to the service.
                        Log.i(TAG, "ServerStatusRunnable() - binding to server");
                        mUtil.writeToSysLogFile("StartupActivity.onStart() - binding to server");
                        mUtil.bindToServer(getApplicationContext(), mConnection);
                        mBindInProgress = true;
                    }
                }
            }

            // Are we Bound to the Service
            tv = (TextView) findViewById(R.id.textItem2);
            pb = (ProgressBar) findViewById(R.id.progressBar2);
            if (mConnection.mBound) {
                tv.setText(getString(R.string.BoundToServiceOk));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_text));
                pb.setIndeterminateDrawable(getCheckboxDrawable());
                pb.setProgressDrawable(getCheckboxDrawable());
            } else {
                tv.setText(getString(R.string.BindingToService));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                pb.setIndeterminate(true);
                allOk = false;
            }

            // Is Watch Connected?
            tv = (TextView) findViewById(R.id.textItem3);
            pb = (ProgressBar) findViewById(R.id.progressBar3);
            if (mConnection.watchConnected()) {
                tv.setText(getString(R.string.WatchConnectedOk));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_text));
                pb.setIndeterminateDrawable(getCheckboxDrawable());
                pb.setProgressDrawable(getCheckboxDrawable());
            } else {
                tv.setText(getString(R.string.WatchNotConnected));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                pb.setIndeterminate(true);
                allOk = false;
            }


            // Do we have seizure detector data?
            tv = (TextView) findViewById(R.id.textItem5);
            pb = (ProgressBar) findViewById(R.id.progressBar5);
            if (mConnection.hasSdData()) {
                tv.setText(getString(R.string.SeizureDetectorDataReceived));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_text));
                pb.setIndeterminateDrawable(getCheckboxDrawable());
                pb.setProgressDrawable(getCheckboxDrawable());
            } else {
                tv.setText(getString(R.string.WaitingForSeizureDetectorData));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                pb.setIndeterminate(true);
                allOk = false;
            }


            // Do we have seizure detector settings yet?
            tv = (TextView) findViewById(R.id.textItem6);
            pb = (ProgressBar) findViewById(R.id.progressBar6);
            if (mConnection.hasSdSettings()) {
                tv.setText(getString(R.string.SeizureDetectorSettingsReceived));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_ok_text));
                pb.setIndeterminateDrawable(getCheckboxDrawable());
                pb.setProgressDrawable(getCheckboxDrawable());
            } else {
                tv.setText(getString(R.string.WaitingForSeizureDetectorSettings));
                tv.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_background));
                tv.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.status_warning_text));
                pb.setIndeterminate(true);
                allOk = false;
            }


            // If all the parameters are ok, close this activity and open the main
            // user interface activity instead.
            if (allOk) {
                // Don't start MainActivity if we're shutting down
                if (mIsShuttingDown || mServerStopRequested) {
                    Log.i(TAG, "serverStatusRunnable() - shutdown in progress, not starting MainActivity");
                    return;
                }

                if (!mDialogDisplayed && !mBatteryOptDialogDisplayed && !mMlIncompatibilityDialogDisplayed) {
                    // Also check if the activity is currently resumed. We can't start a new
                    // activity if a dialog is showing, as we are not in a resumed state.
                    if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        if (!mStartedMainActivity) {
                            Log.i(TAG, "serverStatusRunnable() - starting main activity...");
                            mUtil.writeToSysLogFile("StartupActivity.serverStatusRunnable - all checks ok - starting main activity.");
                            try {
                                Intent intent;
                                intent = new Intent(
                                        getApplicationContext(),
                                        MainActivity2.class);
                                startActivity(intent);
                                mStartedMainActivity = true;
                                finish();
                                return;
                            } catch (Exception ex) {
                                mStartedMainActivity = false;
                                Log.e(TAG, "exception starting main activity " + ex.toString());
                                mUtil.writeToSysLogFile("StartupActivity.serverStatusRunnable - exception starting main activity " + ex.toString());
                            }
                        } else {
                            Log.v(TAG, "allOk, but already started MainActivity so not doing anything");
                            mUtil.writeToSysLogFile("StartupActivity.serverStatusRunnable - allOk, but already started MainActivity so not doing anything");
                        }
                    } else {
                        Log.v(TAG, "allOk, but activity not in resumed state, so not starting MainActivity");
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
            Log.e(TAG, "getVersionName Exception - " + e.toString());
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
        versionName = this.getVersionName(this, StartupActivity.class);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        storedVersionName = (prefs.getString("AppVersionName", null));
        Log.v(TAG, "storedVersionName=" + storedVersionName + ", versionName=" + versionName);

        // CHeck for new installation
        //storedVersionName = null;  // FIXME Force first run dialog for easier testing ****************************
        if (storedVersionName == null || storedVersionName.length() == 0) {
            MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
            final String s = new String(
                    getString(R.string.FirstRunDlgMsg));
            alertDialogBuilder
                    .setTitle(getString(R.string.FirstRunDlgTitle))
                    .setMessage(HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY))
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
            MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
            final String s = new String(
                    getString(R.string.UpgradeMsg) + getString(R.string.changelog)
            );

            alertDialogBuilder
                    .setTitle(getString(R.string.UpdateDialogTitleTxt))
                    .setMessage(HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY))
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
        MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
        final SpannableString s = new SpannableString(
                getString(R.string.battery_usage_optimisation_dialog_text)
        );
        // This makes the links display as links, but they do not respond to clicks for some reason...
        LinkifyCompat.addLinks(s,
                Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS);
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

    private void checkMlCompatibility() {
        if (mMlIncompatibilityDialogDisplayed) return;

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        if (sp.getBoolean("CnnAlarmActive", false)) {
            MlModelManager mm = new MlModelManager(this);
            JSONArray installed = mm.getInstalledModels();

            boolean anyCompatible = false;
            if (installed.length() > 0) {
                for (int i = 0; i < installed.length(); i++) {
                    if (mm.isDeviceCompatible(installed.optJSONObject(i))) {
                        anyCompatible = true;
                        break;
                    }
                }
            }

            if (!anyCompatible) {
                mMlIncompatibilityDialogDisplayed = true;
                new MaterialAlertDialogBuilder(this)
                        .setTitle("ML Compatibility Error")
                        .setMessage("The Machine Learning algorithm is enabled, but no compatible models are installed for this device's CPU.\n\nPlease install a compatible model (e.g. one without 'dotprod' requirements) or disable the ML algorithm.")
                        .setCancelable(false)
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            Intent intent = new Intent(this, PrefActivity.class);
                            intent.putExtra("fragment", "uk.org.openseizuredetector.activity.settings.PrefActivity$MlAlgPrefsFragment");
                            startActivity(intent);
                        })
                        .show();
            }
        }
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

    /**
     * Check if health foreground service permissions are granted (Android 12+)
     * Required for starting foreground service with type health
     */
    public boolean areHealthForegroundServicePermissionsOK() {
        Log.v(TAG, "areHealthForegroundServicePermissionsOK() - SDK=" + Build.VERSION.SDK_INT);

        // Only required on Android 12+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(TAG, "areHealthForegroundServicePermissionsOK() - SDK <31 (Android 12), permission not required");
            return true;
        }

        // Need either ACTIVITY_RECOGNITION or BODY_SENSORS
        boolean hasActivityRecognition = ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        boolean hasBodySensors = ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;

        if (!hasActivityRecognition && !hasBodySensors) {
            Log.i(TAG, "Health foreground service permissions not granted");
            return false;
        }

        Log.d(TAG, "Health foreground service permissions OK");
        return true;
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
            MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
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
                    });
            AlertDialog smsDialog = alertDialogBuilder.create();
            smsDialog.show();
        }
    }


    public void requestLocationPermissions1() {
        if (mLocationPermissions1Requested) {
            Log.i(TAG, "requestLocationPermissions1() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestLocationPermissions1() - requesting permissions");
            mLocationPermissions1Requested = true;
            MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
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
                    });
            AlertDialog locationDialog1 = alertDialogBuilder.create();
            locationDialog1.show();
        }
    }

    public void requestLocationPermissions2() {
        if (mLocationPermissions2Requested) {
            Log.i(TAG, "requestSMSPermissions2() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestSMSPermissions2() - requesting permissions");
            mLocationPermissions2Requested = true;

            MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
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
                    });
            AlertDialog locationDialog2 = alertDialogBuilder.create();
            locationDialog2.show();
        }
    }

    public void requestBTPermissions() {
        if (mBTPermissionsRequested) {
            Log.i(TAG, "requestBTPermissions() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestBTPermissions() - requesting permissions");
            mBTPermissionsRequested = true;
            MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
            alertDialogBuilder
                    .setTitle(R.string.BTpermissions_required)
                    .setMessage(R.string.BT_permissions_rationale)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.okBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            Log.i(TAG, "requestBTPermissions(): Launching ActivityCompat.requestPermissions()");
                            ActivityCompat.requestPermissions(StartupActivity.this,
                                    mUtil.getRequiredBtPermissions(),
                                    46);
                        }
                    });
            AlertDialog btDialog = alertDialogBuilder.create();
            btDialog.show();
        }
    }

    public void requestActivityPermissions() {
        if (mActivityPermissionsRequested) {
            Log.i(TAG, "requestActivityPermissions() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestActivityPermissions() - requesting permissions");
            mActivityPermissionsRequested = true;
            MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
            alertDialogBuilder
                    .setTitle(R.string.activity_permissions_required)
                    .setMessage(R.string.activity_permissions_rationale)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.okBtnTxt), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            Log.i(TAG, "requestActivityPermissions(): Launching ActivityCompat.requestPermissions()");
                            ActivityCompat.requestPermissions(StartupActivity.this,
                                    mUtil.getRequiredActivityPermissions(),
                                    49);
                        }
                    });
            AlertDialog activityDialog = alertDialogBuilder.create();
            activityDialog.show();
        }
    }

    /**
     * Show a dialog when BLE/BLE2 datasource is selected but no device is configured.
     * Gives user options to: scan now, configure later, or go to settings.
     */
    private void showBleDeviceConfigDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);

        // Create the dialog with title and message
        AlertDialog dialog = builder.setTitle("BLE Device Not Selected")
                .setMessage("You selected a BLE data source but haven't configured a device yet. What would you like to do?")
                .setCancelable(false)
                .setPositiveButton("Scan for Device", (dialogInterface, which) -> {
                    Log.i(TAG, "User chose to scan for BLE device");
                    Intent i = new Intent(getApplicationContext(), BLEScanActivity.class);
                    startActivity(i);
                })
                .setNeutralButton("Configure Later", (dialogInterface, which) -> {
                    Log.i(TAG, "User chose to configure later - continuing with app");
                    dialogInterface.dismiss();
                    // Continue with normal startup - the app will use the BLE datasource without a device
                    // and may show warnings or reduced functionality
                })
                .setNegativeButton("Go to Settings", (dialogInterface, which) -> {
                    Log.i(TAG, "User chose to go to settings to change datasource");
                    Intent i = new Intent(this, PrefActivity.class);
                    startActivity(i);
                })
                .create();

        // Reset the flag if the dialog is dismissed so it can be shown again if needed
        dialog.setOnDismissListener(dialogInterface -> {
            Log.i(TAG, "BLE device config dialog dismissed");
            mBleDeviceConfigDialogDisplayed = false;
        });

        dialog.show();
    }

    private Drawable getCheckboxDrawable() {
         return AppCompatResources.getDrawable(this, android.R.drawable.checkbox_on_background);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.i(TAG, "onRequestPermissionsResult - requestCode="+requestCode+" nPermissions="+permissions.length);

        // Handle health foreground service permission request
        if (requestCode == 1) {  // Health service permission request code
            Log.i(TAG, "Health service permission request result received");

            // Check if array is not empty before accessing
            if (permissions.length > 0 && grantResults.length > 0) {
                Log.i(TAG, "Permission: " + permissions[0] + " = " + grantResults[0]);

                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Health service permission GRANTED - server can now start with foreground");
                    // Permission granted - the server status check will retry starting the server
                    // Trigger UI update to retry server startup
                    mHandler.post(serverStatusRunnable);
                } else {
                    // Permission denied - must exit app
                    Log.e(TAG, "Health service permission DENIED - exiting application");
                    showPermissionDeniedDialog();
                }
            } else {
                // Permission cancelled - treat same as denied
                Log.w(TAG, "Permission arrays are empty - permission request was cancelled - exiting");
                showPermissionDeniedDialog();
            }
            return;
        }

        // Original permission handling for other requests
        for (int i = 0; i < permissions.length; i++) {
            Log.i(TAG, String.format("onRequestPermissionsResult: i="+i+", Permission " + permissions[i].toString() + " = " + grantResults[i]));
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Show error dialog and exit app when health foreground service permission is denied
     */
    private void showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Permission Required")
                .setMessage("OpenSeizureDetector requires the 'Activity Recognition' permission to start the health monitoring service on Android 12 and later. " +
                           "Without this permission, the app cannot function properly. " +
                           "Please grant the permission and try again.")
                .setCancelable(false)
                .setPositiveButton("Exit", (dialog, which) -> {
                    Log.i(TAG, "User acknowledged permission denied - exiting app");
                    finish();
                });

        builder.show();
    }


}
