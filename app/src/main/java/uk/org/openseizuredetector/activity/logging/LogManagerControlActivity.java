package uk.org.openseizuredetector.activity.logging;
import uk.org.openseizuredetector.R;

//import androidx.appcompat.app.AppCompatActivity;

import uk.org.openseizuredetector.client.SdServiceConnection;
import uk.org.openseizuredetector.utils.OsdUtil;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.provider.MediaStore;

import androidx.core.graphics.Insets;
import androidx.core.view.MenuCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.util.Log;
import android.view.LayoutInflater;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uk.org.openseizuredetector.activity.auth.AuthenticateActivity;
import uk.org.openseizuredetector.activity.events.EditEventActivity;
import uk.org.openseizuredetector.activity.events.ReportSeizureActivity;
import uk.org.openseizuredetector.activity.export.ExportDataActivity;
import uk.org.openseizuredetector.activity.remote.RemoteDbActivity;
import uk.org.openseizuredetector.activity.settings.PrefActivity;
public class LogManagerControlActivity extends AppCompatActivity {
    private String TAG = "LogManagerControlActivity";
    private static final long GROUPING_WINDOW_MINUTES = 3;
    private static final long GROUPING_WINDOW_MS = GROUPING_WINDOW_MINUTES * 60 * 1000;
    private LogManager mLm;
    private Context mContext;
    private UiTimer mUiTimer;
    private ArrayList<HashMap<String, String>> mEventsList;
    private ArrayList<HashMap<String, String>> mRemoteEventsList;
    private ArrayList<ArrayList<HashMap<String, String>>> mGroupedRemoteEventsList;   // Each item is a list of event objects, similar to mRemoteEventsList
    private ArrayList<HashMap<String, String>> mSysLogList;
    private File[] mLogFiles;
    private File mCurrentLogFile;
    private SdServiceConnection mConnection;
    private OsdUtil mUtil;
    final Handler serverStatusHandler = new Handler(Looper.getMainLooper());
    private Integer mUiTimerPeriodFast = 2000;  // 2 seconds - we use fast updating while UI is blank and we are waiting for first data
    private Integer mUiTimerPeriodSlow = 60000; // 60 seconds - once data has been received and UI populated we only update once per minute.
    private boolean mUpdateSysLog = true;
    private Menu mMenu;
    private CheckBox mGroupEventsCb; // Declare the CheckBox member
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

        // Handle system window insets for all API levels
        View rootView = findViewById(R.id.root_layout_log_manager_control);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            // Get the system bar insets
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

            // Apply padding to your main content view
            LinearLayout content = findViewById(R.id.log_manager_control_content_layout); // Add this ID to your LinearLayout
            content.setPadding(0, top, 0, bottom);

            // Return the insets so they keep propagating
            return WindowInsetsCompat.CONSUMED;
        });
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

        Button remoteDbBtn =
                (Button) findViewById(R.id.refresh_button);
        remoteDbBtn.setOnClickListener(onRefreshBtn);

        Button syslogRefreshBtn = (Button) findViewById(R.id.syslog_refresh_button);
        if (syslogRefreshBtn != null) {
            syslogRefreshBtn.setOnClickListener(v -> refreshSysLog());
        }
        Button syslogSelectBtn = (Button) findViewById(R.id.syslog_select_button);
        if (syslogSelectBtn != null) {
            syslogSelectBtn.setOnClickListener(v -> showLogFilePicker());
        }
        Button syslogExportBtn = (Button) findViewById(R.id.syslog_export_button);
        if (syslogExportBtn != null) {
            syslogExportBtn.setOnClickListener(v -> exportCurrentLog());
        }

        CheckBox includeWarningsCb =
                (CheckBox) findViewById(R.id.include_warnings_cb);
        includeWarningsCb.setOnCheckedChangeListener(onIncludeWarningsCb);
        CheckBox includeNDACb =
                (CheckBox) findViewById(R.id.include_nda_cb);
        includeNDACb.setOnCheckedChangeListener(onIncludeNDACb);

        mGroupEventsCb = findViewById(R.id.group_events_cb);
        mGroupEventsCb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // When the checkbox state changes, re-process and update the UI
                if (mRemoteEventsList != null && !mRemoteEventsList.isEmpty()) {
                    if (isChecked) {
                        createGroupedEventsList();
                    }
                    updateUi(); // Update UI to reflect grouped or non-grouped list
                }
            }
        });


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
        this.mMenu = menu;
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
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
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
        ProgressBar pb = (ProgressBar) findViewById(R.id.remoteAccessPb);
        pb.setIndeterminate(true);
        pb.setVisibility(View.VISIBLE);
        if (mLm != null) {
            // Populate events list - we only do it once when the activity is created because the query might slow down the UI.
            // We could try this code in updateUI() and see though.
            // Based on https://www.tutlane.com/tutorial/android/android-sqlite-listview-with-examples
            mLm.getEventsList(true, (ArrayList<HashMap<String, String>> eventsList) -> {
                mEventsList = eventsList;
                Log.v(TAG, "initialiseServiceConnection() - set mEventsList - Updating UI");
                updateUi();
            });
            refreshSysLog();
        } else {
            Log.e(TAG, "ERROR: initialiseServiceConnection() - mLm is null");
            mUtil.showToast(getString(R.string.error_failed_to_start_log_manager));
        }
    }


    private void getRemoteEvents(boolean includeWarnings, boolean includeNDA) {
        mRemoteEventsList = null;  // clear existing data
        mGroupedRemoteEventsList = null;
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
                            //Log.v(TAG, "getRemoteEvents() - dataTime=" + dataTime);
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
                        if ((osdAlarmState != 1 | includeWarnings) &&
                                (osdAlarmState != 6 | includeNDA)) {
                            mRemoteEventsList.add(eventHashMap);
                        } else {
                            Log.v(TAG, "getRemoteEvents - skipping warning or NDA record");
                        }
                    }

                    // Sort the remote events list by date, descending (newest first)
                    Log.v(TAG, "getRemoteEvents() - Sorting mRemoteEventsList by date");
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.getDefault()); // Adjust format if needed
                    Collections.sort(mRemoteEventsList, (event1, event2) -> {
                        try {
                            String dt1Str = event1.get("dataTime");
                            String dt2Str = event2.get("dataTime");
                            if (
                                    dt1Str == null
                                            || dt2Str == null
                                            || dt1Str.equals("null")
                                            || dt2Str.equals("null"))
                                return 0;
                            Date date1 = sdf.parse(dt1Str);
                            Date date2 = sdf.parse(dt2Str);
                            return date2.compareTo(date1); // Descending
                        } catch (ParseException e) {
                            Log.e(TAG, "Error parsing date for sorting: " + e.getMessage());
                            return 0;
                        }
                    });
                    if (mGroupEventsCb.isChecked() // Check if grouping is enabled
                    ) {
                        createGroupedEventsList();
                        Log.v(TAG, "getRemoteEvents() - created grouped events. Updating UI");
                    } else {
                        mGroupedRemoteEventsList = null; // Ensure grouped list is cleared if not used
                        Log.v(TAG, "getRemoteEvents() - grouping disabled. Updating UI with flat list.");
                    }
                    updateUi();
                } catch (JSONException e) {
                    Log.e(TAG, "getRemoteEvents(): Error Parsing remoteEventsObj: " + e.getMessage());
                    mUtil.showToast("Error Parsing remoteEventsObj - this should not happen!!!");
                    mRemoteEventsList = null;
                    mGroupedRemoteEventsList = null;
                    updateUi(); // Update UI to show error state
                }
            }
        });
    }

    /**
     * createGroupedEventsList()
     * Reads the complete list of remote events mRemoteEventsList and creates a new list mGroupedRemoteEventsList
     * where each item is a list of events that comprise a group based on time (all events within a 3 minute period are grouped together).
     */
    private void createGroupedEventsList() {
        Log.i(TAG, "createGroupedEventsList()");
        /**
         * createGroupedEventsList()
         * Reads the complete list of remote events mRemoteEventsList (sorted newest first)
         * and creates a new list mGroupedRemoteEventsList
         * where each item is a list of events that comprise a group based on time.
         */
        mGroupedRemoteEventsList = new ArrayList<>();
        if (mRemoteEventsList == null || mRemoteEventsList.isEmpty()) {
            Log.i(TAG, "createGroupedEventsList() - mRemoteEventsList is null or empty.");
            return;
        }

        // Helper to parse date strings to long timestamps.
        // Adjust the SimpleDateFormat pattern to match your "dataTime" format.
        // If "dataTime" is already a timestamp (long), you can use it directly.
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.getDefault()); // Example format

        ArrayList<HashMap<String, String>> currentGroup = null;
        long lastEventTimeInGroup = 0;

        for (HashMap<String, String> event : mRemoteEventsList) {
            String dataTimeString = event.get("dataTime");
            if (dataTimeString == null || dataTimeString.equals("null")) {
                Log.w(TAG, "Event has null or invalid dataTime: " + event.get("id"));
                continue; // Skip events with no valid time
            }

            long currentEventTime;
            try {
                Date eventDate = sdf.parse(dataTimeString);
                if (eventDate == null) {
                    Log.w(TAG, "Could not parse dataTime: " + dataTimeString + " for event: " + event.get("id"));
                    continue;
                }
                currentEventTime = eventDate.getTime();
            } catch (ParseException e) {
                Log.e(TAG, "Error parsing date string: " + dataTimeString + " - " + e.getMessage());
                continue; // Skip if date can't be parsed
            }

            if (currentGroup == null || (lastEventTimeInGroup - currentEventTime) > GROUPING_WINDOW_MS) {
                // Start a new group
                if (currentGroup != null) {
                    moveFirstAlarmToFront(currentGroup); // Move the first ALARM event to the front of the group)
                    mGroupedRemoteEventsList.add(currentGroup);
                }
                currentGroup = new ArrayList<>();
                currentGroup.add(event);
                lastEventTimeInGroup = currentEventTime;
            } else {
                // Add to the current group
                currentGroup.add(event);
                // lastEventTimeInGroup remains the time of the first event added to this group (newest)
            }
        }

        // Add the last group if it exists
        if (currentGroup != null && !currentGroup.isEmpty()) {
            moveFirstAlarmToFront(currentGroup); // Move the first ALARM event to the front of the group
            mGroupedRemoteEventsList.add(currentGroup);
        }

        Log.i(TAG, "createGroupedEventsList() - Grouped " + mRemoteEventsList.size() +
                " events into " + mGroupedRemoteEventsList.size() + " groups.");
    }

    /**
     * moveFirstAlarmToFront() - This method checks the group for the first
     * event with an ALARM state (osdAlarmState = 2) and makes that event the
     * first in the list.
     *
      * @param group An ArrayList of HashMaps representing a group of events.
     */
    private void moveFirstAlarmToFront(ArrayList<HashMap<String, String>> group) {
        //Log.i(TAG, "moveFirstAlarmToFront() - Checking group of size: " + group.size());
        for (int i = 0; i < group.size(); i++) {
            HashMap<String, String> event = group.get(i);
            String alarmStateStr = event.get("osdAlarmState");
            if (alarmStateStr != null && alarmStateStr.equals("2")) { // ALARM is 2
                //Log.v(TAG," moveFirstAlarmToFront() - Found ALARM event at index: " + i);
                if (i != 0) {
                    group.remove(i);
                    group.add(0, event);
                }
                break;
            }
        }
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
            //mLm.getLocalDatapointsCount((Long datapointsCount) -> {
            //    TextView tv2 = (TextView) findViewById(R.id.num_local_datapoints_tv);
            //    tv2.setText(String.format("%d", datapointsCount));
            //});
            TextView tv3 = (TextView) findViewById(R.id.nda_time_remaining_tv);
            tv3.setText(String.format("%.1f hrs", mLm.mNDATimeRemaining));
            Log.d(TAG, "mNDATimeRemaining = " + String.format("%.1f hrs", mLm.mNDATimeRemaining));
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
            ProgressBar pb = (ProgressBar) findViewById(R.id.remoteAccessPb);
            pb.setIndeterminate(false);
            pb.setVisibility(View.INVISIBLE);
            ListView lv = (ListView) findViewById(R.id.remoteEventsLv);

            if (mGroupEventsCb.isChecked() && mGroupedRemoteEventsList != null) {
                // Show group summary with start time and duration
                ArrayList<HashMap<String, String>> displayList = new ArrayList<>();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.getDefault());

                for (ArrayList<HashMap<String, String>> group : mGroupedRemoteEventsList) {
                    HashMap<String, String> groupSummary = new HashMap<>(group.get(0)); // Start with first event data

                    // Get the start time (last/oldest event in group) and end time (first/newest event)
                    long startTimeMillis = 0;
                    long endTimeMillis = 0;
                    String startTimeStr = null;
                    String endTimeStr = null;

                    try {
                        // First event in group is newest
                        endTimeStr = group.get(0).get("dataTime");
                        if (endTimeStr != null && !endTimeStr.equals("null")) {
                            try {
                                Date endDate = sdf.parse(endTimeStr);
                                endTimeMillis = endDate.getTime();
                            } catch (ParseException pe) {
                                Log.w(TAG, "Failed to parse end time: " + endTimeStr);
                            }
                        }

                        // Last event in group is oldest
                        startTimeStr = group.get(group.size() - 1).get("dataTime");
                        if (startTimeStr != null && !startTimeStr.equals("null")) {
                            try {
                                Date startDate = sdf.parse(startTimeStr);
                                startTimeMillis = startDate.getTime();
                            } catch (ParseException pe) {
                                Log.w(TAG, "Failed to parse start time: " + startTimeStr);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing group dates: " + e.getMessage());
                    }

                    // Set the group start time (oldest event) - always set it even if parsing failed
                    if (startTimeMillis > 0) {
                        SimpleDateFormat displayFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());
                        String formattedTime = displayFormat.format(new Date(startTimeMillis));
                        Log.v(TAG, "Setting grouped event dataTime to: " + formattedTime);
                        groupSummary.put("dataTime", formattedTime);
                    } else if (startTimeStr != null) {
                        // If parsing failed, use the original time string
                        Log.v(TAG, "Using raw start time: " + startTimeStr);
                        groupSummary.put("dataTime", startTimeStr);
                    }

                    // Calculate duration in seconds
                    long durationSeconds = 0;
                    if (startTimeMillis > 0 && endTimeMillis > 0) {
                        durationSeconds = (endTimeMillis - startTimeMillis) / 1000;
                    }
                    groupSummary.put("duration", String.format("%d sec", durationSeconds));

                    // Ensure desc is present
                    if (!groupSummary.containsKey("desc") || groupSummary.get("desc") == null) {
                        groupSummary.put("desc", "");
                    }

                    displayList.add(groupSummary);
                }
                ListAdapter adapter = new RemoteEventsAdapter(LogManagerControlActivity.this, displayList, R.layout.log_entry_layout_remote,
                        new String[]{"id", "dataTime", "duration", "type", "subType", "osdAlarmStateStr", "desc"},
                        new int[]{R.id.event_id_remote_tv, R.id.event_date_remote_tv, R.id.event_duration_remote_tv, R.id.event_type_remote_tv, R.id.event_subtype_remote_tv,
                                R.id.event_alarmState_remote_tv, R.id.event_notes_remote_tv});
                lv.setAdapter(adapter);
            } else if (mRemoteEventsList != null) {
                ListAdapter adapter = new RemoteEventsAdapter(LogManagerControlActivity.this, mRemoteEventsList, R.layout.log_entry_layout_remote,
                        new String[]{"id", "dataTime", "duration", "type", "subType", "osdAlarmStateStr", "desc"},
                        new int[]{R.id.event_id_remote_tv, R.id.event_date_remote_tv, R.id.event_duration_remote_tv, R.id.event_type_remote_tv, R.id.event_subtype_remote_tv,
                                R.id.event_alarmState_remote_tv, R.id.event_notes_remote_tv});
                lv.setAdapter(adapter);
            } else {
                Log.i(TAG, "UpdateUi: No Remote Events");
            }
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
                Log.i(TAG, "start/stop NDA");
                if (mConnection.mSdServer.mLogNDA) {
                    new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog))
                            .setTitle(R.string.stop_nda_logging_dialog_title)
                            .setMessage(R.string.stop_nda_logging_dialog_meassage)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    mLm.disableNDATimer();
                                    MenuItem startStopNDAMenuItem = mMenu.findItem(R.id.start_stop_nda);
                                    startStopNDAMenuItem.setTitle(R.string.start_nda_menu_title);
                                    mUtil.stopServer();
                                    // Wait 0.5 second to give the server chance to shutdown, then re-start it
                                    // CRITICAL: 100ms was too short and caused duplicate SdDataSource instances
                                    // Increased to 500ms to allow proper cleanup before restart
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        public void run() {
                                            mUtil.startServer();
                                        }
                                    }, 500);
                                }
                            })
                            .setNegativeButton(android.R.string.no, null)
                            .show();
                } else {
                    new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog))
                            .setTitle(R.string.start_nda_logging_dialog_title)
                            .setMessage(R.string.start_nda_logging_dialog_meassage)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    mLm.enableNDATimer();
                                    MenuItem startStopNDAMenuItem = mMenu.findItem(R.id.start_stop_nda);
                                    startStopNDAMenuItem.setTitle(R.string.stop_nda_menu_title);
                                    mUtil.stopServer();
                                    // Wait 0.1 second to give the server chance to shutdown, then re-start it
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        public void run() {
                                            mUtil.startServer();
                                        }
                                    }, 100);
                                }
                            })
                            .setNegativeButton(android.R.string.no, null)
                            .show();

                }
                return true;
            case R.id.action_mark_unknown:
                Log.i(TAG, "action_mark_unknown");
                new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog))
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
                return true;
            case R.id.action_mark_false_alarm:
                Log.i(TAG, "action_mark_false_alarm");
                new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog))
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
                return true;
            case R.id.export_data_menuitem:
                Log.i(TAG, "export data menu item");
                try {
                    Intent i = new Intent(
                            getApplicationContext(),
                            ExportDataActivity.class);
                    this.startActivity(i);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting export data activity " + ex.toString());
                }
                return true;
            case R.id.action_about_datasharing:
                Log.i(TAG, "action_about_datasharing");
                showDataSharingDialog();
                return true;

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
                    AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(mContext, R.style.AppTheme_AlertDialog));
                    builder.setTitle(R.string.prune_database_title);
                    builder.setMessage(String.format(getString(R.string.prune_database_dialog_msg), mLm.mDataRetentionPeriod));
                    builder.setPositiveButton(R.string.yes_button_title, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mLm.pruneLocalDb();
                            dialog.dismiss();
                        }
                    });
                    builder.setNegativeButton(R.string.no_button_title, new DialogInterface.OnClickListener() {
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

                    if (mGroupEventsCb.isChecked() && mGroupedRemoteEventsList != null) {
                        Log.v(TAG,"onItemClickListener() - Creating Grouped Events List from Position=" + position);
                        // Get the group for this position
                        ArrayList<HashMap<String, String>> group = mGroupedRemoteEventsList.get(position);
                        ArrayList<String> eventIds = new ArrayList<>();
                        for (HashMap<String, String> event : group) {
                            Log.v(TAG,"onItemClickListener() - Adding event to edit list: " + event.get("id"));
                            eventIds.add(event.get("id"));
                        }
                        Intent i = new Intent(getApplicationContext(), EditEventActivity.class);
                        i.putStringArrayListExtra("eventIds", eventIds);
                        startActivity(i);
                    } else {
                        Log.v(TAG,"onItemClickListener() - Editing Single event at Position=" + position);
                        HashMap<String, String> eventObj = (HashMap<String, String>) adapter.getItemAtPosition(position);
                        String eventId = eventObj.get("id");
                        Intent i = new Intent(getApplicationContext(), EditEventActivity.class);
                        i.putExtra("eventId", eventId);
                        startActivity(i);
                    }
                }
            };

    AdapterView.OnItemClickListener onRemoteEventListClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onRemoteEventList Click() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    Log.v(TAG, "onItemClickListener() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584

                    if (mGroupEventsCb.isChecked() && mGroupedRemoteEventsList != null) {
                        Log.v(TAG,"onItemClickListener() - Creating Grouped Events List from Position=" + position);
                        // Get the group for this position
                        ArrayList<HashMap<String, String>> group = mGroupedRemoteEventsList.get(position);
                        ArrayList<String> eventIds = new ArrayList<>();
                        for (HashMap<String, String> event : group) {
                            Log.v(TAG,"onItemClickListener() - Adding event to edit list: " + event.get("id"));
                            eventIds.add(event.get("id"));
                        }
                        Intent i = new Intent(getApplicationContext(), EditEventActivity.class);
                        i.putStringArrayListExtra("eventIds", eventIds);
                        startActivity(i);
                    } else {
                        Log.v(TAG,"onItemClickListener() - Editing Single event at Position=" + position);
                        HashMap<String, String> eventObj = (HashMap<String, String>) adapter.getItemAtPosition(position);
                        String eventId = eventObj.get("id");
                        Intent i = new Intent(getApplicationContext(), EditEventActivity.class);
                        i.putExtra("eventId", eventId);
                        startActivity(i);
                    }
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
            String dateStr = (String) dataItem.get("dataTime");

            // Check if date is already formatted (from grouped events) or needs parsing (from API)
            if (dateStr != null && !dateStr.equals("null")) {
                // Try to parse as ISO format first (from API)
                Date dataTime = mUtil.string2date(dateStr);
                if (dataTime != null) {
                    SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
                    tv.setText(dateFormat.format(dataTime));
                } else {
                    // If parsing failed, assume it's already formatted and use as-is
                    Log.v(TAG, "Date already formatted, using as-is: " + dateStr);
                    tv.setText(dateStr);
                }
            } else {
                tv.setText("---");
            }
            return (v);
        }
    }


    private void showDataSharingDialog() {
        mUtil.writeToSysLogFile("MainActivity.showDataSharingDialog()");
        View aboutView = getLayoutInflater().inflate(R.layout.data_sharing_dialog_layout, null, false);
        AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog));
        builder.setIcon(R.drawable.datasharing_fault_24x24);
        builder.setTitle(R.string.data_sharing_dialog_title);
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(getString(R.string.login), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Log.i(TAG, "dataSharingDialog.positiveButton.onClick()");
                try {
                    Intent i = new Intent(
                            LogManagerControlActivity.this,
                            AuthenticateActivity.class);
                    mContext.startActivity(i);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting activity " + ex.toString());
                }

            }
        });
        builder.setView(aboutView);
        builder.create();
        builder.show();
    }


    private void refreshSysLog() {
        mLogFiles = mUtil.getLogFiles();
        if (mLogFiles == null || mLogFiles.length == 0) {
            mSysLogList = new ArrayList<>();
            updateSysLogTitle(null);
            mUpdateSysLog = true;
            updateUi();
            mUtil.showToast(getString(R.string.syslog_no_files));
            return;
        }

        // Pick newest log file by last modified
        Arrays.sort(mLogFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        mCurrentLogFile = mLogFiles[0];
        loadSysLogFromFile(mCurrentLogFile);
    }

    private void showLogFilePicker() {
        mLogFiles = mUtil.getLogFiles();
        if (mLogFiles == null || mLogFiles.length == 0) {
            mUtil.showToast(getString(R.string.syslog_no_files));
            return;
        }

        Arrays.sort(mLogFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[mLogFiles.length];
        for (int i = 0; i < mLogFiles.length; i++) {
            names[i] = mLogFiles[i].getName();
        }

        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog))
                .setTitle(R.string.syslog_select_file_title)
                .setItems(names, (dialog, which) -> {
                    mCurrentLogFile = mLogFiles[which];
                    loadSysLogFromFile(mCurrentLogFile);
                })
                .show();
    }

    private void exportCurrentLog() {
        if (mCurrentLogFile == null || !mCurrentLogFile.exists()) {
            mUtil.showToast(getString(R.string.syslog_no_files));
            return;
        }
        if (exportLogFileToDownloads(mCurrentLogFile)) {
            mUtil.showToast(getString(R.string.syslog_exported));
        } else {
            mUtil.showToast(getString(R.string.syslog_export_failed));
        }
    }

    private void updateSysLogTitle(File logFile) {
        TextView tv = (TextView) findViewById(R.id.syslog_file_tv);
        if (tv == null) {
            return;
        }
        if (logFile == null) {
            tv.setText(getString(R.string.syslog_current_file, "-"));
        } else {
            tv.setText(getString(R.string.syslog_current_file, logFile.getName()));
        }
    }

    private void loadSysLogFromFile(File logFile) {
        ArrayList<HashMap<String, String>> entries = new ArrayList<>();
        if (logFile == null || !logFile.exists()) {
            mSysLogList = entries;
            updateSysLogTitle(null);
            mUpdateSysLog = true;
            updateUi();
            return;
        }

        ArrayDeque<String> tail = new ArrayDeque<>(500);
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (tail.size() == 500) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "loadSysLogFromFile: failed to read log file", e);
        }

        // Newest first
        ArrayList<String> lines = new ArrayList<>(tail);
        Collections.reverse(lines);

        for (String line : lines) {
            HashMap<String, String> entry = new HashMap<>();
            parseLogLine(line, entry);
            entries.add(entry);
        }

        mSysLogList = entries;
        updateSysLogTitle(logFile);
        mUpdateSysLog = true;
        updateUi();
    }

    private void parseLogLine(String line, HashMap<String, String> entry) {
        // Expected format: yyyy-MM-dd HH:mm:ss.SSS [LEVEL] [thread] message
        String dataTime = "";
        String level = "";
        String message = line;
        try {
            int firstBracket = line.indexOf(" [");
            int secondBracket = line.indexOf("] [", firstBracket + 2);
            int thirdBracket = line.indexOf("] ", secondBracket + 3);
            if (firstBracket > 0 && secondBracket > firstBracket && thirdBracket > secondBracket) {
                dataTime = line.substring(0, firstBracket).trim();
                level = line.substring(firstBracket + 2, secondBracket).trim();
                String thread = line.substring(secondBracket + 3, thirdBracket).trim();
                message = "[" + thread + "] " + line.substring(thirdBracket + 2).trim();
            }
        } catch (Exception e) {
            // Keep defaults
        }
        entry.put("dataTime", dataTime);
        entry.put("logLevel", level);
        entry.put("dataJSON", message);
    }

    private boolean exportLogFileToDownloads(File logFile) {
        if (logFile == null || !logFile.exists()) {
            return false;
        }
        try {
            String fileName = logFile.getName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OpenSeizureDetector");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return false;
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri);
                     FileInputStream fis = new FileInputStream(logFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                    os.flush();
                }
                return true;
            } else {
                File destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File dest = new File(destDir, fileName);
                try (OutputStream os = new java.io.FileOutputStream(dest);
                     FileInputStream fis = new FileInputStream(logFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                    os.flush();
                }
                MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, new String[]{"text/plain"}, null);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "exportLogFileToDownloads: failed", e);
            return false;
        }
    }

}
