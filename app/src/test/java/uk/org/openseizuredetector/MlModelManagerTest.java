package uk.org.openseizuredetector;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
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
        MlModelManager mm = new MlModelManager(context);
        // set private fields to point at mock server
        java.lang.reflect.Field f = mm.getClass().getDeclaredField("mUrlBase");
        f.setAccessible(true);
        f.set(mm, baseUrl);
        java.lang.reflect.Field f2 = mm.getClass().getDeclaredField("mModelIndexFname");
        f2.setAccessible(true);
        f2.set(mm, "index.json");

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
        MlModelManager mm = new MlModelManager(context);
        java.lang.reflect.Field f = mm.getClass().getDeclaredField("mUrlBase");
        f.setAccessible(true);
        f.set(mm, baseUrl);

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
        MlModelManager mm = new MlModelManager(context);
        java.lang.reflect.Field f = mm.getClass().getDeclaredField("mUrlBase");
        f.setAccessible(true);
        f.set(mm, baseUrl);

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
        // Ensure no downloaded models in prefs
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().remove(MlModelManager.PREF_INSTALLED_MODELS).commit();

        MlModelManager mm = new MlModelManager(context);
        List<MlModelManager.ModelLoadResult> results = mm.loadAllActiveModels();

        assertNotNull("Result list should not be null", results);
        // We can't assert non-empty without real bundled asset setup; just assert callback occurred.
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

        // Register the fake model in the installed models preference
        JSONArray arr = new JSONArray();
        JSONObject obj = new JSONObject();
        obj.put("fname", "fake.tflite");
        obj.put("localPath", fakeModel.getAbsolutePath());
        obj.put("framework", "tflite");
        obj.put("input_format_val", 1);
        obj.put("input_size", 125);
        obj.put("alarm_threshold", 2.0);
        arr.put(obj);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putString(MlModelManager.PREF_INSTALLED_MODELS, arr.toString()).commit();

        MlModelManager mm = new MlModelManager(context);
        List<MlModelManager.ModelLoadResult> results = mm.loadAllActiveModels();

        assertNotNull("Result list should not be null", results);
        assertTrue("Model should be loaded from installed list", results.size() > 0);
    }

    @org.junit.Ignore("Covered by instrumentation tests; use androidTest instead")
    @Test
    public void testDownloadModel_invalidFilenameInIndex() throws Exception {
        // Serve a valid index.json with a bad filename
        String indexBody = "[ {\"name\": \"Bad Model\", \"fname\": \"bad.tflite\", \"input_format\": 1 } ]";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(indexBody));
        String baseUrl = server.url("/static/ml_models/").toString();
        MlModelManager mm = new MlModelManager(context);
        java.lang.reflect.Field f = mm.getClass().getDeclaredField("mUrlBase");
        f.setAccessible(true);
        f.set(mm, baseUrl);

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
