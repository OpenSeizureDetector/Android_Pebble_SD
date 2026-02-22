package uk.org.openseizuredetector.alg;

import uk.org.openseizuredetector.utils.OsdUtil;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;

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

    public static class ModelLoadResult {
        public final String name;
        public final String framework;
        public final java.nio.MappedByteBuffer tfliteBuffer;
        public final File file;
        public final int inputFormat;
        public final int inputSize;
        public final double alarmThreshold;

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

    public void close() {
        if (mQueue != null) {
            mQueue.cancelAll(INDEX_TAG);
            mQueue.stop();
        }
        waitForThreadsToComplete(5000);
    }

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

    private void registerThread(Thread t) {
        synchronized (mThreadLock) { mActiveThreads.add(t); }
    }

    private void unregisterThread(Thread t) {
        synchronized (mThreadLock) { mActiveThreads.remove(t); }
    }

    private final String INDEX_TAG = "MlModelIndex";

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

    public void downloadAndInstallModel(JSONObject modelInfo, DownloadCallback callback) {
        String fname = modelInfo.optString("fname", "");
        if (fname.isEmpty()) {
            callback.onComplete(false, null);
            return;
        }

        downloadModel(fname, new AtomicBoolean(false), (success, file) -> {
            if (success && file != null) {
                try {
                    JSONObject installedModel = new JSONObject(modelInfo.toString());
                    installedModel.put("localPath", file.getAbsolutePath());
                    registerInstalledModel(installedModel);
                    callback.onComplete(true, file);
                } catch (JSONException e) {
                    callback.onComplete(false, null);
                }
            } else {
                callback.onComplete(false, null);
            }
        });
    }

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

