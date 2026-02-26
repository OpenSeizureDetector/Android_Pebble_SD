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

import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.alg.MlModelManager;

@RunWith(AndroidJUnit4.class)
public class MlModelManagerInstrumentedTest {
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void tearDown() throws Exception {
        // No-op
    }

    @Test
    public void testNoInstalledModelsReturnsEmptyList() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().remove(MlModelManager.PREF_INSTALLED_MODELS).commit();

        MlModelManager mm = new MlModelManager(context);
        assertTrue("No installed models should return empty list", mm.loadAllActiveModels().isEmpty());
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

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        JSONArray installed = new JSONArray();
        org.json.JSONObject model = new org.json.JSONObject();
        model.put("name", "Fake Device Model");
        model.put("fname", fakeModel.getName());
        model.put("framework", "tflite");
        model.put("localPath", fakeModel.getAbsolutePath());
        model.put("input_format_val", 1);
        model.put("input_size", 125);
        model.put("alarm_threshold", 2.0);
        installed.put(model);
        sp.edit().putString(MlModelManager.PREF_INSTALLED_MODELS, installed.toString()).commit();

        MlModelManager mm = new MlModelManager(context);
        java.util.List<MlModelManager.ModelLoadResult> results = mm.loadAllActiveModels();
        assertFalse("Downloaded model should load on device", results.isEmpty());
        MlModelManager.ModelLoadResult loaded = results.get(0);
        assertNotNull("Loaded model file should not be null", loaded.file);
        assertTrue("Loaded model file should exist", loaded.file.exists());
    }

    @Test
    public void testInvalidFilenameDownloadFailsOnDevice() throws Exception {
        String baseUrl = "http://127.0.0.1:1/";
        MlModelManager mm = new MlModelManager(context, baseUrl, "index.json");
        CountDownLatch latchDownload = new CountDownLatch(1);
        mm.downloadModel("bad_device.tflite", new AtomicBoolean(false), (ok, file) -> {
            assertFalse(ok);
            assertNull(file);
            latchDownload.countDown();
        });
        assertTrue(latchDownload.await(5, TimeUnit.SECONDS));
    }
}
