package uk.org.openseizuredetector.alg;

import uk.org.openseizuredetector.utils.OsdUtil;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.data.logging.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class MlModelManager {
    protected Context mContext;
    protected OsdUtil mUtil;
    private final static String TAG = "MlModelManager";
    public static final String PREF_INSTALLED_MODELS = "InstalledMlModels";
    public static final String PREF_ML_ACCEL_STD_THRESHOLD_PCT = "MlAccelStdThresholdPct";
    public static final String PREF_ML_SEIZURE_PROB_THRESHOLD_PCT = "MlSeizureProbabilityThresholdPct";
    public static final String PREF_ML_ACCEL_STD_THRESHOLD_USER_SET = "MlAccelStdThresholdUserSet";
    public static final String PREF_ML_SEIZURE_PROB_THRESHOLD_USER_SET = "MlSeizureProbabilityThresholdUserSet";
    public static final String DEFAULT_ML_ACCEL_STD_THRESHOLD_PCT = "5";
    public static final String DEFAULT_ML_SEIZURE_PROB_THRESHOLD_PCT = "50";

    // Maximum number of ML models that can be managed simultaneously
    public static final int MAX_MODELS = 10;

    public boolean mServerConnectionOk = false;
    private String mUrlBase = "https://osdapi.org.uk/static/ml_models/";
    private String mModelIndexFname = "index.json";
    RequestQueue mQueue;

    // Track active background threads for proper shutdown
    private final Set<Thread> mActiveThreads = new HashSet<>();
    private final Object mThreadLock = new Object();

    public interface JSONArrayCallback {
        void accept(JSONArray retValArr);
    }

    public interface DownloadCallback {
        void onComplete(boolean success, File file);
    }

    public interface ModelLoadCallback {
        void onComplete(ModelLoadResult result);
    }

    public interface ModelUpdateCallback {
        void onUpdateAvailable(JSONObject recommendedModel);
        void onNoUpdate();
    }

    /**
     * ModelLoadResult class.
     * Used to encapsulate the result of loading a model, including its name, framework,
     * memory-mapped buffer (if applicable), file reference, input format, input size, and alarm threshold.
     */
    public static class ModelLoadResult {
        public final String name;
        public final String framework;
        public final java.nio.MappedByteBuffer tfliteBuffer;
        public final File file;
        public final int inputFormat;
        public final int inputSize;
        public final double alarmThreshold;

        /**
         * Constructor for ModelLoadResult.
         * Used for storing model metadata and the loaded model buffer (if applicable).
         */
        public ModelLoadResult(String name, String framework, java.nio.MappedByteBuffer tfliteBuffer,
                               File file, int inputFormat, int inputSize, double alarmThreshold) {
            this.name = name;
            this.framework = framework;
            this.tfliteBuffer = tfliteBuffer;
            this.file = file;
            this.inputFormat = inputFormat;
            this.inputSize = inputSize;
            this.alarmThreshold = alarmThreshold;
        }
    }

    /**
     * Constructor for MlModelManager.
     * @param context - the application context
     */
    public MlModelManager(Context context) {
        mContext = context;
        mUtil = new OsdUtil(mContext, new Handler(Looper.getMainLooper()));
        mQueue = Volley.newRequestQueue(mContext);
    }

    /**
     * Constructor for testing that allows overriding the base URL and index filename.
     */
    public MlModelManager(Context context, String urlBase, String indexFname) {
        this(context);
        this.mUrlBase = urlBase;
        this.mModelIndexFname = indexFname;
    }

    /**
     * close().
     * Closes the ML model manager and stops all active threads.
     */
    public void close() {
        if (mQueue != null) {
            mQueue.cancelAll(INDEX_TAG);
            mQueue.stop();
        }
        waitForThreadsToComplete(5000);
    }

    /**
     * waitForThreadsToComplete(timeoutMs).
     * Waits for all active threads to complete, up to the specified timeout in milliseconds.
     * @param timeoutMs
     */
    private void waitForThreadsToComplete(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        List<Thread> threadsToWait;
        synchronized (mThreadLock) {
            threadsToWait = new ArrayList<>(mActiveThreads);
        }
        for (Thread t : threadsToWait) {
            try {
                long remaining = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remaining <= 0) break;
                t.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * registerThread(t).
     * Registers a thread as active - this is to help with tidying up the app when we close
     * it using the close() method and waitForThreadsToComplete().
     * @param t - the thread to register
     */
    private void registerThread(Thread t) {
        synchronized (mThreadLock) { mActiveThreads.add(t); }
    }

    /**
     * unregisterThread(t).
     * Unregisters a thread as active.
     * @param t - the thread to unregister
     */
    private void unregisterThread(Thread t) {
        synchronized (mThreadLock) { mActiveThreads.remove(t); }
    }

    private final String INDEX_TAG = "MlModelIndex";

    /**
     * getMlModelIndex(callback).
     * Fetches the ML model index from the server and returns it as a JSONArray to the provided callback.
     * Uses the Volley library StringRequest for asynchronous HTTP requests.
     * @param callback
     */
    public void getMlModelIndex(JSONArrayCallback callback) {
        String urlStr = mUrlBase + mModelIndexFname;
        StringRequest req = new StringRequest(Request.Method.GET, urlStr,
                response -> {
                    mServerConnectionOk = true;
                    try {
                        JSONArray rawArr = new JSONArray(response);
                        callback.accept(filterValidModels(rawArr));
                    } catch (JSONException e) {
                        callback.accept(null);
                    }
                },
                error -> {
                    mServerConnectionOk = false;
                    callback.accept(null);
                }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                return params;
            }
        };
        req.setTag(INDEX_TAG);
        mQueue.add(req);
    }

    /**
     * downloadAndInstallModel(modelInfo, callback).
     * Downloads a model file from the server and saves it to the app's internal storage.
     * Then registers the model as installed and applies recommended thresholds if provided.
     * @param modelInfo - a JSONObject containing model metadata, must include "fname" (filename)
     * @param callback - a DownloadCallback that will be called when the download is complete or cancelled
     */
    public void downloadAndInstallModel(JSONObject modelInfo, DownloadCallback callback) {
        String fname = modelInfo.optString("fname", "");
        if (fname.isEmpty()) {
            Log.e(TAG, "downloadAndInstallModel() - modelInfo missing 'fname' - not doing anything.");
            callback.onComplete(false, null);
            return;
        }

        // Notes for next time I try to remember how this works....
        // We are calling downloadModel() with a filename, a boolean and a callback function.
        // But the callback function is defined here as a lambda (un-named) function which takes
        // two parameters, success and file.  The function body is defined in the -> { ... } block.
        // The lambda function is referred to as onComplete() in the DownloadCallback interface.
        // This modern syntax makes me feel like a Luddite, but I'll try to get used to it!
        downloadModel(fname, new AtomicBoolean(false), (success, file) -> {
            if (success && file != null) {
                try {
                    JSONObject installedModel = new JSONObject(modelInfo.toString());
                    installedModel.put("localPath", file.getAbsolutePath());
                    registerInstalledModel(installedModel);
                    applyRecommendedThresholdsFromModel(installedModel);
                    Log.i(TAG, "downloadAndInstallModel() - Successfully downloaded and registered model: " + fname);
                    callback.onComplete(true, file);
                } catch (JSONException e) {
                    Log.e(TAG, "downloadAndInstallModel() - Error registering installed model: " + e.getMessage());
                    callback.onComplete(false, null);
                }
            } else {
                Log.e(TAG, "downloadAndInstallModel() - Failed to download model file: " + fname);
                callback.onComplete(false, null);
            }
        });
    }

    /**
     * downloadModel(fname, cancelFlag, callback).
     * Downloads a model file from the server and saves it to the app's internal storage.
     * calls callback.onComplete(success, file) when done.
     * @param fname  - the filename of the model file on the server (e.g., "model.tflite")
     * @param cancelFlag - an AtomicBoolean that can be set to true to cancel the download
     * @param callback - a DownloadCallback that will be called when the download is complete or cancelled
     */
    public void downloadModel(String fname, AtomicBoolean cancelFlag, DownloadCallback callback) {
        String urlStr = mUrlBase + fname;
        Thread t = new Thread(() -> {
            File outFile = null;
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(urlStr);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000); // 15 seconds
                conn.setReadTimeout(30000);    // 30 seconds
                
                int responseCode = conn.getResponseCode();
                if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw new IOException("Server returned HTTP " + responseCode);
                }

                try (java.io.InputStream in = conn.getInputStream()) {
                    File dir = new File(mContext.getFilesDir(), "models");
                    if (!dir.exists()) dir.mkdirs();
                    outFile = new File(dir, fname);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            if (cancelFlag.get()) {
                                if (outFile.exists()) outFile.delete();
                                callback.onComplete(false, null);
                                return;
                            }
                            fos.write(buf, 0, len);
                        }
                    }
                    callback.onComplete(true, outFile);
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error downloading model: " + ex.getMessage());
                if (outFile != null && outFile.exists()) outFile.delete();
                callback.onComplete(false, null);
            } finally {
                if (conn != null) conn.disconnect();
                unregisterThread(Thread.currentThread());
            }
        });
        registerThread(t);
        t.start();
    }

    /**
     * getInstalledModels().
     * Returns a list of all installed models.
     * The installed models are stored as a JSON array in the PREF_INSTALLED_MODELS preference.
     * @return a JSONArray containing the list of installed models
     */
    public JSONArray getInstalledModels() {
        Log.i(TAG,"getInstalledModels()");
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        String json = sp.getString(PREF_INSTALLED_MODELS, "[]");
        try {
            return new JSONArray(json);
        } catch (JSONException e) {
            Log.e(TAG,"getInstalledModels() - failed to parse list of installed models - returning empty list");
            return new JSONArray();
        }
    }

    /**
     * registerInstalledModel(model).
     * Registers a model as installed by adding it to the list of installed models in SharedPreferences
     * The installed models are stored as a JSON array in the PREF_INSTALLED_MODELS preference.
     * @param model - a JSONObject containing model metadata, must include "fname" (filename)
     */
    private void registerInstalledModel(JSONObject model) {
        JSONArray models = getInstalledModels();
        try {
            // Check if already exists by filename
            String fname = model.getString("fname");
            for (int i = 0; i < models.length(); i++) {
                if (models.getJSONObject(i).getString("fname").equals(fname)) {
                    models.remove(i);
                    break;
                }
            }
            models.put(model);
            PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                    .putString(PREF_INSTALLED_MODELS, models.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Error registering model: " + e.getMessage());
        }
    }

    /**
     * applyRecommendedThresholdsFromModel(modelInfo).
     * Applies recommended thresholds from the model metadata to the app's preferences.
     * It sets the accSD standard deviation threshold and seizure probability threshold if they are provided in the model metadata.
     * It looks for accel_std_threshold_pct and seizure_probability_threshold_pct in the modelInfo JSON object.
     * If the user has already set a preference for a threshold, it will not be overridden
     * @param modelInfo
     */
    public void applyRecommendedThresholdsFromModel(JSONObject modelInfo) {
        if (modelInfo == null) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = prefs.edit();

        Double recommendedSdPct = readRecommendedPercent(modelInfo, "accel_std_threshold_pct");
        boolean userSetSd = prefs.getBoolean(PREF_ML_ACCEL_STD_THRESHOLD_USER_SET, false);
        if (recommendedSdPct != null) {
            if (!userSetSd) {
                String value = toPreferencePercentString(recommendedSdPct);
                editor.putString(PREF_ML_ACCEL_STD_THRESHOLD_PCT, value);
                Log.i(TAG, "Applied recommended accel std threshold from model: " + value + "%");
            } else {
                Log.i(TAG, "Skipping recommended accel std threshold; user preference is set");
            }
        } else {
            Log.i(TAG, "No accel_std_threshold_pct provided in model metadata");
        }

        Double recommendedProbPct = readRecommendedPercent(modelInfo, "seizure_probability_threshold_pct");

        boolean userSetProb = prefs.getBoolean(PREF_ML_SEIZURE_PROB_THRESHOLD_USER_SET, false);
        if (recommendedProbPct != null) {
            if (!userSetProb) {
                String value = toPreferencePercentString(recommendedProbPct);
                editor.putString(PREF_ML_SEIZURE_PROB_THRESHOLD_PCT, value);
                Log.i(TAG, "Applied recommended seizure probability threshold from model: " + value + "%");
            } else {
                Log.i(TAG, "Skipping recommended seizure probability threshold; user preference is set");
            }
        } else {
            Log.i(TAG, "No seizure_probability_threshold_pct provided in model metadata");
        }

        editor.apply();
    }

    private static Double readRecommendedPercent(JSONObject modelInfo, String key) {
        if (!modelInfo.has(key)) {
            return null;
        }
        double value = modelInfo.optDouble(key, Double.NaN);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return clampToPercentRange(value);
    }

    private static double clampToPercentRange(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 100.0) {
            return 100.0;
        }
        return value;
    }

    private static String toPreferencePercentString(double value) {
        double clamped = clampToPercentRange(value);
        if (Math.abs(clamped - Math.rint(clamped)) < 0.0001) {
            return Integer.toString((int) Math.rint(clamped));
        }
        return String.format(java.util.Locale.US, "%.2f", clamped).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * deleteModel(fname).
     * Deletes a model from the list of installed models and removes its local file if it exists.
     * @param fname - the filename of the model to delete
     */
    public void deleteModel(String fname) {
        JSONArray models = getInstalledModels();
        JSONArray newList = new JSONArray();
        for (int i = 0; i < models.length(); i++) {
            try {
                JSONObject m = models.getJSONObject(i);
                if (m.getString("fname").equals(fname)) {
                    String path = m.optString("localPath", "");
                    if (!path.isEmpty()) {
                        File f = new File(path);
                        if (f.exists()) f.delete();
                    }
                } else {
                    newList.put(m);
                }
            } catch (JSONException ignored) {}
        }
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLED_MODELS, newList.toString()).apply();
    }

    private JSONArray filterValidModels(JSONArray rawArr) {
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < rawArr.length(); i++) {
            try {
                JSONObject o = rawArr.getJSONObject(i);
                if (!o.optString("name", "").isEmpty() && !o.optString("fname", "").isEmpty()) {
                    filtered.put(o);
                }
            } catch (JSONException ignored) {}
        }
        return filtered;
    }

    /**
     * Checks if the device is compatible with the model based on its requirements.
     */
    public boolean isDeviceCompatible(JSONObject modelInfo) {
        // Framework specific checks
        String framework = modelInfo.optString("framework", "tflite");
        if ("pytorch".equalsIgnoreCase(framework)) {
            if (!is64Bit()) {
                Log.w(TAG, "Device is incompatible with model: PyTorch requires 64-bit mode.");
                return false;
            }
        }

        JSONArray requiredFeatures = modelInfo.optJSONArray("min_cpu_features");
        if (requiredFeatures == null || requiredFeatures.length() == 0) {
            return true; // Assume compatible if no specific features are required
        }

        for (int i = 0; i < requiredFeatures.length(); i++) {
            String feature = requiredFeatures.optString(i);
            if (!feature.isEmpty() && !hasCpuFeature(feature)) {
                Log.w(TAG, "Device is incompatible with model: missing CPU feature '" + feature + "'");
                return false;
            }
        }
        return true;
    }

    /**
     * Detects if the device is running in 64-bit mode.
     */
    public boolean is64Bit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.os.Process.is64Bit();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Check supported ABIs for any 64-bit architecture
            for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
                if (abi != null && !abi.isEmpty()) return true;
            }
        }
        return false;
    }

    private Set<String> mDetectedCpuFeatures = null;

    /**
     * Checks if a specific CPU feature is present by reading /proc/cpuinfo.
     */
    public boolean hasCpuFeature(String feature) {
        if (mDetectedCpuFeatures == null) {
            mDetectedCpuFeatures = detectCpuFeatures();
        }
        
        boolean found = mDetectedCpuFeatures.contains(feature.toLowerCase());
        
        // Handle common aliases
        if (!found && feature.equalsIgnoreCase("dotprod")) {
            found = mDetectedCpuFeatures.contains("asimddp");
        }
        
        return found;
    }

    private Set<String> detectCpuFeatures() {
        Set<String> featuresSet = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                // Match "Features : ..." line (case insensitive)
                if (trimmed.toLowerCase().startsWith("features")) {
                    int colonIndex = trimmed.indexOf(':');
                    if (colonIndex != -1) {
                        String featuresPart = trimmed.substring(colonIndex + 1).trim();
                        String[] parts = featuresPart.split("\\s+");
                        for (String f : parts) {
                            if (!f.isEmpty()) {
                                featuresSet.add(f.toLowerCase());
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Detected CPU Features: " + featuresSet.toString());
        } catch (IOException e) {
            Log.e(TAG, "Error reading /proc/cpuinfo: " + e.getMessage());
        }
        return featuresSet;
    }

    /**
     * loadAllActiveModels().
     * Loads all installed models that are compatible with the device.
     * For TensorFlow Lite models, it attempts to memory-map the model file for efficient loading.
     * For PyTorch models, it simply registers the model file without memory-mapping.
     * @return - a list of ModelLoadResult objects representing the loaded models
     */
    public List<ModelLoadResult> loadAllActiveModels() {
        List<ModelLoadResult> results = new ArrayList<>();
        JSONArray installed = getInstalledModels();

        for (int i = 0; i < installed.length(); i++) {
            try {
                JSONObject m = installed.getJSONObject(i);

                // Compatibility Check
                if (!isDeviceCompatible(m)) {
                    Log.w(TAG, "Skipping model " + m.optString("name") + " due to device incompatibility.");
                    continue;
                }

                String path = m.optString("localPath", "");
                String fname = m.optString("fname", "");
                String framework = m.optString("framework", "tflite");
                int inputFormat = m.optInt("input_format_val", 1); // fallback to 1
                int inputSize = m.optInt("input_size", 125);
                double threshold = m.optDouble("alarm_threshold", 2.0);

                if (path.isEmpty() && !fname.isEmpty()) {
                    path = new File(mContext.getFilesDir(), "models" + File.separator + fname).getAbsolutePath();
                }

                File f = new File(path);
                if (f.exists()) {
                    String modelName = m.optString("name", fname);
                    if ("tflite".equalsIgnoreCase(framework)) {
                        java.io.FileInputStream fis = null;
                        try {
                            fis = new java.io.FileInputStream(f);
                            java.nio.MappedByteBuffer buffer = fis.getChannel().map(
                                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, f.length());
                            results.add(new ModelLoadResult(modelName, framework, buffer, f, inputFormat, inputSize, threshold));
                        } catch (Exception mapEx) {
                            // In test environments (Robolectric) memory-mapping may not be supported.
                            // Fall back to adding the model with a null buffer so it is considered loaded.
                            Log.w(TAG, "Could not memory-map tflite file, falling back: " + mapEx.getMessage());
                            results.add(new ModelLoadResult(modelName, framework, null, f, inputFormat, inputSize, threshold));
                        } finally {
                            if (fis != null) try { fis.close(); } catch (Exception ignore) {}
                        }
                     } else {
                         // PyTorch/ExecuTorch
                         results.add(new ModelLoadResult(m.optString("name", fname), framework, null, f, inputFormat, inputSize, threshold));
                     }
                 }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load model: " + e.getMessage());
            }
        }

        return results;
    }

    /**
     * checkForModelUpdate()
     * Checks for available model updates.   Downloads the model index from the server and checks for a recommended model that is compatible with the device.
     * Calls the provided callback with the result: either onUpdateAvailable() with the recommended model info, or onNoUpdate() if no compatible recommended model is found.
     * @param prefs - the shared preferences
     * @param callback - the callback to notify of update status
     */
    public void checkForModelUpdate(android.content.SharedPreferences prefs, ModelUpdateCallback callback) {
        getMlModelIndex(arr -> {
            if (arr == null || arr.length() == 0) {
                callback.onNoUpdate();
                return;
            }
            try {
                JSONObject recommendedModel = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject model = arr.getJSONObject(i);
                    if (model.optBoolean("recommended", false)) {
                        // Only recommend if compatible
                        if (isDeviceCompatible(model)) {
                            recommendedModel = model;
                            break;
                        }
                    }
                }
                if (recommendedModel != null) {
                    callback.onUpdateAvailable(recommendedModel);
                } else {
                    callback.onNoUpdate();
                }
            } catch (JSONException e) {
                callback.onNoUpdate();
            }
        });
    }
}

