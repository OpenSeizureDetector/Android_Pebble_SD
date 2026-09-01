package uk.org.openseizuredetector.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import uk.org.openseizuredetector.data.SdData;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SdDataSourcePhoneTest {

    private SdDataSourcePhone mDataSource;
    private List<SdData> mReceivedData = new ArrayList<>();
    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        Handler handler = new Handler(Looper.getMainLooper());
        SdDataReceiver receiver = new SdDataReceiver() {
            @Override
            public void onSdDataReceived(SdData sdData) {
                // Clone the SdData because it might be reused
                SdData clone = new SdData();
                clone.mSampleFreq = sdData.mSampleFreq;
                mReceivedData.add(clone);
            }

            @Override
            public void onSdDataFault(SdData sdData) {
            }
        };
        mDataSource = new SdDataSourcePhone(mContext, handler, receiver);
    }

    private SensorEvent createSensorEvent(long timestampNs, float x, float y, float z) throws Exception {
        // SensorEvent has no public constructor, so we use reflection or a stub.
        // In Robolectric, we can use reflection.
        Constructor<SensorEvent> constructor = SensorEvent.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        SensorEvent event = constructor.newInstance(3); // 3 values for accelerometer

        Field sensorField = SensorEvent.class.getField("sensor");
        sensorField.setAccessible(true);
        // We need a Sensor object. 
        // Robolectric doesn't provide a public way to create Sensors easily, but we can try reflection.
        Constructor<Sensor> sensorConstructor = Sensor.class.getDeclaredConstructor();
        sensorConstructor.setAccessible(true);
        Sensor sensor = sensorConstructor.newInstance();
        
        // Use reflection to set the sensor type to TYPE_ACCELEROMETER (1)
        Field typeField = Sensor.class.getDeclaredField("mType");
        typeField.setAccessible(true);
        typeField.setInt(sensor, Sensor.TYPE_ACCELEROMETER);
        
        event.sensor = sensor;
        event.timestamp = timestampNs;
        event.values[0] = x;
        event.values[1] = y;
        event.values[2] = z;
        
        return event;
    }

    private void setMode(int mode) throws Exception {
        Field modeField = SdDataSourcePhone.class.getDeclaredField("mMode");
        modeField.setAccessible(true);
        modeField.setInt(mDataSource, mode);
    }

    @Test
    public void downsampling_50Hz_to_25Hz() throws Exception {
        // In Mode 1 (Running)
        setMode(1);
        
        // Target is 25Hz (40ms = 40,000,000 ns)
        long intervalNs = 20_000_000L; // 50Hz hardware
        long currentTs = 1000_000_000L; // Start at 1s
        
        // We need 125 samples to trigger doAnalysis
        for (int i = 0; i < 250; i++) {
            SensorEvent event = createSensorEvent(currentTs, 0f, 0f, 9.81f);
            mDataSource.onSensorChanged(event);
            currentTs += intervalNs;
        }
        
        // With 50Hz input and 25Hz target, we should have received 1 full window (125 samples)
        // after 250 hardware samples.
        assertEquals("Should have received 1 data window", 1, mReceivedData.size());
        
        SdData data = mReceivedData.get(0);
        assertEquals("Sample frequency should be 25", 25, data.mSampleFreq);
    }

    @Test
    public void downsampling_jittery_25Hz() throws Exception {
        setMode(1);
        
        long currentTs = 1000_000_000L;
        
        // Let's use 50Hz with +/- 5ms jitter
        for (int i = 0; i < 300; i++) {
            long jitter = (long)((Math.random() - 0.5) * 10_000_000L); 
            SensorEvent event = createSensorEvent(currentTs + jitter, 0f, 0f, 9.81f);
            mDataSource.onSensorChanged(event);
            currentTs += 20_000_000L; 
        }
        
        assertTrue("Should have received data with jittery 50Hz input", !mReceivedData.isEmpty());
    }
}
