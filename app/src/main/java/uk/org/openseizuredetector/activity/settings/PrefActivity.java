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
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.content.Context;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.content.Intent;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.content.SharedPreferences;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.os.Bundle;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.os.Handler;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.preference.Preference;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.preference.PreferenceActivity;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.preference.PreferenceFragment;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.preference.PreferenceManager;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.util.Log;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.view.LayoutInflater;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.view.View;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.widget.Button;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;
import android.widget.Toast;
import uk.org.openseizuredetector.utils.OsdUncaughtExceptionHandler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;
import uk.org.openseizuredetector.activity.startup.StartupActivity;
public class PrefActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {
    private static final String TAG = "PreferenceActivity";
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
        }
        // If we have changed the data source, re-start the whole system
        else if (s.equals("DataSource"))  {
            Log.i(TAG, "onSharedPreferenceChanged(): Data Source Changed - Restarting start-up activity to check permissions");
            mUtil.showToast("Restarting OpenSeizureDetector");
            mUtil.stopServer();
            // Wait 1 second to give the server chance to shutdown, then re-start it
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
        }
        else if (s.equals("advancedMode")) {
            // Advanced mode requires immediate restart to refresh the headers
            Log.i(TAG, "Re-starting PrefActivity to refresh list");
            startActivity(getIntent());
            finish();
        }
        else {
            // For all other preference changes, just track that a change was made
            // The server will be restarted when the user exits the settings screen
            Log.i(TAG, "Setting changed - will restart server when settings screen is closed");
            mPrefChanged = true;
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
        Log.i(TAG, "onStop()");

        // If preferences were changed during this session, stop the server
        // but DON'T restart it here - let MainActivity2 (or StartupActivity) restart it
        // when the user returns. This prevents multiple server instances.
        if (mPrefChanged) {
            Log.i(TAG, "onStop() - Preferences changed, stopping server");
            Log.i(TAG, "onStop() - Server will be restarted when returning to main activity");
            mUtil.showToast("Settings changed - server will restart...");
            mUtil.stopServer();
            mPrefChanged = false; // Reset the flag
        }
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
        private Dialog mProgressDialog;
        private AtomicBoolean mDownloadCancelFlag;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.seizure_detector_prefs);

            Preference modelPref = findPreference("MlModelSelector");
            if (modelPref != null) {
                // Set initial summary to show currently selected model
                SharedPreferences spInit = PreferenceManager.getDefaultSharedPreferences(getActivity());
                String currentModelName = spInit.getString("CnnModelName", null);
                if (currentModelName != null) {
                    modelPref.setSummary(getString(R.string.ml_model_select_summary) + "\n" + getString(R.string.selected_model_label, currentModelName));
                } else {
                    modelPref.setSummary(getString(R.string.ml_model_select_summary) + "\n" + getString(R.string.no_model_selected));
                }
                modelPref.setOnPreferenceClickListener(pref -> {
                    Context ctx = getActivity();
                    MlModelManager mm = new MlModelManager(ctx);
                    showProgress(ctx, getString(R.string.ml_model_download_in_progress), mm::cancelIndexRequests);
                    mm.getMlModelIndex(arr -> {
                        dismissProgress();
                        if (arr == null) {
                            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
                            String existingPath = sp.getString("CnnModelFile", null);
                            Preference modelPref3 = findPreference("MlModelSelector");
                            if (existingPath != null && new File(existingPath).exists()) {
                                Toast.makeText(ctx, getString(R.string.ml_model_download_failed) + " - using existing model", Toast.LENGTH_LONG).show();
                                // keep existing summary
                            } else {
                                Toast.makeText(ctx, R.string.ml_model_download_failed, Toast.LENGTH_SHORT).show();
                                if (modelPref3 != null) {
                                    modelPref3.setSummary(getString(R.string.ml_model_select_summary) + "\n" + getString(R.string.no_model_selected));
                                }
                            }
                            return;
                        }
                        getActivity().runOnUiThread(() -> showModelDialog(ctx, mm, arr));
                    });
                    return true;
                });
            }
        }

        /**
         * Show a dialog allowing the user to select a Machine Learning model to download.
         */
        private void showModelDialog(Context ctx, MlModelManager mm, JSONArray arr) {
            try {
                // Build list of available model names
                String[] names = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String name = o.optString("name", "model" + i);
                    names[i] = o.optBoolean("recommended", false) ? name + " (recommended)" : name;
                }

                // Display dialog to select model to download.
                new AlertDialog.Builder(new android.view.ContextThemeWrapper(ctx, R.style.AppTheme_AlertDialog))
                        .setTitle(R.string.ml_model_select_title)
                        .setItems(names, (dialog, which) -> {
                            try {
                                JSONObject selected = arr.getJSONObject(which);
                                String fname = selected.optString("fname");
                                String inputFmtStr = selected.optString("input_format", "1d_mag");
                                int inputSize = selected.optInt("input_size", 125);
                                String framework = selected.optString("framework", "tflite");
                                mDownloadCancelFlag = new AtomicBoolean(false);
                                showProgress(ctx, getString(R.string.ml_model_download_in_progress), () -> mDownloadCancelFlag.set(true));
                                // The actual model download is handled by MlModelManager mm
                                mm.downloadModel(fname, mDownloadCancelFlag, (ok, file) -> {
                                    android.app.Activity act = getActivity();
                                    if (act == null) {
                                        return;
                                    }
                                    act.runOnUiThread(() -> {
                                        dismissProgress();
                                        if (!ok || file == null) {
                                            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
                                            String existingPath = sp.getString("CnnModelFile", null);
                                            if (existingPath != null && new File(existingPath).exists()) {
                                                Toast.makeText(ctx, getString(R.string.ml_model_download_failed) + " - using existing model", Toast.LENGTH_LONG).show();
                                            } else {
                                                Toast.makeText(ctx, R.string.ml_model_download_failed, Toast.LENGTH_SHORT).show();
                                                Preference modelPref2 = findPreference("MlModelSelector");
                                                if (modelPref2 != null) {
                                                    modelPref2.setSummary(getString(R.string.ml_model_select_summary) + "\n" + getString(R.string.no_model_selected));
                                                }
                                            }
                                            return;
                                        }
                                        // Store the model details in shared preferences.
                                        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
                                        sp.edit()
                                                .putString("CnnModelFile", file.getAbsolutePath())
                                                .putString("CnnModelName", selected.optString("name"))
                                                .putString("CnnInputFormatStr", inputFmtStr)
                                                .putInt("CnnInputSize", inputSize)
                                                .putString("CnnFramework", framework)
                                                .putString("CnnModelId", selected.optString("name"))
                                                .apply();
                                        // Update preference summary to show selected model
                                        Preference modelPref2 = findPreference("MlModelSelector");
                                        if (modelPref2 != null) {
                                            String summary = getString(R.string.ml_model_select_summary) + "\n" +
                                                    getString(R.string.selected_model_label, selected.optString("name")) + "\n" +
                                                    getString(R.string.selected_model_framework_label, framework) + "\n" +
                                                    getString(R.string.selected_model_input_label, inputFmtStr, inputSize);
                                            modelPref2.setSummary(summary);
                                        }
                                        Toast.makeText(ctx, R.string.ml_model_download_success, Toast.LENGTH_SHORT).show();
                                    });
                                 });
                            } catch (Exception ex) {
                                dismissProgress();
                                Toast.makeText(ctx, R.string.ml_model_download_failed, Toast.LENGTH_SHORT).show();
                                Log.e(TAG, "showModelDialog() download failed: " + ex.toString());
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } catch (Exception e) {
                Toast.makeText(ctx, R.string.ml_model_download_failed, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "showModelDialog() download failed: " + e.toString());
            }
        }

        private void showProgress(Context ctx, String message, Runnable onCancel) {
            dismissProgress();
            AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(ctx, R.style.AppTheme_AlertDialog));
            LayoutInflater inflater = LayoutInflater.from(ctx);
            View view = inflater.inflate(R.layout.dialog_progress, null);
            builder.setView(view);
            builder.setMessage(message);
            builder.setCancelable(true);
            builder.setOnCancelListener(dialog -> { if (onCancel != null) onCancel.run(); });
            mProgressDialog = builder.create();
            mProgressDialog.show();
        }

        private void dismissProgress() {
            if (mProgressDialog != null && mProgressDialog.isShowing()) {
                mProgressDialog.dismiss();
            }
            mProgressDialog = null;
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
