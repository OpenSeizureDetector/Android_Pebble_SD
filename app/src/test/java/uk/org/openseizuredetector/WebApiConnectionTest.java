package uk.org.openseizuredetector;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Date;


@RunWith(RobolectricTestRunner.class)
public class WebApiConnectionTest {
    WebApiConnection mWac;
    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();;
        FirebaseApp.initializeApp(context);
        mWac = new WebApiConnection(context) {
            @Override
            public boolean isLoggedIn() {
                return false;
            }

            @Override
            public boolean createEvent(int osdAlarmState, Date eventDate, String type, String subType, String eventDesc, String dataJSON, StringCallback callback) {
                return false;
            }

            @Override
            public boolean getEvent(String eventId, JSONObjectCallback callback) {
                return false;
            }

            /**
             * Retrieve all events accessible to the logged in user, and pass them to the callback function as a JSONObject
             *
             * @param callback
             * @return true on success or false on failure to initiate the request.
             */
            @Override
            public boolean getEvents(JSONObjectCallback callback) {
                return false;
            }

            @Override
            public boolean updateEvent(JSONObject eventObj, JSONObjectCallback callback) {
                return false;
            }

            @Override
            public boolean createDatapoint(JSONObject dataObj, String eventId, StringCallback callback) {
                return false;
            }

            /**
             * Retrieve the file containing the standard event types from the server.
             * Calls the specified callback function, passing a JSONObject as a parameter when the data has been received and parsed.
             *
             * @param callback
             * @return true if request sent successfully or else false.
             */
            @Override
            public boolean getEventTypes(JSONObjectCallback callback) {
                return false;
            }

            @Override
            public boolean getCnnModelInfo(JSONObjectCallback callback) {
                return false;
            }

            /**
             * Retrieve a trivial file from the server to check we have a good server connection.
             * sets mServerConnectionOk.
             *
             * @return true if request sent successfully or else false.
             */
            @Override
            public boolean checkServerConnection() {
                return false;
            }

            @Override
            public boolean getUserProfile(JSONObjectCallback callback) {
                return false;
            }
        };
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void isLoggedIn() {
        assertTrue(mWac.isLoggedIn());
        assertFalse(mWac.isLoggedIn());
    }

    @Test
    public void createEvent() {
    }

    @Test
    public void getEvent() {
    }

    @Test
    public void getEvents() {
    }

    @Test
    public void updateEvent() {
    }

    @Test
    public void createDatapoint() {
    }

    @Test
    public void getUserProfile() {
    }
}