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
import android.app.AlertDialog;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;
import uk.org.openseizuredetector.activity.startup.StartupActivity;

public class PrefActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {
    private static final String TAG = "PreferenceActivity";
    private OsdUtil mUtil;
    private boolean mPrefChanged = false;
    private Context mContext;
    private Handler mHandler;
    private Button mSelectBLEButton;

    private List<HeaderItem> mHeaders = new ArrayList<>();
    private List<HeaderItem> mAllHeaders = new ArrayList<>();
    private ArrayAdapter<String> mHeaderAdapter;

    private static class HeaderItem {
        String fragmentClass;
        int titleRes;
        int summaryRes;
        String titleStr;
        HeaderItem(String fragmentClass, int titleRes, int summaryRes) {
            this.fragmentClass = fragmentClass;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.titleStr = titleRes != 0 ? null : null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set our custom uncaught exception handler to report issues.
        Thread.setDefaultUncaughtExceptionHandler(new OsdUncaughtExceptionHandler(PrefActivity.this));

        mHandler = new Handler(Looper.getMainLooper());
        mContext = getApplicationContext();

        mUtil = new OsdUtil(mContext, mHandler);
        mUtil.writeToSysLogFile("PrefActivity.onCreate()", "LIFECYCLE");
        mUtil.writeMemoryLog("PrefActivity.onCreate");

        setContentView(R.layout.activity_pref);

        // Calculate ActionBar height to add to top padding
        final int actionBarHeight;
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            actionBarHeight = android.util.TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        } else {
            actionBarHeight = 0;
        }

        // Ensure content isn't hidden behind system bars (navigation bar) or ActionBar
        View rootView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top + actionBarHeight;
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

            View fragmentContainer = findViewById(R.id.pref_fragment_container);
            if (fragmentContainer != null) {
                // Add top padding for ActionBar
                fragmentContainer.setPadding(fragmentContainer.getPaddingLeft(),
                        topInset,
                        fragmentContainer.getPaddingRight(),
                        bottomInset);
            }

            // Add top padding to header list so it's not overlapped by ActionBar
            ListView headerList = findViewById(R.id.pref_header_list);
            if (headerList != null) {
                headerList.setPadding(headerList.getPaddingLeft(),
                        topInset,
                        headerList.getPaddingRight(),
                        0);
            }
            return insets;
        });

        // Load headers from XML and populate list
        loadHeadersFromXml();

        ListView lv = findViewById(R.id.pref_header_list);
        ArrayList<String> titles = new ArrayList<>();
        for (HeaderItem h : mHeaders) {
            String t = (h.titleRes != 0) ? getString(h.titleRes) : "";
            titles.add(t);
        }
        mHeaderAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles);
        lv.setAdapter(mHeaderAdapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                HeaderItem hi = mHeaders.get(position);
                showPreferenceFragment(hi.fragmentClass);
            }
        });

        // Filter headers based on current datasource
        updateHeadersForDatasource();

        // If the activity was started with an intent specifying a fragment, show it directly
        String fragmentToShow = getIntent().getStringExtra("fragment");
        if (fragmentToShow != null) {
            showPreferenceFragment(fragmentToShow);
        }

        // Listen for fragment back stack changes to show/hide master list
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                // No fragments on back stack, show master list
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

    private void loadHeadersFromXml() {
        try {
            android.content.res.XmlResourceParser xrp = getResources().getXml(R.xml.preference_headers);
            int eventType = xrp.getEventType();
            final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
            while (eventType != android.content.res.XmlResourceParser.END_DOCUMENT) {
                if (eventType == android.content.res.XmlResourceParser.START_TAG) {
                    String name = xrp.getName();
                    if ("header".equals(name)) {
                        // attributes are in the android namespace (android:fragment, android:title, android:summary)
                        String fragment = xrp.getAttributeValue(ANDROID_NS, "fragment");
                        int titleRes = xrp.getAttributeResourceValue(ANDROID_NS, "title", 0);
                        int summaryRes = xrp.getAttributeResourceValue(ANDROID_NS, "summary", 0);
                        mAllHeaders.add(new HeaderItem(fragment, titleRes, summaryRes));
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

            // Hide the master list and show the detail container
            ListView headerList = findViewById(R.id.pref_header_list);
            FrameLayout detailContainer = findViewById(R.id.pref_fragment_container);

            if (headerList != null) {
                headerList.setVisibility(View.GONE);
            }
            if (detailContainer != null) {
                detailContainer.setVisibility(View.VISIBLE);
            }

            // Show the fragment with back stack so back button returns to list
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.pref_fragment_container, frag)
                    .addToBackStack(null)
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "showPreferenceFragment() - failed to instantiate fragment " + fragmentClassName + ": " + e.toString());
        }
    }

    /**
     * Updates the list of visible headers based on the current datasource setting.
     * Only shows datasource-specific settings (Pebble, Network) when relevant.
     */
    private void updateHeadersForDatasource() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        String currentDatasource = sp.getString("DataSource", "Phone");

        // Clear the filtered list
        mHeaders.clear();

        // Add headers that are always visible
        for (HeaderItem h : mAllHeaders) {
            String fragmentName = h.fragmentClass;

            // Filter out datasource-specific headers if not relevant
            if (fragmentName.contains("PebbleDatasourcePrefsFragment")) {
                if ("Pebble".equals(currentDatasource)) {
                    mHeaders.add(h);
                }
            } else if (fragmentName.contains("NetworkDatasourcePrefsFragment")) {
                if ("Network".equals(currentDatasource)) {
                    mHeaders.add(h);
                }
            } else {
                // Always show non-datasource-specific headers
                mHeaders.add(h);
            }
        }

        // Update the adapter
        if (mHeaderAdapter != null) {
            ArrayList<String> titles = new ArrayList<>();
            for (HeaderItem h : mHeaders) {
                String t = (h.titleRes != 0) ? getString(h.titleRes) : "";
                titles.add(t);
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

        if (s.equals("SMSAlarm"))  {
            if (sharedPreferences.getBoolean("SMSAlarm", false) == true) {
                mUtil.showToast("Restarting OpenSeizureDetector");
                Log.i(TAG, "onSharedPreferenceChanged(): SMS Alarm Enabled - Restarting start-up activity to check permissions");
                Intent i;
                i = new Intent(this, StartupActivity.class);
                startActivity(i);
                Log.i(TAG, "onSharedPreferenceChanged() - finishing PrefActivity");
                finish();
                return;
            } else {
                Log.i(TAG, "OnSharedPreferenceChanged(): SMS Alarm disabled so do not need permissions");
                mPrefChanged = true;
            }
        } else if (s.equals("DataSource"))  {
            Log.i(TAG, "onSharedPreferenceChanged(): Data Source Changed");
            // Update the headers list to show/hide datasource-specific settings
            updateHeadersForDatasource();
            Log.i(TAG, "onSharedPreferenceChanged(): Data Source Changed - Restarting start-up activity to check permissions");
            mUtil.showToast("Restarting OpenSeizureDetector");
            mUtil.stopServer();
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    Intent i;
                    Log.i(TAG, "onSharedPreferenceChanged(): Data Source Changed - Restarting start-up activity to check permissions");
                    i = new Intent(getApplicationContext(), StartupActivity.class);
                    startActivity(i);
                    Log.i(TAG, "onSharedPreferenceChanged() - finishing PrefActivity");
                    finish();
                    return;
                }
            }, 1000);
            return;
        } else if (s.equals("advancedMode")) {
            Log.i(TAG, "Re-starting PrefActivity to refresh list");
            startActivity(getIntent());
            finish();
        } else {
            Log.i(TAG, "Setting changed - will restart server when settings screen is closed");
            mPrefChanged = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.i(TAG, "onRequestPermissionsResult - Permission" + permissions + " = " + grantResults);
        mUtil.stopServer();
        // Increased delay from 100ms to 500ms to ensure server fully stops before restarting
        // CRITICAL: 100ms was too short and could cause duplicate SdDataSource instances
        mHandler.postDelayed(new Runnable() {
            public void run() {
                mUtil.startServer();
            }
        }, 500);

    }

    @Override
    public void onResume() {
        super.onResume();
        mUtil.writeToSysLogFile("PrefActvity.onResume()");
        Log.i(TAG, "onResume()");
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(this);
        SP.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
        mUtil.writeToSysLogFile("PrefActvity.onPause()");
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(this);
        SP.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mUtil.writeToSysLogFile("PrefActvity.onStop()");
        Log.i(TAG, "onStop()");

        if (mPrefChanged) {
            Log.i(TAG, "onStop() - Preferences changed, stopping server");
            Log.i(TAG, "onStop() - Server will be restarted when returning to main activity");
            mUtil.showToast("Settings changed - server will restart...");
            mUtil.stopServer();
            mPrefChanged = false;
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.selectBLEDeviceButton:
                Log.v(TAG, "onClick - SelectBLEDeviceButton");
                final Intent intent = new Intent(this.mContext, BLEScanActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                break;
            default:
                Log.e(TAG, "onClick - unrecognised button");
        }
    }

    /* Preference fragments converted to androidx.preference.PreferenceFragmentCompat */
    public static class GeneralPrefsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.general_prefs, rootKey);
            updateBleButtonVisibility();
        }

        @Override
        public void onResume() {
            super.onResume();
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            sp.registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            sp.unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if ("DataSource".equals(key)) {
                updateBleButtonVisibility();
            }
        }

        /**
         * Show/hide the BLE device selection button based on datasource selection
         */
        private void updateBleButtonVisibility() {
            Preference bleButton = findPreference("SelectBLEDevice");
            if (bleButton != null) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
                String datasource = sp.getString("DataSource", "Phone");

                // Only show BLE button if BLE or BLE2 datasource is selected
                boolean isBleDataSource = datasource.equals("BLE") || datasource.equals("BLE2");
                bleButton.setVisible(isBleDataSource);
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

    public static class SeizureDetectorPrefsFragment extends PreferenceFragmentCompat {
        private ProgressDialog mProgressDialog;
        private AtomicBoolean mDownloadCancelFlag;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.seizure_detector_prefs, rootKey);

            // Setup ML Model preference
            Preference modelPref = findPreference("MlModelSelector");
            if (modelPref != null) {
                SharedPreferences spInit = PreferenceManager.getDefaultSharedPreferences(getContext());
                String currentModelName = spInit.getString("CnnModelName", null);
                if (currentModelName != null) {
                    modelPref.setSummary(getString(R.string.ml_model_select_summary) + "\n" + getString(R.string.selected_model_label, currentModelName));
                } else {
                    modelPref.setSummary(getString(R.string.ml_model_select_summary) + "\n" + getString(R.string.no_model_selected));
                }
                modelPref.setOnPreferenceClickListener(pref -> {
                    showMlModelSelector();
                    return true;
                });
            }

            // Note: Dynamic visibility toggling is no longer needed since we're using
            // full-screen detail views for each algorithm section
        }

        private void showMlModelSelector() {
            Context ctx = getActivity();
            if (ctx == null) return;

            MlModelManager mm = new MlModelManager(ctx);

            // Show progress dialog while downloading
            mProgressDialog = new ProgressDialog(ctx);
            mProgressDialog.setTitle("Downloading ML Models");
            mProgressDialog.setMessage("Fetching available models...");
            mProgressDialog.setCancelable(true);
            mProgressDialog.show();

            mDownloadCancelFlag = new AtomicBoolean(false);
            mProgressDialog.setOnCancelListener(dialog -> mDownloadCancelFlag.set(true));

            // Download model index
            mm.getMlModelIndex(arr -> {
                if (mProgressDialog != null && mProgressDialog.isShowing()) {
                    mProgressDialog.dismiss();
                }

                if (arr == null || mDownloadCancelFlag.get()) {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
                    String existingPath = sp.getString("CnnModelFile", null);
                    if (existingPath != null && new File(existingPath).exists()) {
                        Toast.makeText(ctx, "Download failed - using existing model", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(ctx, "Failed to download model list", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        showModelSelectionDialog(ctx, arr);
                    });
                }
            });
        }

        private void showModelSelectionDialog(Context ctx, JSONArray modelArray) {
            try {
                List<String> modelNames = new ArrayList<>();
                List<String> modelDetails = new ArrayList<>();

                for (int i = 0; i < modelArray.length(); i++) {
                    try {
                        JSONObject model = modelArray.getJSONObject(i);
                        String name = model.optString("name", "Unknown");
                        String size = model.optString("size", "");
                        String version = model.optString("version", "");

                        modelNames.add(name);
                        modelDetails.add("v" + version + " (" + size + ")");
                    } catch (org.json.JSONException e) {
                        Log.w(TAG, "Error parsing model " + i + ": " + e.getMessage());
                    }
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx,
                        android.R.layout.simple_list_item_2,
                        android.R.id.text1,
                        modelNames) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        android.widget.TextView text1 = view.findViewById(android.R.id.text1);
                        android.widget.TextView text2 = view.findViewById(android.R.id.text2);
                        text1.setText(modelNames.get(position));
                        text2.setText(modelDetails.get(position));
                        // Ensure text is readable
                        text1.setTextColor(android.graphics.Color.BLACK);
                        text2.setTextColor(android.graphics.Color.GRAY);
                        return view;
                    }
                };

                AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
                builder.setTitle("Select ML Model")
                        .setAdapter(adapter, (dialog, which) -> {
                            try {
                                downloadAndSelectModel(ctx, modelArray.getJSONObject(which));
                            } catch (org.json.JSONException e) {
                                Log.e(TAG, "Error getting selected model: " + e.getMessage());
                                Toast.makeText(ctx, "Error selecting model", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Exception e) {
                Log.e(TAG, "Error showing model selection dialog: " + e.getMessage(), e);
                Toast.makeText(ctx, "Error loading models list", Toast.LENGTH_SHORT).show();
            }
        }

        private void downloadAndSelectModel(Context ctx, JSONObject selectedModel) {
            try {
                String modelName = selectedModel.optString("name", "Unknown");
                String fileName = selectedModel.optString("file", "");

                if (fileName.isEmpty()) {
                    Toast.makeText(ctx, "Invalid model data", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Show download progress
                mProgressDialog = new ProgressDialog(ctx);
                mProgressDialog.setTitle("Downloading Model");
                mProgressDialog.setMessage(modelName);
                mProgressDialog.setCancelable(true);
                mProgressDialog.show();

                mDownloadCancelFlag = new AtomicBoolean(false);
                mProgressDialog.setOnCancelListener(dialog -> mDownloadCancelFlag.set(true));

                MlModelManager mm = new MlModelManager(ctx);
                mm.downloadModel(fileName, mDownloadCancelFlag, (success, file) -> {
                    if (mProgressDialog != null && mProgressDialog.isShowing()) {
                        mProgressDialog.dismiss();
                    }

                    if (success && !mDownloadCancelFlag.get()) {
                        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
                        sp.edit()
                                .putString("CnnModelName", modelName)
                                .putString("CnnModelFile", file.getAbsolutePath())
                                .apply();

                        Preference modelPref = findPreference("MlModelSelector");
                        if (modelPref != null) {
                            modelPref.setSummary(getString(R.string.ml_model_select_summary) + "\n" +
                                    getString(R.string.selected_model_label, modelName));
                        }
                        Toast.makeText(ctx, "Model selected: " + modelName, Toast.LENGTH_SHORT).show();
                    } else if (!mDownloadCancelFlag.get()) {
                        Toast.makeText(ctx, "Failed to download model", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error downloading model: " + e.getMessage(), e);
                Toast.makeText(ctx, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            // Apply light theme to preference screen for readability
            if (getView() != null) {
                getView().setBackgroundColor(android.graphics.Color.parseColor("#3F51B5"));
            }
        }
    }

    public static class PebbleDatasourcePrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pebble_datasource_prefs, rootKey);
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

    public static class NetworkDatasourcePrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.network_datasource_prefs, rootKey);
        }
    }


}
