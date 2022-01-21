package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class LogManagerControlActivity extends AppCompatActivity {
    private String TAG = "LogManagerControlActivity";
    private LogManager mLm;
    private Context mContext;
    private UiTimer mUiTimer;
    private ArrayList<HashMap<String, String>> mEventsList;
    private ArrayList<HashMap<String, String>> mRemoteEventsList;
    private SdServiceConnection mConnection;
    private OsdUtil mUtil;
    final Handler serverStatusHandler = new Handler();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        mContext = this;
        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);
        mConnection = new SdServiceConnection(getApplicationContext());

        setContentView(R.layout.activity_log_manager_control);

        Button authBtn =
                (Button) findViewById(R.id.auth_button);
        authBtn.setOnClickListener(onAuth);
        Button pruneBtn =
                (Button) findViewById(R.id.pruneDatabaseBtn);
        pruneBtn.setOnClickListener(onPruneBtn);
        Button reportSeizureBtn =
                (Button) findViewById(R.id.reportSeizureBtn);
        reportSeizureBtn.setOnClickListener(onReportSeizureBtn);
        Button remoteDbBtn =
                (Button) findViewById(R.id.refresh_button);
        remoteDbBtn.setOnClickListener(onRefreshBtn);

        ListView lv = (ListView) findViewById(R.id.eventLogListView);
        lv.setOnItemClickListener(onEventListClick);

        lv = (ListView) findViewById(R.id.remoteEventsLv);
        lv.setOnItemClickListener(onRemoteEventListClick);
    }

    @Override
    protected void onStart() {
        Log.v(TAG, "onStart()");
        super.onStart();
        mUtil.bindToServer(getApplicationContext(), mConnection);
        waitForConnection();
        startUiTimer();
    }

    @Override
    protected void onStop() {
        Log.v(TAG,"onStop()");
        super.onStop();
        stopUiTimer();
        mUtil.unbindFromServer(this, mConnection);
    }

    @Override
    protected void onPause() {
        Log.v(TAG,"onPause()");
        super.onPause();
        //stopUiTimer();
    }

    @Override
    protected void onResume() {
        Log.v(TAG,"onResume()");
        super.onResume();
        //startUiTimer();
    }

    private void waitForConnection() {
        // We want the UI to update as soon as it is displayed, but it takes a finite time for
        // the mConnection to bind to the service, so we delay half a second to give it chance
        // to connect before trying to update the UI for the first time (it happens again periodically using the uiTimer)
        if (mConnection.mBound) {
            Log.v(TAG,"waitForConnection - Bound!");
            initialiseServiceConnection();
        } else {
            Log.v(TAG,"waitForConnection - waiting...");
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
        startUiTimer();
        getRemoteEvents();
        // Populate events list - we only do it once when the activity is created because the query might slow down the UI.
        // We could try this code in updateUI() and see though.
        // Based on https://www.tutlane.com/tutorial/android/android-sqlite-listview-with-examples
        mLm.getEventsList(true, (ArrayList<HashMap<String,String>> eventsList)-> {
            mEventsList = eventsList;
            Log.v(TAG,"initialiseServiceConnection() - set mEventsList");
            updateUi();
        });
        //mEventsList = mLm.getEventsList(true);
    }



    private void getRemoteEvents() {
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
                    for (int i = eventsArray.length()-1; i>=0; i--) {
                        JSONObject eventObj = eventsArray.getJSONObject(i);
                        Long id = eventObj.getLong("id");
                        int osdAlarmState = eventObj.getInt("osdAlarmState");
                        String dataTime = eventObj.getString("dataTime");
                        String typeStr = eventObj.getString("type");
                        String subType = eventObj.getString("subType");
                        String desc = eventObj.getString("desc");
                        HashMap<String, String> eventHashMap = new HashMap<String, String>();
                        eventHashMap.put("id", String.valueOf(id));
                        eventHashMap.put("osdAlarmState", String.valueOf(osdAlarmState));
                        eventHashMap.put("osdAlarmStateStr", mUtil.alarmStatusToString(osdAlarmState));
                        eventHashMap.put("dataTime", dataTime);
                        eventHashMap.put("type", typeStr);
                        eventHashMap.put("subType", subType);
                        eventHashMap.put("desc", desc);
                        mRemoteEventsList.add(eventHashMap);
                    }
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
        //Log.v(TAG,"updateUi()");
        boolean stopUpdating = true;
        TextView tv;
        Button btn;
        // Local Database Information
        if (mLm != null) {
            tv = (TextView) findViewById(R.id.num_local_events_tv);
            int eventCount = mLm.getLocalEventsCount(true);
            tv.setText(String.format("%d", eventCount));
            tv = (TextView) findViewById(R.id.num_local_datapoints_tv);
            int datapointsCount = mLm.getLocalDatapointsCount();
            tv.setText(String.format("%d", datapointsCount));
        } else {
            stopUpdating = false;
        }

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
        // Remote Database List View
        if (mRemoteEventsList != null) {
            ListView lv = (ListView) findViewById(R.id.remoteEventsLv);
            ListAdapter adapter = new SimpleAdapter(LogManagerControlActivity.this, mRemoteEventsList, R.layout.log_entry_layout,
                    new String[]{"dataTime", "type", "subType"},
                    new int[]{R.id.event_date, R.id.event_alarmState, R.id.event_uploaded});
            lv.setAdapter(adapter);
        } else {
            //mUtil.showToast("No Remote Events");
            Log.d(TAG,"UpdateUi: No Remote Events");
            stopUpdating = false;
        }


        // Remote Database Information
        if (mLm != null) {
            tv = (TextView) findViewById(R.id.authStatusTv);
            btn = (Button) findViewById(R.id.auth_button);
            if (mLm.mWac.isLoggedIn()) {
                tv.setText("Authenticated");
                btn.setText("Log Out");
            } else {
                tv.setText("NOT AUTHENTICATED");
                btn.setText("Log In");
            }
        } else {
            stopUpdating = false;
        }
        if (stopUpdating) stopUiTimer();
    }  //updateUi();



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


    AdapterView.OnItemClickListener onEventListClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onItemClicKListener() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    HashMap<String, String> eventObj = (HashMap<String,String>)adapter.getItemAtPosition(position);
                    Long eventId = Long.parseLong(eventObj.get("uploaded"));
                    Log.d(TAG,"onItemClickListener(): eventId="+eventId+", eventObj="+eventObj);
                    if (eventId>0) {
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
                    HashMap<String, String> eventObj = (HashMap<String,String>)adapter.getItemAtPosition(position);
                    Long eventId = Long.parseLong(eventObj.get("id"));
                    Log.d(TAG,"onItemClickListener(): eventId="+eventId+", eventObj="+eventObj);
                    Intent i = new Intent(getApplicationContext(), EditEventActivity.class);
                    i.putExtra("eventId",eventId);
                    startActivity(i);
                }
            };


    /*
     * Start the timer that will update the user interface every 5 seconds..
     */
    private void startUiTimer() {
        if (mUiTimer != null) {
            Log.v(TAG, "startUiTimer -timer already running - cancelling it");
            mUiTimer.cancel();
            mUiTimer = null;
        }
        Log.v(TAG, "startUiTimer() - starting UiTimer");
        mUiTimer =
                new UiTimer(2000, 1000);
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


}