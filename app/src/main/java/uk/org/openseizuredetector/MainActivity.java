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
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Button;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.http.conn.util.InetAddressUtils;

//MPAndroidChart
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

public class MainActivity extends Activity {
    static final String TAG = "MainActivity";
    private int okColour = Color.BLUE;
    private int warnColour = Color.MAGENTA;
    private int alarmColour = Color.RED;
    private OsdUtil mUtil;
    private SdServiceConnection mConnection;
    private Menu mOptionsMenu;

    private Intent sdServerIntent;

    final Handler serverStatusHandler = new Handler();
    Messenger messenger = new Messenger(new ResponseHandler());

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUtil = new OsdUtil(this);
        mConnection = new SdServiceConnection(this);

        // Initialise the User Interface
        setContentView(R.layout.main);

	/* Force display of overflow menu - from stackoverflow
     * "how to force use of..."
	 */
        try {
            ViewConfiguration config = ViewConfiguration.get(this);
            Field menuKeyField =
                    ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
            if (menuKeyField != null) {
                menuKeyField.setAccessible(true);
                menuKeyField.setBoolean(config, false);
            }
        } catch (Exception e) {
            Log.v(TAG, "menubar fiddle exception: " + e.toString());
        }

        // Force the screen to stay on when the app is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Deal with the 'AcceptAlarm Button'
        Button button = (Button) findViewById(R.id.acceptAlarmButton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.v(TAG, "acceptAlarmButton.onClick()");
                if (mConnection.mBound) {
                    mConnection.mSdServer.acceptAlarm();
                }
            }
        });

        // start timer to refresh user interface every second.
        Timer uiTimer = new Timer();
        uiTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                updateServerStatus();
            }
        }, 0, 1000);

    }

    /**
     * Create Action Bar
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_activity_actions, menu);
        mOptionsMenu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.v(TAG, "Option " + item.getItemId() + " selected");
        switch (item.getItemId()) {
            case R.id.action_launch_pebble_app:
                Log.v(TAG, "action_launch_pebble_app");
                try {
                    PackageManager pm = this.getPackageManager();
                    Intent pebbleAppIntent = pm.getLaunchIntentForPackage("com.getpebble.android");
                    this.startActivity(pebbleAppIntent);
                } catch (Exception ex) {
                    Log.v(TAG, "exception starting pebble App " + ex.toString());
                }
                return true;

            case R.id.action_accept_alarm:
                Log.v(TAG, "action_accept_alarm");
                if (mConnection.mBound) {
                    mConnection.mSdServer.acceptAlarm();
                }
                return true;
            case R.id.action_start_stop:
                // Respond to the start/stop server menu item.
                Log.v(TAG, "action_sart_stop");
                if (mConnection.mBound) {
                    Log.v(TAG, "Stopping Server");
                    mUtil.unbindFromServer(this, mConnection);
                    stopServer();
                } else {
                    Log.v(TAG, "Starting Server");
                    startServer();
                    // and bind to it so we can see its data
                    mUtil.bindToServer(this, mConnection);
                }
                return true;
            case R.id.action_test_fault_beep:
                Log.v(TAG, "action_test_fault_beep");
                if (mConnection.mBound) {
                    mConnection.mSdServer.faultWarningBeep();
                }
                return true;
            case R.id.action_test_alarm_beep:
                Log.v(TAG, "action_test_alarm_beep");
                if (mConnection.mBound) {
                    mConnection.mSdServer.alarmBeep();
                }
                return true;
            case R.id.action_test_warning_beep:
                Log.v(TAG, "action_test_warning_beep");
                if (mConnection.mBound) {
                    mConnection.mSdServer.warningBeep();
                }
                return true;
            case R.id.action_test_sms_alarm:
                Log.v(TAG, "action_test_sms_alarm");
                if (mConnection.mBound) {
                    mConnection.mSdServer.sendSMSAlarm();
                }
                return true;
            case R.id.action_settings:
                Log.v(TAG, "action_settings");
                try {
                    Intent prefsIntent = new Intent(
                            MainActivity.this,
                            PrefActivity.class);
                    this.startActivity(prefsIntent);
                } catch (Exception ex) {
                    Log.v(TAG, "exception starting settings activity " + ex.toString());
                }
                return true;
            case R.id.action_about:
                Log.v(TAG, "action_about");
                showAbout();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        boolean audibleAlarm = SP.getBoolean("AudibleAlarm", true);
        Log.v(TAG, "onStart - auidbleAlarm = " + audibleAlarm);

        TextView tv;
        tv = (TextView) findViewById(R.id.versionTv);
        String versionName = mUtil.getAppVersionName();
        tv.setText("OpenSeizureDetector Server Version " + versionName);

        mUtil.bindToServer(this, mConnection);

    }

    @Override
    protected void onStop() {
        super.onStop();
        mUtil.unbindFromServer(this,mConnection);
    }


    private void startServer() {
        mUtil.startServer();
        // Change the action bar icon to show the option to stop the service.
        if (mOptionsMenu != null) {
            Log.v(TAG, "Changing menu icons");
            MenuItem menuItem = mOptionsMenu.findItem(R.id.action_start_stop);
            menuItem.setIcon(R.drawable.stop_server);
            menuItem.setTitle("Stop Server");
        } else {
            Log.v(TAG, "mOptionsMenu is null - not changing icons!");
        }
    }

    private void stopServer() {
        Log.v(TAG, "stopping Server...");
        mUtil.stopServer();
        // Change the action bar icon to show the option to start the service.
        if (mOptionsMenu != null) {
            Log.v(TAG, "Changing action bar icons");
            mOptionsMenu.findItem(R.id.action_start_stop).setIcon(R.drawable.start_server);
            mOptionsMenu.findItem(R.id.action_start_stop).setTitle("Start Server");
        } else {
            Log.v(TAG, "mOptionsMenu is null, not changing icons!");
        }
    }



    /*
     * updateServerStatus - called by the uiTimer timer periodically.
     * requests the ui to be updated by calling serverStatusRunnable.
     */
    private void updateServerStatus() {
        serverStatusHandler.post(serverStatusRunnable);
    }

    /*
     * serverStatusRunnable - called by serverStatus - updates the
     * user interface to reflect the current status received from the server.
     */
    final Runnable serverStatusRunnable = new Runnable() {
        public void run() {
            TextView tv;
            tv = (TextView) findViewById(R.id.textView1);
            if (mUtil.isServerRunning()) {
                tv.setText("Server Running OK");
                tv.setBackgroundColor(okColour);
                tv = (TextView) findViewById(R.id.textView2);
                tv.setText("Access Server at http://"
                        + mUtil.getLocalIpAddress()
                        + ":8080");
                tv.setBackgroundColor(okColour);
            } else {
                tv.setText("*** Server Stopped ***");
                tv.setBackgroundColor(alarmColour);
            }


            try {
                if (mConnection.mBound) {
                    tv = (TextView) findViewById(R.id.alarmTv);
                    if ((mConnection.mSdServer.mSdData.alarmState == 0)
                            && !mConnection.mSdServer.mSdData.alarmStanding
                            && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                        tv.setText("OK");
                        tv.setBackgroundColor(okColour);
                    }
                    if ((mConnection.mSdServer.mSdData.alarmState == 1)
                            && !mConnection.mSdServer.mSdData.alarmStanding
                            && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                        tv.setText("WARNING");
                        tv.setBackgroundColor(warnColour);
                    }
                    if (mConnection.mSdServer.mSdData.alarmStanding) {
                        tv.setText("**ALARM**");
                        tv.setBackgroundColor(alarmColour);
                    }
                    if (mConnection.mSdServer.mSdData.fallAlarmStanding) {
                        tv.setText("**FALL**");
                        tv.setBackgroundColor(alarmColour);
                    }
                    tv = (TextView) findViewById(R.id.pebTimeTv);
                    tv.setText(mConnection.mSdServer.mSdData.dataTime.format("%H:%M:%S"));
                    // Pebble Connected Phrase
                    tv = (TextView) findViewById(R.id.pebbleTv);
                    if (mConnection.mSdServer.mSdData.pebbleConnected) {
                        tv.setText("Pebble Watch Connected OK");
                        tv.setBackgroundColor(okColour);
                    } else {
                        tv.setText("** Pebble Watch NOT Connected **");
                        tv.setBackgroundColor(alarmColour);
                    }
                    tv = (TextView) findViewById(R.id.appTv);
                    if (mConnection.mSdServer.mSdData.pebbleAppRunning) {
                        tv.setText("Pebble App OK");
                        tv.setBackgroundColor(okColour);
                    } else {
                        tv.setText("** Pebble App NOT Running **");
                        tv.setBackgroundColor(alarmColour);
                    }
                    tv = (TextView) findViewById(R.id.battTv);
                    tv.setText("Pebble Battery = " + String.valueOf(mConnection.mSdServer.mSdData.batteryPc) + "%");
                    if (mConnection.mSdServer.mSdData.batteryPc <= 20)
                        tv.setBackgroundColor(alarmColour);
                    if (mConnection.mSdServer.mSdData.batteryPc > 20)
                        tv.setBackgroundColor(warnColour);
                    if (mConnection.mSdServer.mSdData.batteryPc >= 40)
                        tv.setBackgroundColor(okColour);

                    tv = (TextView) findViewById(R.id.debugTv);
                    String specStr = "";
                    for (int i = 0; i < 10; i++)
                        specStr = specStr
                                + mConnection.mSdServer.mSdData.simpleSpec[i]
                                + ", ";
                    tv.setText("Spec = " + specStr);
                } else {
                    tv = (TextView) findViewById(R.id.alarmTv);
                    tv.setText("Not Connected to Server");
                    tv.setBackgroundColor(warnColour);
                }
            } catch (Exception e) {
                Log.v(TAG, "ServerStatusRunnable: Exception - " + e.toString());
            }
            ////////////////////////////////////////////////////////////
            // Produce graph
            LineChart mChart = (LineChart) findViewById(R.id.chart1);
            mChart.setDescription("");
            mChart.setNoDataTextDescription("You need to provide data for the chart.");
            // X Values
            ArrayList<String> xVals = new ArrayList<String>();
            for (int i = 0; i < 10; i++) {
                xVals.add((i) + "");
            }
            // Y Values
            ArrayList<Entry> yVals = new ArrayList<Entry>();
            for (int i = 0; i < 10; i++) {
                if (mConnection.mSdServer != null)
                    yVals.add(new Entry(mConnection.mSdServer.mSdData.simpleSpec[i], i));
                else
                    yVals.add(new Entry(i, i));
            }

            // create a dataset and give it a type
            LineDataSet set1 = new LineDataSet(yVals, "DataSet 1");
            set1.setColor(Color.BLACK);
            set1.setLineWidth(1f);

            ArrayList<LineDataSet> dataSets = new ArrayList<LineDataSet>();
            dataSets.add(set1); // add the datasets
            LineData data = new LineData(xVals, dataSets);
            //data.setValueTextSize(10f);
            mChart.setData(data);
            mChart.invalidate();
        }
    };


    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }



    private void showAbout() {
        View aboutView = getLayoutInflater().inflate(R.layout.about_layout, null, false);
        String versionName = mUtil.getAppVersionName();
        Log.v(TAG, "showAbout() - version name = " + versionName);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(R.drawable.icon_24x24);
        builder.setTitle("OpenSeizureDetector V"+versionName);
        builder.setView(aboutView);
        builder.create();
        builder.show();
    }

    class ResponseHandler extends Handler {
        @Override
        public void handleMessage(Message message) {
            Log.v(TAG, "Message=" + message.toString());
        }
    }

}
