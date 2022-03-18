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
        // Check if we are already logged in
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth != null) {
            Log.i(TAG, "Firebase Logged in OK");
            mDb = FirebaseFirestore.getInstance();
        } else {
            Log.e(TAG, "Firebase not logged in");
            mDb = null;
        }

    }

    public void close() {
        Log.i(TAG, "stop()");
        mQueue.stop();
    }

    public boolean isLoggedIn() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth != null) {
            Log.v(TAG, "isLoggedIn(): Firebase Logged in OK");
            return (false);
        } else {
            Log.v(TAG, "isLoggedIn(): Firebase not logged in");
            return (true);
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
        mDb.collection("Events")
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            try {
                                JSONObject retObj = new JSONObject();
                                JSONArray eventArray = new JSONArray();
                                for (QueryDocumentSnapshot document : task.getResult()) {
                                    Log.d(TAG, document.getId() + " => " + document.getData());
                                    eventArray.put(new JSONObject(document.getData()));
                                }
                                retObj.put("events", eventArray);
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


    public boolean updateEvent(final JSONObject eventObj, JSONObjectCallback callback) {
        String eventId;
        Log.v(TAG, "updateEvent()");
        try {
            eventId = eventObj.getString("id");
        } catch (JSONException e) {
            Log.e(TAG, "updateEvent(): Error reading id from eventObj");
            eventId = null;
            return false;
        }
        final String dataStr = eventObj.toString();
        Log.v(TAG, "updateEvent - data=" + dataStr);

        DocumentReference docRef = mDb.collection("Events").document(eventId);
        docRef.update((Map<String, Object>) eventObj)
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
    }

    public boolean createDatapoint(JSONObject dataObj, int eventId, StringCallback callback) {
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
        datapoint.put("dataJSON",dataObj.toString());
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

        mDb.collection("EventTypes")
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            try {
                                JSONObject retObj = new JSONObject();
                                JSONArray eventArray = new JSONArray();
                                for (QueryDocumentSnapshot document : task.getResult()) {
                                    Log.d(TAG, document.getId() + " => " + document.getData());
                                    eventArray.put(new JSONObject(document.getData()));
                                }
                                retObj.put("eventTypes", eventArray);
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

    /**
     * Retrieve a trivial file from the server to check we have a good server connection.
     * sets mServerConnectionOk.
     *
     * @return true if request sent successfully or else false.
     */
    public boolean checkServerConnection() {
        //FIXME There must be a Firebase function for this?
        mServerConnectionOk = true;
        return true;
    }

}
