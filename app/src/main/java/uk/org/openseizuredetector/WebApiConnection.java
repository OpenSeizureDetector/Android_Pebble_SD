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
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


// This class is intended to handle all interactions with the OSD WebAPI
public class WebApiConnection {
    public String retVal;
    public int retCode;
    public boolean mServerConnectionOk = false;
    private String mUrlBase = "https://osdApi.ddns.net";
    private String TAG = "WebApiConnection";
    private String mAuthToken;
    private Context mContext;
    private OsdUtil mUtil;
    FirebaseFirestore mDb;

    RequestQueue mQueue;

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
        mQueue = Volley.newRequestQueue(context);
        mUtil = new OsdUtil(mContext, new Handler());
        loginToFirebase();
    }

    public void loginToFirebase() {
        // Check if we are already logged in
        FirebaseAuth auth = FirebaseAuth.getInstance();
        mDb = FirebaseFirestore.getInstance();
        if (auth != null) {
            if (auth.getCurrentUser() != null) {
                Log.i(TAG, "Firebase Logged in OK -" + auth.getCurrentUser().getDisplayName());
            } else {
                Log.e(TAG, "Firebase not logged in - no current user");
            }
        } else {
            Log.e(TAG, "Firebase not logged in");
        }
    }

    public void close() {
        Log.i(TAG, "stop()");
        mQueue.stop();
    }

    public boolean isLoggedIn() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth != null) {
            if (auth.getCurrentUser() != null) {
                //Log.v(TAG, "isLoggedIn(): Firebase Logged in OK");
                return (true);
            } else {
                //Log.v(TAG, "isLoggedIn(): Current user is null - Firebase not logged in");
                return (false);
            }
        } else {
            //Log.v(TAG, "isLoggedIn(): Firebase not logged in");
            return (false);
        }
    }

    public String getStoredToken() {
        return null;
    }

    public void setStoredToken(String s) {
        return;
    }


    // Create a new event in the remote database, based on the provided parameters.
    // passes the newly created documentId to function callback on successful completion, or null on error.
    public boolean createEvent(final int osdAlarmState, final Date eventDate, final String eventDesc, StringCallback callback) {
        Log.v(TAG, "createEvent()");
        String userId = null;

        if (mDb == null) {
            Log.w(TAG, "createEvent() - mDb is null - not doing anything");
            return false;
        }

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.e(TAG, "ERROR: createEvent() - not logged in");
            return false;
        } else {
            userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }
        Map<String, Object> event = new HashMap<>();
        event.put("dataTime", eventDate.getTime());
        event.put("osdAlarmState", osdAlarmState);
        event.put("desc", eventDesc);
        event.put("type", null);
        event.put("subType", null);
        event.put("userId", userId);

        mDb.collection("Events")
                .add(event)
                .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                    @Override
                    public void onSuccess(DocumentReference documentReference) {
                        Log.d(TAG, "createEvent.onSuccess() - DocumentSnapshot added with ID: " + documentReference.getId());
                        mServerConnectionOk = true;
                        callback.accept(documentReference.getId());
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.w(TAG, "createEvent.onFailure() - Error adding document", e);
                        callback.accept(null);
                    }
                });
        return (true);
    }

    // calls function callback with a JSONObject representation of the event with id 'eventId'
    public boolean getEvent(String eventId, JSONObjectCallback callback) {
        Log.v(TAG, "getEvent()");
        if (mDb == null) {
            Log.w(TAG, "getEvent() - mDb is null - not doing anything");
            return false;
        }

        DocumentReference docRef = mDb.collection("Events").document(eventId);
        docRef.get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                if (task.isSuccessful()) {
                    DocumentSnapshot document = task.getResult();
                    if (document.exists()) {
                        Log.d(TAG, "getEvent.onComplete(): DocumentSnapshot data: " + document.getData());
                        if (document.getData() == null) {
                            callback.accept(null);
                        } else
                            callback.accept(new JSONObject(document.getData()));
                    } else {
                        Log.d(TAG, "No such document");
                        callback.accept(null);
                    }
                } else {
                    Log.d(TAG, "get failed with ", task.getException());
                    callback.accept(null);
                }
            }
        });

        return true;

    }

    /**
     * Retrieve all events accessible to the logged in user, and pass them to the callback function as a JSONObject
     *
     * @param callback
     * @return true on success or false on failure to initiate the request.
     */
    public boolean getEvents(JSONObjectCallback callback) {
        //Long eventId=Long.valueOf(285);
        Log.v(TAG, "getEvents()");
        if (mDb == null) {
            Log.w(TAG, "getEvents() - mDb is null - not doing anything");
            return false;
        }

        if (!isLoggedIn()) {
            Log.w(TAG, "getEvents() - not logged in - not doing anything");
            return false;
        }
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        mDb.collection("Events")  //.where("userId", "==", userId)
                .whereEqualTo("userId", userId)
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            try {
                                JSONObject retObj = new JSONObject();
                                JSONArray eventArray = new JSONArray();
                                Log.d(TAG, "getEvents() - returned " + task.getResult().size());
                                for (QueryDocumentSnapshot document : task.getResult()) {
                                    Log.d(TAG, "getEvents() - " + document.getId() + " => " + document.getData());
                                    JSONObject eventObj = new JSONObject(document.getData());
                                    // Add the event id into the event data because firebase does not include it as part of the document.
                                    eventObj.put("id", document.getId());
                                    eventArray.put(eventObj);
                                }
                                retObj.put("events", eventArray);
                                callback.accept(retObj);
                            } catch (JSONException e) {
                                Log.e(TAG, "getEvents.onResponse(): Error: " + e.getMessage() + "," + e.toString());
                                callback.accept(null);
                            }

                        } else {
                            Log.d(TAG, "Error getting documents: ", task.getException());
                            callback.accept(null);
                        }
                    }
                });
        return (true);
    }

    public boolean updateEvent(final JSONObject eventObj, JSONObjectCallback callback) {
        String eventId;
        Log.v(TAG, "updateEvent()");
        if (mDb == null) {
            Log.w(TAG, "updateEvent() - mDb is null - not doing anything");
            return false;
        }

        try {
            eventId = eventObj.getString("id");
        } catch (JSONException e) {
            Log.e(TAG, "updateEvent(): Error reading id from eventObj");
            eventId = null;
            return false;
        }
        final String dataStr = eventObj.toString();
        Log.v(TAG, "updateEvent - data=" + dataStr);
        Map<String, Object> eventMap = new HashMap<>();
        try {
            eventMap.put("dataTime", eventObj.getLong("dataTime"));
            eventMap.put("osdAlarmState", eventObj.getInt("osdAlarmState"));
            eventMap.put("desc", eventObj.getString("desc"));
            eventMap.put("type", eventObj.getString("type"));
            eventMap.put("subType", eventObj.getString("subType"));
            eventMap.put("userId", eventObj.getString("userId"));
        } catch (JSONException e) {
            Log.e(TAG, "updateEvent(): Error data from eventObj." + e.toString());
            e.printStackTrace();
            return false;
        }
        Log.v(TAG, "updateEvent - map=" + eventMap.toString());

        try {
            DocumentReference docRef = mDb.collection("Events").document(eventId);
            docRef.set(eventMap)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override
                        public void onSuccess(Void aVoid) {
                            JSONObject retObj;
                            try {
                                retObj = new JSONObject("{\"status\":\"OK\"}");
                            } catch (Exception e) {
                                retObj = null;
                            }
                            callback.accept(retObj);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.w(TAG, "Error updating document", e);
                            callback.accept(null);
                        }
                    });
            return (true);
        } catch (Exception e) {
            Log.e(TAG, "updateEvent() - ERROR: " + e.toString());
            e.printStackTrace();
        }
        return (false);
    }

    public boolean createDatapoint(JSONObject dataObj, String eventId, StringCallback callback) {
        Log.v(TAG, "createDatapoint()");
        // Create a new event in the remote database, based on the provided parameters.
        String userId = null;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.e(TAG, "ERROR: createDatapoint() - not logged in");
            return false;
        } else {
            userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }
        String dataTime;
        try {
            dataTime = dataObj.getString("dataTime");
        } catch (JSONException e) {
            dataTime = "";
        }
        Map<String, Object> datapoint = new HashMap<>();
        datapoint.put("dataTime", dataTime);
        datapoint.put("dataJSON", dataObj.toString());
        datapoint.put("userId", userId);
        datapoint.put("eventId", userId);

        mDb.collection("Datapoints")
                .add(datapoint)
                .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                    @Override
                    public void onSuccess(DocumentReference documentReference) {
                        Log.d(TAG, "createDatapoint.onSuccess() - DocumentSnapshot added with ID: " + documentReference.getId());
                        mServerConnectionOk = true;
                        callback.accept(documentReference.getId());
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.w(TAG, "createDatapoint.onFailure() - Error adding document", e);
                        callback.accept(null);
                    }
                });
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
        if (mDb == null) {
            Log.w(TAG, "getEventTypes() - mDb is null - not doing anything");
            return false;
        }

        mDb.collection("EventTypes")
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            try {
                                JSONObject retObj = new JSONObject();
                                for (QueryDocumentSnapshot document : task.getResult()) {
                                    Log.d(TAG, "getEventTypes.onComplete(): " + document.getId() + " => " + document.getData());
                                    Log.v(TAG, "getEventTypes.onComplete() - subtypes=" + document.getData().get("subTypes"));
                                    JSONArray subTypesArray = listToJSONArray((List) document.getData().get("subTypes"));
                                    retObj.put(document.getData().get("type").toString(), subTypesArray);
                                }
                                Log.d(TAG, "getEventTypes.onComplete() - retObj=" + retObj.toString());
                                callback.accept(retObj);
                            } catch (JSONException e) {
                                Log.e(TAG, "getEventTypes.onResponse(): Error: " + e.getMessage() + "," + e.toString());
                                callback.accept(null);
                            }
                        } else {
                            Log.d(TAG, "Error getting documents: ", task.getException());
                            callback.accept(null);
                        }
                    }
                });
        return (true);

    }

    private JSONArray listToJSONArray(List<Object> list) {
        JSONArray arr = new JSONArray();
        for (Object obj : list) {
            arr.put(obj);
        }
        return arr;
    }

    /**
     * Retrieve a trivial file from the server to check we have a good server connection.
     * sets mServerConnectionOk.
     *
     * @return true if request sent successfully or else false.
     */
    public boolean checkServerConnection() {
        //FIXME There must be a Firebase function for this?
        mServerConnectionOk = true;
        return mServerConnectionOk;
    }

}
