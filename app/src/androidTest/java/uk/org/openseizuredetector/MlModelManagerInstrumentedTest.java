package uk.org.openseizuredetector;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

@RunWith(AndroidJUnit4.class)
public class MlModelManagerInstrumentedTest {
    private Context context;
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void testBundledFallbackLoadsOnDevice() throws Exception {
        SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().remove("CnnModelFile").commit();

        MlModelManager mm = new MlModelManager(context);
        CountDownLatch latch = new CountDownLatch(1);
        final java.nio.MappedByteBuffer[] buf = new java.nio.MappedByteBuffer[1];
        mm.loadModel(sp, buffer -> { buf[0] = buffer; latch.countDown(); });

        assertTrue("Callback not invoked", latch.await(5, TimeUnit.SECONDS));
        assertNotNull("Bundled model should load on device", buf[0]);
    }

    @Test
    public void testDownloadedModelLoadsOnDevice() throws Exception {
        // Create fake file to simulate downloaded model
        File dir = new File(context.getFilesDir(), "models");
        dir.mkdirs();
        File fakeModel = new File(dir, "fake_device.tflite");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(fakeModel);
        fos.write(new byte[256]);
        fos.close();

        SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putString("CnnModelFile", fakeModel.getAbsolutePath()).commit();

        MlModelManager mm = new MlModelManager(context);
        CountDownLatch latch = new CountDownLatch(1);
        final java.nio.MappedByteBuffer[] buf = new java.nio.MappedByteBuffer[1];
        mm.loadModel(sp, buffer -> { buf[0] = buffer; latch.countDown(); });

        assertTrue("Callback not invoked", latch.await(5, TimeUnit.SECONDS));
        assertNotNull("Downloaded model should load on device", buf[0]);
    }

    @Test
    public void testInvalidFilenameDownloadFailsOnDevice() throws Exception {
        // Serve index JSON first
        String indexBody = "[ {\"name\": \"Bad Model\", \"fname\": \"bad_device.tflite\", \"input_format\": 1 } ]";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(indexBody));
        String baseUrl = server.url("/static/ml_models/").toString();
        MlModelManager mm = new MlModelManager(context, baseUrl, "index.json");

        CountDownLatch latchIndex = new CountDownLatch(1);
        final JSONArray[] result = new JSONArray[1];
        mm.getMlModelIndex(arr -> { result[0] = arr; latchIndex.countDown(); });
        assertTrue(latchIndex.await(5, TimeUnit.SECONDS));
        assertNotNull(result[0]);

        // Then 404 on download
        server.enqueue(new MockResponse().setResponseCode(404));
        CountDownLatch latchDownload = new CountDownLatch(1);
        mm.downloadModel("bad_device.tflite", new AtomicBoolean(false), (ok, file) -> {
            assertFalse(ok);
            assertNull(file);
            latchDownload.countDown();
        });
        assertTrue(latchDownload.await(5, TimeUnit.SECONDS));
    }
}

