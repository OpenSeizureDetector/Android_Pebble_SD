package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

/*
 * ADDING OR REMOVING TABS AND FRAGMENTS:
 *
 * Tabs are identified by the TAB_* constants below, NOT by their position on screen.
 * Which tabs are shown is decided at runtime by getVisibleTabs(): tabs whose algorithm
 * is switched off in the "Seizure Detector Algorithms Selection" settings are hidden
 * completely (the pager and the TabLayout only ever see the visible ones).
 *
 * To add or remove a tab you must update FOUR places:
 *
 * 1. Add a TAB_* constant (never reuse or renumber an existing one - the value is also
 *    what is saved in the "main_activity_active_tab" preference).
 * 2. getVisibleTabs(): add the tab in the position you want it to appear, together with
 *    the preference key that enables it (or always show it).
 * 3. getTabTitle(): return the label shown on the tab.
 * 4. ScreenSlideFragmentPagerAdapter.createFragment(): return the Fragment for the tab.
 *
 * Tabs (TAB_* id = value; on screen they appear in the order getVisibleTabs() adds them):
 * - TAB_OSD    (0): OSD Algorithm (FragmentOsdAlg)        - hidden if OsdAlarmActive is off
 * - TAB_FLAP   (1): Flap Algorithm (FragmentFlapAlg)      - hidden if FlapAlarmActive is off
 * - TAB_ML     (2): ML Algorithm (FragmentMlAlg)          - hidden if CnnAlarmActive is off
 * - TAB_HR     (3): Heart Rate (FragmentHrAlg)            - hidden if HRAlarmActive is off
 * - TAB_FALL   (4): Fall Detection (FragmentFallAlg)      - hidden if FallActive is off
 * - TAB_SYSTEM (5): System (FragmentSystem - includes Signal & Battery graphs) - always shown
 * Removed/Inactive:
 * - FragmentWatchSig (signal graph moved to System tab)
 * - FragmentBatt (battery graph moved to System tab)
 */

import uk.org.openseizuredetector.client.SdServiceConnection;
import uk.org.openseizuredetector.utils.OsdUtil;
import uk.org.openseizuredetector.utils.PreferenceUtils;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.OnBackPressedCallback;
import androidx.preference.PreferenceManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import uk.org.openseizuredetector.data.logging.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup; // Ensure ViewGroup is imported if needed, though LinearLayout is used directly
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rohitss.uceh.UCEHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import uk.org.openseizuredetector.activity.auth.AuthenticateActivity;
import uk.org.openseizuredetector.activity.events.ReportSeizureActivity;
import uk.org.openseizuredetector.activity.logging.LogManagerControlActivity;
import uk.org.openseizuredetector.activity.settings.PrefActivity;
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

    private SharedPreferences mSharedPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mModePreferenceListener;
    private Boolean mCurrentBasicMode;

    // Tab position persistence
    // PREF_ACTIVE_TAB stores a TAB_* id (not a screen position), because hidden tabs mean
    // the position of a tab changes with the settings. The TAB_* values equal the positions
    // the tabs had when all six were always shown, so previously saved values stay valid.
    private static final String PREF_ACTIVE_TAB = "main_activity_active_tab";
    private static final int TAB_OSD = 0;
    private static final int TAB_FLAP = 1;
    private static final int TAB_ML = 2;
    private static final int TAB_HR = 3;
    private static final int TAB_FALL = 4;
    private static final int TAB_SYSTEM = 5;
    private static final int DEFAULT_TAB = TAB_OSD;

    // The tabs currently shown, in screen order (TAB_* ids). Rebuilt on every onResume().
    private List<Integer> mVisibleTabs = new ArrayList<>();

    final Handler serverStatusHandler = new Handler(Looper.getMainLooper());
    private Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        Log.i(TAG, "onCreate()");

        // UCEHandler is installed in Application class (OsdApplication) to ensure a single install for the whole app.

        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);
        mConnection = new SdServiceConnection(getApplicationContext());
        // Note: Log.i automatically writes to syslog file based on configured level
        mUtil.writeMemoryLog("MainActivity2.onCreate");
        mContext = this;
        mHandler = new Handler(Looper.getMainLooper());

        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mModePreferenceListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
             @Override
             public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                 if ("pref_basic_mode".equals(key)) {
                     recreateCommonFragment();
                 } else if ("LatchAlarms".equals(key)) {
                    invalidateOptionsMenu();
                 }
             }
         };

        // Configure system bar appearance - ensure icons are visible
        // The system will show light icons on dark background and dark icons on light background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean isLightMode = isLightTheme();
                controller.setAppearanceLightStatusBars(isLightMode);
                controller.setAppearanceLightNavigationBars(isLightMode);
            }
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mUtil != null) {
                    Log.i(TAG, "MainActivity2.onBackPressed() - closing activity");
                }
                finish();
            }
        });
    }

    /**
     * Check if the current theme is light mode
     */
    private boolean isLightTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO;
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
    public boolean onPrepareOptionsMenu(Menu menu) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean latchEnabled = PreferenceUtils.getBooleanFromXml(prefs, "LatchAlarms");
        boolean smsEnabled = PreferenceUtils.getBooleanFromXml(prefs, "SMSAlarm");
        MenuItem latchItem = menu.findItem(R.id.action_accept_alarm);
        MenuItem testSmsAlarm = menu.findItem(R.id.action_test_sms_alarm);
        MenuItem sendFalseAlarmSms = menu.findItem(R.id.action_send_false_alarm_sms);

        // Hide some menu items depending on the state of other settings to simplify the
        //      menu where possible.
        if (latchItem != null) {
            latchItem.setVisible(latchEnabled);
        }
        if (testSmsAlarm != null ) {
            testSmsAlarm.setVisible(smsEnabled);
        }
        if (sendFalseAlarmSms != null) {
            sendFalseAlarmSms.setVisible(smsEnabled);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart()");
        Log.i(TAG, "MainActivity.onStart()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        boolean audibleAlarm = PreferenceUtils.getBooleanFromXml(SP, "AudibleAlarm");
        Log.v(TAG, "onStart - audibleAlarm = " + audibleAlarm);

        // Set action bar title with version number
        String versionName = mUtil.getAppVersionName();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.AppTitleText) + " " + versionName);
        }

        if (mUtil.isServerRunning()) {
            Log.i(TAG, "MainActivity2.onStart() - Server Running - Binding to Server");
            Log.i(TAG, "MainActivity2.onStart - Binding to Server");
            mUtil.bindToServer(getApplicationContext(), mConnection);
        } else {
            // Check if user explicitly stopped the service via "Exit"
            // If so, don't auto-start it - let the user start it explicitly
            SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            boolean userStopped = prefs.getBoolean("user_stopped_service", false);

            if (userStopped) {
                Log.i(TAG, "MainActivity2.onStart() - User stopped service via Exit, not auto-starting");
                Log.i(TAG, "MainActivity2.onStart - User stopped service, not auto-starting");
                // Show message and close activity
                mUtil.showToast("Service was stopped. Please restart from launcher.");
                finish();
            } else {
                Log.i(TAG, "MainActivity2.onStart() - Server Not Running - Starting Server");
                Log.i(TAG, "MainActivity2.onStart - Server Not Running - Starting Server");
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


    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop() - unbinding from server");
        Log.i(TAG, "MainActivity.onStop()");
        mUtil.unbindFromServer(getApplicationContext(), mConnection);
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
        if (mSharedPrefs != null && mModePreferenceListener != null) {
            mSharedPrefs.unregisterOnSharedPreferenceChangeListener(mModePreferenceListener);
        }
        if (mUtil != null) {
            Log.i(TAG, "MainActivity2.onPause()");
        }

        // Save the current tab position before detaching
        if (mFragmentPager != null) {
            int position = mFragmentPager.getCurrentItem();
            if (position >= 0 && position < mVisibleTabs.size()) {
                int currentTabId = mVisibleTabs.get(position);
                SharedPreferences prefs = mSharedPrefs != null ? mSharedPrefs : PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putInt(PREF_ACTIVE_TAB, currentTabId).apply();
                Log.d(TAG, "Saved active tab id: " + currentTabId);
            }
        }

        // Detach TabLayoutMediator to prevent memory leaks (only if it exists - portrait layout)
        if (mTabLayoutMediator != null) {
            mTabLayoutMediator.detach();
            Log.d(TAG, "onPause() - Detached TabLayoutMediator");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
        if (mUtil != null) {
            Log.i(TAG, "MainActivity2.onResume()");
            mUtil.writeMemoryLog("MainActivity2.onResume");
        }
        if (mSharedPrefs != null && mModePreferenceListener != null) {
            mSharedPrefs.registerOnSharedPreferenceChangeListener(mModePreferenceListener);
        }
        SharedPreferences prefs = mSharedPrefs != null ? mSharedPrefs : PreferenceManager.getDefaultSharedPreferences(this);

        // Work out which tabs to show (algorithms that are switched off get no tab at all).
        mVisibleTabs = getVisibleTabs(prefs);

        // Instantiate a ViewPager2 and a PagerAdapter.
        mFragmentPager = findViewById(R.id.fragment_pager);
        mFragmentStateAdapter = new ScreenSlideFragmentPagerAdapter(this, mVisibleTabs);
        mFragmentPager.setAdapter(mFragmentStateAdapter);

        // Set up TabLayout with ViewPager2 (portrait layout)
        // The landscape layout doesn't have a TabLayout - it uses sidebar navigation instead
        mTabLayout = findViewById(R.id.tab_layout);
        if (mTabLayout != null) {
            mTabLayoutMediator = new TabLayoutMediator(mTabLayout, mFragmentPager,
                    new TabLayoutMediator.TabConfigurationStrategy() {
                        @Override
                        public void onConfigureTab(TabLayout.Tab tab, int position) {
                            if (position >= 0 && position < mVisibleTabs.size()) {
                                tab.setText(getTabTitle(mVisibleTabs.get(position)));
                            } else {
                                tab.setText("Screen " + position);
                            }
                        }
                    });
            mTabLayoutMediator.attach();
        } else {
            Log.d(TAG, "onResume() - TabLayout not found (landscape layout detected), skipping TabLayoutMediator");
        }

        // Restore the previously active tab
        // If no saved preference, choose tab based on enabled algorithms

        // Basic Mode Toggle Logic
        boolean basicMode = PreferenceUtils.getBooleanFromXml(prefs, "pref_basic_mode");
        if (mCurrentBasicMode == null) {
            mCurrentBasicMode = basicMode;
        } else if (mCurrentBasicMode != basicMode) {
            mCurrentBasicMode = basicMode;
            recreateCommonFragment();
        }
        View fragmentContainer = findViewById(R.id.fragment_common_container_view);

        if (basicMode) {
            if (mTabLayout != null) mTabLayout.setVisibility(View.GONE);
            if (mFragmentPager != null) mFragmentPager.setVisibility(View.GONE);
            if (fragmentContainer != null) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) fragmentContainer.getLayoutParams();
                params.height = LinearLayout.LayoutParams.MATCH_PARENT;
                params.weight = 1.0f;
                fragmentContainer.setLayoutParams(params);
            }
        } else {
            if (mTabLayout != null) mTabLayout.setVisibility(View.VISIBLE);
            if (mFragmentPager != null) mFragmentPager.setVisibility(View.VISIBLE);
            if (fragmentContainer != null) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) fragmentContainer.getLayoutParams();
                params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                params.weight = 0;
                fragmentContainer.setLayoutParams(params);
            }
        }

        int savedTabId = prefs.getInt(PREF_ACTIVE_TAB, -1); // -1 means not set

        if (savedTabId == -1) {
            // No saved preference - select tab based on algorithm preferences
            // Priority order: ML, OSD, HR
            savedTabId = getDefaultTabFromAlgorithmSettings(prefs);
            Log.d(TAG, "No saved tab preference, selected tab based on algorithm settings: " + savedTabId);
        }

        // The saved tab may be hidden now (its algorithm was switched off since last time).
        // Fall back to the default tab, and failing that to the first visible tab.
        int savedPosition = mVisibleTabs.indexOf(savedTabId);
        if (savedPosition < 0) {
            savedPosition = mVisibleTabs.indexOf(getDefaultTabFromAlgorithmSettings(prefs));
        }
        if (savedPosition < 0) {
            savedPosition = 0;
        }

        mFragmentPager.setCurrentItem(savedPosition, false); // false = no animation on restore
        Log.d(TAG, "Restored active tab position: " + savedPosition + " (tab id " + mVisibleTabs.get(savedPosition) + ")");

        Fragment existingCommon = getSupportFragmentManager().findFragmentById(R.id.fragment_common_container_view);
        if (existingCommon == null) {
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.fragment_common_container_view, FragmentCommon.class, null)
                    .commit();
        }

        // Force the screen to stay on when the app is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    }



    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "onOptionsItemSelected() :  " + item.getItemId() + " selected");
        int itemId = item.getItemId();
        if (itemId == R.id.action_accept_alarm) {
            Log.i(TAG, "action_accept_alarm");
            if (mConnection.mBound) {
                mConnection.mSdServer.acceptAlarm();
            }
            return true;
        } else if (itemId == R.id.action_exit) {
            // Respond to the start/stop server menu item.
            Log.i(TAG, "action_exit: Stopping Server");
            mUtil.shutdownApp(this, false, true, "Stopping OpenSeizureDetector...");
            return true;
        } else if (itemId == R.id.action_test_alarm_beep) {
            Log.i(TAG, "action_test_alarm_beep");
            if (mConnection.mBound) {
                mConnection.mSdServer.alarmBeep();
            }
            return true;
        } else if (itemId == R.id.action_test_warning_beep) {
            Log.i(TAG, "action_test_warning_beep");
            if (mConnection.mBound) {
                mConnection.mSdServer.warningBeep();
            }
            return true;
        } else if (itemId == R.id.action_test_sms_alarm) {
            Log.i(TAG, "action_test_sms_alarm");
            if (mConnection.mBound) {
                mConnection.mSdServer.sendSMSAlarm();
            }
            return true;
        } else if (itemId == R.id.action_send_false_alarm_sms) {
            Log.i(TAG, "action_send_false_alarm_sms");
            if (mConnection.mBound) {
                mConnection.mSdServer.sendFalseAlarmSMS();
            }
            return true;
        } else if (itemId == R.id.action_authenticate_api) {
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
        } else if (itemId == R.id.action_about_datasharing) {
            Log.i(TAG, "action_about_datasharing");
            showDataSharingDialog();
            return true;
        } else if (itemId == R.id.action_logmanager) {
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
        } else if (itemId == R.id.action_report_seizure) {
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
        } else if (itemId == R.id.action_settings) {
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
        } else if (itemId == R.id.action_instructions) {
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
        } else if (itemId == R.id.action_troubleshooting) {
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
        } else if (itemId == R.id.action_about) {
            Log.i(TAG, "action_about");
            showAbout();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    /**
     * Returns the ids (TAB_*) of the tabs to show, in screen order. A tab whose algorithm
     * is switched off in the "Seizure Detector Algorithms Selection" settings is left out
     * entirely. The System tab has no such setting and is always included, so the list is
     * never empty.
     */
    private List<Integer> getVisibleTabs(SharedPreferences prefs) {
        List<Integer> tabs = new ArrayList<>();
        if (PreferenceUtils.getBooleanFromXml(prefs, "OsdAlarmActive")) tabs.add(TAB_OSD);
        if (PreferenceUtils.getBooleanFromXml(prefs, "FlapAlarmActive")) tabs.add(TAB_FLAP);
        if (PreferenceUtils.getBooleanFromXml(prefs, "CnnAlarmActive")) tabs.add(TAB_ML);
        if (PreferenceUtils.getBooleanFromXml(prefs, "HRAlarmActive")) tabs.add(TAB_HR);
        if (PreferenceUtils.getBooleanFromXml(prefs, "FallActive")) tabs.add(TAB_FALL);
        tabs.add(TAB_SYSTEM);
        return tabs;
    }

    /** Label shown on the tab for a TAB_* id. */
    private String getTabTitle(int tabId) {
        switch (tabId) {
            case TAB_OSD:
                return "OSD";
            case TAB_FLAP:
                return "Flap";
            case TAB_ML:
                return "ML";
            case TAB_HR:
                return "Heart Rate";
            case TAB_FALL:
                return "Fall";
            case TAB_SYSTEM:
                return "System";
            default:
                return "Screen " + tabId;
        }
    }

    /**
     * A pager adapter that shows the tabs it is given (TAB_* ids, in screen order).
     * Items are identified by their TAB_* id rather than by position, so that fragments
     * restored by the framework are matched to the right tab when tabs are hidden.
     */
    private class ScreenSlideFragmentPagerAdapter extends FragmentStateAdapter {
        private String TAG = "ScreenSlideFragmentPagerAdapter";
        private final List<Integer> mTabIds;

        public ScreenSlideFragmentPagerAdapter(FragmentActivity fa, List<Integer> tabIds) {
            super(fa);
            mTabIds = new ArrayList<>(tabIds);
        }

        @Override
        public Fragment createFragment(int position) {
            switch (mTabIds.get(position)) {
                case TAB_OSD:
                    return new FragmentOsdAlg();
                case TAB_FLAP:
                    return new FragmentFlapAlg();
                case TAB_ML:
                    return new FragmentMlAlg();
                case TAB_HR:
                    return new FragmentHrAlg();
                case TAB_FALL:
                    // Fall detection debug tab - graphs window min/max acceleration history
                    return new FragmentFallAlg();
                case TAB_SYSTEM:
                    return new FragmentSystem();
                default:
                    Log.e(TAG, "createFragment() - invalid tab id " + mTabIds.get(position));
                    return null;
            }
        }

        @Override
        public int getItemCount() {
            return mTabIds.size();
        }

        @Override
        public long getItemId(int position) {
            return mTabIds.get(position);
        }

        @Override
        public boolean containsItem(long itemId) {
            return mTabIds.contains((int) itemId);
        }
    }

    private void startServer() {
        Log.i(TAG, "MainActivity.startServer()");
        Log.i(TAG, "startServer(): starting Server...");

        // Clear the user_stopped_service flag since we're starting the service
        SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("user_stopped_service", false).apply();

        mUtil.startServer();
    }

    private void stopServer() {
        Log.i(TAG, "MainActivity.stopServer()");
        Log.i(TAG, "stopServer(): stopping Server...");
        mUtil.stopServer();
    }


    private void showAbout() {
        Log.i(TAG, "MainActivity.showAbout()");
        View aboutView = getLayoutInflater().inflate(R.layout.about_layout, null, false);
        String versionName = mUtil.getAppVersionName();
        Log.i(TAG, "showAbout() - version name = " + versionName);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
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
        Log.i(TAG, "MainActivity.showDataSharingDialog()");
        View aboutView = getLayoutInflater().inflate(R.layout.data_sharing_dialog_layout, null, false);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
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

    /**
     * Launch the PineTime Firmware Updater app if installed, or show installation dialog
     */
    private void launchPineTimeUpdater() {
        Log.i(TAG, "launchPineTimeUpdater()");
        Log.i(TAG, "MainActivity2.launchPineTimeUpdater()");

        // Package name of the PineTime Updater app
        String pineTimePackageName = "uk.org.openseizuredetector.pinetime";

        try {
            // First check if the package is installed
            boolean isInstalled = false;
            try {
                getPackageManager().getPackageInfo(pineTimePackageName, 0);
                isInstalled = true;
                Log.i(TAG, "PineTime Updater package found: " + pineTimePackageName);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                Log.i(TAG, "PineTime Updater package not found: " + pineTimePackageName);
                isInstalled = false;
            }

            if (isInstalled) {
                // Show warning dialog before proceeding
                showPineTimeUpdaterWarningDialog(pineTimePackageName);
            } else {
                // App not installed - show dialog to install it
                Log.i(TAG, "PineTime Updater app not found - showing install dialog");
                showPineTimeUpdaterInstallDialog();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error launching PineTime Updater: " + ex.toString());
            mUtil.showToast("Error: " + ex.getMessage());
        }
    }

    /**
     * Show warning dialog before launching PineTime Updater explaining that the service will stop
     */
    private void showPineTimeUpdaterWarningDialog(final String pineTimePackageName) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Update PineTime Firmware");
        builder.setMessage("To update your PineTime watch firmware:\n\n" +
                "1. The OpenSeizureDetector monitoring service will be stopped\n" +
                "2. Your PineTime watch will disconnect from this app\n" +
                "3. The PineTime Firmware Updater will launch\n" +
                "4. After the firmware update completes, you must manually restart the OpenSeizureDetector app to resume monitoring\n\n" +
                "WARNING: Seizure detection will NOT be active during the firmware update.\n\n" +
                "Do you want to continue?");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("Continue", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Log.i(TAG, "User confirmed PineTime Updater launch - stopping service");

                // Stop the SdServer service
                if (mConnection.mBound) {
                    mUtil.unbindFromServer(getApplicationContext(), mConnection);
                }
                mUtil.stopServer();
                Log.i(TAG, "MainActivity2: Stopped SdServer for PineTime firmware update");

                // Wait a moment for service to stop, then launch the updater
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pineTimePackageName);
                            if (launchIntent != null) {
                                Log.i(TAG, "Launching PineTime Updater");
                                // Launch PineTime Updater in a new task
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(launchIntent);

                                // Move OSD app to background (keeps service stopped, allows easy return)
                                moveTaskToBack(true);
                                Log.i(TAG, "OSD moved to background - service remains stopped");
                            } else {
                                Log.e(TAG, "Failed to get launch intent for PineTime Updater");
                                mUtil.showToast("Error: Cannot launch PineTime Updater");
                                // Restart the service since we failed to launch
                                mUtil.startServer();
                            }
                        } catch (Exception ex) {
                            Log.e(TAG, "Error launching PineTime Updater: " + ex.toString());
                            mUtil.showToast("Error: " + ex.getMessage());
                            // Restart the service since we failed to launch
                            mUtil.startServer();
                        }
                    }
                }, 500); // 500ms delay to allow service to stop
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Log.i(TAG, "User cancelled PineTime Updater launch");
                dialog.dismiss();
            }
        });

        builder.setCancelable(true);
        builder.create();
        builder.show();
    }

    /**
     * Show dialog prompting user to install PineTime Updater app
     */
    private void showPineTimeUpdaterInstallDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("PineTime Firmware Updater");
        builder.setMessage("The PineTime Firmware Updater app is not installed.\n\n" +
                "This companion app is required to update the firmware on your PineTime watch.\n\n" +
                "Would you like to install it from the Google Play Store?");
        builder.setIcon(android.R.drawable.ic_dialog_info);

        builder.setPositiveButton("Install", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Open the PineTime Updater app in Google Play Store
                String pineTimePackageName = "uk.org.openseizuredetector.pinetime";
                try {
                    // Try to open the Play Store app directly
                    Intent playStoreIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + pineTimePackageName));
                    playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(playStoreIntent);
                } catch (android.content.ActivityNotFoundException e) {
                    // Play Store app not found, open in browser instead
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=" + pineTimePackageName));
                        startActivity(browserIntent);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error opening Play Store: " + ex.toString());
                        mUtil.showToast("Error opening Play Store");
                    }
                }
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        builder.create();
        builder.show();
    }

    private void recreateCommonFragment() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragment_common_container_view, FragmentCommon.class, null)
                .commitNowAllowingStateLoss();
    }

    /**
     * Determines which tab to show based on enabled algorithms
     * Priority order: ML, OSD, HR
     * Returns a TAB_* id, or DEFAULT_TAB if no algorithms are enabled. The caller must
     * check that the tab is actually visible (see getVisibleTabs()).
     */
    private int getDefaultTabFromAlgorithmSettings(SharedPreferences prefs) {
        // Check algorithms in priority order: ML, OSD, HR

        boolean mlEnabled = PreferenceUtils.getBooleanFromXml(prefs, "CnnAlarmActive");
        if (mlEnabled) {
            Log.d(TAG, "ML algorithm is enabled - selecting ML tab");
            return TAB_ML;
        }

        boolean osdEnabled = PreferenceUtils.getBooleanFromXml(prefs, "OsdAlarmActive");
        if (osdEnabled) {
            Log.d(TAG, "OSD algorithm is enabled - selecting OSD tab");
            return TAB_OSD;
        }

        boolean hrEnabled = PreferenceUtils.getBooleanFromXml(prefs, "HRAlarmActive");
        if (hrEnabled) {
            Log.d(TAG, "HR algorithm is enabled - selecting HR tab");
            return TAB_HR;
        }

        // No algorithms enabled - use default (OSD tab)
        Log.d(TAG, "No algorithms enabled - using default tab");
        return DEFAULT_TAB;
    }

}
