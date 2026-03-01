package uk.org.openseizuredetector.activity.bluetooth;
import uk.org.openseizuredetector.R;

/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import uk.org.openseizuredetector.utils.OsdUtil;
import static androidx.core.content.PermissionChecker.PERMISSION_GRANTED;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;

import uk.org.openseizuredetector.activity.startup.StartupActivity;
/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
public class BLEScanActivity extends AppCompatActivity {
    private LeDeviceListAdapter mLeDeviceListAdapter;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private boolean mScanning;
    private Handler mHandler;
    private boolean bleAvailable = false;

    private OsdUtil mUtil;
    private ListView mListView;

    private boolean mPermissionsRequested = false;
    private final String TAG = "BLEScanActivity";
    private ActivityResultLauncher<Intent> mEnableBtLauncher;

    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;

    private int okColour = Color.BLUE;
    private int warnColour = Color.MAGENTA;
    private int alarmColour = Color.RED;
    private int okTextColour = Color.WHITE;
    private int warnTextColour = Color.WHITE;
    private int alarmTextColour = Color.BLACK;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG,"onCreate()");
        setContentView(R.layout.ble_scan_activity);

        // Configure system bar appearance to be edge-to-edge and handle insets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean isLightMode = isLightTheme();
                controller.setAppearanceLightStatusBars(isLightMode);
                controller.setAppearanceLightNavigationBars(isLightMode);
            }
        }


        // Set ActionBar title using getSupportActionBar for AppCompatActivity
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Scan for Bluetooth Devices");
        }

        // Get reference to ListView from layout
        mListView = findViewById(R.id.list);

        mHandler = new Handler(Looper.getMainLooper());
        mUtil = new OsdUtil(this, mHandler);

        // Use this check to determine whether BLE is supported on the device
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Initializes a Bluetooth adapter
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        mPermissionsRequested = false;

        // Initialize the adapter once here
        mLeDeviceListAdapter = new LeDeviceListAdapter();
        mListView.setAdapter(mLeDeviceListAdapter);

        // Set the empty view so it only displays when list is empty
        View emptyView = findViewById(R.id.empty);
        mListView.setEmptyView(emptyView);

        // Handle device selection
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            final BluetoothDevice device = mLeDeviceListAdapter.getDevice(position);
            if (device == null) return;
            Log.i(TAG, "Device selected - Device Addr=" + device.getAddress());
            if (mScanning) {
                stopScan();
            }
            Log.i(TAG, "Saving Device Details");
            SharedPreferences.Editor SPE = PreferenceManager
                    .getDefaultSharedPreferences(this).edit();
            try {
                SPE.putString("BLE_Device_Addr", device.getAddress());
                SPE.putString("BLE_Device_Name", device.getName());
                SPE.apply();
                SPE.commit();

                Log.i(TAG, "Saved Device Name=" + device.getName() + " and Address=" + device.getAddress());
            } catch (SecurityException ex) {
                Log.e(TAG, "Error Saving Device Name and Address!");
                Toast toast = Toast.makeText(this, "Problem Saving Device Name and Address", Toast.LENGTH_SHORT);
                toast.show();
            }
            SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences((this));
            Log.i(TAG, "Check of saved values - Name=" + SP.getString("BLE_Device_Name", "SET_FROM_XML") + ", Addr=" + SP.getString("BLE_Device_Addr", "SET_FROM_XML"));

            Log.i(TAG, "Returning to onboarding with selected device");
            finish();
        });

        mEnableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_CANCELED) {
                        Log.i(TAG, "onActivityResult - Bluetooth not enabled");
                        finish();
                    }
                });
    }

    /**
     * Check if the current theme is light mode
     */
    private boolean isLightTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.ble_scan_menu, menu);
        if (!mScanning) {
            menu.findItem(R.id.menu_stop).setVisible(false);
            menu.findItem(R.id.menu_scan).setVisible(true);
            menu.findItem(R.id.menu_refresh).setActionView(null);
        } else {
            menu.findItem(R.id.menu_stop).setVisible(true);
            menu.findItem(R.id.menu_scan).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(
                    R.layout.actionbar_indeterminate_progress);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_scan) {
            mLeDeviceListAdapter.clear();
            scanLeDevice(true);
        } else if (itemId == R.id.menu_stop) {
            scanLeDevice(false);
        }
        return true;
    }


    public void onScanButtonClick(View v) {
        scanLeDevice(true);
    }

    public void onCancelButtonClick(View v) {
        Log.i(TAG, "Cancel button clicked - exiting without selecting device");
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.i(TAG, "onRequestPermissionsResult - requestCode=" + requestCode);

        if (requestCode == 42) {
            // Check if all permissions were granted
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.i(TAG, "onRequestPermissionsResult - All permissions granted, starting scan");
                scanLeDevice(true);
            } else {
                Log.e(TAG, "onRequestPermissionsResult - Some permissions denied");
                Toast.makeText(this, "Permissions required for Bluetooth scanning", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG,"onResume()");

        // Ensures Bluetooth is enabled on the device
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            mEnableBtLauncher.launch(enableBtIntent);
        }

        // Clear devices
        if (mLeDeviceListAdapter != null) {
            mLeDeviceListAdapter.clear();
        }

        // Check permissions and start scan only if permissions are OK
        if (!mUtil.areBtPermissionsOk()) {
            Log.i(TAG, "onResume - calling requestBTPermissions()");
            requestBTPermissions(this);
            // Scan will start after permissions are granted in onRequestPermissionsResult
        } else {
            Log.i(TAG, "onResume - Bluetooth Permissions OK, starting scan");
            scanLeDevice(true);
        }
    }

    // ...existing code...

    private void onListItemClick(int position) {
        final BluetoothDevice device = mLeDeviceListAdapter.getDevice(position);
        if (device == null) return;
        Log.v(TAG, "onListItemClick: Device Addr=" + device.getAddress());
        if (mScanning) {
            stopScan();
        }
        Log.v(TAG, "Saving Device Details");
        SharedPreferences.Editor SPE = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        try {
            SPE.putString("BLE_Device_Addr", device.getAddress());
            SPE.putString("BLE_Device_Name", device.getName());
            SPE.apply();
            SPE.commit();

            Log.v(TAG, "Saved Device Name=" + device.getName() + " and Address=" + device.getAddress());
        } catch (SecurityException ex) {
            Log.e(TAG, "Error Saving Device Name and Address!");
            Toast toast = Toast.makeText(this, "Problem Saving Device Name and Address", Toast.LENGTH_SHORT);
            toast.show();
        }
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences((this));
        Log.v(TAG, "Check of saved values - Name=" + SP.getString("BLE_Device_Name", "SET_FROM_XML") + ", Addr=" + SP.getString("BLE_Device_Addr", "SET_FROM_XML"));

        Log.i(TAG, "Restarting start-up activity so change takes effect");
        Intent i;
        i = new Intent(this, StartupActivity.class);
        startActivity(i);
        finish();
    }

    public void requestBTPermissions(Activity activity) {
        if (mPermissionsRequested) {
            Log.i(TAG, "requestBTPermissions() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestBTPermissions() - showing rationale (if necessary)");
            boolean showRationale = false;
            String btPermissions[] = mUtil.getRequiredBtPermissions();
            for (int i = 0; i < btPermissions.length; i++) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                        btPermissions[i])) {
                    Log.i(TAG, "shouldShowRationale for permission" + btPermissions[i]);
                    showRationale = true;
                    Toast toast = Toast.makeText(this, "Please give us permission! "+ btPermissions[i], Toast.LENGTH_SHORT);
                    toast.show();
                }
            }
            if (showRationale) {
                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                        new android.view.ContextThemeWrapper(this, R.style.AppTheme_AlertDialog));
                alertDialogBuilder
                        .setTitle(getString(R.string.permissions_required))
                        .setMessage("Additional Permissions are required to scan for Bluetooth Devices - please grant the permissions in the following dialogs")
                        .setCancelable(false)
                        .setNegativeButton(getString(R.string.closeBtnTxt), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                                finish();
                            }
                        })
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        })
                        .create()
                        .show();
            } else {
                Log.i(TAG,"requestBTPermissions() - rationale display not required");
            }

            Log.i(TAG, "requestBTPermissions() - requesting permissions");
            ActivityCompat.requestPermissions(activity,
                    btPermissions,
                    42);
            mPermissionsRequested = true;
        }
    }

    private void startScan() {
        if (!mScanning) {
            Log.i(TAG, "startScan() - starting BLE scan");
            mScanning = true;

            if (mBluetoothLeScanner != null) {
                Log.i(TAG, "startScan() - BluetoothLeScanner found, calling startScan()");

                try {
                    // Use simple scan without filters or settings - just the callback
                    mBluetoothLeScanner.startScan(mLeScanCallback);
                    Log.i(TAG, "startScan() - scan started");
                } catch (Exception e) {
                    Log.e(TAG, "startScan() - exception: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                Log.e(TAG, "startScan() - BluetoothLeScanner is null, cannot start scan");
            }
        } else {
            Log.i(TAG, "startScan() - already scanning, ignoring request");
        }
    }

    private void stopScan() {
        if (mScanning) {
            Log.i(TAG, "stopScan() - stopping BLE scan");
            mScanning = false;

            if (mBluetoothLeScanner != null) {
                try {
                    mBluetoothLeScanner.stopScan(mLeScanCallback);
                    Log.i(TAG, "stopScan() - scan stopped");
                } catch (Exception e) {
                    Log.e(TAG, "stopScan() - exception: " + e.getMessage());
                }
            } else {
                Log.e(TAG, "stopScan() - BluetoothLeScanner is null");
            }
        }
    }

    private void scanLeDevice(final boolean enable) {
        TextView tv;
        Log.i(TAG, "scanLeDevice(" + enable + ") called");

        if (enable) {
            Log.i(TAG, "scanLeDevice - enabling scan");

            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "scanLeDevice - timeout reached, stopping scan");
                    stopScan();
                    invalidateOptionsMenu();
                    TextView tv = (TextView) (findViewById(R.id.ble_scan_status_tv));
                    tv.setText("Scan complete");

                    Button b = (Button) findViewById(R.id.startScanButton);
                    b.setEnabled(true);
                }
            }, SCAN_PERIOD);

            startScan();
            tv = (TextView) (findViewById(R.id.ble_scan_status_tv));
            tv.setText("Scanning...");

            Button b = (Button) findViewById(R.id.startScanButton);
            b.setEnabled(false);

        } else {
            Log.i(TAG, "scanLeDevice - disabling scan");
            stopScan();
            tv = (TextView) (findViewById(R.id.ble_scan_status_tv));
            tv.setText("Ready to scan");
            Button b = (Button) findViewById(R.id.startScanButton);
            b.setEnabled(true);
        }
        invalidateOptionsMenu();
    }

    // Adapter for holding devices found through scanning.
    private class LeDeviceListAdapter extends BaseAdapter {
        private ArrayList<BluetoothDevice> mLeDevices;
        private ArrayList<BluetoothDevice> mPineTimeDevices;
        private ArrayList<BluetoothDevice> mOtherDevices;
        private LayoutInflater mInflator;

        public LeDeviceListAdapter() {
            super();
            mLeDevices = new ArrayList<BluetoothDevice>();
            mPineTimeDevices = new ArrayList<BluetoothDevice>();
            mOtherDevices = new ArrayList<BluetoothDevice>();
            mInflator = BLEScanActivity.this.getLayoutInflater();
        }

        public void addDevice(BluetoothDevice device) {
            if (!mLeDevices.contains(device)) {
                try {
                    Log.v(TAG, "addDevice - " + device.getName());
                } catch (SecurityException e) {
                    Log.e(TAG, "addDevice() - security exception getting device name");
                }

                // Check if device is PineTime or InfiniTime
                String deviceName = device.getName();
                if (deviceName != null && (deviceName.contains("PineTime") || deviceName.contains("InfiniTime"))) {
                    mPineTimeDevices.add(device);
                    // Insert PineTime devices at the beginning
                    mLeDevices.add(mPineTimeDevices.size() - 1, device);
                } else {
                    mOtherDevices.add(device);
                    // Add other devices at the end
                    mLeDevices.add(device);
                }
            }
        }

        public BluetoothDevice getDevice(int position) {
            return mLeDevices.get(position);
        }

        public void clear() {
            mLeDevices.clear();
            mPineTimeDevices.clear();
            mOtherDevices.clear();
        }

        @Override
        public int getCount() {
            return mLeDevices.size();
        }

        @Override
        public Object getItem(int i) {
            return mLeDevices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;
            Log.i(TAG, "scanner getView i=" + i);
            // General ListView optimization code.
            if (view == null) {
                Log.i(TAG, "scanner getView - inflating new view for position " + i);
                view = mInflator.inflate(R.layout.ble_list_item_device, null);
                viewHolder = new ViewHolder();
                viewHolder.deviceAddress = (TextView) view.findViewById(R.id.device_address);
                viewHolder.deviceName = (TextView) view.findViewById(R.id.device_name);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }

            BluetoothDevice device = mLeDevices.get(i);
            final String deviceName = device.getName();
            Log.i(TAG, "scanner getView - setting device name: " + deviceName);
            if (deviceName != null && deviceName.length() > 0)
                viewHolder.deviceName.setText(deviceName);
            else
                viewHolder.deviceName.setText(R.string.unknown_device);
            viewHolder.deviceAddress.setText(device.getAddress());

            // Check if this is a PineTime/InfiniTime device
            if (deviceName != null && (deviceName.contains("PineTime") || deviceName.contains("InfiniTime"))) {
                // PineTime device - use dark color for emphasis on light blue card background
                viewHolder.deviceName.setTextColor(ContextCompat.getColor(BLEScanActivity.this, android.R.color.black));
                viewHolder.deviceAddress.setTextColor(ContextCompat.getColor(BLEScanActivity.this, android.R.color.black));
            } else {
                // Other device - faint color (material design gray)
                viewHolder.deviceName.setTextColor(ContextCompat.getColor(BLEScanActivity.this, android.R.color.darker_gray));
                viewHolder.deviceAddress.setTextColor(ContextCompat.getColor(BLEScanActivity.this, android.R.color.darker_gray));
            }

            return view;
        }
    }

    // Device scan callback.
    private ScanCallback mLeScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    try {
                        String deviceName = result.getDevice().getName();
                        Log.v(TAG, "ScanCallback - onScanResult: device=" + deviceName + ", addr=" + result.getDevice().getAddress());

                        if (mLeDeviceListAdapter != null) {
                            mLeDeviceListAdapter.addDevice(result.getDevice());
                            mLeDeviceListAdapter.notifyDataSetChanged();
                            Log.v(TAG, "ScanCallback - adapter notified, total devices: " + mLeDeviceListAdapter.getCount());
                        } else {
                            Log.e(TAG, "ScanCallback - mLeDeviceListAdapter is null!");
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "ScanCallback - security exception getting device name");
                    }
                }

                @Override
                public void onBatchScanResults(java.util.List<ScanResult> results) {
                    Log.i(TAG, "ScanCallback - onBatchScanResults: " + results.size() + " results");
                    for (ScanResult result : results) {
                        try {
                            String deviceName = result.getDevice().getName();
                            Log.v(TAG, "ScanCallback - batch result: device=" + deviceName + ", addr=" + result.getDevice().getAddress());

                            if (mLeDeviceListAdapter != null) {
                                mLeDeviceListAdapter.addDevice(result.getDevice());
                            }
                        } catch (SecurityException e) {
                            Log.e(TAG, "ScanCallback - batch security exception");
                        }
                    }
                    if (mLeDeviceListAdapter != null) {
                        mLeDeviceListAdapter.notifyDataSetChanged();
                        Log.v(TAG, "ScanCallback - batch adapter notified, total devices: " + mLeDeviceListAdapter.getCount());
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "ScanCallback - onScanFailed: errorCode=" + errorCode);
                    switch (errorCode) {
                        case SCAN_FAILED_ALREADY_STARTED:
                            Log.e(TAG, "Scan failed: SCAN_FAILED_ALREADY_STARTED");
                            break;
                        case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                            Log.e(TAG, "Scan failed: SCAN_FAILED_APPLICATION_REGISTRATION_FAILED");
                            break;
                        case SCAN_FAILED_INTERNAL_ERROR:
                            Log.e(TAG, "Scan failed: SCAN_FAILED_INTERNAL_ERROR");
                            break;
                        case SCAN_FAILED_FEATURE_UNSUPPORTED:
                            Log.e(TAG, "Scan failed: SCAN_FAILED_FEATURE_UNSUPPORTED");
                            break;
                        default:
                            Log.e(TAG, "Scan failed: UNKNOWN_ERROR");
                    }
                }
            };

    static class ViewHolder {
        TextView deviceName;
        TextView deviceAddress;
    }


}
