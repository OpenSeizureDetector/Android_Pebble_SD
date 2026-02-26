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
public class SdDataSourceBLE2Test {

    private SdDataSourceBLE2 createDataSource() {
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
        return new SdDataSourceBLE2(context, handler, receiver);
    }

    private void setAccFormat(SdDataSourceBLE2 dataSource, int format) throws Exception {
        Field accFmt = SdDataSourceBLE2.class.getDeclaredField("mAccFmt");
        accFmt.setAccessible(true);
        accFmt.setInt(dataSource, format);
    }

    private short[] parseAccValues(SdDataSourceBLE2 dataSource, byte[] raw) throws Exception {
        Method method = SdDataSourceBLE2.class.getDeclaredMethod("parseDataToAccVals", byte[].class);
        method.setAccessible(true);
        return (short[]) method.invoke(dataSource, raw);
    }

    @Test
    public void parseAccValues_8bit_scalesMg() throws Exception {
        SdDataSourceBLE2 dataSource = createDataSource();
        setAccFormat(dataSource, SdDataSourceBLE2.ACC_FMT_8BIT);

        byte[] raw = new byte[] {0, 64, -64};
        short[] result = parseAccValues(dataSource, raw);

        assertArrayEquals(new short[] {0, 1000, -1000}, result);
    }

    @Test
    public void parseAccValues_16bit_littleEndian() throws Exception {
        SdDataSourceBLE2 dataSource = createDataSource();
        setAccFormat(dataSource, SdDataSourceBLE2.ACC_FMT_16BIT);

        byte[] raw = new byte[] {0x01, 0x00, 0x00, (byte) 0x80};
        short[] result = parseAccValues(dataSource, raw);

        assertArrayEquals(new short[] {1, (short) 0x8000}, result);
    }
}

