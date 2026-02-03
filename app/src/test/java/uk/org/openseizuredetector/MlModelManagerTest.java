package uk.org.openseizuredetector;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.alg.MlModelManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MlModelManagerTest {
    private Context context;
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @org.junit.Ignore("Covered by instrumentation tests; Volley not reliable in local unit tests")
    @Test
    public void testGetMlModelIndex_networkFailure() throws Exception {
        // Simulate network failure
        server.enqueue(new MockResponse().setResponseCode(500));
        String baseUrl = server.url("/static/ml_models/").toString();
        MlModelManager mm = new MlModelManager(context, baseUrl, "index.json");

        CountDownLatch latch = new CountDownLatch(1);
        final JSONArray[] result = new JSONArray[1];
        mm.getMlModelIndex(arr -> { result[0] = arr; latch.countDown(); });

        assertTrue("Callback not invoked", latch.await(2, TimeUnit.SECONDS));
        assertNull("Index should be null on failure", result[0]);
    }

    @org.junit.Ignore("Covered by instrumentation tests; Volley not reliable in local unit tests")
    @Test
    public void testGetMlModelIndex_invalidJson() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("not a json array"));
        String baseUrl = server.url("/static/ml_models/").toString();
        MlModelManager mm = new MlModelManager(context, baseUrl, "index.json");

        CountDownLatch latch = new CountDownLatch(1);
        final JSONArray[] result = new JSONArray[1];
        mm.getMlModelIndex(arr -> { result[0] = arr; latch.countDown(); });

        assertTrue("Callback not invoked", latch.await(2, TimeUnit.SECONDS));
        assertNull("Index should be null on invalid JSON", result[0]);
    }

    @Test
    public void testDownloadModel_cancelled() throws Exception {
        // Prepare a large response body to allow cancel
        byte[] body = new byte[1024 * 64];
        for (int i = 0; i < body.length; i++) body[i] = (byte)(i % 256);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new String(body)));
        String baseUrl = server.url("/static/ml_models/").toString();
        MlModelManager mm = new MlModelManager(context, baseUrl, "index.json");

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        mm.downloadModel("model.tflite", cancelFlag, (ok, file) -> {
            assertFalse("Download should be reported as failed after cancel", ok);
            assertNull("File should be null after cancel", file);
            latch.countDown();
        });
        // Cancel quickly
        cancelFlag.set(true);
        assertTrue("Callback not invoked", latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void testLoadModel_fallbackToBundledWhenNoDownloaded() throws Exception {
        // Ensure no downloaded model in prefs
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().remove("CnnModelFile").commit();

        MlModelManager mm = new MlModelManager(context);
        CountDownLatch latch = new CountDownLatch(1);
        final MlModelManager.ModelLoadResult[] result = new MlModelManager.ModelLoadResult[1];
        mm.loadModel(sp, modelResult -> { result[0] = modelResult; latch.countDown(); });

        assertTrue("Callback not invoked", latch.await(2, TimeUnit.SECONDS));
        // We can't assert non-null without real bundled asset setup; just assert callback occurred.
        // In real instrumentation tests, we'd include the asset and assert non-null.
        assertNotNull("Model should be loaded from bundled model if present", result[0]);
    }

    @Test
    public void testLoadModel_usesDownloadedIfPresent() throws Exception {
        // Create a fake local model file
        File dir = new File(context.getFilesDir(), "models");
        dir.mkdirs();
        File fakeModel = new File(dir, "fake.tflite");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(fakeModel);
        fos.write(new byte[128]);
        fos.close();

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putString("CnnModelFile", fakeModel.getAbsolutePath()).commit();

        MlModelManager mm = new MlModelManager(context);
        CountDownLatch latch = new CountDownLatch(1);
        final MlModelManager.ModelLoadResult[] result = new MlModelManager.ModelLoadResult[1];
        mm.loadModel(sp, modelResult -> { result[0] = modelResult; latch.countDown(); });

        assertTrue("Callback not invoked", latch.await(2, TimeUnit.SECONDS));
        assertNotNull("Model should be loaded from downloaded file", result[0]);
    }

    @org.junit.Ignore("Covered by instrumentation tests; use androidTest instead")
    @Test
    public void testDownloadModel_invalidFilenameInIndex() throws Exception {
        // Serve a valid index.json with a bad filename
        String indexBody = "[ {\"name\": \"Bad Model\", \"fname\": \"bad.tflite\", \"input_format\": 1 } ]";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(indexBody));
        String baseUrl = server.url("/static/ml_models/").toString();
        MlModelManager mm = new MlModelManager(context, baseUrl, "index.json");

        // First, fetch index
        CountDownLatch latchIndex = new CountDownLatch(1);
        final JSONArray[] result = new JSONArray[1];
        mm.getMlModelIndex(arr -> { result[0] = arr; latchIndex.countDown(); });
        assertTrue("Index callback not invoked", latchIndex.await(2, TimeUnit.SECONDS));
        assertNotNull("Index should not be null", result[0]);
        assertEquals(1, result[0].length());

        // Now simulate a 404 for the bad filename when attempting download
        server.enqueue(new MockResponse().setResponseCode(404));
        CountDownLatch latchDownload = new CountDownLatch(1);
        mm.downloadModel("bad.tflite", new AtomicBoolean(false), (ok, file) -> {
            assertFalse("Download should fail for invalid filename", ok);
            assertNull("File should be null for invalid filename", file);
            latchDownload.countDown();
        });
        assertTrue("Download callback not invoked", latchDownload.await(2, TimeUnit.SECONDS));
    }
}
