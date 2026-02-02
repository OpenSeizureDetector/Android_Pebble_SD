package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Algorithm selection page - choose which seizure detection algorithms to use
 */
public class OnboardingAlgorithmsFragment extends Fragment {
    private static final String TAG = "OnboardingAlgorithms";

    private CheckBox mCheckOsdAlg;
    private CheckBox mCheckFlapAlg;
    private CheckBox mCheckMlAlg;
    private CheckBox mCheckHrAlg;

    private SharedPreferences mPrefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_onboarding_algorithms, container, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        mCheckOsdAlg = view.findViewById(R.id.check_osd_alg);
        mCheckFlapAlg = view.findViewById(R.id.check_flap_alg);
        mCheckMlAlg = view.findViewById(R.id.check_ml_alg);
        mCheckHrAlg = view.findViewById(R.id.check_hr_alg);

        // Load saved preferences
        mCheckOsdAlg.setChecked(mPrefs.getBoolean("OsdAlgActive", true));
        mCheckFlapAlg.setChecked(mPrefs.getBoolean("FlapAlgActive", false));
        mCheckMlAlg.setChecked(mPrefs.getBoolean("MlAlgActive", false));
        mCheckHrAlg.setChecked(mPrefs.getBoolean("HrAlgActive", false));

        // Save selections when changed
        mCheckOsdAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "OsdAlg: " + isChecked);
            mPrefs.edit().putBoolean("OsdAlgActive", isChecked).apply();
        });

        mCheckFlapAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "FlapAlg: " + isChecked);
            mPrefs.edit().putBoolean("FlapAlgActive", isChecked).apply();
        });

        mCheckMlAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "MlAlg: " + isChecked);
            mPrefs.edit().putBoolean("MlAlgActive", isChecked).apply();
        });

        mCheckHrAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "HrAlg: " + isChecked);
            mPrefs.edit().putBoolean("HrAlgActive", isChecked).apply();
        });

        return view;
    }
}
