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

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


// This class manages machine learning models by downloading them from a remote server when necessary.
public class MlModelManager {
    protected Context mContext;
    protected OsdUtil mUtil;
    private String TAG = "MlModelManager";

    public boolean mServerConnectionOk = false;
    public boolean mModelReady = false;
    private final String mUrlBase = "https://openseizuredetector.org.uk/static/MLmodels/";
    private final String mModelIndexFname = "MLmodels.json";
    RequestQueue mQueue;

    public interface JSONObjectCallback {
        public void accept(JSONObject retValObj);
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
    public boolean getMlModelIndex(JSONObjectCallback callback) {
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
                            JSONObject retObj = new JSONObject(response);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG, "getMlModelIndex.onRespons(): Error: " + e.getMessage() + "," + e.toString(), e);
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

        mQueue.add(req);
        return (true);

    }

}
