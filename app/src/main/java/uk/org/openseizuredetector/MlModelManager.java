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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


// This class manages machine learning models by downloading them from a remote server when necessary.
public class MlModelManager {
    protected Context mContext;
    protected OsdUtil mUtil;
    private String TAG = "MlModelManager";

    public boolean mServerConnectionOk = false;
    public boolean mModelReady = false;
    private final String mUrlBase = "https://osdapi.org.uk/static/ml_models/";
    private final String mModelIndexFname = "index.json";
    RequestQueue mQueue;

    public interface JSONArrayCallback {
        void accept(JSONArray retValArr);
    }

    public interface DownloadCallback {
        void onComplete(boolean success, File file);
    }


    public MlModelManager(Context context) {
        Log.i(TAG, "MlModelManager Constructor");
        mContext = context;
        mUtil = new OsdUtil(mContext, new Handler());
        mQueue = Volley.newRequestQueue(mContext);
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

    public Thread downloadModel(String fname, AtomicBoolean cancelFlag, DownloadCallback callback) {
        Thread t = new Thread(() -> {
            File outFile = null;
            try {
                URL url = new URL(mUrlBase + fname);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "downloadModel(): HTTP error " + conn.getResponseCode());
                    callback.onComplete(false, null);
                    return;
                }
                InputStream in = new BufferedInputStream(conn.getInputStream());
                File dir = new File(mContext.getFilesDir(), "models");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                outFile = new File(dir, fname);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        if (cancelFlag != null && cancelFlag.get()) {
                            Log.i(TAG, "downloadModel(): cancelled");
                            outFile.delete();
                            callback.onComplete(false, null);
                            return;
                        }
                        fos.write(buf, 0, len);
                    }
                }
                callback.onComplete(true, outFile);
            } catch (Exception ex) {
                Log.e(TAG, "downloadModel() failed: " + ex.toString());
                if (outFile != null && outFile.exists()) {
                    outFile.delete();
                }
                callback.onComplete(false, outFile);
            }
        });
        t.start();
        return t;
    }
}
