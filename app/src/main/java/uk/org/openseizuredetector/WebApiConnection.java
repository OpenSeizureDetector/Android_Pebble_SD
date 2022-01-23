package uk.org.openseizuredetector;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.text.RelativeDateTimeFormatter;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;


import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;


// This class is intended to handle all interactions with the OSD WebAPI
public class WebApiConnection {
    public String retVal;
    public int retCode;
    private String mUrlBase = "https://osdApi.ddns.net";
    private String TAG = "WebApiConnection";
    private AuthCallbackInterface mAuthCallback;
    private EventCallbackInterface mEventCallback;
    private DatapointCallbackInterface mDatapointCallback;
    private Context mContext;
    private String TOKEN_ID = "webApiAuthToken";
    private OsdUtil mUtil;
    RequestQueue mQueue;

    public WebApiConnection(Context context, AuthCallbackInterface authCallback, EventCallbackInterface eventCallback,
                            DatapointCallbackInterface datapointCallback) {
        mContext = context;
        mAuthCallback = authCallback;
        mEventCallback = eventCallback;
        mDatapointCallback = datapointCallback;
        mQueue = Volley.newRequestQueue(context);
        mUtil = new OsdUtil(mContext, new Handler());
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
                        saveStoredToken(tokenStr);
                        mAuthCallback.authCallback(true, response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            String responseBody = new String(error.networkResponse.data);
                            Log.e(TAG, "Login Error: " + error.toString() + ", message:" + error.getMessage() + ", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                        } else {
                            Log.e(TAG,"Login Error:  Returned null response");
                        }
                        saveStoredToken(null);
                        mAuthCallback.authCallback(false, new String(error.networkResponse.data));
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

    // Remove the stored token so future calls are not authenticated.
    public void logout() {
        Log.v(TAG, "logout()");
        saveStoredToken(null);
    }


    private void saveStoredToken(String tokenStr) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        prefs.edit().putString(TOKEN_ID, tokenStr).commit();

    }

    public String getStoredToken() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String authToken = prefs.getString(TOKEN_ID, null);
        return authToken;
    }

    public boolean isLoggedIn() {
        String authToken = getStoredToken();
        //Log.v(TAG, "isLoggedIn(): token=" + authToken);
        if (authToken == null || authToken.length() == 0) {
            //Log.v(TAG, "isLogged in - not logged in");
            return (false);
        } else {
            return (true);
        }

    }


    // Create a new event in the remote database, based on the provided parameters.
    public boolean createEvent(final int osdAlarmState, final Date eventDate, final String eventDesc) {
        Log.v(TAG, "createEvent()");
        String urlStr = mUrlBase + "/api/events/";
        Log.v(TAG, "urlStr=" + urlStr);
        final String authtoken = getStoredToken();

        if (!isLoggedIn()) {
            Log.v(TAG, "not logged in - doing nothing");
            return (false);
        }
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("osdAlarmState", String.valueOf(osdAlarmState));
            jsonObject.put("dataTime", dateFormat.format(eventDate));
            jsonObject.put("desc", eventDesc);
        } catch (JSONException e) {
            Log.e(TAG, "Error generating event JSON string");
        }
        final String dataStr = jsonObject.toString();
        Log.v(TAG, "createEvent - data=" + dataStr);

        StringRequest req = new StringRequest(Request.Method.POST, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "Response is: " + response);
                        mEventCallback.eventCallback(true, response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            String responseBody = new String(error.networkResponse.data);
                            Log.e(TAG, "Create Event Error: " + error.toString() + ", message:" + error.getMessage() + ", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                            mEventCallback.eventCallback(false, new String(error.networkResponse.data));
                        } else {
                            Log.e(TAG,"Create Event Error - null respones");
                            mEventCallback.eventCallback(false, null);
                        }
                    }
                }) {
            // Note, this is overriding part of StringRequest, not one of the sub-classes above!
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                // params.put("name",sname); // passing parameters to server
                String authToken = getStoredToken();
                params.put("Authorization: Token " + authToken, authToken);
                Log.v(TAG, "getParams: params=" + params.toString());
                return params;
            }

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                params.put("Authorization", "Token " + getStoredToken());
                return params;
            }

            @Override
            public byte[] getBody() throws AuthFailureError {
                try {
                    return dataStr == null ? null : dataStr.getBytes("utf-8");
                } catch (UnsupportedEncodingException uee) {
                    VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s", dataStr, "utf-8");
                    return null;
                }
            }
        };

        mQueue.add(req);
        return (true);
    }

    public boolean getEvent(Long eventId, Consumer<JSONObject> callback) {
        //Long eventId=Long.valueOf(285);
        Log.v(TAG, "getEvent()");
        String urlStr = mUrlBase + "/api/events/"+eventId;
        Log.v(TAG, "getEvent(): urlStr=" + urlStr);
        final String authtoken = getStoredToken();

        if (!isLoggedIn()) {
            Log.v(TAG, "not logged in - doing nothing");
            return (false);
        }

        StringRequest req = new StringRequest(Request.Method.GET, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "Response is: " + response);
                        try {
                            JSONObject retObj = new JSONObject(response);
                            retObj.put("alarmStateStr",mUtil.alarmStatusToString(retObj.getInt("osdAlarmState")));
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG,"getEventTypes.onRespons(): Error: "+e.getMessage()+","+e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            String responseBody = new String(error.networkResponse.data);
                            Log.e(TAG, "Create Event Error: " + error.toString() + ", message:" + error.getMessage() + ", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                        } else {
                            Log.e(TAG, "Create Event Error: returned null response");
                        }
                        callback.accept(null);
                    }
                }) {

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                params.put("Authorization", "Token " + getStoredToken());
                return params;
            }
        };
        mQueue.add(req);
        return (true);
    }

    /**
     * Retrieve all events accessible to the logged in user, and pass them to the callback function as a JSONObject
     * @param callback
     * @return true on success or false on failure to initiate the request.
     */
    public boolean getEvents(Consumer<JSONObject> callback) {
        //Long eventId=Long.valueOf(285);
        Log.v(TAG, "getEvents()");
        String urlStr = mUrlBase + "/api/events/";
        Log.v(TAG, "getEvents(): urlStr=" + urlStr);
        final String authtoken = getStoredToken();

        if (!isLoggedIn()) {
            Log.v(TAG, "not logged in - doing nothing");
            return (false);
        }

        StringRequest req = new StringRequest(Request.Method.GET, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "Response is: " + response);
                        try {
                            JSONObject retObj = new JSONObject();
                            JSONArray eventArray = new JSONArray(response);
                            retObj.put("events", eventArray);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG,"getEventTypes.onRespons(): Error: "+e.getMessage()+","+e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            String responseBody = new String(error.networkResponse.data);
                            Log.e(TAG, "getEvents(): Error: " + error.toString() + ", message:" + error.getMessage() + ", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                        } else {
                            Log.e(TAG,"getEvents(): Error: - request returned null response");
                        }
                        callback.accept(null);
                    }
                }) {

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                params.put("Authorization", "Token " + getStoredToken());
                return params;
            }
        };
        mQueue.add(req);
        return (true);
    }


    public boolean updateEvent(final JSONObject eventObj, Consumer<JSONObject> callback) {
        Long eventId;
        Log.v(TAG, "updateEvent()");
        final String authtoken = getStoredToken();

        if (!isLoggedIn()) {
            Log.v(TAG, "not logged in - doing nothing");
            return (false);
        }
        try {
            eventId = eventObj.getLong("id");
        } catch (JSONException e) {
            Log.e(TAG, "updateEvent(): Error reading id from eventObj");
            eventId= Long.valueOf(-1);
        }
        final String dataStr = eventObj.toString();
        Log.v(TAG, "createEvent - data=" + dataStr);


        int reqMethod;
        String urlStr;
        if (eventId!=-1) {
            Log.v(TAG,"updateEvent() - found eventId "+eventId+", Updating event record");urlStr = mUrlBase + "/api/events/"+eventId+"/";
            Log.v(TAG, "urlStr=" + urlStr);
            reqMethod = Request.Method.PUT;
        } else {
            Log.v(TAG,"updateEvent() - eventId not found - creating new event record");
            urlStr = mUrlBase + "/api/events/";
            Log.v(TAG, "urlStr=" + urlStr);
            reqMethod = Request.Method.POST;
        }

        StringRequest req = new StringRequest(reqMethod, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "Response is: " + response);
                        try {
                            JSONObject retObj = new JSONObject(response);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG,"getEventTypes.onRespons(): Error: "+e.getMessage()+","+e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            String responseBody = new String(error.networkResponse.data);
                            Log.e(TAG, "Create Event Error: " + error.toString() + ", message:" + error.getMessage() + ", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                        } else {
                            Log.e(TAG,"Create Event Error - returned null response");
                        }
                        callback.accept(null);
                    }
                }) {
            // Note, this is overriding part of StringRequest, not one of the sub-classes above!
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                // params.put("name",sname); // passing parameters to server
                String authToken = getStoredToken();
                params.put("Authorization: Token " + authToken, authToken);
                Log.v(TAG, "getParams: params=" + params.toString());
                //params.put("eventType", String.valueOf(eventType));
                //params.put("dataTime", dateFormat.format(eventDate));
                //params.put("desc", eventDesc);
                return params;
            }

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                params.put("Authorization", "Token " + getStoredToken());
                return params;
            }

            @Override
            public byte[] getBody() throws AuthFailureError {
                try {
                    return dataStr == null ? null : dataStr.getBytes("utf-8");
                } catch (UnsupportedEncodingException uee) {
                    VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s", dataStr, "utf-8");
                    return null;
                }
            }
        };

        mQueue.add(req);
        return (true);
    }




    public boolean createDatapoint(JSONObject dataObj, int eventId) {
        Log.v(TAG, "createDatapoint()");
        // Create a new event in the remote database, based on the provided parameters.
        String urlStr = mUrlBase + "/api/datapoints/";
        Log.v(TAG, "urlStr=" + urlStr);
        final String authtoken = getStoredToken();

        if (!isLoggedIn()) {
            Log.v(TAG, "not logged in - doing nothing");
            return (false);
        }

        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        JSONObject jsonObject = new JSONObject();
        try {
            //jsonObject.put("userId", -1);
            jsonObject.put("eventId", String.valueOf(eventId));
            jsonObject.put("dataTime", dataObj.getString("dataTime"));
            jsonObject.put("dataJSON", dataObj.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error generating event JSON string");
        }
        final String dataStr = jsonObject.toString();
        Log.v(TAG, "createDatapoint - dataStr=" + dataStr);


        StringRequest req = new StringRequest(Request.Method.POST, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "Response is: " + response);
                        mDatapointCallback.datapointCallback(true, response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            // Fixme = are we sure that networResponse.data is not null???
                            String responseBody = new String(error.networkResponse.data);
                            Log.e(TAG, "Create Datapoint Error: " + error.toString() + ", message:" + error.getMessage() + ", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                            mDatapointCallback.datapointCallback(false, new String(error.networkResponse.data));
                        } else {
                            Log.e(TAG,"Create Datapoint Error - returned null respones");
                            mDatapointCallback.datapointCallback(false, null);
                        }
                    }
                }) {
            // Note, this is overriding part of StringRequest, not one of the sub-classes above!
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                // params.put("name",sname); // passing parameters to server
                String authToken = getStoredToken();
                params.put("Authorization: Token " + authToken, authToken);
                Log.v(TAG, "getParams: params=" + params.toString());
                //params.put("eventType", String.valueOf(eventType));
                //params.put("dataTime", dateFormat.format(eventDate));
                //params.put("desc", eventDesc);
                return params;
            }

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                params.put("Authorization", "Token " + getStoredToken());
                return params;
            }

            @Override
            public byte[] getBody() throws AuthFailureError {
                try {
                    return dataStr == null ? null : dataStr.getBytes("utf-8");
                } catch (UnsupportedEncodingException uee) {
                    VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s", dataStr, "utf-8");
                    return null;
                }
            }
        };

        mQueue.add(req);
        return (true);

    }

    /**
     * Retrieve the file containing the standard event types from the server.
     * Calls the specified callback function, passing a JSONObject as a parameter when the data has been received and parsed.
     * Note it uses a Consumer callback function to avoid having to create another interface
     *    - see https://medium.com/@pra4mesh/callback-function-in-java-20fa48b27797
     * @return true if request sent successfully or else false.
     */
    public boolean getEventTypes(Consumer<JSONObject> callback) {
        Log.v(TAG, "getEventTypes()");
        String urlStr = mUrlBase + "/static/eventTypes.json";
        Log.v(TAG, "urlStr=" + urlStr);
        final String authtoken = getStoredToken();

        if (!isLoggedIn()) {
            Log.v(TAG, "not logged in - doing nothing");
            return (false);
        }

        StringRequest req = new StringRequest(Request.Method.GET, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "getEventTypes.onResponse(): Response is: " + response);
                        try {
                            JSONObject retObj = new JSONObject(response);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG,"getEventTypes.onRespons(): Error: "+e.getMessage()+","+e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            String responseBody = new String(error.networkResponse.data);
                            Log.e(TAG, "getEventTypes.onErrorResponse(): " + error.toString() + ", message:" + error.getMessage() + ", Response Code:" + error.networkResponse.statusCode + ", Response: " + responseBody);
                        } else {
                            Log.e(TAG,"getEventTypes.onErrorResponse() - returned null response");
                        }
                        callback.accept(null);
                    }
                }) {
            // Note, this is overriding part of StringRequest, not one of the sub-classes above!
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<String, String>();
                params.put("Content-Type", "application/json; charset=UTF-8");
                params.put("Authorization", "Token " + getStoredToken());
                return params;
            }
        };

        mQueue.add(req);
        return (true);

    }


}
