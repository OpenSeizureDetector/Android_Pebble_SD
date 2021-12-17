package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AuthenticateActivity extends AppCompatActivity
        implements AuthCallbackInterface, EventCallbackInterface, DatapointCallbackInterface {
    private String TAG = "AuthenticateActivity";
    private Context mContext;
    private EditText mUnameEt;
    private EditText mPasswdEt;
    private boolean mIsLoggedIn;
    private WebApiConnection mWac;

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

        Button createEventBtn = (Button)findViewById(R.id.createEventBtn);
        createEventBtn.setOnClickListener(onCreateEvent);
        Button createDatapointBtn = (Button)findViewById(R.id.createDatapointBtn);
        createDatapointBtn.setOnClickListener(onCreateDatapoint);

        mUnameEt = (EditText) findViewById(R.id.username);
        mPasswdEt = (EditText) findViewById(R.id.password);
        mWac = new WebApiConnection(this, this, this, this);
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
    View.OnClickListener onCreateEvent =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onCreateEvent");
                    mWac.createEvent(10,new Date(),"eventDescription....");
                }
            };

    View.OnClickListener onCreateDatapoint =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onCreateDatapoint");
                    String jsonStr = "";
                    JSONObject dataObj;
                    try {
                        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss");
                        jsonStr = "{HR:70}";
                        dataObj = new JSONObject(jsonStr);
                        dataObj.put("dataTime", dateFormat.format(new Date()));
                        Log.v(TAG, "Creating Datapoint..."+dataObj.toString());
                        mWac.createDatapoint(dataObj,10);
                    } catch (JSONException e) {
                        Log.v(TAG,"Error Creating JSON Object from string "+jsonStr);
                        dataObj = null;
                        jsonStr = null;
                    }
                    //m_status=false;
                    // mWac.logout();
                    //updateUi();
                }
            };

}