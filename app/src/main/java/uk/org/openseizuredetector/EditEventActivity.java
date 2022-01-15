package uk.org.openseizuredetector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class EditEventActivity extends AppCompatActivity
        implements AuthCallbackInterface, EventCallbackInterface, DatapointCallbackInterface {
    private String TAG = "EditEventActivity";
    private Context mContext;
    private WebApiConnection mWac;
    private LogManager mLm;
    final Handler serverStatusHandler = new Handler();
    private OsdUtil mUtil;
    private List<String> mEventTypesList = null;
    private HashMap<String, ArrayList<String>> mEventSubTypesHashMap = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_event);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            Long eventId = extras.getLong("eventId");
            Log.v(TAG, "onCreate - eventId=" + eventId);
        }
        mUtil = new OsdUtil(this, serverStatusHandler);

        Button cancelBtn =
                (Button) findViewById(R.id.cancelBtn);
        cancelBtn.setOnClickListener(onCancel);
        Button OKBtn = (Button) findViewById(R.id.OKBtn);
        OKBtn.setOnClickListener(onOK);

        ListView lv = (ListView) findViewById(R.id.eventTypeLv);
        lv.setOnItemClickListener(onEventTypeClick);

        mWac = new WebApiConnection(this, this, this, this);
        mLm = new LogManager(this);

        // Retrieve the JSONObject containing the standard event types.
        // Note this obscure syntax is to avoid having to create another interface, so it is worth it :)
        // See https://medium.com/@pra4mesh/callback-function-in-java-20fa48b27797
        mWac.getEventTypes((JSONObject eventTypesObj) -> {
            Log.v(TAG, "onCreate.onEventTypesReceived");

            if (eventTypesObj == null) {
                Log.e(TAG, "onCreate.getEventTypes Callback:  Error Retrieving event types");
                mUtil.showToast("Error Retrieving Event Types from Server - Please Try Again Later!");
            } else {
                Iterator<String> keys = eventTypesObj.keys();
                mEventTypesList = new ArrayList<String>();
                mEventSubTypesHashMap = new HashMap<String, ArrayList<String>>();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Log.v(TAG, "onCreate.getEventTypes Callback: key=" + key);
                    mEventTypesList.add(key);
                    try {
                        JSONArray eventSubTypes = eventTypesObj.getJSONArray(key);
                        ArrayList<String> eventSubtypesList = new ArrayList<String>();
                        for (int i = 0; i < eventSubTypes.length(); i++) {
                            eventSubtypesList.add(eventSubTypes.getString(i));
                        }
                        mEventSubTypesHashMap.put(key, eventSubtypesList);
                    } catch (JSONException e) {
                        Log.e(TAG, "onCreate(getEventTypes Callback: Error parsing JSONObject" + e.getMessage() + e.toString());
                    }
                }
                updateUi();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart()");
        updateUi();
    }

    public void authCallback(boolean authSuccess, String tokenStr) {
        Log.v(TAG, "authCallback");
        updateUi();
    }

    public void eventCallback(boolean success, String eventStr) {
        Log.v(TAG, "eventCallback");
    }

    public void datapointCallback(boolean success, String datapointStr) {
        Log.v(TAG, "datapointCallback");
    }

    private void updateUi() {
        Log.v(TAG, "updateUI");
        if (mEventTypesList != null) {
            //TextView tv = (TextView) findViewById(R.id.tokenTv);
            //tv.setText("Logged in with Token:" + storedAuthToken);
            Log.v(TAG, "updateUi: " + mEventTypesList.toString());
            ListView lv = (ListView) findViewById(R.id.eventTypeLv);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    R.layout.event_type_list_item, R.id.eventTypeTv, mEventTypesList);
            lv.setAdapter(adapter);
        }
    }

    View.OnClickListener onCancel =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onCancel");
                    //m_status=false;
                    finish();
                }
            };

    View.OnClickListener onOK =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //m_status=true;
                    Log.v(TAG, "onOK()");
                    //String uname = mUnameEt.getText().toString();
                    //String passwd = mPasswdEt.getText().toString();
                    //Log.v(TAG,"onOK() - uname="+uname+", passwd="+passwd);
                    //mWac.authenticate(uname,passwd);
                    //finish();
                }
            };

    private void setSubTypesLV(String eventType) {
        ArrayList<String> subtypesArrayList = mEventSubTypesHashMap.get(eventType);
        Log.v(TAG,"setSubtypesLV - eventType="+eventType+", subtypes="+subtypesArrayList);
        ListView lv = (ListView)findViewById(R.id.eventSubTypeLv);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.event_sub_type_list_item, R.id.eventSubTypeLv, subtypesArrayList);
        lv.setAdapter(adapter);
    }

    AdapterView.OnItemClickListener onEventTypeClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onEventTypeClick() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    String selectedEventType = (String) adapter.getItemAtPosition(position);
                    Log.v(TAG,"onEventTypeClick - selected "+selectedEventType);
                    setSubTypesLV(selectedEventType);
                }
            };
}