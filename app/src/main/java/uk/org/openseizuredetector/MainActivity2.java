package uk.org.openseizuredetector;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.rohitss.uceh.UCEHandler;

import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity2 extends AppCompatActivity {
    private String TAG = "MainActivity2";
    private int okColour = Color.BLUE;
    private int warnColour = Color.MAGENTA;
    private int alarmColour = Color.RED;
    private int okTextColour = Color.WHITE;
    private int warnTextColour = Color.WHITE;
    private int alarmTextColour = Color.BLACK;
    private Bundle mSavedInstanceState;

    private ViewPager2 mFragmentPager;
    private FragmentStateAdapter mFragmentStateAdapter;
    private Context mContext;
    private OsdUtil mUtil;
    private SdServiceConnection mConnection;
    final Handler serverStatusHandler = new Handler();
    private SharedPreferences SP;
    private long lastPress;
    private boolean activateStopByBack;
    private Toast backpressToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSavedInstanceState = savedInstanceState;
        createMainActivity(savedInstanceState);
    }

    private void createMainActivity(Bundle savedInstanceState) {
        setContentView(R.layout.activity_main2);

        Log.i(TAG, "onCreate()");

        // Set our custom uncaught exception handler to report issues.
        //Thread.setDefaultUncaughtExceptionHandler(new OsdUncaughtExceptionHandler(MainActivity.this));
        new UCEHandler.Builder(this)
                .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                .build();

        //int i = 5/0;  // Force exception to test handler.
        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);
        mConnection = new SdServiceConnection(getApplicationContext());
        mUtil.writeToSysLogFile("");
        mUtil.writeToSysLogFile("* MainActivity Started     *");
        mUtil.writeToSysLogFile("MainActivity.onCreate()");
        mContext = this;

        /**
         if (savedInstanceState == null) {
         // Instantiate a ViewPager2 and a PagerAdapter.
         mFragmentPager = findViewById(R.id.fragment_pager);
         mFragmentStateAdapter = new ScreenSlideFragmentPagerAdapter(this);
         mFragmentPager.setAdapter(mFragmentStateAdapter);
         getSupportFragmentManager().beginTransaction()
         .setReorderingAllowed(true)
         .add(R.id.fragment_common_container_view, FragmentCommon.class, null)
         .commit();
         }
         */
    }

    /**
     * Create Action Bar
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.i(TAG, "onCreateOptionsMenu()");
        getMenuInflater().inflate(R.menu.main_activity_actions, menu);
        MenuCompat.setGroupDividerEnabled(menu, true);
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart()");
        createMainActivity(null);
        setmFragmentPager();
        mFragmentPager.setId(SP.getInt(Constants.GLOBAL_CONSTANTS.lastPagerId,0));
        serverStatusHandler.postDelayed(()-> {
           mUtil.setBound(true,mConnection);
        },400);
    }

    private void setmFragmentPager() {
        mUtil.writeToSysLogFile("MainActivity.onStart()");
        SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        boolean audibleAlarm = SP.getBoolean("AudibleAlarm", true);
        Log.v(TAG, "onStart - audibleAlarm = " + audibleAlarm);

        TextView tv;
        tv = (TextView) findViewById(R.id.versionTv);
        String versionName = mUtil.getAppVersionName();
        tv.setText(getString(R.string.AppTitleText) + " " + versionName);
        tv.setBackgroundColor(okColour);
        tv.setTextColor(okTextColour);

        if (mUtil.isServerRunning()) {
            Log.i(TAG, "MainActivity2.onStart() - Binding to Server");
            mUtil.writeToSysLogFile("MainActivity2.onStart - Binding to Server");
            mUtil.bindToServer(getApplicationContext(), mConnection);
        } else {
            Log.i(TAG, "MainActivity2.onStart() - Server Not Running");
            mUtil.writeToSysLogFile("MainActivity2.onStart - Server Not Running");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop() - unbinding from server");
        mUtil.writeToSysLogFile("MainActivity.onStop()");
        mUtil.setBound(false,mConnection);
        mUtil.unbindFromServer(getApplicationContext(), mConnection);
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
        if (Objects.nonNull(mFragmentPager)){
            if (Objects.nonNull(SP)){
                SP.edit().putInt(Constants.GLOBAL_CONSTANTS.lastPagerId,mFragmentPager.getId()).apply();
            }
        }
       mUtil.setBound(false,mConnection);

    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
        if (Objects.isNull(mFragmentPager) || Objects.isNull(SP)) {
            createMainActivity(null);
            setmFragmentPager();
        }
        mFragmentPager.setId(SP.getInt(Constants.GLOBAL_CONSTANTS.lastPagerId,0));
        serverStatusHandler.postDelayed(()-> {
            mUtil.setBound(false,mConnection);
        },400);
        // Instantiate a ViewPager2 and a PagerAdapter.
        mFragmentPager = findViewById(R.id.fragment_pager);
        mFragmentStateAdapter = new ScreenSlideFragmentPagerAdapter(this);
        mFragmentPager.setAdapter(mFragmentStateAdapter);
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.fragment_common_container_view, FragmentCommon.class, null)
                .commit();

    }


    @Override
    public void onBackPressed() {
        if (Objects.isNull(mFragmentPager) || mFragmentPager.getCurrentItem() == 0) {
            // If the user is currently looking at the first step, allow the system to handle the
            // Back button. This calls finish() on this activity and pops the back stack.
            try {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastPress > 5000) {
                    backpressToast = Toast.makeText(getBaseContext(), "Press back again to exit", Toast.LENGTH_LONG);
                    backpressToast.show();
                    lastPress = currentTime;
                } else {
                    Log.d(TAG, "onBackPressed: initiating shutdown");
                    if (backpressToast != null) backpressToast.cancel();
                    activateStopByBack = true;
                    if (Objects.nonNull(mConnection))
                        if (mConnection.mBound)
                            mUtil.unbindFromServer(MainActivity2.this, mConnection);
                    if (mUtil.isServerRunning())
                        mUtil.stopServer();
                    serverStatusHandler.postDelayed(MainActivity2.this::finishAffinity, 100);
                    super.onBackPressed();
                }
            } catch (Exception e) {
                Log.e(TAG, "onBackPressed() Error thrown while processing.");
            }
        } else {
            // Otherwise, select the previous step.
            mFragmentPager.setCurrentItem(mFragmentPager.getCurrentItem() - 1);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "onOptionsItemSelected() :  " + item.getItemId() + " selected");
        switch (item.getItemId()) {
            /*case R.id.action_launch_pebble_app:
                Log.i(TAG, "action_launch_pebble_app");
                mConnection.mSdServer.mSdDataSource.startPebbleApp();
                return true;
                */
            case R.id.action_install_watch_app:
                Log.i(TAG, "action_install_watch_app");
                mConnection.mSdServer.mSdDataSource.installWatchApp();
                return true;

            case R.id.action_accept_alarm:
                Log.i(TAG, "action_accept_alarm");
                if (mConnection.mBound) {
                    mConnection.mSdServer.acceptAlarm();
                }
                return true;
            case R.id.action_start_stop:
                // Respond to the start/stop server menu item.
                Log.i(TAG, "action_start_stop");
                if (mConnection.mBound) {
                    Log.i(TAG, "Stopping Server");
                    mUtil.unbindFromServer(getApplicationContext(), mConnection);
                    stopServer();
                    //finish();
                } else {
                    Log.i(TAG, "Starting Server");
                    startServer();
                    // and bind to it so we can see its data
                    Log.i(TAG, "Binding to Server");
                    mUtil.bindToServer(getApplicationContext(), mConnection);
                }
                return true;
            case R.id.action_test_alarm_beep:
                Log.i(TAG, "action_test_alarm_beep");
                if (mConnection.mBound) {
                    mConnection.mSdServer.alarmBeep();
                }
                return true;
            case R.id.action_test_warning_beep:
                Log.i(TAG, "action_test_warning_beep");
                if (mConnection.mBound) {
                    mConnection.mSdServer.warningBeep();
                }
                return true;
            case R.id.action_test_sms_alarm:
                Log.i(TAG, "action_test_sms_alarm");
                if (mConnection.mBound) {
                    mConnection.mSdServer.sendSMSAlarm();
                }
                return true;

            case R.id.action_authenticate_api:
                Log.i(TAG, "action_autheticate_api");
                try {
                    Intent i = new Intent(
                            MainActivity2.this,
                            AuthenticateActivity.class);
                    this.startActivity(i);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting export activity " + ex.toString(), ex);
                }
                return true;
            case R.id.action_about_datasharing:
                Log.i(TAG, "action_about_datasharing");
                showDataSharingDialog();
                return true;
            case R.id.action_logmanager:
                Log.i(TAG, "action_logmanager");
                try {
                    Intent intent = new Intent(
                            MainActivity2.this,
                            LogManagerControlActivity.class);
                    this.startActivity(intent);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting log manager activity " + ex.toString(), ex);
                }
                return true;
            case R.id.action_report_seizure:
                Log.i(TAG, "action_report_seizure");
                try {
                    Intent intent = new Intent(
                            MainActivity2.this,
                            ReportSeizureActivity.class);
                    this.startActivity(intent);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting Report Seizure activity " + ex.toString(), ex);
                }
                return true;
            case R.id.action_settings:
                Log.i(TAG, "action_settings");
                try {
                    Intent prefsIntent = new Intent(
                            MainActivity2.this,
                            PrefActivity.class);
                    this.startActivity(prefsIntent);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting settings activity " + ex.toString(), ex);
                }
                return true;
            case R.id.action_about:
                Log.i(TAG, "action_about");
                showAbout();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }


    /**
     * A simple pager adapter that represents 5 ScreenSlidePageFragment objects, in
     * sequence.
     */
    private class ScreenSlideFragmentPagerAdapter extends FragmentStateAdapter {
        private String TAG = "ScreenSlideFragmentPagerAdapter";

        public ScreenSlideFragmentPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:
                    return new FragmentOsdAlg();
                case 1:
                    return new FragmentHrAlg();
                case 2:
                    return new FragmentBatt();
                case 3:
                    return new FragmentSystem();
                case 4:
                    return new FragmentDataSharing();
                case 5:
                    return new FragmentMlAlg();

                default:
                    Log.e(TAG, "createFragment() - invalid Position " + position);
                    return null;
            }
        }

        @Override
        public int getItemCount() {
            return 5;
        }
    }

    private void startServer() {
        mUtil.writeToSysLogFile("MainActivity.startServer()");
        Log.i(TAG, "startServer(): starting Server...");
        mUtil.startServer();
    }

    private void stopServer() {
        mUtil.writeToSysLogFile("MainActivity.stopServer()");
        Log.i(TAG, "stopServer(): stopping Server...");
        mUtil.stopServer();
    }


    private void showAbout() {
        mUtil.writeToSysLogFile("MainActivity.showAbout()");
        View aboutView = getLayoutInflater().inflate(R.layout.about_layout, null, false);
        String versionName = mUtil.getAppVersionName();
        Log.i(TAG, "showAbout() - version name = " + versionName);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(R.drawable.icon_24x24);
        builder.setTitle("OpenSeizureDetector V" + versionName);
        builder.setNeutralButton(getString(R.string.closeBtnTxt), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });
        builder.setPositiveButton(R.string.privacy_policy_button_title, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
                String url = OsdUtil.PRIVACY_POLICY_URL;
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
                dialog.cancel();
            }
        });
        builder.setNegativeButton(R.string.data_sharing_button_title, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
                String url = OsdUtil.DATA_SHARING_URL;
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
                dialog.cancel();
            }
        });
        builder.setView(aboutView);
        builder.create();
        builder.show();
    }

    private void showDataSharingDialog() {
        mUtil.writeToSysLogFile("MainActivity.showDataSharingDialog()");
        View aboutView = getLayoutInflater().inflate(R.layout.data_sharing_dialog_layout, null, false);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(R.drawable.datasharing_fault_24x24);
        builder.setTitle(R.string.data_sharing_dialog_title);
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(getString(R.string.login), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Log.i(TAG, "dataSharingDialog.positiveButton.onClick()");
                try {
                    Intent i = new Intent(
                            MainActivity2.this,
                            AuthenticateActivity.class);
                    mContext.startActivity(i);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting activity " + ex.toString(), ex);
                }

            }
        });
        builder.setView(aboutView);
        builder.create();
        builder.show();
    }

}