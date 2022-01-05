package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class LogManagerControlActivity extends AppCompatActivity {
    private String TAG = "LogManagerControlActivity";
    private LogManager mLm;
    private Context mContext;
    private UiTimer mUiTimer;


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
        Log.v(TAG,"updateUi()");
        TextView tv;
        Button btn;
        // Local Database Information
        tv = (TextView)findViewById(R.id.num_local_events_tv);
        int eventCount = mLm.getLocalEventsCount(true);
        tv.setText(String.format("%d",eventCount));
        tv = (TextView)findViewById(R.id.num_local_datapoints_tv);
        int datapointsCount = mLm.getLocalDatapointsCount();
        tv.setText(String.format("%d",datapointsCount));



        // Remote Database Information
        tv = (TextView)findViewById(R.id.authStatusTv);
        btn = (Button)findViewById(R.id.auth_button);
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
                    i =new Intent(mContext, AuthenticateActivity.class);
                    startActivity(i);
                }
            };
    View.OnClickListener onPruneBtn =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onPruneBtn");
                    mLm.pruneLocalDb();
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
                new UiTimer(1000, 1000);
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
            Log.v(TAG, "UiTimer - onFinish - Updating UI");
            updateUi();
            // Restart this timer.
            start();
        }

    }


}