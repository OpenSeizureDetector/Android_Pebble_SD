package uk.org.openseizuredetector;

import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;

import uk.org.openseizuredetector.EventLogManager.EventLogListAdapter;
import uk.org.openseizuredetector.EventLogManager.EventLogManager;
import uk.org.openseizuredetector.EventLogManager.LogEntryModel;


public class LogManagerActivity extends FragmentActivity
implements AuthDialogInterface {
    private String TAG = "LogManagerActivity";
    private EventLogListAdapter mEventLogListAdapter;
    private ListView mEventLogListView;
    private EventLogManager mElm;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_manager);

        LogEntryModel lem = new LogEntryModel();
        //lem.setDate(new Date());
        lem.setNote("Test Entry");
        lem.setDataJSON("[]");
        lem.setAlarmState(1);

        //mElm = new EventLogManager(this);
        //mElm.addRow(lem);

        //mEventLogListAdapter = new EventLogListAdapter(this);
        //mEventLogListView = (ListView) findViewById(R.id.eventLogListView);
        //mEventLogListView.setAdapter(mEventLogListAdapter);

        Button b;

        b = (Button) findViewById(R.id.authenticate_button);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.v(TAG, "authenticate button clicked");
                AuthDialog authDialog = new AuthDialog();
                authDialog.show(getSupportFragmentManager(),"authDialog");
            }
        });
    }

    public void updateUi() {
        Log.v(TAG, "updateUi");
    }

    public void onDialogDone(boolean State) {
        Log.v(TAG,"onDialogDOne()");
    }

}