package uk.org.openseizuredetector.datasource;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import uk.org.openseizuredetector.data.SdData;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
public class SdDataSourceNetworkTest {

    private MockWebServer server;
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    static class TestReceiver implements SdDataReceiver {
        CountDownLatch latch = new CountDownLatch(1);
        SdData last = null;
        @Override
        public void onSdDataReceived(SdData sdData) {
            last = sdData;
            latch.countDown();
        }

        @Override
        public void onSdDataFault(SdData sdData) {
            last = sdData;
            latch.countDown();
        }
    }

    @Test
    public void testDownloadParsesMlAndAlgFlagsAndAlarm() throws Exception {
        String body = "{\n" +
                "  \"dataTimeStr\": \"20260222T120000\",\n" +
                "  \"maxVal\": 123,\n" +
                "  \"maxFreq\": 12,\n" +
                "  \"specPower\": 50,\n" +
                "  \"roiPower\": 5,\n" +
                "  \"batteryPc\": 90,\n" +
                "  \"phoneBatteryPc\": 80,\n" +
                "  \"watchConnected\": true,\n" +
                "  \"watchAppRunning\": true,\n" +
                "  \"haveSettings\": true,\n" +
                "  \"alarmState\": 2,\n" +
                "  \"alarmPhrase\": \"ALARM\",\n" +
                "  \"alarmCause\": \"Test Cause\",\n" +
                "  \"sdMode\": 1,\n" +
                "  \"sampleFreq\": 25,\n" +
                "  \"analysisPeriod\": 5,\n" +
                "  \"alarmFreqMin\": 3,\n" +
                "  \"alarmFreqMax\": 15,\n" +
                "  \"alarmThresh\": 10,\n" +
                "  \"alarmRatioThresh\": 2,\n" +
                "  \"hrAlarmActive\": true,\n" +
                "  \"hrAlarmStanding\": false,\n" +
                "  \"hrThreshMin\": 40.0,\n" +
                "  \"hrThreshMax\": 150.0,\n" +
                "  \"hr\": 72.5,\n" +
                "  \"adaptiveHrAv\": 70.0,\n" +
                "  \"averageHrAv\": 71.0,\n" +
                "  \"o2SatAlarmActive\": false,\n" +
                "  \"o2SatAlarmStanding\": false,\n" +
                "  \"o2SatThreshMin\": 80.0,\n" +
                "  \"o2Sat\": 98.0,\n" +
                "  \"cnnAlarmActive\": true,\n" +
                "  \"pSeizure\": 0.88,\n" +
                "  \"OsdAlarmActive\": true,\n" +
                "  \"FlapAlarmActive\": false,\n" +
                "  \"CnnAlarmActive\": true,\n" +
                "  \"osdAlgState\": 2,\n" +
                "  \"flapAlgState\": 0,\n" +
                "  \"fallAlgState\": 0,\n" +
                "  \"hrAlgState\": 0,\n" +
                "  \"cnnAlgState\": 2,\n" +
                "  \"mlNumModels\": 2,\n" +
                "  \"mlModelNames\": [\"modelA\", \"modelB\"],\n" +
                "  \"mlModelProbs\": [0.1, 0.88],\n" +
                "  \"mlModelStates\": [0, 2],\n" +
                "  \"mlModelActive\": [true, true],\n" +
                "  \"simpleSpec\": [1,2,3,4,5,6,7,8,9,10]\n" +
                "}";

        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        TestReceiver receiver = new TestReceiver();

        // Use main looper handler for SdDataSourceNetwork
        Handler h = new Handler(Looper.getMainLooper());
        SdDataSourceNetwork net = new SdDataSourceNetwork(context, h, receiver);

        // Point the datasource to mock server
        String baseUrl = server.url("/").toString();
        net.setServerBaseUrl(baseUrl);

        // Trigger synchronous download using test hook
        SdData sd = net.downloadSdDataSync();
        // deliver to receiver to mimic async behavior
        receiver.onSdDataReceived(sd);

        boolean ok = receiver.latch.await(1, TimeUnit.SECONDS);
        assertTrue("Timed out waiting for data", ok);
        sd = receiver.last;
        System.out.println("DEBUG: sd.serverOK=" + (sd == null ? "null" : String.valueOf(sd.serverOK)));
        if (sd != null) {
            System.out.println("DEBUG: alarmState=" + sd.alarmState + " alarmPhrase=" + sd.alarmPhrase);
            System.out.println("DEBUG: osdActive=" + sd.mOsdAlarmActive + " flapActive=" + sd.mFlapAlarmActive + " cnnActive=" + sd.mCnnAlarmActive);
            System.out.println("DEBUG: mlNumModels=" + sd.mlNumModels);
            for (int i = 0; i < Math.min(sd.mlNumModels, 5); i++) {
                System.out.println("DEBUG: model[" + i + "]=" + sd.mlModelNames[i] + " prob=" + sd.mlModelProbs[i] + " state=" + sd.mlModelStates[i] + " active=" + sd.mlModelActive[i]);
            }
        }

        assertNotNull(sd);
        assertTrue(sd.serverOK);
        assertTrue(sd.mOsdAlarmActive);
        assertFalse(sd.mFlapAlarmActive);
        assertTrue(sd.mCnnAlarmActive);
        assertEquals(2, sd.osdAlgState);
        assertEquals(0, sd.flapAlgState);
        assertEquals(2, sd.cnnAlgState);
        assertEquals(2, sd.mlNumModels);
        assertEquals("modelA", sd.mlModelNames[0]);
        assertEquals("modelB", sd.mlModelNames[1]);
        assertEquals(0.88, sd.mlModelProbs[1], 1e-6);
        assertEquals(2, sd.mlModelStates[1]);
        assertTrue(sd.mlModelActive[1]);
        assertEquals(2, sd.alarmState);
        assertEquals("ALARM", sd.alarmPhrase);
    }

    @Test
    public void testSdDataJsonRoundTrip() throws Exception {
        // Create SdData and populate required fields
        uk.org.openseizuredetector.data.SdData sd0 = new uk.org.openseizuredetector.data.SdData();
        sd0.maxVal = 200;
        sd0.maxFreq = 20;
        sd0.specPower = 100;
        sd0.roiPower = 10;
        sd0.batteryPc = 50;
        sd0.phoneBatteryPc = 60;
        sd0.watchConnected = true;
        sd0.watchAppRunning = true;
        sd0.haveSettings = true;
        sd0.alarmState = 1;
        sd0.alarmPhrase = "WARNING";
        sd0.alarmCause = "unit test";
        sd0.mSdMode = 1;
        sd0.mSampleFreq = 25;
        sd0.analysisPeriod = 5;
        sd0.alarmFreqMin = 1;
        sd0.alarmFreqMax = 10;
        sd0.alarmThresh = 5;
        sd0.alarmRatioThresh = 2;
        sd0.mHRAlarmActive = true;
        sd0.mHRAlarmStanding = false;
        sd0.mHRThreshMin = 40.0;
        sd0.mHRThreshMax = 150.0;
        sd0.mHR = 80.0;
        sd0.mAdaptiveHrAverage = 78.0;
        sd0.mAverageHrAverage = 79.0;
        sd0.mO2SatAlarmActive = false;
        sd0.mO2SatAlarmStanding = false;
        sd0.mO2SatThreshMin = 88.0;
        sd0.mO2Sat = 97.0;
        sd0.mCnnAlarmActive = true;
        sd0.mPseizure = 0.5;
        sd0.mOsdAlarmActive = true;
        sd0.mFlapAlarmActive = false;
        sd0.mCnnAlarmActive = true;
        sd0.osdAlgState = 1;
        sd0.flapAlgState = 0;
        sd0.fallAlgState = 0;
        sd0.hrAlgState = 0;
        sd0.cnnAlgState = 1;
        sd0.mlNumModels = 2;
        sd0.mlModelNames[0] = "mA";
        sd0.mlModelNames[1] = "mB";
        sd0.mlModelProbs[0] = 0.2;
        sd0.mlModelProbs[1] = 0.8;
        sd0.mlModelStates[0] = 0;
        sd0.mlModelStates[1] = 2;
        sd0.mlModelActive[0] = true;
        sd0.mlModelActive[1] = true;
        sd0.simpleSpec = new int[]{1,2,3,4,5,6,7,8,9,10};

        String json = sd0.toJSON(false);
        uk.org.openseizuredetector.data.SdData sd1 = new uk.org.openseizuredetector.data.SdData();
        sd1.fromJSON(json);

        assertEquals(sd0.maxVal, sd1.maxVal);
        assertEquals(sd0.maxFreq, sd1.maxFreq);
        assertEquals(sd0.specPower, sd1.specPower);
        assertEquals(sd0.roiPower, sd1.roiPower);
        assertEquals(sd0.batteryPc, sd1.batteryPc);
        assertEquals(sd0.phoneBatteryPc, sd1.phoneBatteryPc);
        assertEquals(sd0.watchConnected, sd1.watchConnected);
        assertEquals(sd0.haveSettings, sd1.haveSettings);
        assertEquals(sd0.alarmState, sd1.alarmState);
        assertEquals(sd0.alarmPhrase, sd1.alarmPhrase);
        assertEquals(sd0.mPseizure, sd1.mPseizure, 1e-9);
        assertEquals(sd0.mlNumModels, sd1.mlNumModels);
        assertEquals(sd0.mlModelNames[1], sd1.mlModelNames[1]);
        assertEquals(sd0.mlModelProbs[1], sd1.mlModelProbs[1], 1e-9);
        assertEquals(sd0.mlModelStates[1], sd1.mlModelStates[1]);
    }

}
