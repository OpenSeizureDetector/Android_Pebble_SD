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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

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

    private ActivityResultLauncher<Intent> mPineTimeUpdaterLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register for the result of the PineTime Updater activity
        mPineTimeUpdaterLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "onActivityResult - resultCode: " + result.getResultCode());
                    if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null && data.hasExtra("mac_address")) {
                            String macAddress = data.getStringExtra("mac_address");
                            if (macAddress != null && !macAddress.isEmpty()) {
                                Log.i(TAG, "Received MAC address from PineTime Updater: " + macAddress);

                                // Save the MAC address to SharedPreferences
                                SharedPreferences.Editor editor = mPrefs.edit();
                                editor.putString("BLE_Device_Addr", macAddress);
                                // The updater does not provide the device name, so we'll use a placeholder.
                                // The name will be updated on the next connection.
                                editor.putString("BLE_Device_Name", "PineTime");
                                editor.apply();

                                // Refresh the display to show the new device
                                refreshPineTimeDisplay();

                                Log.i(TAG, "BLE device address updated: " + macAddress);
                            }
                        }
                    }
                });
    }

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
        String dataSource = mPrefs.getString("DataSource", "SET_FROM_XML");

        // Always update the displayed config to match the current datasource preference
        // This ensures we show the correct config even if user changed datasource and returned
        Log.i(TAG, "onResume - Current data source: " + dataSource + ", Previously shown: " + mCurrentDataSource);

        // Clear the container and add the appropriate config view
        mConfigContainer.removeAllViews();

        // Update mCurrentDataSource to reflect what we're about to display
        mCurrentDataSource = dataSource;

        switch (dataSource) {
            case "BLE2":
                mConfigContainer.addView(mPineTimeConfigView);
                // Refresh the selected device display and check PineTime Updater status
                refreshPineTimeDisplay();
                checkPineTimeUpdaterStatusOnDisplay();
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

    /**
     * Refresh the PineTime device display
     */
    private void refreshPineTimeDisplay() {
        TextView tvSelectedDevice = mPineTimeConfigView.findViewById(R.id.tv_selected_device);
        if (tvSelectedDevice != null) {
            updateSelectedDeviceDisplay(tvSelectedDevice);
        }
    }

    /**
     * Configure PineTime configuration: set up scan and firmware update buttons
     */
    private void configurePineTimeConfig(View configView) {
        MaterialButton btnInstallUpdater = configView.findViewById(R.id.btn_install_pinetime_updater);
        MaterialButton btnUpdate = configView.findViewById(R.id.btn_update_pinetime);
        MaterialButton btnScan = configView.findViewById(R.id.btn_scan_pinetime);
        TextView tvUpdaterStatus = configView.findViewById(R.id.tv_updater_status);
        TextView tvSelectedDevice = configView.findViewById(R.id.tv_selected_device);


        // Install Updater button - open Google Play Store
        btnInstallUpdater.setOnClickListener(v -> {
            Log.i(TAG, "Install PineTime Updater button clicked");
            launchGooglePlayStore("uk.org.openseizuredetector.pinetime");
        });

        // Display currently selected device if available
        updateSelectedDeviceDisplay(tvSelectedDevice);

        // Scan button
        btnScan.setOnClickListener(v -> {
            Log.i(TAG, "Scan PineTime button clicked");
            Intent intent = new Intent(requireActivity(), BLEScanActivity.class);
            startActivity(intent);
        });

        // Firmware Update button
        btnUpdate.setOnClickListener(v -> {
            Log.i(TAG, "Update PineTime firmware button clicked");
            launchPineTimeUpdater();
        });
    }

    /**
     * Check if PineTime Updater app is installed and update UI accordingly
     * Called automatically when the PineTime config is displayed
     */
    private void checkPineTimeUpdaterStatusOnDisplay() {
        MaterialButton btnInstallUpdater = mPineTimeConfigView.findViewById(R.id.btn_install_pinetime_updater);
        MaterialButton btnUpdate = mPineTimeConfigView.findViewById(R.id.btn_update_pinetime);
        TextView tvUpdaterStatus = mPineTimeConfigView.findViewById(R.id.tv_updater_status);

        if (btnInstallUpdater == null || btnUpdate == null || tvUpdaterStatus == null) {
            Log.w(TAG, "PineTime config views not found");
            return;
        }

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
                // Updater app is installed
                tvUpdaterStatus.setText(requireContext().getString(R.string.onboarding_pinetime_config_updater_installed));
                tvUpdaterStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));

                btnInstallUpdater.setVisibility(View.GONE);

                // Enable firmware update button
                btnUpdate.setEnabled(true);

                Log.i(TAG, "PineTime Updater app is installed");
            } else {
                // Updater app is not installed
                tvUpdaterStatus.setText(requireContext().getString(R.string.onboarding_pinetime_config_updater_not_installed));
                tvUpdaterStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light));

                btnInstallUpdater.setVisibility(View.VISIBLE);

                // Disable firmware update button
                btnUpdate.setEnabled(false);

                Log.i(TAG, "PineTime Updater app is not installed");
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error checking PineTime Updater status: " + ex.toString());
            tvUpdaterStatus.setText(requireContext().getString(R.string.onboarding_pinetime_config_error_checking));
            tvUpdaterStatus.setTextColor(requireContext().getColor(android.R.color.holo_orange_light));
        }
    }

    /**
     * Launch Google Play Store to install the PineTime Updater app
     */
    private void launchGooglePlayStore(String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
            startActivity(intent);
        } catch (Exception ex) {
            Log.e(TAG, "Error launching Google Play Store: " + ex.toString());
            Toast.makeText(requireContext(), "Cannot open Google Play Store", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Update the TextView to show the currently selected BLE device
     */
    private void updateSelectedDeviceDisplay(TextView tvSelectedDevice) {
        String deviceName = mPrefs.getString("BLE_Device_Name", "SET_FROM_XML");
        String deviceAddr = mPrefs.getString("BLE_Device_Addr", "SET_FROM_XML");

        if (deviceName != null && deviceAddr != null) {
            String displayText = "✓ " + deviceName + "\nMAC: " + deviceAddr;
            tvSelectedDevice.setText(displayText);
            tvSelectedDevice.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));
        } else {
            tvSelectedDevice.setText("No device selected");
            tvSelectedDevice.setTextColor(requireContext().getColor(android.R.color.holo_orange_light));
        }
    }

    /**
     * Configure Network configuration: IP address input with server validation
     */
    private void configureNetworkConfig(View configView) {
        TextInputLayout ipInputLayout = configView.findViewById(R.id.ip_address_input_layout);
        EditText ipInput = configView.findViewById(R.id.ip_address_input);
        TextView validationText = configView.findViewById(R.id.ip_validation_text);
        MaterialButton retryButton = configView.findViewById(R.id.btn_retry_validation);

        // Load saved IP if available
        String savedIp = mPrefs.getString("NetworkIP", "");
        if (!savedIp.isEmpty()) {
            ipInput.setText(savedIp);
            // Validate the saved IP on fragment load
            if (isValidIpAddress(savedIp)) {
                validationText.setText("Validating server...");
                validationText.setTextColor(requireContext().getColor(android.R.color.darker_gray));
                validateServer(savedIp, validationText, retryButton);
            }
        }

        // Validate IP address as user types
        ipInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String ip = s.toString().trim();
                retryButton.setVisibility(View.GONE);

                if (ip.isEmpty()) {
                    ipInputLayout.setError(null);
                    validationText.setText("Enter the IP address of the OpenSeizureDetector device");
                    validationText.setTextColor(requireContext().getColor(android.R.color.darker_gray));
                    disableNextButton();
                } else if (isValidIpAddress(ip)) {
                    ipInputLayout.setError(null);
                    validationText.setText("Validating server...");
                    validationText.setTextColor(requireContext().getColor(android.R.color.darker_gray));
                    validateServer(ip, validationText, retryButton);
                } else {
                    ipInputLayout.setError("Invalid IP address format");
                    validationText.setText("Please enter a valid IP address (e.g., 192.168.1.100)");
                    validationText.setTextColor(requireContext().getColor(android.R.color.holo_orange_light));
                    disableNextButton();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        retryButton.setOnClickListener(v -> {
            String ip = ipInput.getText().toString().trim();
            if (isValidIpAddress(ip)) {
                validationText.setText("Validating server...");
                validationText.setTextColor(requireContext().getColor(android.R.color.darker_gray));
                retryButton.setVisibility(View.GONE);
                validateServer(ip, validationText, retryButton);
            }
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
                    // Remove the NEW_TASK flag to ensure the result is returned correctly
                    launchIntent.setFlags(launchIntent.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
                    mPineTimeUpdaterLauncher.launch(launchIntent);
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

    /**
     * Validate server by attempting HTTP connection to http://<ip>:8080/data
     */
    private void validateServer(String ip, TextView validationText, MaterialButton retryButton) {
        new Thread(() -> {
            try {
                String url = "http://" + ip + ":8080/data";
                Log.i(TAG, "Validating server at: " + url);

                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                connection.disconnect();

                Log.i(TAG, "Server validation response code: " + responseCode);

                requireActivity().runOnUiThread(() -> {
                    if (responseCode == 200) {
                        onServerValidationSuccess(ip, validationText, retryButton);
                    } else {
                        showValidationError(validationText, retryButton);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Server validation failed: " + e.getMessage());
                requireActivity().runOnUiThread(() -> showValidationError(validationText, retryButton));
            }
        }).start();
    }

    /**
     * Handle successful server validation
     */
    private void onServerValidationSuccess(String ip, TextView validationText, MaterialButton retryButton) {
        validationText.setText("✓ Server validated successfully");
        validationText.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));

        // Save preferences
        Log.i(TAG, "Server validated - saving Network Datasource IP: " + ip);
        mPrefs.edit().putString("ServerIP", ip).apply();
        mPrefs.edit().putString("NetworkIP", ip).apply();
        mPrefs.edit().putString("DataUpdatePeriod", "2000").apply();
        mPrefs.edit().putString("ConnectTimeoutPeriod", "5000").apply();
        mPrefs.edit().putString("ReadTimeoutPeriod", "5000").apply();

        enableNextButton();
        retryButton.setVisibility(View.GONE);
    }

    /**
     * Handle server validation error
     */
    private void showValidationError(TextView validationText, MaterialButton retryButton) {
        validationText.setText("✗ Cannot reach server. Please check:\n• IP address is correct\n• Server is running\n• Device is on the same WiFi network");
        validationText.setTextColor(requireContext().getColor(android.R.color.holo_orange_light));
        retryButton.setVisibility(View.VISIBLE);
        disableNextButton();
    }

    /**
     * Enable the next button in the onboarding activity
     */
    private void enableNextButton() {
        try {
            MaterialButton nextButton = requireActivity().findViewById(R.id.btn_next);
            if (nextButton != null) {
                nextButton.setEnabled(true);
                Log.i(TAG, "Next button enabled");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enabling next button: " + e.getMessage());
        }
    }

    /**
     * Disable the next button in the onboarding activity
     */
    private void disableNextButton() {
        try {
            MaterialButton nextButton = requireActivity().findViewById(R.id.btn_next);
            if (nextButton != null) {
                nextButton.setEnabled(false);
                Log.i(TAG, "Next button disabled");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error disabling next button: " + e.getMessage());
        }
    }
}
