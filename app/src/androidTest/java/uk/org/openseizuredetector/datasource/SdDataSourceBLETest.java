package uk.org.openseizuredetector.datasource;

import static org.junit.Assert.assertArrayEquals;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import uk.org.openseizuredetector.data.SdData;

@RunWith(AndroidJUnit4.class)
public class SdDataSourceBLETest {

    private SdDataSourceBLE createDataSource() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Handler handler = new Handler(Looper.getMainLooper());
        SdDataReceiver receiver = new SdDataReceiver() {
            @Override
            public void onSdDataReceived(SdData sdData) {
                // No-op for tests.
            }

            @Override
            public void onSdDataFault(SdData sdData) {
                // No-op for tests.
            }
        };
        return new SdDataSourceBLE(context, handler, receiver);
    }

    private Object getGattCallback(SdDataSourceBLE dataSource) throws Exception {
        Field callbackField = SdDataSourceBLE.class.getDeclaredField("mGattCallback");
        callbackField.setAccessible(true);
        return callbackField.get(dataSource);
    }

    private void setAccFormat(SdDataSourceBLE dataSource, int format) throws Exception {
        Field accFmt = SdDataSourceBLE.class.getDeclaredField("mAccFmt");
        accFmt.setAccessible(true);
        accFmt.setInt(dataSource, format);
    }

    private short[] parseAccValues(SdDataSourceBLE dataSource, byte[] raw) throws Exception {
        Object callback = getGattCallback(dataSource);
        Method method = callback.getClass().getDeclaredMethod("parseDataToAccVals", byte[].class);
        method.setAccessible(true);
        return (short[]) method.invoke(callback, raw);
    }

    @Test
    public void parseAccValues_8bit_scalesMg() throws Exception {
        SdDataSourceBLE dataSource = createDataSource();
        setAccFormat(dataSource, SdDataSourceBLE.ACC_FMT_8BIT);

        byte[] raw = new byte[] {0, 64, -64};
        short[] result = parseAccValues(dataSource, raw);

        assertArrayEquals(new short[] {0, 1000, -1000}, result);
    }

    @Test
    public void parseAccValues_16bit_littleEndian() throws Exception {
        SdDataSourceBLE dataSource = createDataSource();
        setAccFormat(dataSource, SdDataSourceBLE.ACC_FMT_16BIT);

        byte[] raw = new byte[] {0x01, 0x00, 0x00, (byte) 0x80};
        short[] result = parseAccValues(dataSource, raw);

        assertArrayEquals(new short[] {1, (short) 0x8000}, result);
    }
}

