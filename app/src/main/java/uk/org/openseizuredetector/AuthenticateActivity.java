package uk.org.openseizuredetector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

public class AuthenticateActivity extends AppCompatActivity {
    private String TAG = "AuthenticateActivity";
    private EditText mUnameEt;
    private EditText mPasswdEt;
    private WebApiConnection mWac;
    private LogManager mLm;
    private SdServiceConnection mConnection;
    private OsdUtil mUtil;
    final Handler serverStatusHandler = new Handler();
    private String TOKEN_ID = "webApiAuthToken";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_authenticate);

        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);

        if (!mUtil.isServerRunning()) {
            mUtil.showToast("ERROR:  OpenSeizureDetector Server is not running - please re-start it");
            finish();
        }
        mConnection = new SdServiceConnection(getApplicationContext());

        Button cancelBtn =
                (Button) findViewById(R.id.cancelBtn);
        cancelBtn.setOnClickListener(onCancel);
        Button OKBtn = (Button) findViewById(R.id.OKBtn);
        OKBtn.setOnClickListener(onOK);
        Button logoutCancelBtn =
                (Button) findViewById(R.id.logoutCancelBtn);
        logoutCancelBtn.setOnClickListener(onCancel);
        Button logoutBtn = (Button)findViewById(R.id.logoutBtn);
        logoutBtn.setOnClickListener(onLogout);
        Button registerBtn = (Button) findViewById(R.id.RegisterBtn);
        registerBtn.setOnClickListener(onRegister);
        Button resetPasswordBtn = (Button) findViewById(R.id.ResetPasswordBtn);
        resetPasswordBtn.setOnClickListener(onResetPassword);

        mUnameEt = (EditText) findViewById(R.id.username);
        mPasswdEt = (EditText) findViewById(R.id.password);
        //mWac = new WebApiConnection(this, String tokenStr);
        //mLm = new LogManager(this);
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart()");
        super.onStart();
        mUtil.bindToServer(getApplicationContext(), mConnection);
        waitForConnection();
        updateUi();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop()");
        super.onStop();
        mUtil.unbindFromServer(getApplicationContext(), mConnection);
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
        mWac = mConnection.mSdServer.mLm.mWac;
    }

    private void updateUi() {
        SharedPreferences prefs;
        String storedAuthToken;
        LinearLayout loginLl = (LinearLayout)findViewById(R.id.login_ui);
        LinearLayout logoutLl = (LinearLayout)findViewById(R.id.logout_ui);
        Log.i(TAG, "switchUi()");
        storedAuthToken = getAuthToken(); //mWac.getStoredToken();
        //prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //storedAuthToken = (prefs.getString("webApiAuthToken", null));
        Log.v(TAG, "storedAuthToken=" + storedAuthToken);

        // Check if we are already logged in
        if (storedAuthToken == null || storedAuthToken.length() == 0) {
            Log.v(TAG, "Not Logged in - showing log in UI");
            loginLl.setVisibility(View.VISIBLE);
            logoutLl.setVisibility(View.GONE);
        } else {
            Log.v(TAG, "Already Logged in - showing Log Out prompt");
            loginLl.setVisibility(View.GONE);
            logoutLl.setVisibility(View.VISIBLE);
            TextView tv = (TextView)findViewById(R.id.tokenTv);
            tv.setText("Logged in with Token:"+storedAuthToken);
        }

    }

    View.OnClickListener onCancel =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onCancel");
                    //m_status=false;
                    finish();
                }
            };

    View.OnClickListener onOK =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //m_status=true;
                    Log.v(TAG, "onOK()");
                    String uname = mUnameEt.getText().toString();
                    String passwd = mPasswdEt.getText().toString();
                    Log.v(TAG,"onOK() - uname="+uname+", passwd="+passwd);
                    mWac.authenticate(uname,passwd, (String retVal) -> {
                        if (retVal != null) {
                            Log.d(TAG,"Authentication Success - token is "+retVal);
                            mUtil.showToast("Login Successful");
                            saveAuthToken(retVal);
                            updateUi();
                        } else {
                            Log.e(TAG,"onOk: Authentication failure for "+uname+", "+passwd);
                            mUtil.showToast("ERROR: Authentication Failed - Please Try Again");
                            mUtil.writeToSysLogFile("AuthActivity - Authorisation failed for "+uname+", "+passwd);
                        }
                    });
                    //finish();
                }
            };

    View.OnClickListener onLogout =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onLogout");
                    //m_status=false;
                    mWac.logout();
                    saveAuthToken(null);
                    updateUi();
                }
            };

    View.OnClickListener onRegister =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.d(TAG, "onRegisterBtn");
                    //Intent i;
                    //i = new Intent(getApplicationContext(), RemoteDbActivity.class);
                    //i.putExtra("url", "https://osdapi.ddns.net/static/register.html");
                    //startActivity(i);
                    String url = "https://osdapi.ddns.net/static/register.html";
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    startActivity(i);
                }
            };

    View.OnClickListener onResetPassword =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.d(TAG, "onResetPasswordBtn");
                    //Intent i;
                    //i = new Intent(getApplicationContext(), RemoteDbActivity.class);
                    //i.putExtra("url", "https://osdapi.ddns.net/static/register.html");
                    //startActivity(i);
                    String url = "https://osdapi.ddns.net/static/request_password_reset.html";
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    startActivity(i);
                }
            };


    private void saveAuthToken(String tokenStr) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        prefs.edit().putString(TOKEN_ID, tokenStr).commit();
        mWac.setStoredToken(tokenStr);
    }

    public String getAuthToken() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String authToken = prefs.getString(TOKEN_ID, null);
        return authToken;
    }



}