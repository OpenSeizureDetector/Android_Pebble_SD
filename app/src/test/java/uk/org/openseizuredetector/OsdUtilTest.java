package uk.org.openseizuredetector;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.TelephonyManager;

import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ServiceTestRule;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.any;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.Matchers.is;

import static org.junit.Assert.*;

import com.google.android.gms.common.internal.safeparcel.SafeParcelable;

/**
 * Created by graham on 01/01/16.
 * Modified by bram on 22/04/23
 */
@MediumTest
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.TIRAMISU}, packageName = "uk.org.openseizuredetector")
public class OsdUtilTest <T extends Service> extends ServiceTestRule{

    Class<T> mServiceClass;

    private Context mSystemContext;
    private Application mApplication;

    //public OsdUtilTest(Class<T> serviceClass) {
    //    mServiceClass = serviceClass;
    //}
    public OsdUtilTest(){}


    private T mService;
    private boolean mServiceAttached = false;
    private boolean mServiceCreated = false;
    private boolean mServiceStarted = false;
    private boolean mServiceBound = false;
    private Intent mServiceIntent = null;
    private int mServiceId;

    @Rule
    public final ServiceTestRule mServiceRule = ServiceTestRule.withTimeout(100L,TimeUnit.SECONDS);




    Context testContext;

    class LocalServiceTestCase extends ServiceTestCase<SdServer>{

        public LocalServiceTestCase(Class<SdServer> serviceClass) {
            super(serviceClass);
        }
    }
    @Test
    public void testUsingServiceTestCase() throws Exception {
        LocalServiceTestCase serviceTestCase = new LocalServiceTestCase(SdServer.class);
        serviceTestCase.setUp();
        serviceTestCase.setupService();
        Intent sdServerIntent = new Intent(InstrumentationRegistry.getInstrumentation().getContext(), SdServer.class)
                .setData(Constants.GLOBAL_CONSTANTS.mStartUri);
        serviceTestCase.startService(sdServerIntent);
    }

    @Test
    public void testWithBoundService() throws TimeoutException {
        // Create the service Intent.
        Intent serviceIntent =
                new Intent(getApplicationContext(), SdServer.class);

        // Data can be passed to the service via the Intent.
        serviceIntent.setData(Constants.GLOBAL_CONSTANTS.mStartUri);

        // Bind the service and grab a reference to the binder.
        IBinder binder = mServiceRule.bindService(serviceIntent);

        // Get the reference to the service, or you can call public methods on the binder directly.
        SdServer service = ((SdServer.SdBinder) binder).getService();

        assertTrue(Objects.nonNull(service));
        // Verify that the service is working correctly.
        //assertThat(service.getRandomInt(), CoreMatchers.is(any(Integer.class)));
    }

    @Test
    public void testIsServerNotRunning() throws Exception {
        if (Objects.isNull(testContext))
            testContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClassLoader cl = InstrumentationRegistry.getArguments().getClassLoader();
        Application testApplication = InstrumentationRegistry.getInstrumentation().newApplication(cl,SdServer.class.getName(),testContext);
        assertEquals("uk.org.openseizuredetector", testContext.getPackageName());
        Looper looper = testContext.getMainLooper();
        Handler handler = new Handler(looper);
        OsdUtil util = new OsdUtil(testContext,handler);
        assertFalse(util.isServerRunning());
    }

    @Test
    public void testStartServer() throws Exception, TimeoutException {
        //Activity a = new Activity();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context tContext = ApplicationProvider.getApplicationContext();
        instrumentation.start();


        assertTrue(true);
        if (Objects.isNull(testContext)) {

            testContext = tContext;//instrumentation.getTargetContext();
        }


        assertEquals("uk.org.openseizuredetector", testContext.getPackageName());
        ClassLoader cl = InstrumentationRegistry.getArguments().getClassLoader();
        cl.loadClass(SdServer.class.getName());

        //Application testApplication = InstrumentationRegistry.getInstrumentation().newApplication(cl,this.zzgetName(),testContext);
        //testContext.


        Looper looper = testContext.getMainLooper();
        Handler handler = new Handler(looper);
        OsdUtil util = new OsdUtil(testContext,handler);
        assertFalse(util.isServerRunning());
        Class<SdServer> SdServerClass;
        SdServerClass = (Class<SdServer>) Class.forName(SdServer.class.getName(),true,cl);
        mService = (T) SdServerClass.newInstance();
        Intent sdServerIntent = new Intent(testContext, SdServer.class);
        sdServerIntent.setData(Uri.parse("Start"));
        util.startServer();
        mServiceRule.startService(sdServerIntent);
        mService.onCreate();

        mService.onStartCommand(sdServerIntent,Service.START_FLAG_REDELIVERY,new Random().nextInt());
        IBinder mServiceBinder = mService.onBind(sdServerIntent);
        SdServiceConnection sdServiceConnection = new SdServiceConnection(testContext);
        testContext.startForegroundService(sdServerIntent);
        IBinder serviceIBinder = mServiceRule.bindService(sdServerIntent,sdServiceConnection,Service.BIND_AUTO_CREATE);

        mServiceRule.startService(sdServerIntent);
        mServiceRule.notify();
        Binder serviceBinder = (SdServer.SdBinder) mServiceRule.bindService(sdServerIntent);

        assertTrue(true);
        //assertThat(true, is(false));
    }

    @Test
    public void testIsServerRunning() throws Exception {
        if (Objects.isNull(testContext))
            testContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertEquals("uk.org.openseizuredetector", testContext.getPackageName());
        Looper looper = testContext.getMainLooper();
        Handler handler = new Handler(looper);
        OsdUtil util = new OsdUtil(testContext,handler);
        assertTrue(util.isServerRunning());
    }

    @Test
    public void testStopServer() throws Exception {
        if (Objects.isNull(testContext))
            testContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertEquals("uk.org.openseizuredetector", testContext.getPackageName());
        Looper looper = testContext.getMainLooper();
        Handler handler = new Handler(looper);
        OsdUtil util = new OsdUtil(testContext,handler);
        if (util.isServerRunning())
            assertTrue(util.isServerRunning());
        util.stopServer();
        assertFalse(util.isServerRunning());
    }
}