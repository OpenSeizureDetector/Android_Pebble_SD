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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.rohitss.uceh.UCEHandler;

import java.util.Timer;
import java.util.TimerTask;

/**
 * StartupActivity is shown on app start-up.  It starts the SdServer background service and waits
 * for it to start and to receive data and settings from the seizure detector before exiting and
 * starting the main activity.
 */
public class StartupActivity extends Activity {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG,"onCreate()");

        // Set our custom uncaught exception handler to report issues.
        //Thread.setDefaultUncaughtExceptionHandler(new OsdUncaughtExceptionHandler(StartupActivity.this));
        new UCEHandler.Builder(this)
                .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                .build();


        mHandler = new Handler();
        mUtil = new OsdUtil(this, mHandler);
        mUtil.writeToSysLogFile("");
        mUtil.writeToSysLogFile("*******************************");
        mUtil.writeToSysLogFile("* StartUpActivity Started     *");
        mUtil.writeToSysLogFile("*******************************");

        // Force the screen to stay on when the app is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        setContentView(R.layout.startup_activity);

        // Read the default settings from the xml preferences files, so we do
        // not have to use the hard coded ones in the java files.
        PreferenceManager.setDefaultValues(this, R.xml.alarm_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.camera_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.general_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.network_datasource_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.pebble_datasource_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.garmin_datasource_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.seizure_detector_prefs, true);
        PreferenceManager.setDefaultValues(this, R.xml.network_passive_datasource_prefs, true);


        Button b;
        b = (Button) findViewById(R.id.installPebbleAppButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "install Pebble app button clicked");
                try {
                    mUtil.writeToSysLogFile("Installing Pebble App");
                    Intent intent = new Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse("market://details?id=" + mUtil.getPreferredPebbleAppPackageName()));
                    startActivity(intent);
                } catch (Exception ex) {
                    Log.v(TAG, "exception starting play store activity " + ex.toString());
                    mUtil.writeToSysLogFile("ERROR Starting play store Activity");
                    mUtil.showToast("Error Starting Google Play Store to Install App - is Play Store instaled?");
                }

            }
        });

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

        b = (Button) findViewById(R.id.pebbleButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "pebble button clicked");
                mUtil.writeToSysLogFile("Starting Pebble Phone App");
                mConnection.mSdServer.mSdDataSource.startPebbleApp();
            }
        });

        b = (Button) findViewById(R.id.installOsdAppButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "install Osd Watch App button clicked");
                mUtil.writeToSysLogFile("Installing Watch App");
                mConnection.mSdServer.mSdDataSource.installWatchApp();
            }
        });

        mConnection = new SdServiceConnection(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG,"onStart()");
        mUtil.writeToSysLogFile("StartupActivity.onStart()");
        TextView tv;

        if (mUtil.arePermissionsOK()) {
            Log.i(TAG,"onStart() - Permissions OK");
        } else {
            Log.i(TAG,"onStart() - Permissions Not OK - requesting them");
            mUtil.requestPermissions(this);
        }


        String versionName = mUtil.getAppVersionName();
        tv = (TextView) findViewById(R.id.appNameTv);
        tv.setText("OpenSeizureDetector V" + versionName);

        // Display the DataSource name
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        ;
        String dataSourceName = SP.getString("DataSource", "Pebble");
        tv = (TextView) findViewById(R.id.dataSourceTextView);
        tv.setText("DataSource = " + dataSourceName);

        // disable pebble configuration buttons if we have not selected the pebble datasource
        if (!dataSourceName.equals("Pebble")) {
            Log.v(TAG, "Not Pebble Datasource - deactivating Pebble Button");
            mUsingPebbleDataSource = false;
            Button b = (Button) findViewById(R.id.pebbleButton);
            b.setEnabled(false);
            b = (Button) findViewById(R.id.installOsdAppButton);
            b.setEnabled(false);
        } else {
            mUsingPebbleDataSource = true;
        }



        if (mUtil.isServerRunning()) {
            Log.i(TAG, "onStart() - server running - stopping it");
            mUtil.writeToSysLogFile("StartupActivity.onStart() - server already running - stopping it.");
            mUtil.stopServer();
        }
        mUtil.writeToSysLogFile("StartupActivity.onStart() - starting server");
        Log.i(TAG,"onStart() - starting server");
        mUtil.startServer();

        // Bind to the service.
        Log.i(TAG,"onStart() - binding to server");
        mUtil.writeToSysLogFile("StartupActivity.onStart() - binding to server");
        mUtil.bindToServer(this, mConnection);

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
        }, 0, 1000);


    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop() - unbinding from server");
        mUtil.writeToSysLogFile("StartupActivity.onStop() - unbinding from server");
        mUtil.unbindFromServer(this, mConnection);
        mUiTimer.cancel();
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

            Log.v(TAG,"serverStatusRunnable()");

            // Settings ok
            tv = (TextView) findViewById(R.id.textItem1);
            pb = (ProgressBar) findViewById(R.id.progressBar1);
            if (mUtil.arePermissionsOK()) {
                tv.setText("App Permissions OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Problem with App Permissions");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
                mUtil.requestPermissions(StartupActivity.this);
            }

            // Are we Bound to the Service
            tv = (TextView) findViewById(R.id.textItem2);
            pb = (ProgressBar) findViewById(R.id.progressBar2);
            if (mConnection.mBound) {
                tv.setText("Bound to Service OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Binding to Background Service...");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
            }

            // Is Pebble Watch Connected?
            tv = (TextView) findViewById(R.id.textItem3);
            pb = (ProgressBar) findViewById(R.id.progressBar3);
            if (mConnection.pebbleConnected()) {
                tv.setText("Pebble Watch Connected OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Watch Not Connected");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
            }

            /*
            // Is Pebble Watch App Running?
            tv = (TextView) findViewById(R.id.textItem4);
            pb = (ProgressBar) findViewById(R.id.progressBar4);
            if (mConnection.pebbleAppRunning()) {
                tv.setText("Watch App Running OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                //pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Watch App Not Running");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
            }
            */


            // Do we have seizure detector data?
            tv = (TextView) findViewById(R.id.textItem5);
            pb = (ProgressBar) findViewById(R.id.progressBar5);
            if (mConnection.hasSdData()) {
                tv.setText("Seizure Detector Data Received OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Waiting for Seizure Detector Data...");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
            }


            // Do we have seizure detector settings yet?
            tv = (TextView) findViewById(R.id.textItem6);
            pb = (ProgressBar) findViewById(R.id.progressBar6);
            if (mConnection.hasSdSettings()) {
                tv.setText("Seizure Detector Settings Received OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Waiting for Seizure Detector Settings...");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
            }

            // Is Pebble Watch App Installed?
            tv = (TextView) findViewById(R.id.textItem7);
            pb = (ProgressBar) findViewById(R.id.progressBar7);
            boolean pebbleAndroidAppInstalled;
            mPebbleAppPackageName = mUtil.isPebbleAppInstalled();
            if (mPebbleAppPackageName != null)
                pebbleAndroidAppInstalled = true;
            else
                pebbleAndroidAppInstalled = false;

            if (mUsingPebbleDataSource) {
                if (pebbleAndroidAppInstalled) {
                    tv.setText("Pebble Android App Installed");
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                    pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
                } else {
                    tv.setText("Pebble App NOT Installed: ");
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                    pb.setIndeterminate(true);
                    allOk = false;
                }
            } else {
                tv.setText("Pebble Android App Not Required");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            }


            // If all the parameters are ok, close this activity and open the main
            // user interface activity instead.
            if (allOk) {
                if (!mDialogDisplayed) {
                    if (!mStartedMainActivity) {
                        Log.i(TAG, "serverStatusRunnable() - starting main activity...");
                        mUtil.writeToSysLogFile("StartupActivity.serverStatusRunnable - all checks ok - starting main activity.");
                        try {
                            Intent intent = new Intent(
                                    getApplicationContext(),
                                    MainActivity.class);
                            intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(intent);
                            mStartedMainActivity = true;
                            finish();
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
        Log.i(TAG,"checkFirstRun()");
        versionName = this.getVersionName(this, StartupActivity.class);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        storedVersionName = (prefs.getString("AppVersionName", null));
        Log.v(TAG,"storedVersionName="+storedVersionName+", versionName="+versionName);

        // CHeck for new installation
        if (storedVersionName == null || storedVersionName.length() == 0) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                    this);
            final SpannableString s = new SpannableString(
                    "OpenSeizureDetector does not collect any personal data. "
                            + "This does mean that it is not possible for me to contact users if I find an "
                            + "issue with the app that you should be aware of.   \nPlease subscribe to updates at "
                            + "http://openseizuredetector.org.uk, or the app Facebook page at https://www.facebook.com/openseizuredetector. "
                            + "so I can get in touch if necessary.\nThank you!  Graham \ngraham@openseizuredetector.org.uk "
                            + "\n\nChanges in this version:"
                            + "\n- Added support for 'Wifi Datasources' - initially for the experimental ESP8266 based seizure detector."
                            + "\n  "
                            + "\n  "
                            + "\n  "
                            + "\n  ."
                );
            // This makes the links display as links, but they do not respond to clicks for some reason...
            Linkify.addLinks(s, Linkify.ALL);
            alertDialogBuilder
                    .setTitle("Welcome to OpenSeizureDetector")
                    .setMessage(s)
                    .setCancelable(false)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            mDialogDisplayed = false;
                            //MainActivity.this.finish();
                        }
                    });
            FirstRunDialog = alertDialogBuilder.create();
            Log.i(TAG, "Displaying First Run Dialog");
            FirstRunDialog.show();
            mDialogDisplayed = true;
        } else if (!storedVersionName.equals(versionName)) {
            // Check for update of installed application
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                    this);
            final SpannableString s = new SpannableString(
                    "OpenSeizureDetector does not collect any personal data. "
                            + "This does mean that it is not possible for me to contact users if I find an "
                            + "issue with the app that you should be aware of.   \nPlease subscribe to updates at "
                            + "http://openseizuredetector.org.uk, or the app Facebook page at https://www.facebook.com/openseizuredetector. "
                            + "so I can get in touch if necessary.\nThank you!  Graham \ngraham@openseizuredetector.org.uk "
                            + "\n\nChanges in this version:"
                            + "\n- Added support for 'Wifi Datasources' - initially for the experimental ESP8266 based seizure detector."
                            + "\n- Improved logging of network status to help debugging network data source issues."
                            + "\n- "
            );
            // This makes the links display as links, but they do not respond to clicks for some reason...
            Linkify.addLinks(s, Linkify.ALL);
            alertDialogBuilder
                    .setTitle("Thank you for Updating OpenSeizureDetector")
                    .setMessage(s)
                    .setCancelable(false)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
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

}
