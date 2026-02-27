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

package uk.org.openseizuredetector.activity.settings;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.alg.MlModelManager;
import uk.org.openseizuredetector.utils.OsdUtil;
import uk.org.openseizuredetector.utils.SettingsUtil;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceScreen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;
import uk.org.openseizuredetector.activity.startup.StartupActivity;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class PrefActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {
    private static final String TAG = "PreferenceActivity";
    private OsdUtil mUtil;
    private boolean mPrefChanged = false;
    private boolean mUiRestartNeeded = false;
    private boolean mIsShuttingDown = false;  // Flag to prevent operations during shutdown
    private Context mContext;
    private Handler mHandler;

    private List<HeaderItem> mHeaders = new ArrayList<>();
    private List<HeaderItem> mAllHeaders = new ArrayList<>();
    private ArrayAdapter<String> mHeaderAdapter;

    private static class HeaderItem {
        String fragmentClass;
        String title;
        int summaryRes;
        String algorithmKey; // Key to check if this header should be visible
        HeaderItem(String fragmentClass, String title, int summaryRes, String algorithmKey) {
            this.fragmentClass = fragmentClass;
            this.title = title;
            this.summaryRes = summaryRes;
            this.algorithmKey = algorithmKey;
        }
    }

    /**
     * Centralised method to initialise all default preference values from XML files.
     * This should be called once during app startup (e.g., in StartupActivity).
     *
     * @param context The application context
     * @param readAgain Whether to force-read the defaults even if they have been read before.
     */
    public static void initialiseDefaultValues(Context context, boolean readAgain) {
        SettingsUtil.initialiseDefaultValues(context, readAgain);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler(Looper.getMainLooper());
        mContext = getApplicationContext();

        mUtil = new OsdUtil(mContext, mHandler);
        mUtil.writeToSysLogFile("PrefActivity.onCreate()", "LIFECYCLE");
        mUtil.writeMemoryLog("PrefActivity.onCreate");

        OsdUtil.applyTheme(this);

        setContentView(R.layout.activity_pref);

        // Configure system bar appearance to be edge-to-edge and handle insets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean isLightMode = isLightTheme();
                controller.setAppearanceLightStatusBars(isLightMode);
                controller.setAppearanceLightNavigationBars(isLightMode);
            }
        }


        loadHeadersFromXml();

        ListView lv = findViewById(R.id.pref_header_list);
        mHeaderAdapter = new ArrayAdapter<>(this, R.layout.pref_header_item, new ArrayList<>());
        lv.setAdapter(mHeaderAdapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                HeaderItem hi = mHeaders.get(position);
                showPreferenceFragment(hi.fragmentClass);
            }
        });

        updateHeaders();

        String fragmentToShow = getIntent().getStringExtra("fragment");
        if (fragmentToShow != null) {
            showPreferenceFragment(fragmentToShow);
        }

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                ListView headerList = findViewById(R.id.pref_header_list);
                FrameLayout detailContainer = findViewById(R.id.pref_fragment_container);
                if (headerList != null) {
                    headerList.setVisibility(View.VISIBLE);
                }
                if (detailContainer != null) {
                    detailContainer.setVisibility(View.GONE);
                }
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


    private void loadHeadersFromXml() {
        try {
            android.content.res.XmlResourceParser xrp = getResources().getXml(R.xml.preference_headers);
            int eventType = xrp.getEventType();
            final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
            while (eventType != android.content.res.XmlResourceParser.END_DOCUMENT) {
                if (eventType == android.content.res.XmlResourceParser.START_TAG) {
                    String name = xrp.getName();
                    if ("header".equals(name)) {
                        String fragment = xrp.getAttributeValue(ANDROID_NS, "fragment");
                        
                        int titleRes = xrp.getAttributeResourceValue(ANDROID_NS, "title", 0);
                        String title;
                        if (titleRes != 0) {
                            title = getString(titleRes);
                        } else {
                            title = xrp.getAttributeValue(ANDROID_NS, "title");
                        }
                        
                        int summaryRes = xrp.getAttributeResourceValue(ANDROID_NS, "summary", 0);
                        String algKey = xrp.getAttributeValue(null, "algorithmKey");
                        
                        mAllHeaders.add(new HeaderItem(fragment, title, summaryRes, algKey));
                    }
                }
                eventType = xrp.next();
            }
            xrp.close();
        } catch (Exception e) {
            Log.e(TAG, "loadHeadersFromXml() - error parsing headers: " + e.toString());
        }
    }

    private void showPreferenceFragment(String fragmentClassName) {
        try {
            Class<?> cls = Class.forName(fragmentClassName);
            androidx.fragment.app.Fragment frag = (androidx.fragment.app.Fragment) cls.getDeclaredConstructor().newInstance();

            ListView headerList = findViewById(R.id.pref_header_list);
            FrameLayout detailContainer = findViewById(R.id.pref_fragment_container);

            if (headerList != null) {
                headerList.setVisibility(View.GONE);
            }
            if (detailContainer != null) {
                detailContainer.setVisibility(View.VISIBLE);
            }

            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.pref_fragment_container, frag)
                    .addToBackStack(null)
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "showPreferenceFragment() - failed to instantiate fragment " + fragmentClassName + ": " + e.toString());
        }
    }

    private void updateHeaders() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        String currentDatasource = sp.getString("DataSource", "Phone");

        mHeaders.clear();

        for (HeaderItem h : mAllHeaders) {
            String fragmentName = h.fragmentClass;

            if (fragmentName.contains("PebbleDatasourcePrefsFragment") && !"Pebble".equals(currentDatasource)) {
                continue;
            }
            if (fragmentName.contains("NetworkDatasourcePrefsFragment") && !"Network".equals(currentDatasource)) {
                continue;
            }

            if (h.algorithmKey != null && !h.algorithmKey.isEmpty()) {
                continue;
            }

            mHeaders.add(h);
        }

        if (mHeaderAdapter != null) {
            ArrayList<String> titles = new ArrayList<>();
            for (HeaderItem h : mHeaders) {
                titles.add(h.title != null ? h.title : "");
            }
            mHeaderAdapter.clear();
            mHeaderAdapter.addAll(titles);
            mHeaderAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mUtil.writeToSysLogFile("PrefActvity.onStart()");
        Log.i(TAG, "onStart()");
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        Log.i(TAG, "SharedPreference " + s + " Changed.");

        if (s.equals("darkMode")) {
            OsdUtil.applyTheme(this);
        }

        if (s.equals("DataSource")) {
            updateHeaders();
        }

        // Preferences that require UI restart (going through StartupActivity)
        // These affect whether onboarding should be shown
        if (s.equals("first_run_complete")) {
            mUiRestartNeeded = true;
            return;
        }

        if (s.equals("SMSAlarm"))  {
            if (sharedPreferences.getBoolean("SMSAlarm", false) == true) {
                mIsShuttingDown = true;
                mUtil.showToast("Restarting OpenSeizureDetector");
                Intent i = new Intent(this, StartupActivity.class);
                startActivity(i);
                finish();
                return;
            } else {
                mPrefChanged = true;
            }
        } else if (s.equals("DataSource"))  {
            mIsShuttingDown = true;
            mUtil.showToast("Restarting OpenSeizureDetector");
            mUtil.stopServer();
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    Intent i = new Intent(getApplicationContext(), StartupActivity.class);
                    startActivity(i);
                    finish();
                }}, 1000);
            return;
        } else if (s.equals("advancedMode")) {
            startActivity(getIntent());
            finish();
        } else {
            mPrefChanged = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (mIsShuttingDown) return;
        mUtil.stopServer();
        mHandler.postDelayed(new Runnable() {
            public void run() {
                if (!mIsShuttingDown) {
                    mUtil.startServer();
                }
            }
        }, 500);
    }

    @Override
    public void onResume() {
        super.onResume();
        mUtil.writeToSysLogFile("PrefActvity.onResume()");
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(this);
        SP.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(this);
        SP.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPrefChanged || mUiRestartNeeded) {
            // If UI restart is needed (e.g., onboarding setting changed), restart the entire app
            // by going through StartupActivity to properly handle the onboarding flow
            if (mUiRestartNeeded) {
                mUtil.showToast("Settings changed - restarting app...");
                mUtil.stopServer();
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Intent i = new Intent(getApplicationContext(), StartupActivity.class);
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    }
                }, 1000);
            } else {
                // For other preference changes, just restart the server
                mUtil.showToast("Settings changed - server will restart...");
                mUtil.stopServer();

                // Use a handler to start the server after a short delay
                // to allow the old service instance to finish closing native resources
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Check if we're shutting down to avoid trying to start service from background
                        if (mIsShuttingDown) {
                            Log.i(TAG, "Shutdown in progress, skipping server restart.");
                            return;
                        }
                        Log.i(TAG, "Restarting server after preference change.");
                        try {
                            mUtil.startServer();
                        } catch (Exception e) {
                            Log.e(TAG, "Error restarting server after preference change: " + e.getMessage());
                            mUtil.writeToSysLogFile("Error restarting server after preference change: " + e.getMessage());
                            mUtil.showToast("Error restarting server. Please restart the app.");
                        }
                    }
                }, 1500); // 1.5 second delay is usually enough for GC/Cleanup
            }

            mPrefChanged = false;
            mUiRestartNeeded = false;
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.selectBLEDeviceButton) {
            final Intent intent = new Intent(this.mContext, BLEScanActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        }
    }

    /* Core Fragments */
    public static class GeneralPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.general_prefs, rootKey);
        }
    }

    public static class DataSourcePrefsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.data_source_prefs, rootKey);
            updateBleButtonVisibility();

            // Ensure the DataSource preference shows the current selection as its summary
            try {
                ListPreference dsPref = findPreference("DataSource");
                if (dsPref != null) {
                    dsPref.setSummaryProvider(preference -> {
                        CharSequence entry = dsPref.getEntry();
                        if (entry != null) return entry;
                        // fallback to stored value
                        return PreferenceManager.getDefaultSharedPreferences(getContext()).getString("DataSource", "Phone");
                    });
                }
            } catch (Exception ignored) {}

            // Add network datasource settings under a dedicated category within Data Source
            PreferenceCategory netCategory = new PreferenceCategory(getPreferenceManager().getContext());
            netCategory.setTitle(R.string.network_datasource_title);
            getPreferenceScreen().addPreference(netCategory);

            // Inflate network datasource preferences into a temporary PreferenceScreen
            PreferenceScreen temp = getPreferenceManager().createPreferenceScreen(getContext());
            getPreferenceManager().inflateFromResource(getContext(), R.xml.network_datasource_prefs, temp);

            // Move each preference from the temporary screen into the category, removing parent first
            while (temp.getPreferenceCount() > 0) {
                Preference p = temp.getPreference(0);
                temp.removePreference(p);
                netCategory.addPreference(p);
            }

            // Ensure EditTextPreference summaries show the current value immediately and update automatically
            try {
                EditTextPreference serverIpPref = findPreference("ServerIP");
                if (serverIpPref != null) {
                    serverIpPref.setSummaryProvider(preference -> {
                        String val = PreferenceManager.getDefaultSharedPreferences(getContext()).getString("ServerIP", "192.168.1.175");
                        return val;
                    });
                }

                EditTextPreference updatePeriodPref = findPreference("DataUpdatePeriod");
                if (updatePeriodPref != null) {
                    updatePeriodPref.setSummaryProvider(preference -> {
                        String val = PreferenceManager.getDefaultSharedPreferences(getContext()).getString("DataUpdatePeriod", "2000");
                        return val + " ms";
                    });
                }

                EditTextPreference connectTimeoutPref = findPreference("ConnectTimeoutPeriod");
                if (connectTimeoutPref != null) {
                    connectTimeoutPref.setSummaryProvider(preference -> {
                        String val = PreferenceManager.getDefaultSharedPreferences(getContext()).getString("ConnectTimeoutPeriod", "5000");
                        return val + " ms";
                    });
                }

                EditTextPreference readTimeoutPref = findPreference("ReadTimeoutPeriod");
                if (readTimeoutPref != null) {
                    readTimeoutPref.setSummaryProvider(preference -> {
                        String val = PreferenceManager.getDefaultSharedPreferences(getContext()).getString("ReadTimeoutPeriod", "5000");
                        return val + " ms";
                    });
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void onResume() {
            super.onResume();
            PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if ("DataSource".equals(key)) updateBleButtonVisibility();
        }

        private void updateBleButtonVisibility() {
            Preference bleButton = findPreference("SelectBLEDevice");
            if (bleButton != null) {
                String datasource = PreferenceManager.getDefaultSharedPreferences(getContext()).getString("DataSource", "Phone");
                bleButton.setVisible(datasource.equals("BLE") || datasource.equals("BLE2"));
            }
        }
    }

    public static class AlarmPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.alarm_prefs, rootKey);
        }
    }

    public static class LoggingPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.logging_prefs, rootKey);
        }
    }

    public static class AlgorithmSelectionPrefsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
        private PreferenceCategory mSettingsCategory;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_main, rootKey);
            
            mSettingsCategory = new PreferenceCategory(getPreferenceManager().getContext());
            mSettingsCategory.setTitle("Algorithm Settings");
            getPreferenceScreen().addPreference(mSettingsCategory);
            
            updateDynamicSettings();
        }

        @Override
        public void onResume() {
            super.onResume();
            PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key.equals("CnnAlarmActive") && sharedPreferences.getBoolean(key, false)) {
                // Compatibility check when enabling ML
                MlModelManager mm = new MlModelManager(getContext());
                JSONArray installed = mm.getInstalledModels();
                if (installed.length() > 0) {
                    boolean anyCompatible = false;
                    for (int i = 0; i < installed.length(); i++) {
                        if (mm.isDeviceCompatible(installed.optJSONObject(i))) {
                            anyCompatible = true;
                            break;
                        }
                    }
                    if (!anyCompatible) {
                        new AlertDialog.Builder(getContext())
                            .setTitle("CPU Incompatibility")
                            .setMessage("None of your installed ML models are compatible with this device's CPU. " +
                                       "You may need to download a 'generic' version of the model if available.")
                            .setPositiveButton("OK", null)
                            .show();
                    }
                }
            }
            if (key.endsWith("Active") || key.equals("FidgetDetectorEnabled")) {
                updateDynamicSettings();
            }
        }

        private void updateDynamicSettings() {
            if (mSettingsCategory == null) return;
            mSettingsCategory.removeAll();
            
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            
            addSettingLink("Algorithm Voting", "Configure how multiple algorithms work together", VotingPrefsFragment.class.getName());

            if (sp.getBoolean("OsdAlarmActive", true)) {
                addSettingLink("OSD Algorithm Settings", "Core vibration detection parameters", OsdAlgPrefsFragment.class.getName());
            }
            if (sp.getBoolean("FlapAlarmActive", true)) {
                addSettingLink("Flap Alarm Settings", "Experimental arm flapping detection", FlapAlgPrefsFragment.class.getName());
            }
            if (sp.getBoolean("CnnAlarmActive", false)) {
                addSettingLink("Machine Learning Settings", "Manage models and sensitivity", MlAlgPrefsFragment.class.getName());
            }
            if (sp.getBoolean("HRAlarmActive", false)) {
                addSettingLink("Heart Rate Settings", "Threshold and adaptive alarms", HrAlgPrefsFragment.class.getName());
            }
            if (sp.getBoolean("O2SatAlarmActive", false)) {
                addSettingLink("O2 Saturation Settings", "Oxygen saturation alarm levels", O2SatPrefsFragment.class.getName());
            }
            if (sp.getBoolean("FallActive", false)) {
                addSettingLink("Fall Detection Settings", "Fall detection sensitivity", FallAlgPrefsFragment.class.getName());
            }
            if (sp.getBoolean("FidgetDetectorEnabled", false)) {
                addSettingLink("Fidget Settings", "Configure fidget detector parameters", FidgetAlgPrefsFragment.class.getName());
            }
        }

        private void addSettingLink(String title, String summary, String fragmentClass) {
            Preference pref = new Preference(getPreferenceManager().getContext());
            pref.setTitle(title);
            pref.setSummary(summary);
            pref.setFragment(fragmentClass);
            mSettingsCategory.addPreference(pref);
        }
    }

    public static class VotingPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_voting, rootKey);
        }
    }

    public static class OsdAlgPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_osd, rootKey);
        }
    }

    public static class FlapAlgPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_flap, rootKey);
        }
    }

    public static class MlAlgPrefsFragment extends PreferenceFragmentCompat {
         private MlModelManager mMm;
         private OsdUtil mOsdUtil;
         private AlertDialog mProgressDialog;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_ml, rootKey);
            mMm = new MlModelManager(getContext());
            mOsdUtil = new OsdUtil(getContext(), new Handler(Looper.getMainLooper()));
            
            Preference addPref = findPreference("add_ml_model");
            if (addPref != null) {
                addPref.setOnPreferenceClickListener(pref -> {
                    showAvailableModelsDialog();
                    return true;
                });
            }
            
            refreshInstalledModelsList();
        }

        private void refreshInstalledModelsList() {
            PreferenceCategory category = findPreference("installed_models_category");
            if (category == null) return;
            
            category.removeAll();
            JSONArray installed = mMm.getInstalledModels();
            
            if (installed.length() == 0) {
                Preference empty = new Preference(getContext());
                empty.setTitle("No models installed");
                empty.setSummary("Click 'Add ML Model' below to download one.");
                empty.setSelectable(false);
                category.addPreference(empty);
            }

            for (int i = 0; i < installed.length(); i++) {
                try {
                    JSONObject m = installed.getJSONObject(i);
                    String label = "ML" + (i + 1);
                    category.addPreference(new MlModelPreference(getContext(), m, label, mMm, this::refreshInstalledModelsList));
                } catch (Exception ignored) {}
            }
        }

        private void showProgressDialog(String title, String message) {
            if (getContext() == null) return;
            View content = LayoutInflater.from(getContext()).inflate(R.layout.dialog_progress, null);
            TextView messageView = content.findViewById(R.id.progress_message);
            if (messageView != null) {
                messageView.setText(message);
            }
            mProgressDialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle(title)
                .setView(content)
                .setCancelable(false)
                .show();
        }

        private void dismissProgressDialog() {
            if (mProgressDialog != null) {
                mProgressDialog.dismiss();
                mProgressDialog = null;
            }
        }

        private void showAvailableModelsDialog() {
            if (!mOsdUtil.isNetworkConnected()) {
                new AlertDialog.Builder(getContext())
                    .setTitle("No Connection")
                    .setMessage("You must be connected to the internet to browse and download ML models.")
                    .setPositiveButton("OK", null)
                    .show();
                return;
            }

            showProgressDialog("Checking Server", "Fetching available models...");
            mMm.getMlModelIndex(arr -> {
                dismissProgressDialog();
                if (arr == null) {
                    new AlertDialog.Builder(getContext())
                        .setTitle("Connection Error")
                        .setMessage("Failed to fetch the list of available models from the server. Please check your internet connection.")
                        .setPositiveButton("OK", null)
                        .show();
                    return;
                }
                
                List<String> names = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    boolean isCompatible = mMm.isDeviceCompatible(obj);
                    String label = obj.optString("name", "Unknown");
                    if (obj.optBoolean("recommended", false)) label += " (Recommended)";
                    if (!isCompatible) label += " [INCOMPATIBLE]";
                    names.add(label);
                }

                new AlertDialog.Builder(getContext())
                    .setTitle("Select Model to Download")
                    .setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_list_item_2, android.R.id.text1, names) {
                        @Override
                        public View getView(int pos, View convert, ViewGroup parent) {
                            View v = super.getView(pos, convert, parent);
                            JSONObject obj = arr.optJSONObject(pos);
                            ((TextView)v.findViewById(android.R.id.text1)).setText(names.get(pos));
                            ((TextView)v.findViewById(android.R.id.text2)).setText(obj.optString("description", "No description available."));

                            boolean isCompatible = mMm.isDeviceCompatible(obj);
                            if (!isCompatible) {
                                ((TextView)v.findViewById(android.R.id.text1)).setTextColor(0xFFFF0000);
                            } else {
                                // Reset to default color if compatible
                                ((TextView)v.findViewById(android.R.id.text1)).setTextColor(
                                    MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface)
                                );
                            }
                            return v;
                        }
                    }, (dialog, which) -> {
                        JSONObject selected = arr.optJSONObject(which);
                        if (!mMm.isDeviceCompatible(selected)) {
                            new AlertDialog.Builder(getContext())
                                .setTitle("Compatibility Warning")
                                .setMessage("This model is marked as incompatible with your device's CPU and will likely crash if used. Download anyway?")
                                .setPositiveButton("Download", (d, w) -> downloadModel(selected))
                                .setNegativeButton("Cancel", null)
                                .show();
                        } else {
                            downloadModel(selected);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        private void downloadModel(JSONObject modelInfo) {
            String modelName = modelInfo.optString("name", "Model");
            showProgressDialog("Downloading", modelName);
            mMm.downloadAndInstallModel(modelInfo, (success, file) -> {
                dismissProgressDialog();
                getActivity().runOnUiThread(() -> {
                    if (success) {
                        refreshInstalledModelsList();
                        Toast.makeText(getContext(), "Model installed successfully", Toast.LENGTH_SHORT).show();
                    } else {
                        new AlertDialog.Builder(getContext())
                            .setTitle("Download Failed")
                            .setMessage("Failed to download " + modelName + ". Please check your internet connection and try again.")
                            .setPositiveButton("OK", null)
                            .show();
                    }
                });
            });
        }
    }

    private static class MlModelPreference extends Preference {
        private final JSONObject mModel;
        private final String mLabel;
        private final MlModelManager mMm;
        private final Runnable mOnDeleted;

        public MlModelPreference(Context context, JSONObject model, String label, MlModelManager mm, Runnable onDeleted) {
            super(context);
            mModel = model;
            mLabel = label;
            mMm = mm;
            mOnDeleted = onDeleted;
            setLayoutResource(R.layout.ml_model_list_item);
            setTitle(label + ": " + model.optString("name", "Unknown Model"));
            setSummary("v" + model.optString("version", "?") + " (" + model.optString("size", "?") + ")");
        }

        @Override
        public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            TextView title = (TextView) holder.findViewById(R.id.model_name);
            TextView details = (TextView) holder.findViewById(R.id.model_details);
            ImageButton delete = (ImageButton) holder.findViewById(R.id.delete_button);

            boolean isCompatible = mMm.isDeviceCompatible(mModel);
            String titleText = mLabel + ": " + mModel.optString("name", "Unknown");
            if (!isCompatible) {
                titleText += " [INCOMPATIBLE]";
                title.setTextColor(0xFFFF0000);
            }
            title.setText(titleText);

            details.setText("Version: " + mModel.optString("version", "?") + " | Size: " + mModel.optString("size", "?"));
            
            delete.setOnClickListener(v -> {
                new AlertDialog.Builder(getContext())
                    .setTitle("Delete Model " + mLabel + "?")
                    .setMessage("Are you sure you want to remove " + mModel.optString("name") + "?")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        mMm.deleteModel(mModel.optString("fname"));
                        mOnDeleted.run();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }
    }

    public static class HrAlgPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_hr, rootKey);
        }
    }

    public static class O2SatPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_o2, rootKey);
        }
    }

    public static class FallAlgPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_fall, rootKey);
        }
    }

    public static class FidgetAlgPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_fidget, rootKey);
        }
    }

    public static class PebbleDatasourcePrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pebble_datasource_prefs, rootKey);
        }
    }

    public static class NetworkDatasourcePrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.network_datasource_prefs, rootKey);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mUtil != null) {
            mUtil.writeToSysLogFile("PrefActivity.onDestroy()", "LIFECYCLE");
            mUtil.writeMemoryLog("PrefActivity.onDestroy");
        }
    }
}
