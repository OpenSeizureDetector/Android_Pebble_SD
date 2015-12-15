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
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;

/**
 * StartupActivity is shown on app start-up.  It starts the SdServer background service and waits
 * for it to start and to receive data and settings from the seizure detector before exiting and
 * starting the main activity.
 */
public class StartupActivity extends Activity {
    private String TAG = "StartupActivity";
    private int okColour = Color.BLUE;
    private int warnColour = Color.MAGENTA;
    private int alarmColour = Color.RED;
    private int okTextColour = Color.WHITE;
    private int warnTextColour = Color.BLACK;
    private int alarmTextColour = Color.BLACK;


    private OsdUtil mUtil;
    private Timer mUiTimer;
    private SdServiceConnection mConnection;
    final Handler mServerStatusHandler = new Handler();   // used to update ui from mUiTimer


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.startup_activity);
        mUtil = new OsdUtil(this);

        Button b = (Button)findViewById(R.id.settingsButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "settings button clicked");
                try {
                    Intent intent = new Intent(
                            StartupActivity.this,
                            PrefActivity.class);
                    startActivity(intent);
                } catch (Exception ex) {
                    Log.v(TAG, "exception starting settings activity " + ex.toString());
                }

            }
        });

        b = (Button)findViewById(R.id.pebbleButton);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "pebble button clicked");
                try {
                    PackageManager pm = getPackageManager();
                    Intent pebbleAppIntent = pm.getLaunchIntentForPackage("com.getpebble.android");
                    startActivity(pebbleAppIntent);
                } catch (Exception ex) {
                    Log.v(TAG, "exception starting pebble App " + ex.toString());
                }

            }
        });

    }

    @Override
    protected void onStart() {
        super.onStart();

        // Display the DataSource name
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());;
        String dataSourceName = SP.getString("DataSource","undefined");
        TextView tv = (TextView)findViewById(R.id.dataSourceTextView);
        tv.setText("DataSource = "+dataSourceName);

        // disable pebble configuration button if we have not selected the pebble datasource
        if (!dataSourceName.equals("Pebble")) {
            Log.v(TAG, "Not Pebble Datasource - deactivating Pebble Button");
            Button b = (Button) findViewById(R.id.pebbleButton);
            b.setEnabled(false);
        }

        if (mUtil.isServerRunning()) {
            Log.v(TAG, "onStart() - server running - stopping it");
            mUtil.stopServer();
        }
        mUtil.startServer();

        // Bind to the service.
        mConnection = new SdServiceConnection(this);
        mUtil.bindToServer(this, mConnection);

        // start timer to refresh user interface every second.
        mUiTimer = new Timer();
        mUiTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mServerStatusHandler.post(serverStatusRunnable);
                //updateServerStatus();
            }
        }, 0, 1000);

    }

    @Override
    protected void onStop() {
        Log.v(TAG, "onStop()");
        super.onStop();
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

            // Service Running
            tv = (TextView) findViewById(R.id.textItem1);
            pb = (ProgressBar) findViewById(R.id.progressBar1);
            if (mUtil.isServerRunning()) {
                tv.setText("Background Service Running OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Waiting for Background Service...");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
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
                tv.setText("Waiting to Connect to Pebble Watch.....");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
            }

            // Is Pebble Watch App Running?
            tv = (TextView) findViewById(R.id.textItem4);
            pb = (ProgressBar) findViewById(R.id.progressBar4);
            if (mConnection.pebbleAppRunning()) {
                tv.setText("Watch App Running OK");
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                pb.setIndeterminateDrawable(getResources().getDrawable(R.drawable.start_server));
                pb.setProgressDrawable(getResources().getDrawable(R.drawable.start_server));
            } else {
                tv.setText("Waiting for Watch App to Start.....");
                tv.setBackgroundColor(alarmColour);
                tv.setTextColor(alarmTextColour);
                pb.setIndeterminate(true);
                allOk = false;
            }


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


            // If all the parameters are ok, close this activity and open the main
            // user interface activity instead.
            if (allOk) {
                Log.v(TAG, "starting main activity...");
                try {
                    Intent intent = new Intent(
                            getApplicationContext(),
                            MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                    finish();
                } catch (Exception ex) {
                    Log.v(TAG, "exception starting settings activity " + ex.toString());
                }

            }
        }
    };
}
