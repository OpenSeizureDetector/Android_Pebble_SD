package uk.org.openseizuredetector;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v7.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AuthenticateActivity extends AppCompatActivity
        implements AuthCallbackInterface, EventCallbackInterface, DatapointCallbackInterface {
    private String TAG = "AuthenticateActivity";
    private Context mContext;
    private EditText mUnameEt;
    private EditText mPasswdEt;
    private WebApiConnection mWac;
    private LogManager mLm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_authenticate);
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

        mUnameEt = (EditText) findViewById(R.id.username);
        mPasswdEt = (EditText) findViewById(R.id.password);
        mWac = new WebApiConnection(this, this, this, this);
        mLm = new LogManager(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateUi();
    }

    public void authCallback(boolean authSuccess, String tokenStr) {
        Log.v(TAG,"authCallback");
        updateUi();
    }

    public void eventCallback(boolean success, String eventStr) {
        Log.v(TAG,"eventCallback");
    }

    public void datapointCallback(boolean success, String datapointStr) {
        Log.v(TAG,"datapointCallback");
    }

    private void updateUi() {
        SharedPreferences prefs;
        String storedAuthToken;
        LinearLayout loginLl = (LinearLayout)findViewById(R.id.login_ui);
        LinearLayout logoutLl = (LinearLayout)findViewById(R.id.logout_ui);
        Log.i(TAG, "switchUi()");
        storedAuthToken = mWac.getStoredToken();
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
                    mWac.authenticate(uname,passwd);
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
                    updateUi();
                }
            };
}