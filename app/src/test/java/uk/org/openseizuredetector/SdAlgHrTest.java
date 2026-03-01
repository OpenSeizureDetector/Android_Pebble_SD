package uk.org.openseizuredetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.alg.SdAlgHr;
import uk.org.openseizuredetector.activity.settings.PrefActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SdAlgHrTest {
    private SdAlgHr mSdAlgHr;
    private Context mContext;
    private SharedPreferences sharedPrefs;

    @Before
    public void setUp() throws Exception {
        // Use Robolectric application context for local unit test
        mContext = RuntimeEnvironment.getApplication();
        PrefActivity.initialiseDefaultValues(mContext, true);
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mSdAlgHr = new SdAlgHr(mContext);
        assertNotNull(mSdAlgHr);
    }

    @Test
    public void testCheckHr() throws Exception {
        assertNotNull(mSdAlgHr);
        mSdAlgHr.checkHr(60.0);
        mSdAlgHr.checkHr(70.0);
        mSdAlgHr.checkHr(80.0);
        double hrAv = mSdAlgHr.getAverageHrAverage();
        assertEquals(70.0, hrAv, 0.0001);
    }
}