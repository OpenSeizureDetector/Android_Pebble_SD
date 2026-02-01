package uk.org.openseizuredetector;

/*
 * ADDING OR REMOVING TABS AND FRAGMENTS:
 *
 * To add or remove tabs from the main screen, you must update THREE places:
 *
 * 1. TabLayoutMediator Configuration (in onResume() method, around line 169):
 *    - Add/remove/modify the case statements that set tab labels
 *    - Each case number corresponds to a tab position (0-indexed)
 *    - Example: case 0: tab.setText("OSD");
 *
 * 2. ScreenSlideFragmentPagerAdapter.createFragment() method (around line 409):
 *    - Add/remove/modify the case statements that return Fragment instances
 *    - Each case must match the tab position from step 1
 *    - Example: case 0: return new FragmentOsdAlg();
 *
 * 3. ScreenSlideFragmentPagerAdapter.getItemCount() method (around line 434):
 *    - Update the return value to match the TOTAL number of active tabs
 *    - This MUST equal the number of active cases in steps 1 and 2
 *    - Example: return 4; (for tabs at positions 0, 1, 2, 3)
 *
 * IMPORTANT: All three places must be kept in sync!
 * - If getItemCount() returns N, you must have cases 0 through N-1 defined
 * - Position numbers must be consecutive starting from 0
 * - Commented out cases do NOT count toward the total
 *
 * Current Active Tabs (as of January 2026):
 * - Position 0: OSD Algorithm (FragmentOsdAlg)
 * - Position 1: ML Algorithm (FragmentMlAlg)
 * - Position 2: Heart Rate (FragmentHrAlg)
 * - Position 3: System (FragmentSystem - includes Signal & Battery graphs)
 *
 * Removed/Inactive:
 * - FragmentWatchSig (signal graph moved to System tab)
 * - FragmentBatt (battery graph moved to System tab)
 */

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.MenuCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    private ViewPager2 mFragmentPager;
    private FragmentStateAdapter mFragmentStateAdapter;
    private TabLayout mTabLayout;
    private TabLayoutMediator mTabLayoutMediator;
    private Context mContext;
    private OsdUtil mUtil;
    private SdServiceConnection mConnection;
    final Handler serverStatusHandler = new Handler();
    private Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        // Handle system window insets for all API levels
        View rootView = findViewById(R.id.activity_main2_root_layout);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            // Get the system bar insets
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

            // Apply padding to your main content view
            LinearLayout content = findViewById(R.id.activity_main2_content_layout);
            content.setPadding(0, top, 0, bottom);

            // Return the insets so they keep propagating
            return WindowInsetsCompat.CONSUMED;
        });


        Log.i(TAG, "onCreate()");

        // Set our custom uncaught exception handler to report issues.
        //Thread.setDefaultUncaughtExceptionHandler(new OsdUncaughtExceptionHandler(MainActivity.this));
        new UCEHandler.Builder(this)
                .addCommaSeparatedEmailAddresses("crashreports@openseizuredetector.org.uk,")
                .build();

        //int i = 5/0;  // Force exception to test handler.
        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);
        mConnection = new SdServiceConnection(getApplicationContext());
        mUtil.writeToSysLogFile("MainActivity2.onCreate()");
        mContext = this;
        mHandler = new Handler();
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
        mUtil.writeToSysLogFile("MainActivity.onStart()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        boolean audibleAlarm = SP.getBoolean("AudibleAlarm", true);
        Log.v(TAG, "onStart - audibleAlarm = " + audibleAlarm);

        // Set action bar title with version number
        String versionName = mUtil.getAppVersionName();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.AppTitleText) + " " + versionName);
        }

        if (mUtil.isServerRunning()) {
            Log.i(TAG, "MainActivity2.onStart() - Server Running - Binding to Server");
            mUtil.writeToSysLogFile("MainActivity2.onStart - Binding to Server");
            mUtil.bindToServer(getApplicationContext(), mConnection);
        } else {
            Log.i(TAG, "MainActivity2.onStart() - Server Not Running - Starting Server");
            mUtil.writeToSysLogFile("MainActivity2.onStart - Server Not Running - Starting Server");
            mUtil.startServer();
            // Give server a moment to start before binding
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    Log.i(TAG, "MainActivity2.onStart() - Now binding to server");
                    mUtil.bindToServer(getApplicationContext(), mConnection);
                }
            }, 500);
        }


    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop() - unbinding from server");
        mUtil.writeToSysLogFile("MainActivity.onStop()");
        mUtil.unbindFromServer(getApplicationContext(), mConnection);
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");

        // Detach TabLayoutMediator to prevent memory leaks
        if (mTabLayoutMediator != null) {
            mTabLayoutMediator.detach();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
        // Instantiate a ViewPager2 and a PagerAdapter.
        mFragmentPager = findViewById(R.id.fragment_pager);
        mFragmentStateAdapter = new ScreenSlideFragmentPagerAdapter(this);
        mFragmentPager.setAdapter(mFragmentStateAdapter);

        // Set up TabLayout with ViewPager2
        mTabLayout = findViewById(R.id.tab_layout);
        mTabLayoutMediator = new TabLayoutMediator(mTabLayout, mFragmentPager,
                new TabLayoutMediator.TabConfigurationStrategy() {
                    @Override
                    public void onConfigureTab(TabLayout.Tab tab, int position) {
                        switch (position) {
                            case 0:
                                tab.setText("OSD");
                                break;
                            case 1:
                                tab.setText("ML");
                                break;
                            case 2:
                                tab.setText("Heart Rate");
                                break;
                            case 3:
                                tab.setText("System");
                                break;
                            //case 4:
                            //    tab.setText("Signal");
                            //    break;
                            //case 5:
                            //    tab.setText("Battery");
                            //    break;
                            default:
                                tab.setText("Screen " + position);
                        }
                    }
                });
        mTabLayoutMediator.attach();

        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.fragment_common_container_view, FragmentCommon.class, null)
                .commit();

        // Force the screen to stay on when the app is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    }


    @Override
    public void onBackPressed() {
        if (Objects.isNull(mFragmentPager) || mFragmentPager.getCurrentItem() == 0) {
            // If the user is currently looking at the first step, allow the system to handle the
            // Back button. This calls finish() on this activity and pops the back stack.
            super.onBackPressed();
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
            case R.id.action_exit:
                // Respond to the start/stop server menu item.
                Log.i(TAG, "action_exit: Stopping Server");
                mUtil.unbindFromServer(getApplicationContext(), mConnection);
                stopServer();
                // We exit this activity as a crude way of forcing the fragments to disconnect from the server
                // so that the server exits properly - otherwise we end up with multiple threads running.
                // FIXME - tell the threads to unbind from the serer before calling stopServer as an alternative.
                finish();
                return true;
            case R.id.action_start_stop:
                // FIXME: We need to unbind the fragments from the service, or else unbindFromServer does not work!
                // Disabled this menu option until I work out how to fix it!
                Log.i(TAG, "action_start_stop: restarting server");
                mUtil.unbindFromServer(this, mConnection );
                mUtil.showToast("Stopping Background Service....");
                mUtil.stopServer();
                // Wait 1 second to give the server chance to shutdown, then re-start it
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        mUtil.showToast("NOT Re-Starting Background Service...");
                        //mUtil.startServer();
                    }
                }, 1000);
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
            case R.id.action_send_false_alarm_sms:
                Log.i(TAG, "action_send_false_alarm_sms");
                if (mConnection.mBound) {
                    mConnection.mSdServer.sendFalseAlarmSMS();
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
                    Log.i(TAG, "exception starting export activity " + ex.toString());
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
                    Log.i(TAG, "exception starting log manager activity " + ex.toString());
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
                    Log.i(TAG, "exception starting Report Seizure activity " + ex.toString());
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
                    Log.i(TAG, "exception starting settings activity " + ex.toString());
                }
                return true;
            case R.id.action_instructions:
                Log.i(TAG, "action_instructions");
                try {
                    String url = "https://www.openseizuredetector.org.uk/?page_id=1894";
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ex) {
                    Log.v(TAG, "exception displaying instructions " + ex.toString());
                    mUtil.showToast("ERROR Displaying Instructions");
                }
                return true;

            case R.id.action_troubleshooting:
                Log.i(TAG, "action_troubleshooting");
                try {
                    String url = "https://www.openseizuredetector.org.uk/?page_id=2235";
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ex) {
                    Log.v(TAG, "exception displaying troubleshooting " + ex.toString());
                    mUtil.showToast("ERROR Displaying Troubleshooting Tips");
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
            // Note - the number of positions must match the value returned by getItemCount() below.
            switch (position) {
                case 0:
                    return new FragmentOsdAlg();
                case 1:
                    return new FragmentMlAlg();
                case 2:
                    return new FragmentHrAlg();
                case 3:
                    return new FragmentSystem();
                //case 4:
                //    return new FragmentWatchSig();
                //case 5:
                //    return new FragmentBatt();
                //case 4:
                //    return new FragmentDataSharing();

                default:
                    Log.e(TAG, "createFragment() - invalid Position " + position);
                    return null;
            }
        }

        @Override
        public int getItemCount() {
            return 4; // Must match the number of active cases in createFragment() above
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
                    Log.i(TAG, "exception starting activity " + ex.toString());
                }

            }
        });
        builder.setView(aboutView);
        builder.create();
        builder.show();
    }

}