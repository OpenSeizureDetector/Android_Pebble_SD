package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashMap;

public class RemoteDbActivity extends AppCompatActivity {
    private String TAG = "RemoteDbActivity";
    private Context mContext;
    private UiTimer mUiTimer;
    private LogManager mLm;
    private WebView mWebView;
    private SdServiceConnection mConnection;
    private OsdUtil mUtil;
    final Handler serverStatusHandler = new Handler();
    private String TOKEN_ID = "webApiAuthToken";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(R.layout.activity_remote_db);
        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);
        mConnection = new SdServiceConnection(getApplicationContext());
        mUtil.bindToServer(getApplicationContext(), mConnection);
        waitForConnection();

        //mLm= new LogManager(mContext);

        Button authBtn =
                (Button) findViewById(R.id.auth_button);
        authBtn.setOnClickListener(onAuth);
        //Button pruneBtn =
        //        (Button) findViewById(R.id.pruneDatabaseBtn);
        //pruneBtn.setOnClickListener(onPruneBtn);

        mWebView = (WebView) findViewById(R.id.remote_db_webview);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);

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

    private void initialiseServiceConnection() {
        mLm = mConnection.mSdServer.mLm;
        //mWac = mConnection.mSdServer.mLm.mWac;
    }


    @Override
    protected void onStart() {
        super.onStart();
        waitForConnection();
        updateUi();
        //startUiTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUiTimer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startUiTimer();
        mWebView.loadUrl("https://osdapi.ddns.net/api/events/", getAuthHeaders());
    }

    private HashMap<String, String> getAuthHeaders() {
        HashMap<String, String> headersMap = new HashMap<>();
        String authToken = getAuthToken();
        headersMap.put("Authorization", "Token "+authToken);
        return (headersMap);
    }

    public String getAuthToken() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String authToken = prefs.getString(TOKEN_ID, null);
        return authToken;
    }

    private void updateUi() {
        Log.v(TAG,"updateUi()");
        TextView tv;
        Button btn;
        // Local Database Information
        //tv = (TextView)findViewById(R.id.num_local_events_tv);
        //int eventCount = 0;
        //tv.setText(String.format("%d",eventCount));
        //tv = (TextView)findViewById(R.id.num_local_datapoints_tv);
        //int datapointsCount = 0;
        //tv.setText(String.format("%d",datapointsCount));



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