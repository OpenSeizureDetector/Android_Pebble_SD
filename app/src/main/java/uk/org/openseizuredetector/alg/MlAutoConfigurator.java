package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import uk.org.openseizuredetector.utils.OsdUtil;

/**
 * Helper that automatically fetches and installs the recommended ML model.
 */
public final class MlAutoConfigurator {
    private MlAutoConfigurator() {}

    public static class Options {
        public Runnable onFlowComplete = () -> {};
        public Runnable onMlDisabled = () -> {};
        public boolean showSuccessDialog = true;
    }

    public static void configure(@NonNull Fragment fragment, @NonNull Options options) {
        FragmentActivity activity = fragment.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        Context context = fragment.requireContext();
        OsdUtil osdUtil = new OsdUtil(context, new Handler(Looper.getMainLooper()));
        MlModelManager mm = new MlModelManager(context);

        if (!osdUtil.isNetworkConnected()) {
            handleNetworkIssue(context, mm, options, "No network connectivity. Please connect to download ML models.");
            return;
        }

        MaterialAlertDialogBuilder progressBuilder = new MaterialAlertDialogBuilder(context)
            .setTitle("ML Algorithm Configuration")
            .setMessage("Fetching available models...")
            .setCancelable(false);
        AlertDialog progressDialog = progressBuilder.show();

        mm.getMlModelIndex(modelArray -> {
            FragmentActivity host = fragment.getActivity();
            if (host == null || host.isFinishing()) {
                if (progressDialog != null) progressDialog.dismiss();
                return;
            }
            host.runOnUiThread(() -> {
                progressDialog.dismiss();
                if (modelArray == null) {
                    handleNetworkIssue(context, mm, options, "Failed to contact the model server. Please try again later.");
                    return;
                }

                JSONObject selected = pickCompatibleModel(modelArray, mm);
                if (selected == null) {
                    options.onMlDisabled.run();
                    new MaterialAlertDialogBuilder(context)
                        .setTitle("ML Algorithm Disabled")
                        .setMessage("This device is not compatible with any available ML models (missing required CPU features). The ML algorithm has been turned off.")
                        .setPositiveButton("OK", (d, w) -> options.onFlowComplete.run())
                        .setCancelable(false)
                        .show();
                    return;
                }

                String modelName = selected.optString("name", "Unknown Model");
                persistModelName(context, modelName);
                downloadModel(fragment, mm, selected, modelName, options);
            });
        });
    }

    private static void downloadModel(Fragment fragment, MlModelManager mm, JSONObject model, String modelName, Options options) {
        Context context = fragment.requireContext();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
            .setTitle("Downloading ML Model")
            .setMessage("Downloading " + modelName + "... Please wait")
            .setCancelable(false);
        AlertDialog downloadDialog = builder.show();

        mm.downloadAndInstallModel(model, (success, file) -> {
            FragmentActivity activity = fragment.getActivity();
            if (activity == null || activity.isFinishing()) {
                if (downloadDialog != null) downloadDialog.dismiss();
                return;
            }
            activity.runOnUiThread(() -> {
                downloadDialog.dismiss();
                if (success) {
                    if (options.showSuccessDialog) {
                        new MaterialAlertDialogBuilder(context)
                            .setTitle("ML Model Ready")
                            .setMessage("The recommended ML model has been installed (" + modelName + "). You can change it later from Settings.")
                            .setPositiveButton("OK", (d, w) -> options.onFlowComplete.run())
                            .setCancelable(false)
                            .show();
                    } else {
                        Toast.makeText(context, "Installed ML model: " + modelName, Toast.LENGTH_LONG).show();
                        options.onFlowComplete.run();
                    }
                } else {
                    handleNetworkIssue(context, mm, options, "Failed to download " + modelName + ". Please check your connection and try again.");
                }
            });
        });
    }

    private static JSONObject pickCompatibleModel(JSONArray array, MlModelManager mm) {
        JSONObject fallback = null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject model = array.optJSONObject(i);
            if (model == null) continue;
            if (!mm.isDeviceCompatible(model)) continue;
            if (model.optBoolean("recommended", false)) {
                return model;
            }
            if (fallback == null) {
                fallback = model;
            }
        }
        return fallback;
    }

    private static void persistModelName(Context context, String modelName) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!TextUtils.isEmpty(modelName)) {
            prefs.edit().putString("CnnModelName", modelName).apply();
        }
    }

    private static void handleNetworkIssue(Context context, MlModelManager mm, Options options, String message) {
        JSONArray installed = mm.getInstalledModels();
        if (installed.length() == 0) {
            options.onMlDisabled.run();
            new MaterialAlertDialogBuilder(context)
                .setTitle("ML Algorithm Disabled")
                .setMessage(message + "\n\nAs no models are installed, the ML algorithm has been turned off. You can retry later from Settings when you have connectivity.")
                .setPositiveButton("OK", (d, w) -> options.onFlowComplete.run())
                .setCancelable(false)
                .show();
        } else {
            new MaterialAlertDialogBuilder(context)
                .setTitle("Connection Error")
                .setMessage(message + "\n\nExisting models will continue to be used.")
                .setPositiveButton("OK", (d, w) -> options.onFlowComplete.run())
                .setCancelable(false)
                .show();
        }
    }
}
