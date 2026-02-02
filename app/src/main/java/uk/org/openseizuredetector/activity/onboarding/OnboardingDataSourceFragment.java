package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
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
    private LinearLayout mWatchTypeLayout;
    private RadioGroup mWatchTypeGroup;
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
        mWatchTypeLayout = view.findViewById(R.id.watch_type_layout);
        mWatchTypeGroup = view.findViewById(R.id.watch_type_group);
        mBtnScanPineTime = view.findViewById(R.id.btn_scan_pinetime);
        mBtnUpdatePineTime = view.findViewById(R.id.btn_update_pinetime);

        // Load saved preference
        String currentDataSource = mPrefs.getString("DataSource", "0");
        selectDataSourceRadio(currentDataSource);

        // Show/hide watch type based on selection
        mDataSourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_watch) {
                mWatchTypeLayout.setVisibility(View.VISIBLE);
                saveDataSource("0"); // Watch
            } else if (checkedId == R.id.radio_phone) {
                mWatchTypeLayout.setVisibility(View.GONE);
                saveDataSource("3"); // Phone (demo mode)
            } else if (checkedId == R.id.radio_network) {
                mWatchTypeLayout.setVisibility(View.GONE);
                saveDataSource("2"); // Network
            }
        });

        // Handle watch type selection
        mWatchTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_pinetime) {
                mBtnScanPineTime.setVisibility(View.VISIBLE);
                mBtnUpdatePineTime.setVisibility(View.VISIBLE);
                saveWatchType("PineTime");
            } else if (checkedId == R.id.radio_garmin) {
                mBtnScanPineTime.setVisibility(View.GONE);
                mBtnUpdatePineTime.setVisibility(View.GONE);
                saveWatchType("Garmin");
            } else if (checkedId == R.id.radio_other) {
                mBtnScanPineTime.setVisibility(View.GONE);
                mBtnUpdatePineTime.setVisibility(View.GONE);
                saveWatchType("Other");
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

        // Initial visibility state
        if (mDataSourceGroup.getCheckedRadioButtonId() == R.id.radio_watch) {
            mWatchTypeLayout.setVisibility(View.VISIBLE);
            updatePineTimeButtonVisibility();
        } else {
            mWatchTypeLayout.setVisibility(View.GONE);
        }

        return view;
    }

    private void selectDataSourceRadio(String dataSource) {
        int radioId = R.id.radio_watch; // default
        if (dataSource.equals("0")) {
            radioId = R.id.radio_watch;
        } else if (dataSource.equals("3")) {
            radioId = R.id.radio_phone;
        } else if (dataSource.equals("2")) {
            radioId = R.id.radio_network;
        }
        mDataSourceGroup.check(radioId);
    }

    private void saveDataSource(String value) {
        Log.i(TAG, "saveDataSource: " + value);
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putString("DataSource", value);
        editor.apply();
    }

    private void saveWatchType(String watchType) {
        Log.i(TAG, "saveWatchType: " + watchType);
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putString("WatchType", watchType);
        editor.apply();
    }

    private void updatePineTimeButtonVisibility() {
        if (mWatchTypeGroup.getCheckedRadioButtonId() == R.id.radio_pinetime) {
            mBtnScanPineTime.setVisibility(View.VISIBLE);
            mBtnUpdatePineTime.setVisibility(View.VISIBLE);
        } else {
            mBtnScanPineTime.setVisibility(View.GONE);
            mBtnUpdatePineTime.setVisibility(View.GONE);
        }
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
