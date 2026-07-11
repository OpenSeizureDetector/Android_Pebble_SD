package uk.org.openseizuredetector.activity.remote;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.activity.ServiceConnectedActivity;
import uk.org.openseizuredetector.data.logging.LogManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import androidx.preference.PreferenceManager;

import androidx.core.view.WindowInsetsCompat;

import uk.org.openseizuredetector.data.logging.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashMap;

import uk.org.openseizuredetector.activity.auth.AuthenticateActivity;
public class RemoteDbActivity extends ServiceConnectedActivity {
    private String TAG = "RemoteDbActivity";
    private Context mContext;
    private UiTimer mUiTimer;
    private LogManager mLm;
    private WebView mWebView;
    // mConnection, mUtil, serverStatusHandler now inherited from ServiceConnectedActivity
    private String TOKEN_ID = "webApiAuthToken";
    private String mRemtoteUrl = "https://osdapi.ddns.net/";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);  // Initializes mUtil, mConnection, configures system bars
        mContext = this;
        setContentView(R.layout.activity_remote_db);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            String remoteUrl = extras.getString("url");
            mRemtoteUrl = remoteUrl;
            Log.d(TAG, "onCreate - mRemoteUrl=" + mRemtoteUrl);
        }

        Button authBtn = (Button) findViewById(R.id.auth_button);
        authBtn.setOnClickListener(onAuth);

        mWebView = (WebView) findViewById(R.id.remote_db_webview);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
    }

    /**
     * Called by ServiceConnectedActivity when service connection is established.
     */
    @Override
    protected void onServiceConnected(LogManager logManager) {
        Log.d(TAG, "onServiceConnected()");
        mLm = logManager;
        mWebView.loadUrl(mRemtoteUrl, getAuthHeaders());
    }

    @Override
    protected void onStart() {
        super.onStart();  // Handles service binding and connection
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
    }

    private HashMap<String, String> getAuthHeaders() {
        HashMap<String, String> headersMap = new HashMap<>();
        String authToken = getAuthToken();
        headersMap.put("Authorization", "Token " + authToken);
        return (headersMap);
    }

    public String getAuthToken() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String authToken = prefs.getString(TOKEN_ID, null);
        return authToken;
    }

    private void updateUi() {
        Log.v(TAG, "updateUi()");
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
        tv = (TextView) findViewById(R.id.authStatusTv);
        btn = (Button) findViewById(R.id.auth_button);
        if (mLm != null && mLm.mWac != null) {
            if (mLm.mWac.isLoggedIn()) {
                tv.setText("Authenticated");
                btn.setText("Log Out");
            } else {
                tv.setText("NOT AUTHENTICATED");
                btn.setText("Log In");
            }
        } else {
            Log.w(TAG, "updateUi() - mLm or mWac is null, skipping auth status update");
            tv.setText("NOT CONNECTED");
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