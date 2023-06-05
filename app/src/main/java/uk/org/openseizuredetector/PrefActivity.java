/*
  Pebble_sd - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015.

  This file is part of pebble_sd.

  Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.
  
  Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.
  
  You should have received a copy of the GNU General Public License
  along with pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/

package uk.org.openseizuredetector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceActivity;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.util.List;
import java.util.Objects;

public class PrefActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {
    private String TAG = "PreferenceActivity";
    private OsdUtil mUtil;
    private boolean mPrefChanged = false;
    private Context mContext;
    private Handler mHandler;
    private Button mSelectBLEButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set our custom uncaught exception handler to report issues.
        Thread.setDefaultUncaughtExceptionHandler(new OsdUncaughtExceptionHandler(PrefActivity.this));
        //int i = 5/0;  // Force exception to test handler.

        mHandler = new Handler();
        mContext = getApplicationContext();

        mUtil = new OsdUtil(mContext, mHandler);
        mUtil.writeToSysLogFile("PrefActvity.onCreate()");
    }

    /**
     * Populate the activity with the top-level headers.
     */
    @Override
    public void onBuildHeaders(List<Header> target) {
        String titleStr;
        loadHeadersFromResource(R.xml.preference_headers, target);
        Log.v(TAG, "onBuildHeaders - target.size=" + target.size());
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(this.getApplicationContext());
        String dataSourceStr = SP.getString("DataSource", "Pebble");
        Log.i(TAG, "onBuildHeaders DataSource = " + dataSourceStr);
        //Boolean advancedMode = SP.getBoolean("advancedMode", false);
        Boolean advancedMode = true;
        Log.i(TAG, "onBuildHeaders advancedMode = " + advancedMode);

        if (advancedMode) {
            for (int i = 0; i < target.size(); i++) {
                Header h = target.get(i);
                if (h.title != null) {
                    titleStr = h.title.toString();
                } else {
                    titleStr = getResources().getString(h.titleRes);
                }
                Log.v(TAG, "found - " + titleStr);
                if (titleStr.equals(getString(R.string.seizure_detector_settings_title))) {
                    Log.v(TAG, "found Seizure Detector Header");
                    if (dataSourceStr.equals("Network")) {
                        Log.v(TAG, "Removing seizure detector settings header");
                        target.remove(i);
                        i = i - 1;
                    }
                }
                if (titleStr.equals(getString(R.string.network_datasource_title))) {
                    Log.v(TAG, "found Network Datasource Header");
                    if (!dataSourceStr.equals("Network")) {
                        Log.v(TAG, "Removing network settings header");
                        target.remove(i);
                        i = i - 1;
                    }
                }
                if (titleStr.equals(getString(R.string.pebble_datasource_title))) {
                    Log.v(TAG, "found Pebble Datasource Header");
                    if (!dataSourceStr.equals("Pebble")) {
                        Log.v(TAG, "Removing Pebble settings header");
                        target.remove(i);
                        i = i - 1;
                    }
                }
            }
        } else {
            for (int i = 0; i < target.size(); i++) {
                Header h = target.get(i);
                if (h.title != null) {
                    titleStr = h.title.toString();
                } else {
                    titleStr = getResources().getString(h.titleRes);
                }
                Log.v(TAG, "i=" + i + ": found - " + titleStr + " looking for " + getString(R.string.basic_settings_title));
                if (!titleStr.equals(getString(R.string.basic_settings_title))) {
                    Log.v(TAG, "an Advanced Mode Header, so removing it....");
                    target.remove(i);
                    i = i - 1;
                }

            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mUtil.writeToSysLogFile("PrefActvity.onStart()");
        //invalidateHeaders();
        Log.i(TAG, "onStart()");
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        Log.i(TAG, "SharedPreference " + s + " Changed.");

        // if we have enabled the SMS alarm, we may need extra permissions approving.  This is handled in
        // StartUpActivity, so we exit this activity and start start-up activity.
        if (s.equals("SMSAlarm")) {
            if (sharedPreferences.getBoolean("SMSAlarm", false) == true) {
                Log.i(TAG, "onSharedPreferenceChanged(): SMS Alarm Enabled - Restarting start-up activity to check permissions");
                Intent i;
                i = new Intent(this, StartupActivity.class);
                startActivity(i);
                Log.i(TAG,"onSharedPreferenceChanged() - finishing PrefActivity");
                finish();
                return;
            } else {
                Log.i(TAG, "OnSharedPreferenceChanged(): SMS Alarm disabled so do not need permissions");
            }
        }
        // For all other preference changes we just restart SdServer so it is not as alarming for the user!
        //mUtil.showToast("Setting " + s + " Changed - restarting server");
        mPrefChanged = true;
        mUtil.stopServer();
        // Wait 0.1 second to give the server chance to shutdown, then re-start it
        mHandler.postDelayed(new Runnable() {
            public void run() {
                mUtil.startServer();
            }
        }, 100);

        if (s.equals("DataSource") || s.equals("advancedMode")) {
            Log.i(TAG, "Re-starting PrefActivity to refresh list");
            finish();
            startActivity(getIntent());
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.i(TAG, "onRequestPermissionsResult - Permission" + permissions + " = " + grantResults);
        //mUtil.showToast("Permissions Changed - restarting server");
        mUtil.stopServer();
        // Wait 0.1 second to give the server chance to shutdown, then re-start it
        mHandler.postDelayed(new Runnable() {
            public void run() {
                mUtil.startServer();
            }
        }, 100);

    }

    @Override
    public void onResume() {
        super.onResume();
        mUtil.writeToSysLogFile("PrefActvity.onResume()");
        Log.i(TAG, "onResume()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        SP.registerOnSharedPreferenceChangeListener(this);
        invalidateHeaders();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
        mUtil.writeToSysLogFile("PrefActvity.onPause()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        SP.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mUtil.writeToSysLogFile("PrefActvity.onStop()");
        Log.i(TAG,"onStop()");
    }

    /**
     * FIXME - this just returns true so it is the same as for older versions of Android.
     * We should really check that the fragmentName is one of the fragments defined below.
     *
     * @param fragmentName
     * @return
     */
    @Override
    protected boolean isValidFragment(String fragmentName) {
        return true;
    }

    @Override
    public void onClick(View view) {
        if(Objects.equals(R.id.selectBLEDeviceButton,view.getId())) {
            Log.v(TAG, "onClick - SelectBLEDeviceButton");
            final Intent intent = new Intent(this.mContext, BLEScanActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);

        }
        else {
                Log.e(TAG, "onClick - unrecognised button");
        }
    }

    /**
     * This fragment shows the preferences for the first header.
     */
    public static class BasicPrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.basic_prefs);
        }
    }

    public static class GeneralPrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.general_prefs);
        }
    }

    /**
     * This fragment contains a second-level set of preference that you
     * can get to by tapping an item in the first preferences fragment.
     */
    public static class AlarmPrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.alarm_prefs);
        }
    }

    public static class LoggingPrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.logging_prefs);
        }
    }


    public static class SeizureDetectorPrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.seizure_detector_prefs);
        }
    }

    public static class PebbleDatasourcePrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.pebble_datasource_prefs);
        }
    }

    public static class NetworkDatasourcePrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.network_datasource_prefs);
        }
    }


}
