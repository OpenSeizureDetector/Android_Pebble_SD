package uk.org.openseizuredetector;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.text.RelativeDateTimeFormatter;
import android.preference.PreferenceManager;
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

import java.util.HashMap;
import java.util.Map;


// This class is intended to handle all interactions with the OSD WebAPI
public class WebApiConnection {
    public String retVal;
    public int retCode;
    private String mUrlBase = "https://osdApi.ddns.net";
    private String TAG = "WebApiConnection";
    private AuthCallbackInterface mAuthCallback;
    private Context mContext;
    RequestQueue mQueue;

    public WebApiConnection(Context context, AuthCallbackInterface authCallback) {
        mContext = context;
        mAuthCallback = authCallback;
        mQueue = Volley.newRequestQueue(context);
    }

    public boolean authenticate(final String uname, final String passwd) {
        // NOTE:  the 'final' keyword is necessary for uname and passwd to be accessible to getParams below - I don't know why!
        // We know that this command works, so we just need the Java equivalent:
        // curl -X POST -d 'login=graham4&password=testpwd1' https://osdapi.ddns.net/api/accounts/login/
        // sending the credentials as a JSONObject postData did not work, so try the method from:
        //    https://protocoderspoint.com/login-and-registration-form-in-android-using-volley-keeping-user-logged-in/#Login_Registration_form_in_android_using_volley_library
        String urlStr = mUrlBase + "/api/accounts/login/";
        Log.v(TAG, "urlStr=" + urlStr);

        StringRequest req = new StringRequest(Request.Method.POST, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        String tokenStr;
                        Log.v(TAG, "Response is: " + response);
                        try {
                            JSONObject jo = new JSONObject(response);
                            tokenStr = jo.getString("token");
                        } catch (JSONException e) {
                            tokenStr = "Error Parsing Rsponse";
                        }
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
                        prefs.edit().putString("webApiAuthToken", tokenStr).commit();
                        mAuthCallback.authCallback(true, response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        String responseBody = new String(error.networkResponse.data);
                        Log.v(TAG, "Login Error: " + error.toString() + ", message:" + error.getMessage()+", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
                        prefs.edit().putString("webApiAuthToken", null).commit();
                        mAuthCallback.authCallback(false,new String(error.networkResponse.data));
                    }
                }) {
            // Note, this is overriding part of StringRequest, not one of the sub-classes above!
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                // params.put("name",sname); // passing parameters to server
                params.put("login", uname);
                params.put("password", passwd);
                return params;
            }
        };

        mQueue.add(req);
        return (true);
    }

    // Remove the stored token so future calls are not authemticated.
    public void logout() {
        Log.v(TAG, "logout()");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        prefs.edit().putString("webApiAuthToken", null).commit();
    }


    // Create a new event in the remote database, based on the provided parameters.
    public boolean createEvent() {
        Log.v(TAG,"createEvent() - FIXME - This does not do anything!");

    }

    public boolean createDatapoint() {
        Log.v(TAG,"createDatapoint() - FIXME - This does not do anything!");

    }

}
