package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.RadioButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Data Source selection page - choose between phone (demo), PineTime, Garmin, or Network
 * This fragment handles selection only. Configuration of the selected source
 * is handled by OnboardingDataSourceConfigFragment.
 * Supports both portrait (RadioGroup) and landscape (individual RadioButtons) layouts.
 */
public class OnboardingDataSourceFragment extends Fragment {
    private static final String TAG = "OnboardingDataSource";

    private RadioGroup mDataSourceGroup;
    private RadioButton mRadioPhone;
    private RadioButton mRadioPineTime;
    private RadioButton mRadioGarmin;
    private RadioButton mRadioNetwork;
    private SharedPreferences mPrefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_onboarding_datasource, container, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Try to find RadioGroup (portrait layout)
        mDataSourceGroup = view.findViewById(R.id.data_source_group);

        // Also find individual RadioButtons (landscape layout)
        mRadioPhone = view.findViewById(R.id.radio_phone);
        mRadioPineTime = view.findViewById(R.id.radio_pinetime);
        mRadioGarmin = view.findViewById(R.id.radio_garmin);
        mRadioNetwork = view.findViewById(R.id.radio_network);

        // Load saved preference - default to "Phone" if not set
        String currentDataSource = mPrefs.getString("DataSource", "SET_FROM_XML");
        Log.i(TAG, "onCreateView - Loaded DataSource from preferences: " + currentDataSource);
        selectDataSourceRadio(currentDataSource);

        // Setup listeners for portrait layout (RadioGroup)
        if (mDataSourceGroup != null) {
            mDataSourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.radio_pinetime) {
                    saveDataSource("PineTime");
                } else if (checkedId == R.id.radio_garmin) {
                    saveDataSource("Garmin");
                } else if (checkedId == R.id.radio_phone) {
                    saveDataSource("Phone");
                } else if (checkedId == R.id.radio_network) {
                    saveDataSource("Network");
                }
            });
        }

        // Setup listeners for landscape layout (individual RadioButtons)
        if (mRadioPhone != null) {
            mRadioPhone.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked) saveDataSource("Phone");
            });
        }
        if (mRadioPineTime != null) {
            mRadioPineTime.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked) saveDataSource("PineTime");
            });
        }
        if (mRadioGarmin != null) {
            mRadioGarmin.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked) saveDataSource("Garmin");
            });
        }
        if (mRadioNetwork != null) {
            mRadioNetwork.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked) saveDataSource("Network");
            });
        }

        return view;
    }


    private void selectDataSourceRadio(String dataSource) {
        Log.d(TAG, "selectDataSourceRadio - dataSource: " + dataSource);
        int radioId;

        switch (dataSource) {
            case "BLE2":
                // BLE2 is the saved value for PineTime
                radioId = R.id.radio_pinetime;
                break;
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
                Log.w(TAG, "Unknown data source: " + dataSource + ", defaulting to Phone");
                radioId = R.id.radio_phone;
        }

        Log.d(TAG, "selectDataSourceRadio - selected radioId: " + radioId);

        // For portrait layout with RadioGroup
        if (mDataSourceGroup != null) {
            mDataSourceGroup.check(radioId);
        }

        // For landscape layout with individual RadioButtons
        if (radioId == R.id.radio_phone && mRadioPhone != null) {
            mRadioPhone.setChecked(true);
        } else if (radioId == R.id.radio_pinetime && mRadioPineTime != null) {
            mRadioPineTime.setChecked(true);
        } else if (radioId == R.id.radio_garmin && mRadioGarmin != null) {
            mRadioGarmin.setChecked(true);
        } else if (radioId == R.id.radio_network && mRadioNetwork != null) {
            mRadioNetwork.setChecked(true);
        }
    }

    private void saveDataSource(String dataSource) {
        Log.i(TAG, "saveDataSource: " + dataSource);
        SharedPreferences.Editor editor = mPrefs.edit();
        switch (dataSource) {
            case "PineTime":
                Log.d(TAG, "PineTime selected - setting DataSource to BLE2");
                editor.putString("DataSource", "BLE2");
                break;
            case "Garmin":
                Log.d(TAG, "Garmin selected - setting DataSource to Garmin");
                editor.putString("DataSource", "Garmin");
                break;
            case "Network":
                Log.d(TAG, "Network selected - setting DataSource to Network");
                editor.putString("DataSource", "Network");
                break;
            default:
                Log.d(TAG, "Default switch branch - setting DataSource to Phone");
                editor.putString("DataSource", "Phone");
        }
        editor.apply();
    }
}
