package uk.org.openseizuredetector;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.android.volley.toolbox.RequestFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


// This class manages machine learning models by downloading them from a remote server when necessary.
// Design note:
//   - getMlModelIndex() uses Volley (async callback-based) for index retrieval
//   - downloadModel() uses background thread + raw HttpURLConnection for file downloads
//   Both approaches are compatible because:
//     1. Index fetch is lightweight, async callback pattern is convenient
//     2. File downloads are large, blocking on background thread is acceptable
//     3. Large file downloads over Volley would require custom handling anyway
//   Future: Could unify by creating a shared HttpHelper, but current approach is pragmatic
public class MlModelManager {
    protected Context mContext;
    protected OsdUtil mUtil;
    private final String TAG = "MlModelManager";
    private final String DEFAULT_BUNDLED_MODEL = "cnn_v0.24.tflite";

    public boolean mServerConnectionOk = false;
    public boolean mModelReady = false;
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

    public static class ModelLoadResult {
        public final String framework;
        public final java.nio.MappedByteBuffer tfliteBuffer;
        public final File file;
        public final boolean fromBundled;

        public ModelLoadResult(String framework, java.nio.MappedByteBuffer tfliteBuffer, File file, boolean fromBundled) {
            this.framework = framework;
            this.tfliteBuffer = tfliteBuffer;
            this.file = file;
            this.fromBundled = fromBundled;
        }
    }


    public MlModelManager(Context context) {
        Log.i(TAG, "MlModelManager Constructor");
        mContext = context;
        mUtil = new OsdUtil(mContext, new Handler());
        mQueue = Volley.newRequestQueue(mContext);
    }

    // Test-friendly constructor allowing URL and index filename injection
    MlModelManager(Context context, String urlBase, String indexFname) {
        Log.i(TAG, "MlModelManager Test Constructor");
        mContext = context;
        mUtil = new OsdUtil(mContext, new Handler());
        mQueue = Volley.newRequestQueue(mContext);
        if (urlBase != null) this.mUrlBase = urlBase;
        if (indexFname != null) this.mModelIndexFname = indexFname;
    }

    public void close() {
        Log.i(TAG, "close()");

        // Cancel any pending Volley requests
        if (mQueue != null) {
            mQueue.cancelAll(INDEX_TAG);
            mQueue.stop();
        }

        // Wait for background threads to complete with timeout
        waitForThreadsToComplete(10000); // 10 second timeout
    }

    /**
     * Wait for all active background threads to complete or timeout.
     * This ensures clean shutdown without orphaned threads.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    private void waitForThreadsToComplete(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        List<Thread> threadsToWait = new ArrayList<>();

        synchronized (mThreadLock) {
            threadsToWait.addAll(mActiveThreads);
        }

        for (Thread t : threadsToWait) {
            try {
                long elapsed = System.currentTimeMillis() - startTime;
                long remainingTime = timeoutMs - elapsed;

                if (remainingTime <= 0) {
                    Log.w(TAG, "Timeout waiting for threads to complete");
                    break;
                }

                Log.d(TAG, "Waiting for thread: " + t.getName());
                t.join(remainingTime);

                if (t.isAlive()) {
                    Log.w(TAG, "Thread did not complete within timeout: " + t.getName());
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for thread: " + t.getName());
                Thread.currentThread().interrupt();
            }
        }

        synchronized (mThreadLock) {
            mActiveThreads.clear();
        }
    }

    /**
     * Register a thread as active so it can be tracked for shutdown.
     */
    private void registerThread(Thread t) {
        synchronized (mThreadLock) {
            mActiveThreads.add(t);
        }
    }

    /**
     * Unregister a thread when it completes.
     */
    private void unregisterThread(Thread t) {
        synchronized (mThreadLock) {
            mActiveThreads.remove(t);
        }
    }


    /**
     * Retrieve the file containing the list of available ML models from the server.
     * Calls the specified callback function, passing a JSONObject as a parameter when the data has been received and parsed.
     *
     * @return true if request sent successfully or else false.
     */
    private final String INDEX_TAG = "MlModelIndex";

    public boolean getMlModelIndex(JSONArrayCallback callback) {
        Log.v(TAG, "getMlModelIndex()");
        String urlStr = mUrlBase + mModelIndexFname;
        Log.v(TAG, "urlStr=" + urlStr);

        StringRequest req = new StringRequest(Request.Method.GET, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "getMlModelIndex.onResponse(): Response is: " + response);
                        mServerConnectionOk = true;
                        try {
                            JSONArray rawArr = new JSONArray(response);
                            JSONArray filtered = filterValidModels(rawArr);
                            callback.accept(filtered.length() > 0 ? filtered : null);
                        } catch (JSONException e) {
                            Log.e(TAG, "getMlModelIndex.onResponse(): Error: " + e.getMessage() + "," + e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        mServerConnectionOk = false;
                        if (error != null) {
                            Log.e(TAG, "getMlModelIndex.onErrorResponse(): " + error.toString() + ", message:" + error.getMessage());
                        } else {
                            Log.e(TAG, "getMlModelIndex.onErrorResponse() - returned null response");
                        }
                        callback.accept(null);
                    }
                }) {
            // Note, this is overriding part of StringRequest, not one of the sub-classes above!
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                //params.put("Authorization", "Token " + getStoredToken());
                return params;
            }
        };
        req.setTag(INDEX_TAG);

        mQueue.add(req);
        return (true);

    }

    public void cancelIndexRequests() {
        if (mQueue != null) {
            mQueue.cancelAll(INDEX_TAG);
        }
    }

    public void downloadModel(String fname, AtomicBoolean cancelFlag, DownloadCallback callback) {
        Log.v(TAG, "downloadModel(): " + fname);
        String urlStr = mUrlBase + fname;

        // Use HttpURLConnection on background thread to download file directly to disk
        // This approach is preferred for large files (better than Volley which buffers in memory)
        Thread t = new Thread(() -> {
            File outFile = null;
            java.io.InputStream in = null;
            java.io.FileOutputStream fos = null;
            try {
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "downloadModel(): HTTP error " + conn.getResponseCode());
                    callback.onComplete(false, null);
                    return;
                }

                in = new java.io.BufferedInputStream(conn.getInputStream());
                File dir = new File(mContext.getFilesDir(), "models");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                outFile = new File(dir, fname);
                fos = new java.io.FileOutputStream(outFile);

                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        Log.i(TAG, "downloadModel(): cancelled");
                        if (outFile.exists()) {
                            outFile.delete();
                        }
                        callback.onComplete(false, null);
                        return;
                    }
                    fos.write(buf, 0, len);
                }
                fos.flush();
                Log.i(TAG, "downloadModel(): file downloaded to " + outFile.getAbsolutePath());
                callback.onComplete(true, outFile);

            } catch (Exception ex) {
                Log.e(TAG, "downloadModel() failed: " + ex.getMessage());
                if (outFile != null && outFile.exists()) {
                    outFile.delete();
                }
                callback.onComplete(false, null);
            } finally {
                try {
                    if (fos != null) fos.close();
                    if (in != null) in.close();
                } catch (Exception e) {
                    Log.w(TAG, "downloadModel(): error closing streams: " + e.getMessage());
                }
                unregisterThread(Thread.currentThread());
            }
        });
        t.setName("MlModelDownload-" + fname);
        registerThread(t);
        t.start();
    }

    /**
     * Load a model from disk (either downloaded or bundled).
     * First tries to load user-downloaded model from CnnModelFile preference,
     * then falls back to bundled model if not found.
     * Runs on a background thread and calls the callback with the loaded buffer.
     */
    public void loadModel(android.content.SharedPreferences prefs, ModelLoadCallback callback) {
        Log.v(TAG, "loadModel(): attempting to load model");
        Thread t = new Thread(() -> {
            java.nio.MappedByteBuffer modelBuffer = null;
            File modelFile = null;
            String framework = prefs.getString("CnnFramework", "tflite");
            boolean fromBundled = false;
            try {
                String userModelPath = prefs.getString("CnnModelFile", null);
                if (framework != null) {
                    framework = framework.toLowerCase();
                } else {
                    framework = "tflite";
                }

                if ("pytorch".equals(framework)) {
                    if (userModelPath != null) {
                        File userModel = new File(userModelPath);
                        if (userModel.exists()) {
                            Log.d(TAG, "loadModel(): Loading downloaded PyTorch model: " + userModelPath);
                            modelFile = userModel;
                        } else {
                            Log.w(TAG, "loadModel(): PyTorch model file not found: " + userModelPath);
                        }
                    }
                    if (modelFile == null) {
                        Log.e(TAG, "loadModel(): No PyTorch model available");
                        callback.onComplete(null);
                        return;
                    }
                    callback.onComplete(new ModelLoadResult(framework, null, modelFile, fromBundled));
                    return;
                }

                boolean loadedUserModel = false;
                if (userModelPath != null) {
                    File userModel = new File(userModelPath);
                    if (userModel.exists()) {
                        Log.d(TAG, "loadModel(): Loading downloaded model: " + userModelPath);
                        try {
                            java.io.FileInputStream fis = new java.io.FileInputStream(userModel);
                            java.nio.channels.FileChannel fileChannel = fis.getChannel();
                            long size = fileChannel.size();
                            modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size);
                            fileChannel.close();
                            fis.close();
                            loadedUserModel = true;
                            Log.d(TAG, "loadModel(): Downloaded model loaded successfully");
                        } catch (Exception e) {
                            Log.w(TAG, "loadModel(): Failed to load downloaded model: " + e.getMessage());
                        }
                    }
                }

                if (!loadedUserModel) {
                    Log.d(TAG, "loadModel(): Loading bundled model: " + DEFAULT_BUNDLED_MODEL);
                    modelBuffer = org.tensorflow.lite.support.common.FileUtil.loadMappedFile(mContext, DEFAULT_BUNDLED_MODEL);
                    fromBundled = true;
                }

                if (modelBuffer == null) {
                    Log.e(TAG, "loadModel(): Failed to load any model - modelBuffer is null");
                    callback.onComplete(null);
                } else {
                    Log.d(TAG, "loadModel(): Model loaded successfully");
                    callback.onComplete(new ModelLoadResult(framework, modelBuffer, null, fromBundled));
                }

            } catch (Exception e) {
                Log.e(TAG, "loadModel() failed: " + e.getMessage());
                callback.onComplete(null);
            } finally {
                unregisterThread(Thread.currentThread());
            }
        });
        t.setName("MlModelLoad");
        registerThread(t);
        t.start();
    }

    // Validate and filter models using new schema (framework, input_format string, input_size int)
    private JSONArray filterValidModels(JSONArray rawArr) {
        List<JSONObject> valid = new ArrayList<>();
        for (int i = 0; i < rawArr.length(); i++) {
            try {
                JSONObject o = rawArr.getJSONObject(i);
                String name = o.optString("name", "");
                String fname = o.optString("fname", "");
                String framework = o.optString("framework", "").toLowerCase();
                String inputFmt = o.optString("input_format", "");
                int inputSize = o.optInt("input_size", -1);
                if (name.isEmpty() || fname.isEmpty()) {
                    Log.w(TAG, "filterValidModels(): skipping entry missing name/fname at index " + i);
                    continue;
                }
                if (framework.isEmpty() || !(framework.equals("tflite") || framework.equals("pytorch"))) {
                    Log.w(TAG, "filterValidModels(): skipping entry with unsupported framework at index " + i + " framework=" + framework);
                    continue;
                }
                // Validate file extensions match framework
                if (framework.equals("pytorch") && !fname.endsWith(".ptl")) {
                    Log.w(TAG, "filterValidModels(): PyTorch model '" + name + "' should use .ptl extension (TorchScript Mobile format), found: " + fname);
                    // Don't skip, just warn - user may have named it differently
                }
                if (framework.equals("tflite") && !fname.endsWith(".tflite")) {
                    Log.w(TAG, "filterValidModels(): TFLite model '" + name + "' should use .tflite extension, found: " + fname);
                }
                if (inputFmt.isEmpty()) {
                    Log.w(TAG, "filterValidModels(): skipping entry with empty input_format at index " + i);
                    continue;
                }
                if (inputSize <= 0) {
                    Log.w(TAG, "filterValidModels(): skipping entry with invalid input_size at index " + i + " size=" + inputSize);
                    continue;
                }
                valid.add(o);
            } catch (JSONException e) {
                Log.w(TAG, "filterValidModels(): skipping malformed entry " + i + " err=" + e.getMessage());
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : valid) {
            out.put(o);
        }
        return out;
    }
}

