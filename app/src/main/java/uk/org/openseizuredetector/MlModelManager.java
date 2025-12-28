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
import java.util.HashMap;
import java.util.Map;
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

    public interface JSONArrayCallback {
        void accept(JSONArray retValArr);
    }

    public interface DownloadCallback {
        void onComplete(boolean success, File file);
    }

    public interface ModelLoadCallback {
        void onComplete(java.nio.MappedByteBuffer buffer);
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
        mQueue.stop();
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
                            JSONArray retObj = new JSONArray(response);
                            callback.accept(retObj);
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
        }
        });
        t.setName("MlModelDownload-" + fname);
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
            try {
                String userModelPath = prefs.getString("CnnModelFile", null);
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
                }

                if (modelBuffer == null) {
                    Log.e(TAG, "loadModel(): Failed to load any model - modelBuffer is null");
                } else {
                    Log.d(TAG, "loadModel(): Model loaded successfully");
                }
                callback.onComplete(modelBuffer);

            } catch (Exception e) {
                Log.e(TAG, "loadModel() failed: " + e.getMessage());
                callback.onComplete(null);
            }
        });
        t.setName("MlModelLoad");
        t.start();
    }
}

