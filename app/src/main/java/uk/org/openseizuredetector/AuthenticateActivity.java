package uk.org.openseizuredetector;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Arrays;

public class AuthenticateActivity extends AppCompatActivity {
    private String TAG = "AuthenticateActivity";
    private OsdUtil mUtil;
    private EditText mUnameEt;
    private EditText mPasswdEt;
    private SdServiceConnection mConnection;
    final Handler serverStatusHandler = new Handler();
    private WebApiConnection mWac;
    private LogManager mLm;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_authenticate);

        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);
        if (!mUtil.isServerRunning()) {
            mUtil.showToast(getString(R.string.error_server_not_running));
            finish();
            return;
        }

        Button cancelBtn =
                (Button) findViewById(R.id.cancelBtn);
        cancelBtn.setOnClickListener(onCancel);
        Button loginBtn = (Button) findViewById(R.id.loginBtn);
        loginBtn.setOnClickListener(onLogin);
        Button logoutCancelBtn =
                (Button) findViewById(R.id.logoutCancelBtn);
        logoutCancelBtn.setOnClickListener(onCancel);
        Button logoutBtn = (Button) findViewById(R.id.logoutBtn);
        logoutBtn.setOnClickListener(onLogout);

        // Components required only for osdapi backend
        if (LogManager.USE_FIREBASE_BACKEND) { }
        else {
            mConnection = new SdServiceConnection(getApplicationContext());

            Button registerBtn = (Button) findViewById(R.id.RegisterBtn);
            registerBtn.setOnClickListener(onRegister);
            Button resetPasswordBtn = (Button) findViewById(R.id.ResetPasswordBtn);
            resetPasswordBtn.setOnClickListener(onResetPassword);

            mUnameEt = (EditText) findViewById(R.id.username);
            mPasswdEt = (EditText) findViewById(R.id.password);
        }

        Button aboutDataSharingBtn = (Button) findViewById(R.id.aboutDataSharingBtn);
        aboutDataSharingBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Log.v(TAG,"aboutDataSharingBtn.onClick()");
                        String url = OsdUtil.DATA_SHARING_URL;
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                    }
                }
        );
        Button privacyPolicyBtn = (Button) findViewById(R.id.privacyPolicyBtn);
        privacyPolicyBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Log.v(TAG,"privacyPolicyBtn.onClick()");
                        String url = OsdUtil.PRIVACY_POLICY_URL;
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                    }
                }
        );

    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart()");
        super.onStart();
        if (LogManager.USE_FIREBASE_BACKEND) {
            updateUi();
        } else {
            mUtil.bindToServer(getApplicationContext(), mConnection);
            waitForConnection();
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop()");
        super.onStop();
        if (LogManager.USE_FIREBASE_BACKEND) {

        } else {
            mUtil.unbindFromServer(getApplicationContext(), mConnection);
        }
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
        updateUi();
    }



    // Called after the Firebase Auth UI has completed
    private ActivityResultLauncher<Intent> signInLauncher = registerForActivityResult(
            new FirebaseAuthUIActivityResultContract(),
            (result) -> {
                Log.i(TAG, "FirebaseAuthUIActivityResult - " + result.toString());
                updateUi();
            });

// ...


    private void updateUi() {
        LinearLayout loginLl = (LinearLayout) findViewById(R.id.login_ui);
        LinearLayout logoutLl = (LinearLayout) findViewById(R.id.logout_ui);

        // Check if we are already logged in
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.i(TAG, "Not Logged in - showing log in UI");
            loginLl.setVisibility(View.VISIBLE);
            logoutLl.setVisibility(View.GONE);
        } else {
            Log.i(TAG, "Already Logged in - showing Log Out prompt - " + auth.getCurrentUser().toString());
            loginLl.setVisibility(View.GONE);
            logoutLl.setVisibility(View.VISIBLE);
            TextView tv2 = (TextView) findViewById(R.id.userIdTv);
            tv2.setText(auth.getCurrentUser().getDisplayName());
            tv2 = (TextView) findViewById(R.id.usernameTv);
            tv2.setText(auth.getCurrentUser().getEmail());
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

    View.OnClickListener onLogin =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //m_status=true;
                    Log.v(TAG, "onLogin() - using Firebase Login");
                    Intent signInIntent = AuthUI.getInstance()
                            .createSignInIntentBuilder()
                            .setAvailableProviders(Arrays.asList(
                                    new AuthUI.IdpConfig.GoogleBuilder().build(),
                                    //new AuthUI.IdpConfig.FacebookBuilder().build(),
                                    //new AuthUI.IdpConfig.TwitterBuilder().build(),
                                    //new AuthUI.IdpConfig.MicrosoftBuilder().build(),
                                    //new AuthUI.IdpConfig.YahooBuilder().build(),
                                    //new AuthUI.IdpConfig.AppleBuilder().build(),
                                    new AuthUI.IdpConfig.EmailBuilder().build()
                                    //new AuthUI.IdpConfig.PhoneBuilder().build()
                                    //new AuthUI.IdpConfig.AnonymousBuilder().build()))
                            ))
                            // ... options ...
                            .build();
                    signInLauncher.launch(signInIntent);
                }
            };

    View.OnClickListener onLogout = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "onLogout");
                AuthUI.getInstance()
                        .signOut(getApplicationContext())
                        .addOnCompleteListener(new OnCompleteListener<Void>() {
                            public void onComplete(@NonNull Task<Void> task) {
                                // user is now signed out
                                updateUi();
                            }
                        });
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