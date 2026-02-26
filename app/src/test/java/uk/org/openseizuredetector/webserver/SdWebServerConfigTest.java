package uk.org.openseizuredetector.webserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import uk.org.openseizuredetector.SdServer;
import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.PersistentFileLogger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SdWebServerConfigTest {
    private Context context;
    private SdWebServer server;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        SdServer sdServer = Mockito.mock(SdServer.class);
        server = new SdWebServer(context, new SdData(), sdServer);
    }

    @Test
    public void getConfigReturnsJsonWithKnownPreference() throws Exception {
        NanoHTTPD.Response response = server.serve(new TestSession(NanoHTTPD.Method.GET, "/config", null));
        assertEquals(NanoHTTPD.Response.Status.OK, response.getStatus());

        String body = readBody(response);
        JSONObject json = new JSONObject(body);
        assertTrue(json.has("DataSource"));
    }

    @Test
    public void postConfigUpdatesExistingPreference() throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean("AutoPruneDb", false).commit();

        String payload = "{\"AutoPruneDb\":true}";
        NanoHTTPD.Response response = server.serve(new TestSession(NanoHTTPD.Method.POST, "/config", payload));
        assertEquals(NanoHTTPD.Response.Status.OK, response.getStatus());

        String body = readBody(response);
        JSONObject result = new JSONObject(body);
        assertTrue(result.has("updatedKeys"));
        assertTrue(prefs.getBoolean("AutoPruneDb", false));
    }

    @Test
    public void postConfigIgnoresUnknownPreference() throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertFalse(prefs.contains("UnknownPref"));

        String payload = "{\"UnknownPref\":123}";
        NanoHTTPD.Response response = server.serve(new TestSession(NanoHTTPD.Method.POST, "/config", payload));
        assertEquals(NanoHTTPD.Response.Status.OK, response.getStatus());

        String body = readBody(response);
        JSONObject result = new JSONObject(body);
        assertTrue(arrayContains(result.optJSONArray("ignoredKeys"), "UnknownPref"));
        assertFalse(prefs.contains("UnknownPref"));
    }

    @Test
    public void logsEndpointListsAndReturnsFiles() throws Exception {
        PersistentFileLogger logger = getServerLogger();
        File dataDir = logger.getDataLogDir();
        File persistentDir = logger.getLogDir();
        if (!persistentDir.exists()) {
            assertTrue(persistentDir.mkdirs());
        }

        File dataFile = new File(dataDir, "DataLog_test.txt");
        File persistentFile = new File(persistentDir, "osd_log_2099-01-01.txt");
        writeFile(dataFile, "data-log-content");
        writeFile(persistentFile, "persistent-log-content");

        NanoHTTPD.Response listResponse = server.serve(new TestSession(NanoHTTPD.Method.GET, "/logs", null));
        String listBody = readBody(listResponse);
        JSONObject listJson = new JSONObject(listBody);
        JSONArray files = listJson.optJSONArray("logFileList");
        assertTrue(arrayContains(files, dataFile.getName()));
        assertTrue(arrayContains(files, persistentFile.getName()));

        NanoHTTPD.Response dataResponse = server.serve(new TestSession(NanoHTTPD.Method.GET, "/logs/" + dataFile.getName(), null));
        String dataBody = readBody(dataResponse);
        assertTrue(dataBody.contains("data-log-content"));

        NanoHTTPD.Response persistentResponse = server.serve(new TestSession(NanoHTTPD.Method.GET, "/logs/" + persistentFile.getName(), null));
        String persistentBody = readBody(persistentResponse);
        assertTrue(persistentBody.contains("persistent-log-content"));
    }

    private static String readBody(NanoHTTPD.Response response) throws Exception {
        InputStream inputStream = response.getData();
        if (inputStream == null) {
            return "";
        }
        byte[] buffer = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static boolean arrayContains(JSONArray array, String value) {
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static void writeFile(File file, String contents) throws Exception {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            assertTrue(file.getParentFile().mkdirs());
        }
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(contents);
        }
    }

    private PersistentFileLogger getServerLogger() throws Exception {
        java.lang.reflect.Field utilField = SdWebServer.class.getDeclaredField("mUtil");
        utilField.setAccessible(true);
        Object util = utilField.get(server);
        java.lang.reflect.Method getLogger = util.getClass().getDeclaredMethod("getFileLogger");
        getLogger.setAccessible(true);
        return (PersistentFileLogger) getLogger.invoke(util);
    }

    private static class TestSession implements NanoHTTPD.IHTTPSession {
        private final NanoHTTPD.Method method;
        private final String uri;
        private final String body;
        private final Map<String, String> headers = new HashMap<>();
        private final Map<String, String> parms = new HashMap<>();

        private TestSession(NanoHTTPD.Method method, String uri, String body) {
            this.method = method;
            this.uri = uri;
            this.body = body;
        }

        @Override
        public void execute() {
            // Not needed for unit tests.
        }

        @Override
        public Map<String, String> getParms() {
            return parms;
        }

        @Override
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public String getUri() {
            return uri;
        }

        @Override
        public String getQueryParameterString() {
            return null;
        }

        @Override
        public NanoHTTPD.Method getMethod() {
            return method;
        }

        @Override
        public InputStream getInputStream() {
            if (body == null) {
                return new ByteArrayInputStream(new byte[0]);
            }
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public NanoHTTPD.CookieHandler getCookies() {
            return null;
        }

        @Override
        public void parseBody(Map<String, String> files) {
            if (body != null) {
                files.put("postData", body);
            }
        }
    }
}
