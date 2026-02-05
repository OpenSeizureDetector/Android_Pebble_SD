package uk.org.openseizuredetector.activity.onboarding;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Data Source configuration page - configure the selected data source
 *
 * This fragment displays configuration options based on the selected data source:
 * - PineTime: Show scan and firmware update buttons
 * - Garmin: Show installation instructions
 * - Network: Show IP address input field
 * - Phone: Show demo mode information
 * - Other: Show settings instructions
 */
public class OnboardingDataSourceConfigFragment extends Fragment {
    private static final String TAG = "OnboardingDataSourceConfig";

    private SharedPreferences mPrefs;
    private ScrollView mScrollView;
    private LinearLayout mConfigContainer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_onboarding_datasource_config, container, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mScrollView = view.findViewById(R.id.config_scrollview);
        mConfigContainer = view.findViewById(R.id.config_container);

        // Get the selected data source from preferences
        String dataSource = mPrefs.getString("DataSource", "Phone");

        Log.i(TAG, "onCreateView() - Configuring for data source: " + dataSource);

        // Show appropriate configuration UI based on data source
        switch (dataSource) {
            case "BLE2":
                showPineTimeConfig(inflater);
                break;
            case "Garmin":
                showGarminConfig(inflater);
                break;
            case "Network":
                showNetworkConfig(inflater);
                break;
            case "Phone":
                showPhoneConfig(inflater);
                break;
            default:
                showOtherConfig(inflater);
        }

        return view;
    }

    /**
     * Show PineTime configuration: scan and firmware update buttons
     */
    private void showPineTimeConfig(LayoutInflater inflater) {
        View configView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_pinetime, mConfigContainer, false);

        MaterialButton btnScan = configView.findViewById(R.id.btn_scan_pinetime);
        MaterialButton btnUpdate = configView.findViewById(R.id.btn_update_pinetime);

        btnScan.setOnClickListener(v -> {
            Log.i(TAG, "Scan PineTime button clicked");
            Intent intent = new Intent(requireActivity(), BLEScanActivity.class);
            startActivity(intent);
        });

        btnUpdate.setOnClickListener(v -> {
            Log.i(TAG, "Update PineTime firmware button clicked");
            launchPineTimeUpdater();
        });

        mConfigContainer.addView(configView);
    }

    /**
     * Show Garmin configuration: installation instructions
     */
    private void showGarminConfig(LayoutInflater inflater) {
        View configView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_garmin, mConfigContainer, false);
        mConfigContainer.addView(configView);
    }

    /**
     * Show Network configuration: IP address input
     */
    private void showNetworkConfig(LayoutInflater inflater) {
        View configView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_network, mConfigContainer, false);

        TextInputLayout ipInputLayout = configView.findViewById(R.id.ip_address_input_layout);
        EditText ipInput = configView.findViewById(R.id.ip_address_input);
        TextView validationText = configView.findViewById(R.id.ip_validation_text);

        // Load saved IP if available
        String savedIp = mPrefs.getString("NetworkIP", "");
        if (!savedIp.isEmpty()) {
            ipInput.setText(savedIp);
        }

        // Validate IP address as user types
        ipInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String ip = s.toString().trim();
                if (ip.isEmpty()) {
                    ipInputLayout.setError(null);
                    validationText.setText("Enter the IP address of the OpenSeizureDetector device");
                    validationText.setTextColor(requireContext().getColor(android.R.color.darker_gray));
                } else if (isValidIpAddress(ip)) {
                    ipInputLayout.setError(null);
                    validationText.setText("✓ Valid IP address");
                    validationText.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));
                    // Save IP preference
                    mPrefs.edit().putString("NetworkIP", ip).apply();
                } else {
                    ipInputLayout.setError("Invalid IP address format");
                    validationText.setText("Please enter a valid IP address (e.g., 192.168.1.100)");
                    validationText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        mConfigContainer.addView(configView);
    }

    /**
     * Show Phone (demo mode) configuration
     */
    private void showPhoneConfig(LayoutInflater inflater) {
        View configView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_phone, mConfigContainer, false);
        mConfigContainer.addView(configView);
    }

    /**
     * Show Other/Unknown configuration
     */
    private void showOtherConfig(LayoutInflater inflater) {
        View configView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_other, mConfigContainer, false);
        mConfigContainer.addView(configView);
    }

    /**
     * Validate IP address format
     */
    private boolean isValidIpAddress(String ip) {
        // Check if it's a valid IPv4 address
        String ipv4Pattern = "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$";
        if (!ip.matches(ipv4Pattern)) {
            return false;
        }

        // Check that each octet is between 0-255
        String[] parts = ip.split("\\.");
        for (String part : parts) {
            int value = Integer.parseInt(part);
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * Launch the PineTime Updater application
     */
    private void launchPineTimeUpdater() {
        String pineTimePackageName = "uk.org.openseizuredetector.pinetime";

        try {
            boolean isInstalled = false;
            try {
                requireActivity().getPackageManager().getPackageInfo(pineTimePackageName, 0);
                isInstalled = true;
            } catch (PackageManager.NameNotFoundException e) {
                isInstalled = false;
            }

            if (isInstalled) {
                Intent launchIntent = requireActivity().getPackageManager()
                        .getLaunchIntentForPackage(pineTimePackageName);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    Toast.makeText(requireContext(), "Cannot launch PineTime Updater",
                            Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(requireContext(),
                        "PineTime Updater not installed. Install from Play Store.",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error launching PineTime Updater: " + ex.toString());
            Toast.makeText(requireContext(), "Error: " + ex.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
