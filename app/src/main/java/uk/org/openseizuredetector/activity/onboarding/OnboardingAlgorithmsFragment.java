package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import uk.org.openseizuredetector.alg.MlModelManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;

/**
 * Algorithm selection page - choose which seizure detection algorithms to use.
 *
 * After selection, user is guided through configuration of each selected algorithm:
 * - ML Algorithm: Download ML model
 * - Heart Rate: Show "default config enabled" message
 * - OSD: Show "default config enabled" message
 * - OSD+Flap: Show "default config enabled" message
 */
public class OnboardingAlgorithmsFragment extends Fragment {
    private static final String TAG = "OnboardingAlgorithms";

    private CheckBox mCheckMlAlg;
    private CheckBox mCheckHrAlg;
    private CheckBox mCheckOsdAlg;
    private CheckBox mCheckFlapAlg;
    private FrameLayout mConfigMessageContainer;

    private SharedPreferences mPrefs;
    private MaterialButton mNextButton;

    // Track which algorithms need configuration
    private boolean[] mSelectedAlgorithms = new boolean[4]; // ML, HR, OSD, Flap
    private int mCurrentConfigIndex = -1; // -1 = selection phase, 0+ = config phase
    private static final int ALG_ML = 0;
    private static final int ALG_HR = 1;
    private static final int ALG_OSD = 2;
    private static final int ALG_FLAP = 3;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_onboarding_algorithms, container, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Get checkbox references (reordered: ML, HR, OSD, Flap)
        mCheckMlAlg = view.findViewById(R.id.check_ml_alg);
        mCheckHrAlg = view.findViewById(R.id.check_hr_alg);
        mCheckOsdAlg = view.findViewById(R.id.check_osd_alg);
        mCheckFlapAlg = view.findViewById(R.id.check_flap_alg);
        mConfigMessageContainer = view.findViewById(R.id.config_message_container);
        
        // Get next button from parent activity
        mNextButton = requireActivity().findViewById(R.id.btn_next);

        // Load saved preferences using correct preference keys from seizure_detector_prefs.xml
        boolean osdAlgActive = mPrefs.getBoolean("OsdAlarmActive", true);
        boolean flapAlgActive = mPrefs.getBoolean("FlapAlarmActive", true);
        boolean mlAlgActive = mPrefs.getBoolean("CnnAlarmActive", false);
        boolean hrAlgActive = mPrefs.getBoolean("HRAlarmActive", false);

        // Set checkbox states based on loaded preferences
        mCheckMlAlg.setChecked(mlAlgActive);
        mCheckHrAlg.setChecked(hrAlgActive);
        mCheckOsdAlg.setChecked(osdAlgActive);
        mCheckFlapAlg.setChecked(flapAlgActive);

        // Add change listeners to all checkboxes
        mCheckMlAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "ML Algorithm: " + isChecked);
            mPrefs.edit().putBoolean("CnnAlarmActive", isChecked).apply();
            updateNextButtonState();
        });

        mCheckHrAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "Heart Rate: " + isChecked);
            mPrefs.edit().putBoolean("HRAlarmActive", isChecked).apply();
            updateNextButtonState();
        });

        mCheckOsdAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "OSD Algorithm: " + isChecked);
            mPrefs.edit().putBoolean("OsdAlarmActive", isChecked).apply();
            updateNextButtonState();
        });

        mCheckFlapAlg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.i(TAG, "Flap Detection: " + isChecked);
            mPrefs.edit().putBoolean("FlapAlarmActive", isChecked).apply();
            updateNextButtonState();
        });

        // Initial button state
        updateNextButtonState();

        return view;
    }

    /**
     * Update next button state based on algorithm selections
     * Disabled until at least one algorithm is selected
     */
    private void updateNextButtonState() {
        boolean anySelected = mCheckMlAlg.isChecked() || mCheckHrAlg.isChecked() ||
                            mCheckOsdAlg.isChecked() || mCheckFlapAlg.isChecked();

        if (mNextButton != null) {
            mNextButton.setEnabled(anySelected);
            Log.i(TAG, "Next button: " + (anySelected ? "ENABLED" : "DISABLED"));
        }
    }

    /**
     * Called by parent activity when next button is clicked during algorithm configuration.
     * Determines whether to show algorithm configuration or proceed to next page.
     * @return true if handled (stay on this fragment), false if done (go to next page)
     */
    public boolean handleNextClick() {
        if (mCurrentConfigIndex == -1) {
            // First click on next - start algorithm configuration
            startAlgorithmConfiguration();
            return true; // Stay on this fragment
        } else {
            // Subsequent clicks - proceed to next configured algorithm
            mCurrentConfigIndex++;
            if (!showNextAlgorithmConfiguration()) {
                // All algorithms configured - allow proceeding
                return false;
            }
            return true; // Stay on fragment for next algorithm
        }
    }

    /**
     * Start the algorithm configuration process by building list of selected algorithms
     */
    private void startAlgorithmConfiguration() {
        Log.i(TAG, "Starting algorithm configuration");

        // Build array of selected algorithms in order: ML, HR, OSD, Flap
        mSelectedAlgorithms[ALG_ML] = mCheckMlAlg.isChecked();
        mSelectedAlgorithms[ALG_HR] = mCheckHrAlg.isChecked();
        mSelectedAlgorithms[ALG_OSD] = mCheckOsdAlg.isChecked();
        mSelectedAlgorithms[ALG_FLAP] = mCheckFlapAlg.isChecked();

        mCurrentConfigIndex = 0;
        showNextAlgorithmConfiguration();
    }

    /**
     * Show configuration UI for the next selected algorithm
     * @return true if algorithm shown, false if all done
     */
    private boolean showNextAlgorithmConfiguration() {
        while (mCurrentConfigIndex < 4) {
            if (mSelectedAlgorithms[mCurrentConfigIndex]) {
                switch (mCurrentConfigIndex) {
                    case ALG_ML:
                        showMlAlgorithmConfiguration();
                        return true;
                    case ALG_HR:
                        showHeartRateConfiguration();
                        return true;
                    case ALG_OSD:
                        showOsdConfiguration();
                        return true;
                    case ALG_FLAP:
                        showFlapConfiguration();
                        return true;
                }
            }
            mCurrentConfigIndex++;
        }
        // All algorithms configured
        return false;
    }

    /**
     * Configure ML Algorithm - automatically select recommended model
     */
    private void showMlAlgorithmConfiguration() {
        Log.i(TAG, "Configuring ML Algorithm");

        // Show progress while fetching model index
        MaterialAlertDialogBuilder progressBuilder = new MaterialAlertDialogBuilder(requireContext())
            .setTitle("ML Algorithm Configuration")
            .setMessage("Fetching available models...")
            .setCancelable(false);
        AlertDialog progressDialog = progressBuilder.show();

        // Fetch available ML models from server
        MlModelManager mm = new MlModelManager(requireContext());
        mm.getMlModelIndex(modelArray -> {
            if (getActivity() == null || getActivity().isDestroyed()) return;
            
            getActivity().runOnUiThread(() -> {
                progressDialog.dismiss();

                if (modelArray == null) {
                    showMlIncompatibilityDialog("Failed to connect to the model server. Please check your internet connection.");
                    return;
                }

                // Check for compatible models
                List<JSONObject> compatibleModels = new ArrayList<>();
                JSONObject recommendedModel = null;

                try {
                    for (int i = 0; i < modelArray.length(); i++) {
                        JSONObject modelObj = modelArray.getJSONObject(i);
                        if (mm.isDeviceCompatible(modelObj)) {
                            compatibleModels.add(modelObj);
                            if (modelObj.optBoolean("recommended", false)) {
                                recommendedModel = modelObj;
                            }
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing model list: " + e.getMessage());
                }

                if (compatibleModels.isEmpty()) {
                    showMlIncompatibilityDialog("Your device is not compatible with the available ML models. This is usually due to missing CPU features (e.g. dotprod). The ML algorithm will be disabled.");
                } else {
                    // Use recommended if compatible, otherwise pick the first compatible one
                    JSONObject modelToDownload = (recommendedModel != null) ? recommendedModel : compatibleModels.get(0);
                    String modelName = modelToDownload.optString("name", "Unknown Model");
                    
                    // Save recommended model preference
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                    prefs.edit().putString("CnnModelName", modelName).apply();
                    
                    showDownloadProgressDialog(mm, modelToDownload, modelName);
                }
            });
        });
    }

    private void showMlIncompatibilityDialog(String message) {
        // Disable ML algorithm in preferences
        mPrefs.edit().putBoolean("CnnAlarmActive", false).apply();
        if (mCheckMlAlg != null) mCheckMlAlg.setChecked(false);

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("ML Algorithm Disabled")
            .setMessage(message)
            .setPositiveButton("OK", (d, w) -> {
                // Skip to next algorithm or next page
                if (mNextButton != null) {
                    mNextButton.performClick();
                }
            })
            .setCancelable(false)
            .show();
    }

    /**
     * Download the selected ML model with progress feedback
     */
    private void showDownloadProgressDialog(MlModelManager mm, JSONObject modelInfo, String modelName) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Downloading ML Model")
            .setMessage("Downloading " + modelName + "...\nPlease wait...")
            .setCancelable(false);

        AlertDialog downloadDialog = builder.show();

        // Start model download
        mm.downloadAndInstallModel(modelInfo, (success, file) -> {
            if (getActivity() != null && !getActivity().isDestroyed()) {
                getActivity().runOnUiThread(() -> {
                    downloadDialog.dismiss();

                    if (success) {
                        Log.i(TAG, "ML Model downloaded successfully: " + modelName);
                        new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("ML Algorithm Configuration")
                            .setMessage("ML Algorithm model has been configured and downloaded:\n" +
                                       modelName + "\n\n" +
                                       "You can select a different model in the Settings menu when the app starts.")
                            .setPositiveButton("OK", (d, w) -> {
                                if (mNextButton != null) {
                                    mNextButton.performClick();
                                }
                            })
                            .setCancelable(false)
                            .show();
                    } else {
                        Log.e(TAG, "Failed to download ML model: " + modelName);
                        new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Download Failed")
                            .setMessage("Failed to download " + modelName + ".\n\n" +
                                       "The app will use the default model configuration.\n" +
                                       "You can try downloading a different model in the Settings menu when the app starts.")
                            .setPositiveButton("OK", (d, w) -> {
                                if (mNextButton != null) {
                                    mNextButton.performClick();
                                }
                            })
                            .setCancelable(false)
                            .show();
                    }
                });
            }
        });
    }

    /**
     * Configure Heart Rate Algorithm - show default config message
     */
    private void showHeartRateConfiguration() {
        Log.i(TAG, "Configuring Heart Rate Algorithm");

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Heart Rate Alerts")
            .setMessage("Default Heart Rate Alert configuration has been enabled.\n\n" +
                       "You can review and update these settings in the Settings menu when the app starts.")
            .setPositiveButton("OK", (d, w) -> {
                if (mNextButton != null) {
                    mNextButton.performClick();
                }
            })
            .setCancelable(false)
            .show();
    }

    /**
     * Configure OSD Algorithm - show default config message
     */
    private void showOsdConfiguration() {
        Log.i(TAG, "Configuring OSD Algorithm");

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("OSD Algorithm")
            .setMessage("Default OpenSeizureDetector Algorithm settings have been applied.\n\n" +
                       "You can customize these settings in the Settings menu when the app starts.")
            .setPositiveButton("OK", (d, w) -> {
                if (mNextButton != null) {
                    mNextButton.performClick();
                }
            })
            .setCancelable(false)
            .show();
    }

    /**
     * Configure OSD with Flap Detection - show default config message
     */
    private void showFlapConfiguration() {
        Log.i(TAG, "Configuring OSD Flap Detection");

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("OSD with Flap Detection")
            .setMessage("Default settings for OSD Flap Detection have been applied.\n\n" +
                       "You can customize these settings in the Settings menu when the app starts.")
            .setPositiveButton("OK", (d, w) -> {
                if (mNextButton != null) {
                    mNextButton.performClick();
                }
            })
            .setCancelable(false)
            .show();
    }
}
