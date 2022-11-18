package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;

import androidx.core.view.MenuCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogManagerControlActivity extends AppCompatActivity {
    private String TAG = "LogManagerControlActivity";
    private LogManager mLm;
    private Context mContext;
    private UiTimer mUiTimer;
    private ArrayList<HashMap<String, String>> mEventsList;
    private ArrayList<HashMap<String, String>> mRemoteEventsList;
    private ArrayList<HashMap<String, String>> mSysLogList;
    private SdServiceConnection mConnection;
    private OsdUtil mUtil;
    final Handler serverStatusHandler = new Handler();
    private Integer mUiTimerPeriodFast = 2000;  // 2 seconds - we use fast updating while UI is blank and we are waiting for first data
    private Integer mUiTimerPeriodSlow = 60000; // 60 seconds - once data has been received and UI populated we only update once per minute.
    private boolean mUpdateSysLog = true;
    private Menu mMenu;
    //private Integer UI_MODE_LOCAL = 0;
    //private Integer UI_MODE_SHARED = 1;
    //private Integer mUiMode = UI_MODE_SHARED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        mContext = this;
        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);

        if (!mUtil.isServerRunning()) {
            mUtil.showToast(getString(R.string.error_server_not_running));
            finish();
            return;
        }

        mConnection = new SdServiceConnection(getApplicationContext());

        setContentView(R.layout.activity_log_manager_control);

        /* Force display of overflow menu - from stackoverflow
         * "how to force use of..."
         */
        try {
            Log.v(TAG, "trying menubar fiddle...");
            ViewConfiguration config = ViewConfiguration.get(this);
            Field menuKeyField =
                    ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
            if (menuKeyField != null) {
                Log.v(TAG, "menuKeyField is not null - configuring....");
                menuKeyField.setAccessible(true);
                menuKeyField.setBoolean(config, false);
            } else {
                Log.v(TAG, "menuKeyField is null - doing nothing...");
            }
        } catch (Exception e) {
            Log.v(TAG, "menubar fiddle exception: " + e.toString());
        }

        Button authBtn =
                (Button) findViewById(R.id.auth_button);
        authBtn.setOnClickListener(onAuth);
        //Button pruneBtn =
        //        (Button) findViewById(R.id.pruneDatabaseBtn);
        //pruneBtn.setOnClickListener(onPruneBtn);
        //Button reportSeizureBtn =
        //        (Button) findViewById(R.id.reportSeizureBtn);
        //reportSeizureBtn.setOnClickListener(onReportSeizureBtn);
        Button remoteDbBtn =
                (Button) findViewById(R.id.refresh_button);
        remoteDbBtn.setOnClickListener(onRefreshBtn);

        CheckBox includeWarningsCb =
                (CheckBox) findViewById(R.id.include_warnings_cb);
        includeWarningsCb.setOnCheckedChangeListener(onIncludeWarningsCb);
        CheckBox includeNDACb =
                (CheckBox) findViewById(R.id.include_nda_cb);
        includeNDACb.setOnCheckedChangeListener(onIncludeNDACb);

        ListView lv = (ListView) findViewById(R.id.eventLogListView);
        lv.setOnItemClickListener(onEventListClick);

        lv = (ListView) findViewById(R.id.remoteEventsLv);
        lv.setOnItemClickListener(onRemoteEventListClick);
    }

    /**
     * Create Action Bar
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.i(TAG, "onCreateOptionsMenu()");
        getMenuInflater().inflate(R.menu.log_manager_activity_menu, menu);
        MenuCompat.setGroupDividerEnabled(menu, true);
        mMenu = menu;
        MenuItem startStopNDAMenuItem = mMenu.findItem(R.id.start_stop_nda);
        if (mConnection.mSdServer.mLm.mLogNDA) {
            startStopNDAMenuItem.setTitle(R.string.stop_nda_menu_title);
        } else {
            startStopNDAMenuItem.setTitle(R.string.start_nda_menu_title);
        }

        return true;
    }


    @Override
    protected void onStart() {
        Log.v(TAG, "onStart()");
        super.onStart();
        mUtil.bindToServer(getApplicationContext(), mConnection);
        waitForConnection();
        startUiTimer(mUiTimerPeriodFast);
    }

    @Override
    protected void onStop() {
        Log.v(TAG, "onStop()");
        super.onStop();
        stopUiTimer();
        mUtil.unbindFromServer(getApplicationContext(), mConnection);
    }

    @Override
    protected void onPause() {
        Log.v(TAG, "onPause()");
        super.onPause();
        //stopUiTimer();
    }

    @Override
    protected void onResume() {
        Log.v(TAG, "onResume()");
        super.onResume();
        //startUiTimer();
    }

    private void waitForConnection() {
        // We want the UI to update as soon as it is displayed, but it takes a finite time for
        // the mConnection to bind to the service, so we delay half a second to give it chance
        // to connect before trying to update the UI for the first time (it happens again periodically using the uiTimer)
        if (mConnection.mBound) {
            Log.v(TAG, "waitForConnection - Bound!");
            initialiseServiceConnection();
        } else {
            Log.v(TAG, "waitForConnection - waiting...");
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    waitForConnection();
                }
            }, 100);
        }
    }

    // FIXME - for some reason this never gets called, which is why we have the 'waitForConnection()'
    //         function that polls the connection until it is connected.
    public void onServiceConnected(ComponentName name, IBinder service) {
        Log.w(TAG, "onServiceConnected()");
        initialiseServiceConnection();
    }

    private void initialiseServiceConnection() {
        mLm = mConnection.mSdServer.mLm;
        startUiTimer(mUiTimerPeriodFast);

        final CheckBox includeWarningsCb = (CheckBox) findViewById(R.id.include_warnings_cb);
        final CheckBox includeNDACb = (CheckBox) findViewById(R.id.include_nda_cb);
        getRemoteEvents(includeWarningsCb.isChecked(), includeNDACb.isChecked());
        ProgressBar pb = (ProgressBar)findViewById(R.id.remoteAccessPb);
        pb.setIndeterminate(true);
        pb.setVisibility(View.VISIBLE);
        // Populate events list - we only do it once when the activity is created because the query might slow down the UI.
        // We could try this code in updateUI() and see though.
        // Based on https://www.tutlane.com/tutorial/android/android-sqlite-listview-with-examples
        mLm.getEventsList(true, (ArrayList<HashMap<String, String>> eventsList) -> {
            mEventsList = eventsList;
            Log.v(TAG, "initialiseServiceConnection() - set mEventsList - Updating UI");
            updateUi();
        });
        mUtil.getSysLogList((ArrayList<HashMap<String, String>> syslogList) -> {
            mSysLogList = syslogList;
            Log.v(TAG, "initialiseServiceConnection() - set mSysLogList - Updating UI");
            updateUi();
        });
    }


    private void getRemoteEvents(boolean includeWarnings, boolean includeNDA) {
        mRemoteEventsList = null;  // clear existing data
        // Retrieve events from remote database
        mLm.mWac.getEvents((JSONObject remoteEventsObj) -> {
            Log.v(TAG, "getRemoteEvents()");
            if (remoteEventsObj == null) {
                Log.e(TAG, "getRemoteEvents Callback:  Error Retrieving events");
                mUtil.showToast("Error Retrieving Remote Events from Server - Please Try Again Later!");
            } else {
                //Log.v(TAG, "remoteEventsObj = " + remoteEventsObj.toString());
                try {
                    JSONArray eventsArray = remoteEventsObj.getJSONArray("events");
                    mRemoteEventsList = new ArrayList<HashMap<String, String>>();
                    // A bit of a hack to display in reverse chronological order
                    for (int i = eventsArray.length() - 1; i >= 0; i--) {
                        JSONObject eventObj = eventsArray.getJSONObject(i);
                        Log.v(TAG, "getRemoteEvents() - " + eventObj.toString());
                        String id = null;
                        if (!eventObj.isNull("id")) {
                            id = eventObj.getString("id");
                        }
                        int osdAlarmState = -1;
                        if (!eventObj.isNull("osdAlarmState")) {
                            osdAlarmState = eventObj.getInt("osdAlarmState");
                        }
                        String dataTime = "null";
                        if (!eventObj.isNull("dataTime")) {
                            dataTime = eventObj.getString("dataTime");
                            Log.v(TAG, "getRemoteEvents() - dataTime=" + dataTime);
                        }
                        String typeStr = "null";
                        if (!eventObj.isNull("type")) {
                            typeStr = eventObj.getString("type");
                        }
                        String subType = "null";
                        if (!eventObj.isNull("subType")) {
                            subType = eventObj.getString("subType");
                        }
                        String desc = "null";
                        if (!eventObj.isNull("desc")) {
                            desc = eventObj.getString("desc");
                        }
                        HashMap<String, String> eventHashMap = new HashMap<String, String>();
                        eventHashMap.put("id", id);
                        eventHashMap.put("osdAlarmState", String.valueOf(osdAlarmState));
                        eventHashMap.put("osdAlarmStateStr", mUtil.alarmStatusToString(osdAlarmState));
                        eventHashMap.put("dataTime", dataTime);
                        eventHashMap.put("type", typeStr);
                        eventHashMap.put("subType", subType);
                        eventHashMap.put("desc", desc);
                        if ((osdAlarmState!=1 | includeWarnings) &&
                            (osdAlarmState!=6 | includeNDA)) {
                            mRemoteEventsList.add(eventHashMap);
                        } else {
                            Log.v(TAG,"getRemoteEvents - skipping warning or NDA record");
                        }
                    }
                    Log.v(TAG, "getRemoteEvents() - set mRemoteEventsList().  Updating UI");
                    updateUi();
                } catch (JSONException e) {
                    Log.e(TAG, "getRemoteEvents(): Error Parsing remoteEventsObj: " + e.getMessage());
                    mUtil.showToast("Error Parsing remoteEventsObj - this should not happen!!!");
                    mRemoteEventsList = null;
                }
                //Log.v(TAG, "getRemoteEvents(): mRemoteEventsList = " + mRemoteEventsList.toString());
            }
        });
    }


    private void updateUi() {
        Log.i(TAG, "updateUi()");
        boolean stopUpdating = true;
        TextView tv;
        Button btn;
        // Local Database Information
        if (mLm != null) {
            mLm.getLocalEventsCount(true, (Long eventCount) -> {
                TextView tv1 = (TextView) findViewById(R.id.num_local_events_tv);
                tv1.setText(String.format("%d", eventCount));
            });
            mLm.getLocalDatapointsCount((Long datapointsCount) -> {
                TextView tv2 = (TextView) findViewById(R.id.num_local_datapoints_tv);
                tv2.setText(String.format("%d", datapointsCount));
            });
            TextView tv3 = (TextView)findViewById(R.id.nda_time_remaining_tv);
            tv3.setText(String.format("%.1f hrs",mLm.mNDATimeRemaining));
            Log.d(TAG,"mNDATimeRemaining = "+String.format("%.1f hrs",mLm.mNDATimeRemaining));
        } else {
            stopUpdating = false;
        }
        // Local Database ListView
        if (mEventsList != null) {
            ListView lv = (ListView) findViewById(R.id.eventLogListView);
            ListAdapter adapter = new SimpleAdapter(LogManagerControlActivity.this, mEventsList, R.layout.log_entry_layout,
                    new String[]{"dataTime", "status", "uploaded"},
                    new int[]{R.id.event_date, R.id.event_alarmState, R.id.event_uploaded});
            lv.setAdapter(adapter);
            //Log.v(TAG,"eventsList="+mEventsList);
        } else {
            stopUpdating = false;
        }
        // SysLog ListView
        if (mSysLogList != null && mUpdateSysLog) {
            ListView lv = (ListView) findViewById(R.id.sysLogListView);
            ListAdapter adapter = new SimpleAdapter(LogManagerControlActivity.this, mSysLogList, R.layout.syslog_entry_layout,
                    new String[]{"dataTime", "logLevel", "dataJSON"},
                    new int[]{R.id.syslog_entry_date_tv, R.id.syslog_level_tv, R.id.syslog_entry_text_tv});
            lv.setAdapter(adapter);
            //Log.v(TAG,"eventsList="+mEventsList);
            mUpdateSysLog = false;
        }
        // Remote Database List View
        if (mRemoteEventsList != null) {
            ProgressBar pb = (ProgressBar)findViewById(R.id.remoteAccessPb);
            pb.setIndeterminate(false);
            pb.setVisibility(View.INVISIBLE);
            ListView lv = (ListView) findViewById(R.id.remoteEventsLv);
            ListAdapter adapter = new RemoteEventsAdapter(LogManagerControlActivity.this, mRemoteEventsList, R.layout.log_entry_layout_remote,
                    new String[]{"id", "dataTime", "type", "subType", "osdAlarmStateStr", "desc"},
                    new int[]{R.id.event_id_remote_tv, R.id.event_date_remote_tv, R.id.event_type_remote_tv, R.id.event_subtype_remote_tv,
                            R.id.event_alarmState_remote_tv, R.id.event_notes_remote_tv});
            lv.setAdapter(adapter);
            //Log.i(TAG,"adapter[0]="+adapter.getItem(0));
            //Log.i(TAG,"adapter[3]="+adapter.getItem(3));
        } else {
            //mUtil.showToast("No Remote Events");
            Log.i(TAG, "UpdateUi: No Remote Events");
            stopUpdating = false;
        }


        // Remote Database Information
        if (mLm != null) {
            tv = (TextView) findViewById(R.id.authStatusTv);
            btn = (Button) findViewById(R.id.auth_button);
            if (mLm.mWac.isLoggedIn()) {
                tv.setText(getString(R.string.logged_in_with_token));
                btn.setText(getString(R.string.logout));
            } else {
                tv.setText(getString(R.string.not_authenticated));
                btn.setText(getString(R.string.login));
            }
        } else {
            stopUpdating = false;
        }

        // Note we do not really stop updating the UI, just change from the fast update period to the slow update period
        // to save hammering the databases once the UI has been populated once.
        if (stopUpdating) {
            stopUiTimer();
            //startUiTimer(mUiTimerPeriodSlow);
        }

    }  //updateUi();

    public void onRadioButtonClicked(View view) {
        LinearLayout localDataLl = (LinearLayout) findViewById(R.id.local_data_ll);
        LinearLayout sharedDataLl = (LinearLayout) findViewById(R.id.shared_data_ll);
        LinearLayout syslogLl = (LinearLayout) findViewById(R.id.syslog_ll);
        // Is the button now checked?
        boolean checked = ((RadioButton) view).isChecked();

        // Check which radio button was clicked
        switch (view.getId()) {
            case R.id.local_data_rb:
                if (checked) {
                    // Switch to the local data view
                    localDataLl.setVisibility(View.VISIBLE);
                    sharedDataLl.setVisibility(View.GONE);
                    syslogLl.setVisibility(View.GONE);
                }
                break;
            case R.id.shared_data_rb:
                if (checked) {
                    // Switch to the local data view
                    localDataLl.setVisibility(View.GONE);
                    sharedDataLl.setVisibility(View.VISIBLE);
                    syslogLl.setVisibility(View.GONE);
                }
                break;
            case R.id.syslog_rb:
                if (checked) {
                    // Switch to the local data view
                    localDataLl.setVisibility(View.GONE);
                    sharedDataLl.setVisibility(View.GONE);
                    syslogLl.setVisibility(View.VISIBLE);
                }
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "onOptionsItemSelected() :  " + item.getItemId() + " selected");
        switch (item.getItemId()) {
            case R.id.action_authenticate_api:
                Log.i(TAG, "action_autheticate_api");
                try {
                    Intent i = new Intent(
                            getApplicationContext(),
                            AuthenticateActivity.class);
                    this.startActivity(i);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting export activity " + ex.toString());
                }
                return true;
            case R.id.pruneDatabaseMenuItem:
                Log.i(TAG, "action_pruneDatabase");
                onPruneBtn.onClick(null);
                return true;
            case R.id.action_report_seizure:
                Log.i(TAG, "action_report_seizure");
                try {
                    Intent intent = new Intent(
                            getApplicationContext(),
                            ReportSeizureActivity.class);
                    this.startActivity(intent);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting Report Seizure activity " + ex.toString());
                }
                return true;
            case R.id.action_settings:
                Log.i(TAG, "action_settings");
                try {
                    Intent prefsIntent = new Intent(
                            getApplicationContext(),
                            PrefActivity.class);
                    this.startActivity(prefsIntent);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting settings activity " + ex.toString());
                }
                return true;
            case R.id.start_stop_nda:
                // FIXME: When we use this we get left in a state with two processes running and the system
                //        alternating between OK and FAULT - I don't know why yet!
                Log.i(TAG,"start/stop NDA");
                if (mConnection.mSdServer.mLm.mLogNDA) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.stop_nda_logging_dialog_title)
                            .setMessage(R.string.stop_nda_logging_dialog_meassage)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    mLm.disableNDATimer();
                                    MenuItem startStopNDAMenuItem = mMenu.findItem(R.id.start_stop_nda);
                                    startStopNDAMenuItem.setTitle(R.string.start_nda_menu_title);
                                    mUtil.restartServer();
                                }
                            })
                            .setNegativeButton(android.R.string.no, null)
                            .show();

                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.start_nda_logging_dialog_title)
                            .setMessage(R.string.start_nda_logging_dialog_meassage)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    mLm.enableNDATimer();
                                    MenuItem startStopNDAMenuItem = mMenu.findItem(R.id.start_stop_nda);
                                    startStopNDAMenuItem.setTitle(R.string.stop_nda_menu_title);
                                    mUtil.restartServer();
                                }
                            })
                            .setNegativeButton(android.R.string.no, null)
                            .show();

                }

                return true;
            case R.id.action_mark_unknown:
                Log.i(TAG, "action_mark_unknown");
                new AlertDialog.Builder(this)
                        .setTitle(R.string.mark_unverified_events_unknown_dialog_title)
                        .setMessage(R.string.mark_unverified_events_unknown_dialog_message)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                mLm.mWac.markUnverifiedEventsAsUnknown();
                            }
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .show();
            case R.id.action_mark_false_alarm:
                Log.i(TAG, "action_mark_false_alarm");
                new AlertDialog.Builder(this)
                        .setTitle(R.string.mark_unverified_events_false_alarm_dialog_title)
                        .setMessage(R.string.mark_unverified_events_false_alarm_dialog_message)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                mLm.mWac.markUnverifiedEventsAsFalseAlarm();
                            }
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .show();
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    View.OnClickListener onAuth =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onAuth");
                    Intent i;
                    i = new Intent(mContext, AuthenticateActivity.class);
                    startActivity(i);
                }
            };
    View.OnClickListener onPruneBtn =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onPruneBtn");
                    // Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                    builder.setTitle("Prune Database");
                    builder.setMessage(String.format("This will remove all data from the database that is more than %d days old."
                            + "\nThis can NOT be undone.\nAre you sure?", mLm.mDataRetentionPeriod));
                    builder.setPositiveButton("YES", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mLm.pruneLocalDb();
                            dialog.dismiss();
                        }
                    });
                    builder.setNegativeButton("NO", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    AlertDialog alert = builder.create();
                    alert.show();
                }
            };

    View.OnClickListener onReportSeizureBtn =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onReportSeizureBtn");
                    Intent i;
                    i = new Intent(mContext, ReportSeizureActivity.class);
                    startActivity(i);
                }
            };

    View.OnClickListener onRemoteDbBtn =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onRemoteDbBtn");
                    Intent i;
                    i = new Intent(mContext, RemoteDbActivity.class);
                    startActivity(i);
                }
            };

    View.OnClickListener onRefreshBtn =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onRefreshBtn");
                    initialiseServiceConnection();
                }
            };

    CompoundButton.OnCheckedChangeListener onIncludeWarningsCb =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                    Log.v(TAG, "onIncludeWarningsCb");
                    initialiseServiceConnection();
                }
            };

    CompoundButton.OnCheckedChangeListener onIncludeNDACb =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                    Log.v(TAG, "onIncludeNDACb");
                    initialiseServiceConnection();
                }
            };


    AdapterView.OnItemClickListener onEventListClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onItemClicKListener() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    HashMap<String, String> eventObj = (HashMap<String, String>) adapter.getItemAtPosition(position);
                    String eventId = eventObj.get("uploaded");
                    Log.d(TAG, "onItemClickListener(): eventId=" + eventId + ", eventObj=" + eventObj);
                    if (eventId != null) {
                        Intent i = new Intent(getApplicationContext(), EditEventActivity.class);
                        i.putExtra("eventId", eventId);
                        startActivity(i);
                    } else {
                        mUtil.showToast("You Must Wait for Event to Upload before Editing it");
                    }
                }
            };

    AdapterView.OnItemClickListener onRemoteEventListClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onRemoteEventList Click() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    HashMap<String, String> eventObj = (HashMap<String, String>) adapter.getItemAtPosition(position);
                    String eventId = eventObj.get("id");
                    Log.d(TAG, "onItemClickListener(): eventId=" + eventId + ", eventObj=" + eventObj);
                    Intent i = new Intent(getApplicationContext(), EditEventActivity.class);
                    i.putExtra("eventId", eventId);
                    startActivity(i);
                }
            };


    /*
     * Start the timer that will update the user interface every 5 seconds..
     */
    private void startUiTimer(Integer uiTimerPeriod) {
        if (mUiTimer != null) {
            Log.v(TAG, "startUiTimer -timer already running - cancelling it");
            mUiTimer.cancel();
            mUiTimer = null;
        }
        Log.v(TAG, "startUiTimer() - starting UiTimer");
        mUiTimer =
                new UiTimer(uiTimerPeriod, 1000);
        mUiTimer.start();
    }


    /*
     * Cancel the remote logging timer to prevent attempts to upload to remote database.
     */
    public void stopUiTimer() {
        if (mUiTimer != null) {
            Log.v(TAG, "stopUiTimer(): cancelling UI timer");
            mUiTimer.cancel();
            mUiTimer = null;
        }
    }

    /**
     * Upload recorded data to the remote database periodically.
     */
    private class UiTimer extends CountDownTimer {
        public UiTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onTick(long l) {
            // Do Nothing
        }

        @Override
        public void onFinish() {
            //Log.v(TAG, "UiTimer - onFinish - Updating UI");
            updateUi();
            // Restart this timer.
            if (mUiTimer != null)
                start();
        }

    }


    private class RemoteEventsAdapter extends SimpleAdapter {

        /**
         * Constructor
         *
         * @param context  The context where the View associated with this SimpleAdapter is running
         * @param data     A List of Maps. Each entry in the List corresponds to one row in the list. The
         *                 Maps contain the data for each row, and should include all the entries specified in
         *                 "from"
         * @param resource Resource identifier of a view layout that defines the views for this list
         *                 item. The layout file should include at least those named views defined in "to"
         * @param from     A list of column names that will be added to the Map associated with each
         *                 item.
         * @param to       The views that should display column in the "from" parameter. These should all be
         *                 TextViews. The first N views in this list are given the values of the first N columns
         */
        public RemoteEventsAdapter(Context context, List<? extends Map<String, ?>> data, int resource, String[] from, int[] to) {
            super(context, data, resource, from, to);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            Map<String, ?> dataItem = (Map<String, ?>) getItem(position);
            Log.v(TAG, "getView() " + dataItem.toString());
            switch (dataItem.get("type").toString()) {
                case "null":
                case "":
                    v.setBackgroundColor(Color.parseColor("#ffaaaa"));
                    break;
                case "Seizure":
                    v.setBackgroundColor(Color.parseColor("#ff6060"));
                    break;
                default:
                    v.setBackgroundColor(Color.TRANSPARENT);
            }

            // Convert date format to something more readable.
            TextView tv = (TextView) v.findViewById(R.id.event_date_remote_tv);
            Date dataTime = null;
            String dateStr = (String) dataItem.get("dataTime");
            dataTime = mUtil.string2date(dateStr);
            if (dataTime != null) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
                tv.setText(dateFormat.format(dataTime));
            } else {
                tv.setText("---");
            }
            return (v);
        }
    }

}