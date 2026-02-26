package uk.org.openseizuredetector.ble;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;

@RunWith(AndroidJUnit4.class)
public class BLEScanActivityTest {

    @Test
    public void showsListWithEmptyViewAndStatusText() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue(appContext.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE));

        BluetoothManager bluetoothManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        Assume.assumeTrue("Bluetooth adapter required for this test", adapter != null);
        Assume.assumeTrue("Bluetooth must be enabled for this test", adapter.isEnabled());

        try (ActivityScenario<BLEScanActivity> scenario = ActivityScenario.launch(BLEScanActivity.class)) {
            scenario.onActivity(activity -> {
                ListView listView = activity.findViewById(R.id.list);
                TextView emptyView = activity.findViewById(R.id.empty);
                TextView statusView = activity.findViewById(R.id.ble_scan_status_tv);
                Button startScanButton = activity.findViewById(R.id.startScanButton);

                assertNotNull(listView);
                assertNotNull(emptyView);
                assertSame(emptyView, listView.getEmptyView());
                assertNotNull(statusView);
                assertNotNull(startScanButton);
                assertNotNull(listView.getAdapter());

                String statusText = statusView.getText().toString();
                List<String> allowed = Arrays.asList("Ready to scan", "Scanning...");
                assertTrue("Unexpected status text: " + statusText, allowed.contains(statusText));
            });
        }
    }
}

