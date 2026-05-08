package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.utils.PreferenceUtils;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import uk.org.openseizuredetector.data.logging.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Final page - setup complete with configuration summary
 */
public class OnboardingCompleteFragment extends Fragment {
    private static final String TAG = "OnboardingComplete";

    private View mRootView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.fragment_onboarding_complete, container, false);
        return mRootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-read preferences every time fragment is displayed in case they changed
        if (mRootView != null) {
            displayConfigurationSummary(mRootView);
        }
    }

    /**
     * Display summary of configured data source and algorithms
     */
    private void displayConfigurationSummary(View view) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Get data source
        String dataSource = prefs.getString("DataSource", "SET_FROM_XML");
        String dataSourceDisplay = getDataSourceDisplay(dataSource, prefs);

        Log.i(TAG, "DataSource: " + dataSource);
        Log.i(TAG, "OsdAlarmActive: " + PreferenceUtils.getBooleanFromXml(prefs, "OsdAlarmActive"));
        Log.i(TAG, "FlapAlarmActive: " + PreferenceUtils.getBooleanFromXml(prefs, "FlapAlarmActive"));
        Log.i(TAG, "CnnAlarmActive: " + PreferenceUtils.getBooleanFromXml(prefs, "CnnAlarmActive"));
        Log.i(TAG, "HRAlarmActive: " + PreferenceUtils.getBooleanFromXml(prefs, "HRAlarmActive"));

        // Get enabled algorithms
        List<String> enabledAlgorithms = getEnabledAlgorithms(prefs, dataSource);
        String algorithmsDisplay = formatAlgorithms(enabledAlgorithms);

        Log.i(TAG, "Enabled algorithms: " + enabledAlgorithms.toString());

        // Update TextViews
        TextView dataSourceView = view.findViewById(R.id.config_data_source);
        TextView algorithmsView = view.findViewById(R.id.config_algorithms);

        dataSourceView.setText(dataSourceDisplay);
        algorithmsView.setText(algorithmsDisplay);
    }

    /**
     * Get human-readable data source name with additional details for some sources
     */
    private String getDataSourceDisplay(String dataSource, SharedPreferences prefs) {
        switch (dataSource) {
            case "BLE2":
                String deviceName = prefs.getString("BLE_Device_Name", "SET_FROM_XML");
                String deviceAddr = prefs.getString("BLE_Device_Addr", "SET_FROM_XML");
                if (deviceName != null && deviceAddr != null) {
                    return "PineTime Watch (BLE)\n" + deviceName + "\n" + deviceAddr;
                } else {
                    return "PineTime Watch (BLE)\n(No device selected)";
                }
            case "Garmin":
                return "Garmin Watch";
            case "Network":
                return "Network (Remote Device)";
            case "Phone":
                return "Phone Accelerometer";
            default:
                return dataSource;
        }
    }

    /**
     * Get list of enabled algorithms based on data source
     */
    private List<String> getEnabledAlgorithms(SharedPreferences prefs, String dataSource) {
        List<String> algorithms = new ArrayList<>();

        // Network data source doesn't use local algorithms
        if ("Network".equals(dataSource)) {
            algorithms.add("Configured on remote device");
            return algorithms;
        }

        // Check which algorithms are enabled
        // Use false as defaults to match what was set in onboarding
        if (PreferenceUtils.getBooleanFromXml(prefs, "OsdAlarmActive")) {
            algorithms.add("Original OpenSeizureDetector (OSD)");
        }

        if (PreferenceUtils.getBooleanFromXml(prefs, "FlapAlarmActive")) {
            algorithms.add("OSD with Flap Detection");
        }

        if (PreferenceUtils.getBooleanFromXml(prefs, "CnnAlarmActive")) {
            String modelName = prefs.getString("CnnModelName", null);
            if (modelName != null) {
                algorithms.add("ML Algorithm (" + modelName + ")");
            } else {
                algorithms.add("ML Algorithm");
            }
        }

        if (PreferenceUtils.getBooleanFromXml(prefs, "HRAlarmActive")) {
            algorithms.add("Heart Rate Alerts");
        }

        return algorithms;
    }

    /**
     * Format algorithm list as bullet points
     */
    private String formatAlgorithms(List<String> algorithms) {
        if (algorithms.isEmpty()) {
            return "No algorithms configured";
        }

        StringBuilder sb = new StringBuilder();
        for (String algo : algorithms) {
            sb.append("• ").append(algo).append("\n");
        }

        // Remove trailing newline
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }
}
