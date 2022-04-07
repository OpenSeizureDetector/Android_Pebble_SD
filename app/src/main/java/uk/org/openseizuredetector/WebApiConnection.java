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
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


// This class is intended to handle all interactions with the OSD WebAPI
public abstract class WebApiConnection {
    private Context mContext;
    private OsdUtil mUtil;
    private String TAG = "WebApiConnection";


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
    public abstract boolean createEvent(final int osdAlarmState, final Date eventDate, final String eventDesc, StringCallback callback);

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


    /**
     * Retrieve a trivial file from the server to check we have a good server connection.
     * sets mServerConnectionOk.
     *
     * @return true if request sent successfully or else false.
     */
    public abstract boolean checkServerConnection();

}
