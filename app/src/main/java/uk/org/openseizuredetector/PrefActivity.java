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
import android.widget.Toast;

import java.util.List;

public class PrefActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private String TAG = "PreferenceActivity";
    private OsdUtil mUtil;
    private boolean mPrefChanged = false;
    private Context mContext;
    private Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set our custom uncaught exception handler to report issues.
        Thread.setDefaultUncaughtExceptionHandler(new OsdUncaughtExceptionHandler(PrefActivity.this));
        //int i = 5/0;  // Force exception to test handler.

        mHandler = new Handler();
        mContext = getApplicationContext();

        mUtil = new OsdUtil(mContext,mHandler);
        mUtil.writeToSysLogFile("PrefActvity.onCreate()");
    }

    /**
     * Populate the activity with the top-level headers.
     */
    @Override
    public void onBuildHeaders(List<Header> target) {
        loadHeadersFromResource(R.xml.preference_headers, target);

        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(this.getApplicationContext());
        String dataSourceStr = SP.getString("DataSource", "Pebble");
        Log.v(TAG, "onBuildHeaders DataSource = " + dataSourceStr);

        Boolean cameraEnabled = SP.getBoolean("UseIpCamera", false);
        Log.v(TAG, "onBuildHeaders cameraEnabled = " + cameraEnabled);

        for (int i = 0; i < target.size(); i++) {
            Header h = target.get(i);
            Log.v(TAG,"found - "+h.title.toString());
            if (h.title.toString().equals("Pebble Datasource")) {
                Log.v(TAG, "found Pebble Datasource Header");
                if (!dataSourceStr.equals("Pebble")) {
                    Log.v(TAG, "Removing pebble settings header");
                    target.remove(i);
                    i = i-1;
                }
            }
            if (h.title.toString().equals("Network Datasource")) {
                Log.v(TAG, "found Network Datasource Header");
                if (!dataSourceStr.equals("Network")) {
                    Log.v(TAG, "Removing network settings header");
                    target.remove(i);
                    i = i -1;
                }
            }
            if (h.title.toString().equals("Camera Settings")) {
                Log.v(TAG, "found Camera Settings Header");
                if (!cameraEnabled) {
                    Log.v(TAG, "Removing camera settings header");
                    target.remove(i);
                    i = i-1;
                }
            }
        }

    }

    @Override
    public void onStart() {
        super.onStart();
        mUtil.writeToSysLogFile("PrefActvity.onStart()");
        invalidateHeaders();
        Log.v(TAG, "onStart()");
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        Log.v(TAG, "SharedPreference " + s + " Changed.");
        //mUtil.showToast("Shared Preference " + s + " Changed.");
        mPrefChanged = true;
    }


    @Override
    public void onResume() {
        super.onResume();
        mUtil.writeToSysLogFile("PrefActvity.onResume()");
        Log.v(TAG, "onResume()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        SP.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mUtil.writeToSysLogFile("PrefActvity.onPause()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        SP.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mUtil.writeToSysLogFile("PrefActvity.onDestroy()");
        Log.v(TAG, "onDestroy.  mPrefChanged=" + mPrefChanged);
        if (mPrefChanged) {
            mUtil.writeToSysLogFile("PrefActvity.onDestroy() - settings changed - re-starting....");
            mUtil.showToast("Settings Changed - re-starting OpenSeizureDetector....");
            Intent intent = new Intent(getApplicationContext(), StartupActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }
    }

    /**
     * This fragment shows the preferences for the first header.
     */
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

    public static class CameraPrefsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.camera_prefs);
        }
    }

}
