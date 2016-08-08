package uk.org.openseizuredetector;

import android.content.Context;
import android.util.EventLog;
import android.util.Log;

import junit.framework.TestCase;
import android.test.mock.MockContext;

import org.junit.Test;
import org.mockito.internal.exceptions.ExceptionIncludingMockitoWarnings;

import uk.org.openseizuredetector.EventLogManager.EventLogManager;
import uk.org.openseizuredetector.EventLogManager.LogEntryModel;

/**
 * Created by graham on 12/05/16.
 */
public class EventLogManagerTest extends TestCase {
    private final static String TAG = "EventLogManagerTest";
    Context mContext;

    protected void setUp() throws Exception {
        super.setUp();
        Log.v(TAG,"setUp()");
        mContext = new MockContext();
}

    @Test
    public void testOpenDb() throws Exception {
        Log.v(TAG,"testOpenDb()");
        EventLogManager em = new EventLogManager(mContext);
        assertNotNull(em);


        LogEntryModel lem = new LogEntryModel();
        //lem.setDate(new Date());
        lem.setNote("Test Entry");
        lem.setDataJSON("[]");
        lem.setAlarmState(1);

        em.addRow(lem);

    }

}
