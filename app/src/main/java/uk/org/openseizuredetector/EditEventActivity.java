package uk.org.openseizuredetector;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
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
    private String mEventTypeStr = null;
    private String mEventSubTypeStr = null;
    private Long mEventId;
    private String mEventNotes = "";
    private Date mEventDateTime;
    private RadioGroup mEventTypeRg;
    private boolean mEventTypesListChanged = false;


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

        mEventTypeRg = findViewById(R.id.eventTypeRg);
        mEventTypeRg.setOnCheckedChangeListener(onEventTypeChange);
        ListView lv;
        //lv = (ListView) findViewById(R.id.eventTypeLv);
        //lv.setOnItemClickListener(onEventTypeClick);
        lv = (ListView) findViewById(R.id.eventSubTypeLv);
        lv.setOnItemClickListener(onEventSubTypeClick);


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
                        mEventTypesListChanged = true;
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
        TextView tv;
        // tv = (TextView) findViewById(R.id.tokenTv);
        //tv.setText("Logged in with Token:" + storedAuthToken);

        // Regenerate event type button group if necessary
        if (mEventTypesList != null && mEventTypesListChanged) {
            Log.v(TAG, "updateUi: " + mEventTypesList.toString());
            //ListView lv = (ListView) findViewById(R.id.eventTypeLv);
            //ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            //        R.layout.event_type_list_item, R.id.eventTypeTv, mEventTypesList);
            //lv.setAdapter(adapter);
            mEventTypeRg.removeAllViews();
            for (String eventTypeStr : mEventTypesList) {
                RadioButton b = new RadioButton(this);
                b.setText(eventTypeStr);
                mEventTypeRg.addView(b);
            }
            mEventTypesListChanged = false;
        }

        // Check the correct button in the event type group
        RadioButton b;
        for (int index = 0; index < mEventTypeRg.getChildCount(); index++) {
            b = (RadioButton) mEventTypeRg.getChildAt(index);
            String buttonText = b.getText().toString();
            if (buttonText.equals(mEventTypeStr)) {
                Log.v(TAG, "updateUi - selecting button " + mEventTypeStr);
                b.setChecked(true);
            }
        }


        if (mEventSubTypesHashMap != null) {
            if (mEventTypeStr != null) {
                // based on https://androidexample.com/create-a-simple-listview
                ArrayList<String> subtypesArrayList = mEventSubTypesHashMap.get(mEventTypeStr);
                Log.v(TAG, "setSubtypesLV - eventType=" + mEventTypeStr + ", subtypes=" + subtypesArrayList);
                ListView lv = (ListView) findViewById(R.id.eventSubTypeLv);
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                        R.layout.event_sub_type_list_item, R.id.eventSubTypeTv, subtypesArrayList);
                lv.setAdapter(adapter);
            }
        }
        tv = (TextView) findViewById(R.id.eventTypeTv);
        if (mEventTypeStr != null) {
            tv.setText(mEventTypeStr);
        } else {
            tv.setText(R.string.selectFromOptionselow);
        }
        tv = (TextView) findViewById(R.id.eventSubTypeTv);
        if (mEventSubTypeStr != null) {
            tv.setText(mEventSubTypeStr);
        } else {
            tv.setText(R.string.selectFromOptionselow);
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
        // based on https://androidexample.com/create-a-simple-listview
        ArrayList<String> subtypesArrayList = mEventSubTypesHashMap.get(eventType);
        Log.v(TAG, "setSubtypesLV - eventType=" + eventType + ", subtypes=" + subtypesArrayList);
        ListView lv = (ListView) findViewById(R.id.eventSubTypeLv);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.event_sub_type_list_item, R.id.eventSubTypeTv, subtypesArrayList);
        lv.setAdapter(adapter);
    }

    AdapterView.OnItemClickListener onEventTypeClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onEventTypeClick() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    mEventTypeStr = (String) adapter.getItemAtPosition(position);
                    Log.v(TAG, "onEventTypeClick - selected " + mEventTypeStr);
                    updateUi();
                    //setSubTypesLV(selectedEventType);
                }
            };

    AdapterView.OnItemClickListener onEventSubTypeClick =
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> adapter, View v, int position, long id) {
                    Log.v(TAG, "onEventSubTypeClick() - Position=" + position + ", id=" + id);// Confirmation dialog based on: https://stackoverflow.com/a/12213536/2104584
                    mEventSubTypeStr = (String) adapter.getItemAtPosition(position);
                    Log.v(TAG, "onEventSubTypeClick - selected " + mEventSubTypeStr);
                    updateUi();
                    //setSubTypesLV(selectedEventType);
                }
            };
    RadioGroup.OnCheckedChangeListener onEventTypeChange =
            new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    Log.v(TAG,"onEventTypeChange() - id="+checkedId);
                    RadioButton b = (RadioButton)findViewById(group.getCheckedRadioButtonId());
                    String selectedEventType = b.getText().toString();
                    mEventTypeStr = selectedEventType;
                    updateUi();
                }
            };

}