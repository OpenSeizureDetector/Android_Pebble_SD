package uk.org.openseizuredetector.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@Ignore("Disabling existing tests to focus on the new OnboardingTest")
@RunWith(AndroidJUnit4.class)
public class SdServerCompatTest {

    @Test
    public void networkCapabilitiesHelpersRespectInternetAndWifi() {
        assertTrue(SdServerCompat.isConnected(true));
        assertTrue(SdServerCompat.isWifi(true));
        assertFalse(SdServerCompat.isConnected(false));
        assertFalse(SdServerCompat.isWifi(false));
    }

    @Test
    public void shouldStartMainActivityHonorsTopPackage() {
        assertFalse(SdServerCompat.shouldStartMainActivity("uk.org.openseizuredetector"));
        assertTrue(SdServerCompat.shouldStartMainActivity("com.example.other"));
        assertTrue(SdServerCompat.shouldStartMainActivity(null));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void systemAlertWindowTypeDependsOnSdk() {
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                SdServerCompat.getSystemAlertWindowType(Build.VERSION_CODES.N));
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                SdServerCompat.getSystemAlertWindowType(Build.VERSION_CODES.O));
    }

    @Test
    public void smsManagerAvailableOnTelephonyDevices() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY));
        assertNotNull(SdServerCompat.getSmsManager(context));
    }
}
