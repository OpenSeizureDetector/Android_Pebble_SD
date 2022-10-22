package uk.org.openseizuredetector;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.core.OrderBy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


// This class is intended to handle all interactions with the OSD WebAPI
public abstract class WebApiConnection {
    protected Context mContext;
    protected OsdUtil mUtil;
    private String TAG = "WebApiConnection";
    private String mAuthToken;


    public interface JSONObjectCallback {
        public void accept(JSONObject retValObj);
    }

    public interface StringCallback {
        public void accept(String retValStr);
    }

    public interface LongCallback {
        public void accept(Long retVal);
    }

    public WebApiConnection(Context context) {
        mContext = context;
        mUtil = new OsdUtil(mContext, new Handler());
    }

    public void close() {
        Log.i(TAG, "stop()");
    }

    public abstract boolean isLoggedIn();


    // Create a new event in the remote database, based on the provided parameters.
    // passes the newly created documentId to function callback on successful completion, or null on error.
    public abstract boolean createEvent(final int osdAlarmState, final Date eventDate, final String type, final String subType,
                                        final String eventDesc, final String dataJSON, StringCallback callback);

    // calls function callback with a JSONObject representation of the event with id 'eventId'
    public abstract boolean getEvent(String eventId, JSONObjectCallback callback);


    /**
     * Retrieve all events accessible to the logged in user, and pass them to the callback function as a JSONObject
     *
     * @param callback
     * @return true on success or false on failure to initiate the request.
     */
    public abstract boolean getEvents(JSONObjectCallback callback);

    public abstract boolean updateEvent(final JSONObject eventObj, JSONObjectCallback callback);

    public abstract boolean createDatapoint(JSONObject dataObj, String eventId, StringCallback callback);

    /**
     * Retrieve the file containing the standard event types from the server.
     * Calls the specified callback function, passing a JSONObject as a parameter when the data has been received and parsed.
     *
     * @return true if request sent successfully or else false.
     */
    public abstract boolean getEventTypes(JSONObjectCallback callback);

    public abstract boolean getCnnModelInfo(JSONObjectCallback callback);


    /**
     * Retrieve a trivial file from the server to check we have a good server connection.
     * sets mServerConnectionOk.
     *
     * @return true if request sent successfully or else false.
     */
    public abstract boolean checkServerConnection();

    public abstract boolean getUserProfile(JSONObjectCallback callback);


    public boolean authenticate(final String uname, final String passwd, StringCallback callback) {
        Log.e(TAG, "WebApiConnection.authenticate(username, password, callback) Not Implemented");
        return false;
    }

    // Remove the stored token so future calls are not authenticated.
    public void logout() {
        Log.v(TAG, "logout()");
        setStoredToken(null);
    }

    protected void setStoredToken(String authToken) {
        mAuthToken = authToken;
    }

    protected String getStoredToken() {
        return (mAuthToken);
    }


    /**
     * Mark all of the events with IDs contained in eventList as the specified type and subtype.
     * @param eventList list of String IDs of the events to mark as unknown.
     * @param typeStr
     * @param subTypeStr
     * @return true if request sent successfully or false.
     */
    private boolean markEventsAsTypeSubType(ArrayList<String>eventList, String typeStr, String subTypeStr) {
        if (eventList.size()>0) {
            Log.i(TAG,"markEventsAsTypeSubtype - eventList.size()="+eventList.size());
            Log.i(TAG,"markEventsAsSypeSubtype - eventList(0) = "+eventList.get(0));
            getEvent(eventList.get(0), new WebApiConnection.JSONObjectCallback() {
                @Override
                public void accept(JSONObject eventObj) {
                    Log.v(TAG, "markEventsAsTypeSubtype.getEvent.callback: "+eventObj);
                    if (eventObj != null) {
                        Log.v(TAG, "markEventsAsTypeSubtype.getEvent.callback:  eventObj=" + eventObj.toString());
                        try {
                            eventObj.put("type", typeStr);
                            eventObj.put("subType", subTypeStr);
                            String notesStr = eventObj.getString("desc");
                            if (notesStr == null) notesStr = new String("");
                            notesStr = notesStr + " bulk type/subtype set";
                            eventObj.put("desc", notesStr);
                            updateEvent(eventObj,new WebApiConnection.JSONObjectCallback() {
                                @Override
                                public void accept(JSONObject eventObj) {
                                    if (eventObj != null) {
                                        Log.i(TAG, "markEventsAsTypeSubtype.updateEvent.callback" + eventObj.toString());
                                        // Remove the first item from the list,then call this whole procedure again to modify the next one on the list.
                                        eventList.remove(0);
                                        markEventsAsTypeSubType(eventList, typeStr, subTypeStr);
                                    } else {
                                        Log.e(TAG, "markEventsAsTypeSubtype.updateEvent.callback - eventObj is null");
                                        mUtil.showToast("markEventsAsTypeSubtype.updateEvent.callback - eventObj is null");
                                    }
                                }
                            });
                        } catch (JSONException e) {
                            Log.e(TAG,"markEventsAsTypeSubtype.getEvent.callback: Error editing eventObj");
                            mUtil.showToast("markEventsAsTypeSubtype.getEvent.callback: Error editing eventObj");
                        }
                    } else {
                        mUtil.showToast("Failed to Retrieve Event from Remote Database");
                        return;
                    }
                }
            });
        } else {
            Log.i(TAG,"markEventsAsTypeSubtype(): No more events to Modify");
            mUtil.showToast("No more unvalidated events to modify.");

        }
        return(true);
    }

    /**
     * Mark all unverified events in the remote database as unknown
     *
     * @return true if request is successful or false.
     */
    public boolean markUnverifiedEventsAsTypeSubtype(String typeStr, String subTypeStr) {
        if (getEvents((JSONObject remoteEventsObj) -> {
            Log.v(TAG, "markUnverifiedEventsAsTypeSubtype.getEvents.Callback()");
            Boolean haveUnvalidatedEvent = false;
            if (remoteEventsObj == null) {
                Log.e(TAG, "markUnverifiedEventsAsTypeSubtype.getEvents.Callback:  Error Retrieving events");
            } else {
                try {
                    JSONArray eventsArray = remoteEventsObj.getJSONArray("events");
                    ArrayList<String> unvalidatedEventsList = new ArrayList<String>();
                    for (int i = eventsArray.length() - 1; i >= 0; i--) {
                        JSONObject eventObj = eventsArray.getJSONObject(i);
                        String currTypeStr = eventObj.getString("type");
                        if (currTypeStr.equals("null") || currTypeStr.equals("")) {
                            haveUnvalidatedEvent = true;
                            unvalidatedEventsList.add(eventObj.getString("id"));
                        }
                    }
                    Log.v(TAG, "markUnverifiedEventsAsTypeSubtype.getEvents.onFinish.callback - haveUnvalidatedEvent = " +
                            haveUnvalidatedEvent);
                    markEventsAsTypeSubType(unvalidatedEventsList, typeStr, subTypeStr);

                } catch (JSONException e) {
                    Log.e(TAG, "markUnverifiedEventsAsTypeSubtype.getEvents.onFinish(): Error Parsing remoteEventsObj: " + e.getMessage());
                    //mUtil.showToast("Error Parsing remoteEventsObj - this should not happen!!!");
                }
            }
        })) {
            Log.v(TAG, "markUnverifiedEventsAsTypeSubtype.getEvents - requested events");
        } else {
            Log.v(TAG, "markUnverifiedEventsAsTypeSubtype.getEvents - Not Logged In");

        }


        return (true);
    }

    public boolean markUnverifiedEventsAsUnknown() {
        markUnverifiedEventsAsTypeSubtype("Unknown", "");
        return true;
    }
    public boolean markUnverifiedEventsAsFalseAlarm() {
        markUnverifiedEventsAsTypeSubtype("False Alarm", "");
        return true;
    }
}
