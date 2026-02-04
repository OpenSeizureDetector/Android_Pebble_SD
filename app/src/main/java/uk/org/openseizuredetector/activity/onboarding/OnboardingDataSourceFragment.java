package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;
/**
 * Data Source selection page - choose between phone (demo), watch, or network
 */
public class OnboardingDataSourceFragment extends Fragment {
    private static final String TAG = "OnboardingDataSource";

    private RadioGroup mDataSourceGroup;

    private Button mBtnScanPineTime;
    private Button mBtnUpdatePineTime;

    private SharedPreferences mPrefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_onboarding_datasource, container, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        mDataSourceGroup = view.findViewById(R.id.data_source_group);
        mBtnScanPineTime = view.findViewById(R.id.btn_scan_pinetime);
        mBtnUpdatePineTime = view.findViewById(R.id.btn_update_pinetime);

        // Load saved preference
        String currentDataSource = mPrefs.getString("DataSource", "0");
        selectDataSourceRadio(currentDataSource);

        // Show/hide watch type based on selection
        mDataSourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_pinetime) {
                saveDataSource("PineTime"); // PineTime
                mBtnScanPineTime.setVisibility(View.VISIBLE);
                mBtnUpdatePineTime.setVisibility(View.VISIBLE);

            } else if (checkedId == R.id.radio_garmin) {
                saveDataSource("Garmin"); // Garmin
                mBtnScanPineTime.setVisibility(View.GONE);
                mBtnUpdatePineTime.setVisibility(View.GONE);

            } else if (checkedId == R.id.radio_phone) {
                saveDataSource("Phone"); // Phone (demo mode)
                mBtnScanPineTime.setVisibility(View.GONE);
                mBtnUpdatePineTime.setVisibility(View.GONE);

            } else if (checkedId == R.id.radio_network) {
                saveDataSource("Network"); // Network
                mBtnScanPineTime.setVisibility(View.GONE);
                mBtnUpdatePineTime.setVisibility(View.GONE);

            }
        });


        // Scan for PineTime watch
        mBtnScanPineTime.setOnClickListener(v -> {
            Log.i(TAG, "Scan PineTime button clicked");
            Intent intent = new Intent(requireActivity(), BLEScanActivity.class);
            startActivity(intent);
        });

        // Update PineTime firmware
        mBtnUpdatePineTime.setOnClickListener(v -> {
            Log.i(TAG, "Update PineTime firmware button clicked");
            launchPineTimeUpdater();
        });


        return view;
    }

    private void selectDataSourceRadio(String dataSource) {
        int radioId;
        switch (dataSource) {
            case "Garmin":
                radioId = R.id.radio_garmin;
                break;
            case "PineTime":
                radioId = R.id.radio_pinetime;
                break;
            case "Phone":
                radioId = R.id.radio_phone;
                break;
            case "Network":
                radioId = R.id.radio_network;
                break;
            default:
                radioId = R.id.radio_phone;
        }
        mDataSourceGroup.check(radioId);
    }

    private void saveDataSource(String dataSource) {
        Log.i(TAG, "saveDataSource: " + dataSource);
        SharedPreferences.Editor editor = mPrefs.edit();
        switch (dataSource) {
            case "PineTime":
                editor.putString("DataSource", "BLE2");
                break;
            case "Garmin":
                editor.putString("DataSource", "Garmin");
                break;
            case "Network":
                editor.putString("DataSource", "Network");
                break;
            default:
                editor.putString("DataSource", "Phone");
        }
        editor.apply();
    }


    private void launchPineTimeUpdater() {
        String pineTimePackageName = "uk.org.openseizuredetector.pinetime";

        try {
            boolean isInstalled = false;
            try {
                requireActivity().getPackageManager().getPackageInfo(pineTimePackageName, 0);
                isInstalled = true;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
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
