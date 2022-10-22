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


// This class is intended to handle all interactions with the OSD WebAPI
public class WebApiConnection_osdapi extends WebApiConnection {
    public String retVal;
    public int retCode;
    public boolean mServerConnectionOk = false;
    private String mUrlBase = "https://osdApi.ddns.net";
    private String TAG = "WebApiConnection_osdapi";
    RequestQueue mQueue;

    public WebApiConnection_osdapi(Context context) {
        super(context);
        mQueue = Volley.newRequestQueue(context);
    }

    public void close() {
        super.close();
        Log.i(TAG,"stop()");
        mQueue.stop();
    }

    /**
     * Attempt to authenticate with the web API using user name uname and password passwd.  Calls function callback with either
     * the authentication token on success or null on failure.
     *
     * @param uname    - user name
     * @param passwd   - password
     * @param callback - call back function callback(String retVal)
     * @return true if request sent, or false if failed to send request.
     */
    @Override
    public boolean authenticate(final String uname, final String passwd, StringCallback callback) {
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
                        String tokenStr = null;
                        Log.v(TAG, "Response is: " + response);
                        try {
                            JSONObject jo = new JSONObject(response);
                            tokenStr = jo.getString("token");
                            mServerConnectionOk = true;
                        } catch (JSONException e) {
                            tokenStr = "Error Parsing Rsponse";
                        }
                        setStoredToken(tokenStr);
                        callback.accept(tokenStr);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            Log.e(TAG, "Login Error: " + error.toString() + ", message:" + error.getMessage());
                        } else {
                            Log.e(TAG, "Login Error:  Returned null response");
                        }
                        mServerConnectionOk = false;
                        setStoredToken(null);
                        callback.accept(null);
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




    public boolean isLoggedIn() {
        String authToken = getStoredToken();
        Log.v(TAG, "isLoggedIn(): token=" + authToken);
        if (authToken == null || authToken.length() == 0) {
            Log.v(TAG, "isLogged in - not logged in");
            return (false);
        } else {
            Log.v(TAG,"isLoggedIn - logged in ok");
            return (true);
        }

    }


    // Create a new event in the remote database, based on the provided parameters.
    public boolean createEvent(final int osdAlarmState, final Date eventDate, final String type, final String subType,
                               final String eventDesc, final String dataJSON, StringCallback callback) {
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
            jsonObject.put("type", type);
            jsonObject.put("subType", subType);
            jsonObject.put("desc", eventDesc);
            jsonObject.put("dataJSON", dataJSON);
        } catch (JSONException e) {
            Log.e(TAG, "Error generating event JSON string");
        }
        final String dataStr = jsonObject.toString();
        Log.v(TAG, "createEvent - data=" + dataStr);

        StringRequest req = new StringRequest(Request.Method.POST, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "createEvent.onResponse - Response is: " + response);
                        mServerConnectionOk = true;
                        // we return just the eventId to be consistent with the firebase version of WebApiConnection.
                        String retVal = null;
                        try {
                            JSONObject retObj = new JSONObject(response);
                            retVal = retObj.getString("id");
                        } catch (JSONException e) {
                            Log.e(TAG, "createEvent.onResponse(): Error: " + e.getMessage() + "," + e.toString());
                            retVal = null;
                        }
                        callback.accept(retVal);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        mServerConnectionOk = false;
                        if (error != null) {
                            Log.e(TAG, "createEvent Error: " + error.toString() + ", message:" + error.getMessage());
                            callback.accept(null);
                        } else {
                            Log.e(TAG, "createEvent Error - null response");
                            callback.accept(null);
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

    public boolean getEvent(String eventId, JSONObjectCallback callback) {
        Log.v(TAG, "getEvent()");
        String urlStr = mUrlBase + "/api/events/" + eventId;
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
                            retObj.put("alarmStateStr", mUtil.alarmStatusToString(retObj.getInt("osdAlarmState")));
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG, "getEventTypes.onRespons(): Error: " + e.getMessage() + "," + e.toString());
                            callback.accept(null);
                        }
                        mServerConnectionOk = true;
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            Log.e(TAG, "Create Event Error: " + error.toString() + ", message:" + error.getMessage());
                        } else {
                            Log.e(TAG, "Create Event Error: returned null response");
                        }
                        mServerConnectionOk = false;
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
     *
     * @param callback
     * @return true on success or false on failure to initiate the request.
     */
    public boolean getEvents(JSONObjectCallback callback) {
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
                        mServerConnectionOk = true;
                        try {
                            JSONObject retObj = new JSONObject();
                            JSONArray eventArray = new JSONArray(response);
                            retObj.put("events", eventArray);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG, "getEventTypes.onRespons(): Error: " + e.getMessage() + "," + e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        //if ((error != null) && (error.networkResponse != null) && (error.networkResponse.data != null)) {#
                        mServerConnectionOk = false;
                        if (error != null) {
                            if (error.networkResponse != null) {
                                Log.e(TAG, "getEvents(): Error: " + error.toString() + ", message:" + error.getMessage());
                            } else {
                                Log.e(TAG, "getEvents(): Error: - request returned null networkResponse");
                            }
                        } else{
                            Log.e(TAG, "getEvents(): Error: - request returned null response");
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


    public boolean updateEvent(final JSONObject eventObj, JSONObjectCallback callback) {
        String eventId;
        Log.v(TAG, "updateEvent()");
        final String authtoken = getStoredToken();

        if (!isLoggedIn()) {
            Log.v(TAG, "not logged in - doing nothing");
            return (false);
        }
        try {
            eventId = eventObj.getString("id");
        } catch (JSONException e) {
            Log.e(TAG, "updateEvent(): Error reading id from eventObj");
            eventId = null;
        }
        final String dataStr = eventObj.toString();
        Log.v(TAG, "updateEvent - data=" + dataStr);

        int reqMethod;
        String urlStr;
        if (eventId != null) {
            Log.v(TAG, "updateEvent() - found eventId " + eventId + ", Updating event record");
            urlStr = mUrlBase + "/api/events/" + eventId + "/";
            Log.v(TAG, "urlStr=" + urlStr);
            reqMethod = Request.Method.PUT;
        } else {
            Log.v(TAG, "updateEvent() - eventId not found - creating new event record");
            urlStr = mUrlBase + "/api/events/";
            Log.v(TAG, "urlStr=" + urlStr);
            reqMethod = Request.Method.POST;
        }

        StringRequest req = new StringRequest(reqMethod, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "Response is: " + response);
                        mServerConnectionOk = true;
                        try {
                            JSONObject retObj = new JSONObject(response);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG, "getEventTypes.onRespons(): Error: " + e.getMessage() + "," + e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        mServerConnectionOk = false;
                        if (error != null) {
                            Log.e(TAG, "Create Event Error: " + error.toString() + ", message:" + error.getMessage());
                        } else {
                            Log.e(TAG, "Create Event Error - returned null response");
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


    public boolean createDatapoint(JSONObject dataObj, String eventId, StringCallback callback) {
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
                        mServerConnectionOk = true;
                        callback.accept(response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        mServerConnectionOk = false;
                        if (error != null) {
                            Log.e(TAG, "Create Datapoint Error: " + error.toString() + ", message:" + error.getMessage());
                            callback.accept(null);
                        } else {
                            Log.e(TAG, "Create Datapoint Error - returned null respones");
                            callback.accept(null);
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

    /**
     * Retieve the user profile of the authenticated user from the server, and return it to the callback function.
     * @param callback - function to be called with a JSONObject as a parameter that contains the user profile data.
     * @return true if request sent successfully, or else false.
     */
    public boolean getUserProfile(JSONObjectCallback callback) {
        Log.v(TAG, "getUserProfile()");
        String urlStr = mUrlBase + "/api/accounts/profile/";
        Log.v(TAG, "getUserProfile(): urlStr=" + urlStr);
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
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG, "getUserProfile.onResponse(): Error: " + e.getMessage() + "," + e.toString());
                            callback.accept(null);
                        }
                        mServerConnectionOk = true;
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (error != null) {
                            Log.e(TAG, "Create Event Error: " + error.toString() + ", message:" + error.getMessage());
                        } else {
                            Log.e(TAG, "Create Event Error: returned null response");
                        }
                        mServerConnectionOk = false;
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
     * Retrieve the file containing the standard event types from the server.
     * Calls the specified callback function, passing a JSONObject as a parameter when the data has been received and parsed.
     *
     * @return true if request sent successfully or else false.
     */
    public boolean getEventTypes(JSONObjectCallback callback) {
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
                        mServerConnectionOk = true;
                        try {
                            JSONObject retObj = new JSONObject(response);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG, "getEventTypes.onRespons(): Error: " + e.getMessage() + "," + e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        mServerConnectionOk = false;
                        if (error != null) {
                            Log.e(TAG, "getEventTypes.onErrorResponse(): " + error.toString() + ", message:" + error.getMessage());
                        } else {
                            Log.e(TAG, "getEventTypes.onErrorResponse() - returned null response");
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

    /**
     * Retrieve the file containing the CNN Models information from the server.
     * Calls the specified callback function, passing a JSONObject as a parameter when the data has been received and parsed.
     *
     * @return true if request sent successfully or else false.
     */
    public boolean getCNNModelInfo(JSONObjectCallback callback) {
        Log.v(TAG, "getCNNModelInfo()");
        String urlStr = mUrlBase + "/static/cnnModelInfo.json";
        Log.v(TAG, "urlStr=" + urlStr);
        final String authtoken = getStoredToken();

        StringRequest req = new StringRequest(Request.Method.GET, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "getCNNModelInfo.onResponse(): Response is: " + response);
                        mServerConnectionOk = true;
                        try {
                            JSONObject retObj = new JSONObject(response);
                            callback.accept(retObj);
                        } catch (JSONException e) {
                            Log.e(TAG, "getCNNModelInfo.onRespons(): Error: " + e.getMessage() + "," + e.toString());
                            callback.accept(null);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        mServerConnectionOk = false;
                        if (error != null) {
                            Log.e(TAG, "getCNNModelInfo.onErrorResponse(): " + error.toString() + ", message:" + error.getMessage());
                        } else {
                            Log.e(TAG, "getCNNModelInfo.onErrorResponse() - returned null response");
                        }
                        callback.accept(null);
                    }
                }) {
        };

        mQueue.add(req);
        return (true);

    }




    /**
     * Retrieve a trivial file from the server to check we have a good server connection.
     *  sets mServerConnectionOk.
     * @return true if request sent successfully or else false.
     */
    public boolean checkServerConnection() {
        Log.v(TAG, "checkServerConnection()");
        String urlStr = mUrlBase + "/static/test.txt";
        Log.v(TAG, "urlStr=" + urlStr);

        StringRequest req = new StringRequest(Request.Method.GET, urlStr,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.v(TAG, "checkServerConnection.onResponse(): Response is: " + response);
                        mServerConnectionOk = true;
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.v(TAG, "checkServerConnection.onErrorResponse");
                        mServerConnectionOk = false;
                    }
                });

        mQueue.add(req);
        return (true);

    }

}
