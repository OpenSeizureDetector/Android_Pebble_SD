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
import android.widget.TextView;
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
     *                  Set to false for normal startup, true if defaults have changed.
     */
    public static void initialiseDefaultValues(Context context, boolean readAgain) {
        Log.i(TAG, "initialiseDefaultValues(force=" + readAgain + ")");
        
        // List of all preference XML resource IDs
        int[] prefResources = {
                R.xml.general_prefs,
                R.xml.alarm_prefs,
                R.xml.logging_prefs,
                R.xml.sd_prefs_main,
                R.xml.sd_prefs_voting,
                R.xml.sd_prefs_osd,
                R.xml.sd_prefs_flap,
                R.xml.sd_prefs_ml,
                R.xml.sd_prefs_hr,
                R.xml.sd_prefs_o2,
                R.xml.sd_prefs_fall,
                R.xml.sd_prefs_fidget,
                R.xml.pebble_datasource_prefs,
                R.xml.network_datasource_prefs,
                R.xml.network_passive_datasource_prefs
        };

        for (int resId : prefResources) {
            PreferenceManager.setDefaultValues(context, resId, readAgain);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Note: UncaughtExceptionHandler is now installed at the app level in OsdApplication.

        mHandler = new Handler(Looper.getMainLooper());
        mContext = getApplicationContext();

        mUtil = new OsdUtil(mContext, mHandler);
        mUtil.writeToSysLogFile("PrefActivity.onCreate()", "LIFECYCLE");
        mUtil.writeMemoryLog("PrefActivity.onCreate");

        setContentView(R.layout.activity_pref);

        final int actionBarHeight;
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            actionBarHeight = android.util.TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        } else {
            actionBarHeight = 0;
        }

        View rootView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top + actionBarHeight;
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

            View fragmentContainer = findViewById(R.id.pref_fragment_container);
            if (fragmentContainer != null) {
                fragmentContainer.setPadding(fragmentContainer.getPaddingLeft(),
                        topInset,
                        fragmentContainer.getPaddingRight(),
                        bottomInset);
            }

            ListView headerList = findViewById(R.id.pref_header_list);
            if (headerList != null) {
                headerList.setPadding(headerList.getPaddingLeft(),
                        topInset,
                        headerList.getPaddingRight(),
                        0);
            }
            return insets;
        });

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
                        
                        // Handle both resource references and literal strings for the title
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
                if (!sp.getBoolean(h.algorithmKey, false)) {
                    continue;
                }
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

        if (s.endsWith("Active") || s.equals("DataSource")) {
            updateHeaders();
        }

        if (s.equals("SMSAlarm"))  {
            if (sharedPreferences.getBoolean("SMSAlarm", false) == true) {
                mUtil.showToast("Restarting OpenSeizureDetector");
                Intent i = new Intent(this, StartupActivity.class);
                startActivity(i);
                finish();
                return;
            } else {
                mPrefChanged = true;
            }
        } else if (s.equals("DataSource"))  {
            mUtil.showToast("Restarting OpenSeizureDetector");
            mUtil.stopServer();
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    Intent i = new Intent(getApplicationContext(), StartupActivity.class);
                    startActivity(i);
                    finish();
                }
            }, 1000);
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
        mUtil.stopServer();
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
        if (mPrefChanged) {
            mUtil.showToast("Settings changed - server will restart...");
            mUtil.stopServer();
            mPrefChanged = false;
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
    public static class GeneralPrefsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.general_prefs, rootKey);
            updateBleButtonVisibility();
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

    /* Algorithm Fragments */
    public static class AlgorithmSelectionPrefsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_main, rootKey);
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
        private ProgressDialog mProgressDialog;
        private AtomicBoolean mDownloadCancelFlag;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.sd_prefs_ml, rootKey);
            Preference modelPref = findPreference("MlModelSelector");
            if (modelPref != null) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
                String modelName = sp.getString("CnnModelName", null);
                modelPref.setSummary(getString(R.string.ml_model_select_summary) + "\n" + (modelName != null ? getString(R.string.selected_model_label, modelName) : getString(R.string.no_model_selected)));
                modelPref.setOnPreferenceClickListener(pref -> { showMlModelSelector(); return true; });
            }
        }

        private void showMlModelSelector() {
            Context ctx = getActivity();
            if (ctx == null) return;
            MlModelManager mm = new MlModelManager(ctx);
            mProgressDialog = new ProgressDialog(ctx);
            mProgressDialog.setTitle("Downloading ML Models");
            mProgressDialog.setMessage("Fetching available models...");
            mProgressDialog.setCancelable(true);
            mProgressDialog.show();
            mDownloadCancelFlag = new AtomicBoolean(false);
            mProgressDialog.setOnCancelListener(dialog -> mDownloadCancelFlag.set(true));
            mm.getMlModelIndex(arr -> {
                if (mProgressDialog != null && mProgressDialog.isShowing()) mProgressDialog.dismiss();
                if (arr == null || mDownloadCancelFlag.get()) return;
                if (getActivity() != null) getActivity().runOnUiThread(() -> showModelSelectionDialog(ctx, arr));
            });
        }

        private void showModelSelectionDialog(Context ctx, JSONArray modelArray) {
            List<String> modelNames = new ArrayList<>();
            List<String> modelDetails = new ArrayList<>();
            for (int i = 0; i < modelArray.length(); i++) {
                JSONObject model = modelArray.optJSONObject(i);
                if (model != null) {
                    modelNames.add(model.optString("name", "Unknown"));
                    modelDetails.add("v" + model.optString("version", "") + " (" + model.optString("size", "") + ")");
                }
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_2, android.R.id.text1, modelNames) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View view = super.getView(position, convertView, parent);
                    ((TextView)view.findViewById(android.R.id.text1)).setText(modelNames.get(position));
                    ((TextView)view.findViewById(android.R.id.text2)).setText(modelDetails.get(position));
                    return view;
                }
            };
            new AlertDialog.Builder(ctx).setTitle("Select ML Model").setAdapter(adapter, (dialog, which) -> downloadAndSelectModel(ctx, modelArray.optJSONObject(which))).setNegativeButton("Cancel", null).show();
        }

        private void downloadAndSelectModel(Context ctx, JSONObject selectedModel) {
            if (selectedModel == null) return;
            String modelName = selectedModel.optString("name", "Unknown");
            String fileName = selectedModel.optString("file", "");
            mProgressDialog = new ProgressDialog(ctx);
            mProgressDialog.setTitle("Downloading Model");
            mProgressDialog.setMessage(modelName);
            mProgressDialog.setCancelable(true);
            mProgressDialog.show();
            mDownloadCancelFlag = new AtomicBoolean(false);
            MlModelManager mm = new MlModelManager(ctx);
            mm.downloadModel(fileName, mDownloadCancelFlag, (success, file) -> {
                if (mProgressDialog != null && mProgressDialog.isShowing()) mProgressDialog.dismiss();
                if (success && !mDownloadCancelFlag.get()) {
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("CnnModelName", modelName).putString("CnnModelFile", file.getAbsolutePath()).apply();
                    Preference modelPref = findPreference("MlModelSelector");
                    if (modelPref != null) modelPref.setSummary(getString(R.string.ml_model_select_summary) + "\n" + getString(R.string.selected_model_label, modelName));
                }
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
