package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
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

import java.util.ArrayList;
import java.util.HashMap;

public class LogManagerControlActivity extends AppCompatActivity {
    private String TAG = "LogManagerControlActivity";
    private LogManager mLm;
    private Context mContext;
    private UiTimer mUiTimer;
    private ArrayList<HashMap<String, String>> mEventsList;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        mContext = this;
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
                (Button) findViewById(R.id.view_remote_db_button);
        remoteDbBtn.setOnClickListener(onRemoteDbBtn);

        ListView lv = (ListView) findViewById(R.id.eventLogListView);
        lv.setOnItemClickListener(onEventListClick);


        mLm = new LogManager(this);

        updateUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startUiTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUiTimer();
    }


    private void updateUi() {
        //Log.v(TAG,"updateUi()");
        TextView tv;
        Button btn;
        // Local Database Information
        tv = (TextView) findViewById(R.id.num_local_events_tv);
        int eventCount = mLm.getLocalEventsCount(true);
        tv.setText(String.format("%d", eventCount));
        tv = (TextView) findViewById(R.id.num_local_datapoints_tv);
        int datapointsCount = mLm.getLocalDatapointsCount();
        tv.setText(String.format("%d", datapointsCount));

        // Populate events list - we only do it once when the activity is created because the query might slow down the UI.
        // We could try this code in updateUI() and see though.
        // Based on https://www.tutlane.com/tutorial/android/android-sqlite-listview-with-examples
        mEventsList = mLm.getEventsList(true);
        ListView lv = (ListView) findViewById(R.id.eventLogListView);
        ListAdapter adapter = new SimpleAdapter(LogManagerControlActivity.this, mEventsList, R.layout.log_entry_layout,
                new String[]{"dataTime", "status", "uploaded"},
                new int[]{R.id.event_date, R.id.event_alarmState, R.id.event_uploaded});
        lv.setAdapter(adapter);
        //Log.v(TAG,"eventsList="+mEventsList);


        // Remote Database Information
        tv = (TextView) findViewById(R.id.authStatusTv);
        btn = (Button) findViewById(R.id.auth_button);
        if (mLm.mWac.isLoggedIn()) {
            tv.setText("Authenticated");
            btn.setText("Log Out");
        } else {
            tv.setText("NOT AUTHENTICATED");
            btn.setText("Log In");
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

    AdapterView.OnItemClickListener onEventListClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onItemClicKListener() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                    builder.setTitle("Edit Remote Event Details");
                    builder.setMessage("Edit this event details on the remote database?");
                    builder.setPositiveButton("YES", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Do Something!
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
                    
                    //MyClass selItem = (MyClass) myList.getSelectedItem(); //
                    //String value= selItem.getTheValue(); //getter method
                }
            };

    /*
     * Start the timer that will upload data to the remote server after a given period.
     */
    private void startUiTimer() {
        if (mUiTimer != null) {
            Log.v(TAG, "startRemoteLogTimer -timer already running - cancelling it");
            mUiTimer.cancel();
            mUiTimer = null;
        }
        Log.v(TAG, "startRemoteLogTimer() - starting RemoteLogTimer");
        mUiTimer =
                new UiTimer(5000, 1000);
        mUiTimer.start();
    }


    /*
     * Cancel the remote logging timer to prevent attempts to upload to remote database.
     */
    public void stopUiTimer() {
        if (mUiTimer != null) {
            Log.v(TAG, "stopRemoteLogTimer(): cancelling Remote Log timer");
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
            start();
        }

    }


}