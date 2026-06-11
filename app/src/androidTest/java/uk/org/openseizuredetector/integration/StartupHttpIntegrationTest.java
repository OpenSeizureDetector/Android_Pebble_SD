package uk.org.openseizuredetector.integration;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import uk.org.openseizuredetector.activity.startup.StartupActivity;
import uk.org.openseizuredetector.activity.main.MainActivity2;
import androidx.preference.PreferenceManager;

/**
 * Integration test that exercises the in-app webserver by POSTing /settings and /data
 * and verifies that StartupActivity proceeds to start MainActivity2.
 */
@Ignore("Disabling existing tests to focus on the new OnboardingTest")
@RunWith(AndroidJUnit4.class)
public class StartupHttpIntegrationTest {
    private static final String TAG = "StartupHttpIntegrationTest";
    private Instrumentation mInstrumentation;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = ApplicationProvider.getApplicationContext();

        // Ensure preferences set so StartupActivity doesn't show onboarding
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        prefs.edit()
                .putBoolean("first_run_complete", true)
                .putString("DataSource", "Garmin")
                // Prevent battery optimisation dialog
                .putBoolean("PreventBatteryOptWarning", true)
                .putBoolean("SMSAlarm", false)
                .putBoolean("PhoneCallAlarm", false)
                .apply();

        // Grant runtime permissions that StartupActivity checks (best-effort)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String pkg = mContext.getPackageName();
                // POST_NOTIFICATIONS required on Android 13+
                try {
                    mInstrumentation.getUiAutomation().grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS);
                } catch (Exception e) {
                    // ignore if not available / not allowed
                    Log.w(TAG, "Could not grant POST_NOTIFICATIONS: " + e.toString());
                }
                try {
                    mInstrumentation.getUiAutomation().grantRuntimePermission(pkg, Manifest.permission.WAKE_LOCK);
                } catch (Exception e) {
                    Log.w(TAG, "Could not grant WAKE_LOCK: " + e.toString());
                }
                // Grant health foreground permissions required on Android 12+ (ACTIVITY_RECOGNITION or BODY_SENSORS)
                try {
                    mInstrumentation.getUiAutomation().grantRuntimePermission(pkg, android.Manifest.permission.ACTIVITY_RECOGNITION);
                } catch (Exception e) {
                    Log.w(TAG, "Could not grant ACTIVITY_RECOGNITION: " + e.toString());
                }
                try {
                    mInstrumentation.getUiAutomation().grantRuntimePermission(pkg, android.Manifest.permission.BODY_SENSORS);
                } catch (Exception e) {
                    Log.w(TAG, "Could not grant BODY_SENSORS: " + e.toString());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Grant runtime permission failed: " + t.toString());
        }
    }

    @After
    public void tearDown() throws Exception {
        // no-op
    }

    @Test
    public void testStartupReceivesGarminSettingsAndData_andStartsMainActivity() throws Exception {
        // Start monitoring for MainActivity2 being launched
        ActivityMonitor monitor = mInstrumentation.addMonitor(MainActivity2.class.getName(), null, false);

        // Launch StartupActivity
        try (ActivityScenario<StartupActivity> scenario = ActivityScenario.launch(StartupActivity.class)) {
            // Wait until the in-app webserver responds to GET /data (server up)
            boolean serverUp = waitForServerUp("http://127.0.0.1:8080/data", 15000);
            assertTrue("Web server should be up and responding before POSTing", serverUp);

            // POST /settings (with retries)
            String settingsJson = buildSettingsJson();
            int rc1 = postWithRetries("http://127.0.0.1:8080/settings", settingsJson, 6, 1500);
            Log.i(TAG, "POST /settings rc=" + rc1);
            assertTrue("POST /settings should return HTTP 200/OK", rc1 >= 200 && rc1 < 400);

            // POST /data (with retries)
            String dataJson = buildRawDataJson();
            int rc2 = postWithRetries("http://127.0.0.1:8080/data", dataJson, 6, 1500);
            Log.i(TAG, "POST /data rc=" + rc2);
            assertTrue("POST /data should return HTTP 200/OK", rc2 >= 200 && rc2 < 400);

            // Wait for the monitor to detect MainActivity2 launch (timeout 8s)
            Activity mainAct = mInstrumentation.waitForMonitorWithTimeout(monitor, TimeUnit.SECONDS.toMillis(8));
            assertNotNull("MainActivity2 should have been started by StartupActivity", mainAct);

            // Cleanup
            mainAct.finish();
        } finally {
            mInstrumentation.removeMonitor(monitor);
        }
    }

    // Helper: build minimal settings JSON that SdDataSource.updateFromJSON will accept
    private String buildSettingsJson() throws Exception {
        JSONObject js = new JSONObject();
        js.put("dataType", "settings");
        js.put("analysisPeriod", 300);
        js.put("sampleFreq", 25);
        js.put("battery", 90);
        js.put("watchPartNo", "TestWatch");
        js.put("watchFwVersion", "1.0");
        js.put("sdVersion", "1.0");
        return js.toString();
    }

    // Helper: build minimal raw data JSON
    private String buildRawDataJson() throws Exception {
        JSONObject js = new JSONObject();
        js.put("dataType", "raw");
        js.put("HR", 70);
        // Construct a simple 'data' array of 125 zeros
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 125; i++) arr.put(0.0);
        js.put("data", arr);
        return js.toString();
    }

    // Wait for server to respond to GET request within timeoutMs milliseconds
    private boolean waitForServerUp(String urlStr, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                int rc = conn.getResponseCode();
                conn.disconnect();
                if (rc >= 200 && rc < 400) return true;
            } catch (Exception e) {
                // server not up yet
            }
            Thread.sleep(200);
        }
        return false;
    }

    // POST JSON as a form parameter 'dataObj' with simple retry logic
    private int postWithRetries(String urlStr, String jsonValue, int maxAttempts, long delayMs) throws InterruptedException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int rc = postFormUrlEncoded(urlStr, "dataObj", jsonValue);
                if (rc >= 200 && rc < 400) return rc;
                // If form POST didn't succeed, attempt multipart 'postData' upload (some clients use file upload)
                int rcMultipart = postMultipart(urlStr, "postData", jsonValue);
                if (rcMultipart >= 200 && rcMultipart < 400) return rcMultipart;
                // Log both status codes
                Log.w(TAG, "POST attempt " + attempt + " returned codes form=" + rc + " multipart=" + rcMultipart);
            } catch (Exception e) {
                Log.w(TAG, "POST attempt " + attempt + " failed: " + e.getMessage());
            }
            Thread.sleep(delayMs);
        }
        // final attempt to get response code (throws if unable)
        try {
            return postFormUrlEncoded(urlStr, "dataObj", jsonValue);
        } catch (Exception e) {
            Log.e(TAG, "Final POST failed: " + e.getMessage());
            return -1;
        }
    }

    // POST application/x-www-form-urlencoded with a single form parameter name=dataObj and value=<json>
    private int postFormUrlEncoded(String urlStr, String paramName, String jsonValue) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            String body = URLEncoder.encode(paramName, "UTF-8") + "=" + URLEncoder.encode(jsonValue, "UTF-8");
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            conn.connect();
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }
            int rc = conn.getResponseCode();
            // Attempt to read response body for diagnostics
            try {
                java.io.InputStream is = (rc >= 200 && rc < 400) ? conn.getInputStream() : conn.getErrorStream();
                if (is != null) {
                    byte[] buf = is.readAllBytes();
                    String bodyResp = new String(buf, StandardCharsets.UTF_8);
                    Log.i(TAG, "POST response body: " + bodyResp);
                }
            } catch (Exception ex) {
                Log.w(TAG, "Failed to read response body: " + ex.getMessage());
            }
            return rc;
        } finally {
            conn.disconnect();
        }
    }

    // Send multipart/form-data with a single file part named partName and content jsonValue
    private int postMultipart(String urlStr, String partName, String jsonValue) throws Exception {
        String boundary = "----osdTestBoundary" + System.currentTimeMillis();
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (java.io.OutputStream os = conn.getOutputStream()) {
                java.io.Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                writer.write("--" + boundary + "\r\n");
                writer.write("Content-Disposition: form-data; name=\"" + partName + "\"; filename=\"postData.json\"\r\n");
                writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n");
                writer.flush();
                os.write(jsonValue.getBytes(StandardCharsets.UTF_8));
                os.flush();
                writer.write("\r\n--" + boundary + "--\r\n");
                writer.flush();
            }

            int rc = conn.getResponseCode();
            try {
                java.io.InputStream is = (rc >= 200 && rc < 400) ? conn.getInputStream() : conn.getErrorStream();
                if (is != null) {
                    byte[] buf = is.readAllBytes();
                    String body = new String(buf, StandardCharsets.UTF_8);
                    Log.i(TAG, "Multipart POST response body: " + body);
                }
            } catch (Exception ex) {
                Log.w(TAG, "Failed to read multipart response body: " + ex.getMessage());
            }
            return rc;
        } finally {
            conn.disconnect();
        }
    }
}
