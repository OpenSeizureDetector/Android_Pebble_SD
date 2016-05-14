package uk.org.openseizuredetector;

import android.app.Activity;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ListView;

import java.util.Date;

import uk.org.openseizuredetector.EventLogManager.EventLogListAdapter;
import uk.org.openseizuredetector.EventLogManager.EventLogManager;
import uk.org.openseizuredetector.EventLogManager.LogEntryModel;

public class LogManagerActivity extends Activity {

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

        mElm = new EventLogManager(this);
        mElm.addRow(lem);

        mEventLogListAdapter = new EventLogListAdapter(this);
        mEventLogListView = (ListView) findViewById(R.id.eventLogListView);
        mEventLogListView.setAdapter(mEventLogListAdapter);


    }


}
