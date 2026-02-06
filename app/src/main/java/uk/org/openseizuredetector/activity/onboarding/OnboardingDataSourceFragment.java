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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Data Source selection page - choose between phone (demo), PineTime, Garmin, or Network
 * This fragment handles selection only. Configuration of the selected source
 * is handled by OnboardingDataSourceConfigFragment.
 */
public class OnboardingDataSourceFragment extends Fragment {
    private static final String TAG = "OnboardingDataSource";

    private RadioGroup mDataSourceGroup;
    private SharedPreferences mPrefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_onboarding_datasource, container, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        mDataSourceGroup = view.findViewById(R.id.data_source_group);

        // Load saved preference
        String currentDataSource = mPrefs.getString("DataSource", "0");
        selectDataSourceRadio(currentDataSource);

        // Save selection when changed
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

