package uk.org.openseizuredetector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SdAlgHrTest extends TestCase {
    private SdAlgHr mSdAlgHr = null;

    //@Mock
    private Context mContext;


    private SharedPreferences sharedPrefs;
    private static PreferenceManager mPreferenceManager;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        //mContext = ApplicationProvider.getApplicationContext();
        this.sharedPrefs = Mockito.mock(SharedPreferences.class);
        this.mContext = Mockito.mock(Context.class);
        mPreferenceManager = Mockito.mock(PreferenceManager.class);
        //Mockito.when(mContext.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPrefs);
        Mockito.when(mPreferenceManager.getDefaultSharedPreferences(any())).thenReturn(sharedPrefs);
        mSdAlgHr = new SdAlgHr(mContext);
        assertNotNull(mSdAlgHr);

    }

    public void tearDown() throws Exception {
    }

    @Test
    public void testCheckHr() throws Exception{
        setUp();
        assertNotNull(mSdAlgHr);
        mSdAlgHr.checkHr(60.);
        mSdAlgHr.checkHr(70.);
        mSdAlgHr.checkHr(80.);
        double hrAv = mSdAlgHr.getAverageHrAverage();
        assertEquals(hrAv, 70);
    }
}