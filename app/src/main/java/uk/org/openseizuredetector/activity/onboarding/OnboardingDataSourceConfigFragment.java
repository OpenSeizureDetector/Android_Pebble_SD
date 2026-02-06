package uk.org.openseizuredetector.activity.onboarding;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
    private View mMainView;

    // Store references to all inflated config views
    private View mPineTimeConfigView;
    private View mGarminConfigView;
    private View mNetworkConfigView;
    private View mPhoneConfigView;
    private View mOtherConfigView;

    // Track current data source to avoid unnecessary updates
    private String mCurrentDataSource = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mMainView = inflater.inflate(R.layout.fragment_onboarding_datasource_config, container, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mScrollView = mMainView.findViewById(R.id.config_scrollview);
        mConfigContainer = mMainView.findViewById(R.id.config_container);

        // Inflate all configuration views in onCreate
        mPineTimeConfigView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_pinetime, mConfigContainer, false);
        mGarminConfigView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_garmin, mConfigContainer, false);
        mNetworkConfigView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_network, mConfigContainer, false);
        mPhoneConfigView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_phone, mConfigContainer, false);
        mOtherConfigView = inflater.inflate(R.layout.fragment_onboarding_datasource_config_other, mConfigContainer, false);

        // Configure event listeners for each view type
        configurePineTimeConfig(mPineTimeConfigView);
        configureNetworkConfig(mNetworkConfigView);

        return mMainView;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Get the currently selected data source
        String dataSource = mPrefs.getString("DataSource", "Phone");

        // Only update UI if data source has changed
        if (!dataSource.equals(mCurrentDataSource)) {
            mCurrentDataSource = dataSource;
            Log.i(TAG, "onResume - Updating UI for data source: " + dataSource);

            // Clear the container and add the appropriate config view
            mConfigContainer.removeAllViews();

            switch (dataSource) {
                case "BLE2":
                    mConfigContainer.addView(mPineTimeConfigView);
                    break;
                case "Garmin":
                    mConfigContainer.addView(mGarminConfigView);
                    break;
                case "Network":
                    mConfigContainer.addView(mNetworkConfigView);
                    break;
                case "Phone":
                    mConfigContainer.addView(mPhoneConfigView);
                    break;
                default:
                    mConfigContainer.addView(mOtherConfigView);
            }
        }

    }

    /**
     * Configure PineTime configuration: set up scan and firmware update buttons
     */
    private void configurePineTimeConfig(View configView) {
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
    }

    /**
     * Configure Network configuration: IP address input
     */
    private void configureNetworkConfig(View configView) {
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
                    Log.i(TAG, "onTextChanged - saving Network Datasource IP: " + ip + "and setting default update and timeout periods");
                    mPrefs.edit().putString("ServerIP", ip).apply();
                    mPrefs.edit().putString("DataUpdatePeriod", "2000").apply();
                    mPrefs.edit().putString("ConnectTimeoutPeriod", "5000").apply();
                    mPrefs.edit().putString("ReadTimeoutPeriod", "5000").apply();
                } else {
                    ipInputLayout.setError("Invalid IP address format");
                    validationText.setText("Please enter a valid IP address (e.g., 192.168.1.100)");
                    validationText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
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
