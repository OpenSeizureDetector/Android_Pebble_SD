package uk.org.openseizuredetector;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class AuthenticateActivity extends AppCompatActivity {
    private String TAG = "AuthenticateActivity";
    private OsdUtil mUtil;
    private EditText mUnameEt;
    private EditText mPasswdEt;
    private SdServiceConnection mConnection;
    final Handler serverStatusHandler = new Handler();
    private WebApiConnection mWac;
    private static final String TOKEN_ID = "webApiAuthToken";


    View.OnClickListener onCancel =
            view -> {
                Log.v(TAG, "onCancel");
                //m_status=false;
                finish();
            };

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

    View.OnClickListener onLogout = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Log.v(TAG, "onLogout");
            if (LogManager.USE_FIREBASE_BACKEND) {
                AuthUI.getInstance()
                        .signOut(getApplicationContext())
                        .addOnCompleteListener(task -> {
                            // user is now signed out
                            updateUi();
                        });
            } else {
                if (mWac != null) {
                    mWac.logout();
                    saveAuthToken(null);
                } else {
                    Log.e(TAG, "logout() - mWac is null - not doing anything");
                }
            }
            updateUi();
        }
    };
    View.OnClickListener onRegister =
            view -> {
                Log.d(TAG, "onRegisterBtn");
                //Intent i;
                //i = new Intent(getApplicationContext(), RemoteDbActivity.class);
                //i.putExtra("url", "https://osdapi.ddns.net/static/register.html");
                //startActivity(i);
                String url = "https://osdapi.ddns.net/static/register.html";
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
            };
    View.OnClickListener onResetPassword =
            view -> {
                Log.d(TAG, "onResetPasswordBtn");
                //Intent i;
                //i = new Intent(getApplicationContext(), RemoteDbActivity.class);
                //i.putExtra("url", "https://osdapi.ddns.net/static/register.html");
                //startActivity(i);
                String url = "https://osdapi.ddns.net/static/request_password_reset.html";
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
            };

    // ...
    // Called after the Firebase Auth UI has completed
    private ActivityResultLauncher<Intent> signInLauncher = registerForActivityResult(
            new FirebaseAuthUIActivityResultContract(),
            (result) -> {
                Log.i(TAG, "FirebaseAuthUIActivityResult - " + result.toString());
                updateUi();
            });
    View.OnClickListener onLogin =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //m_status=true;
                    if (LogManager.USE_FIREBASE_BACKEND) {
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
                    } else {
                        // Use Username and password authentication for OSDAPI.
                        // FIXME - make this work with Google Authentication like we do for Firebase.
                        String uname = mUnameEt.getText().toString();
                        String passwd = mPasswdEt.getText().toString();
                        Log.v(TAG, "onOK() - uname=" + uname + ", passwd=" + passwd);
                        mWac.authenticate(uname, passwd, retVal -> {
                            if (retVal != null) {
                                Log.d(TAG, "Authentication Success - token is " + retVal);
                                mUtil.showToast("Login Successful");
                                saveAuthToken(retVal);
                                updateUi();
                            } else {
                                Log.e(TAG, "onOk: Authentication failure for " + uname + ", " + passwd);
                                mUtil.showToast("ERROR: Authentication Failed - Please Try Again");
                                mUtil.writeToSysLogFile("AuthActivity - Authorisation failed for " + uname + ", " + passwd);
                            }
                        });
                    }
                }
            };

    private void updateUi() {
        Log.v(TAG, "updateUi()");
        LinearLayout loginLl = (LinearLayout) findViewById(R.id.login_ui);
        LinearLayout osdApiLoginLl = (LinearLayout) findViewById(R.id.login_osdapi_ui);
        LinearLayout logoutLl = (LinearLayout) findViewById(R.id.logout_ui);

        if (mWac == null) {
            Log.i(TAG, "mWac is null - not updating UI");
            return;
        }

        if (mWac.isLoggedIn()) {
            Log.v(TAG, "Already Logged in - showing Log Out prompt");
            loginLl.setVisibility(View.GONE);
            logoutLl.setVisibility(View.VISIBLE);
            if (!LogManager.USE_FIREBASE_BACKEND) {
                osdApiLoginLl.setVisibility(View.GONE);
            }
            mWac.getUserProfile((JSONObject profileObj) -> {
                try {
                    String userId = profileObj.getString("id");
                    String userName = profileObj.getString("username");
                    TextView tv2 = (TextView) findViewById(R.id.userIdTv);
                    tv2.setText(userId);
                    tv2 = (TextView) findViewById(R.id.usernameTv);
                    tv2.setText(userName);
                } catch (JSONException e) {
                    Log.e(TAG, "Error Parsing profileObj: " + e.getMessage());
                    mUtil.showToast("Error Parsing profileObj - this should not happen!!!");
                }
            });
        } else {
            Log.v(TAG, "updateUi() - not logged in..");
            loginLl.setVisibility(View.VISIBLE);
            logoutLl.setVisibility(View.GONE);
            if (!LogManager.USE_FIREBASE_BACKEND) {
                osdApiLoginLl.setVisibility(View.VISIBLE);
            }

        }
    }

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
        if (LogManager.USE_FIREBASE_BACKEND) {
        } else {
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
                view -> {
                    Log.v(TAG, "aboutDataSharingBtn.onClick()");
                    String url = OsdUtil.DATA_SHARING_URL;
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    startActivity(i);
                }
        );
        Button privacyPolicyBtn = (Button) findViewById(R.id.privacyPolicyBtn);
        privacyPolicyBtn.setOnClickListener(
                view -> {
                    Log.v(TAG, "privacyPolicyBtn.onClick()");
                    String url = OsdUtil.PRIVACY_POLICY_URL;
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    startActivity(i);
                }
        );

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
            new Handler().postDelayed(() -> waitForConnection(), 100);
        }
    }

    private void initialiseServiceConnection() {
        Log.v(TAG, "initialiseServiceConnection()");
        LogManager mLm = mConnection.mSdServer.mLm;
        mWac = mConnection.mSdServer.mLm.mWac;
        updateUi();
    }


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