package uk.org.openseizuredetector;

import android.content.Context;
import android.util.Log;


import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

// This class is intended to handle all interactions with the OSD WebAPI
public class WebApiConnection {
    public String retVal;
    public int retCode;
    private String mUrlBase = "https://osdApi.ddns.net";
    private String TAG = "WebApiConnection";
    RequestQueue mQueue;

    public WebApiConnection(Context context) {
        mQueue = Volley.newRequestQueue(context);
    }

    public boolean authenticate(String uname, String passwd) {
        // We know that this command works, so we just need the Java equivalent:
        // curl -X POST -d 'login=graham4&password=testpwd1' https://osdapi.ddns.net/api/accounts/login/
        String urlStr = mUrlBase + "/api/accounts/login/";
        Log.v(TAG, "urlStr=" + urlStr);

        JSONObject postData = new JSONObject();
        try {
            postData.put("login", uname);
            postData.put("password", passwd);

        } catch (JSONException e) {
            Log.e(TAG,e.getMessage());
        }

        Log.v(TAG,postData.toString());
        JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, urlStr, postData,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        // Display the first 500 characters of the response string.
                        Log.v(TAG, "Response is: " + response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        String responseBody = new String(error.networkResponse.data);
                        Log.v(TAG, "Login Error" + error.toString()+","+error.getMessage()+error.networkResponse.statusCode+","+responseBody);
                    }
                }
        );
        mQueue.add(req);
        return (true);
    }

}
