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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.data.logging.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
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

import com.google.android.material.button.MaterialButton;

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
    private int mSelectedPosition = -1;

    private OsdUtil mUtil;
    private ListView mListView;
    private MaterialButton mScanButtonFilled;
    private MaterialButton mScanButtonOutlined;
    private MaterialButton mSelectButtonFilled;
    private MaterialButton mSelectButtonOutlined;
    private TextView mBleScanStatusTv;


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
        mScanButtonFilled = findViewById(R.id.scanButtonFilled);
        mScanButtonOutlined = findViewById(R.id.scanButtonOutlined);
        mSelectButtonFilled = findViewById(R.id.selectButtonFilled);
        mSelectButtonOutlined = findViewById(R.id.selectButtonOutlined);
        mBleScanStatusTv = findViewById(R.id.ble_scan_status_tv);

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
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);


        // Set the empty view so it only displays when list is empty
        View emptyView = findViewById(R.id.empty);
        mListView.setEmptyView(emptyView);

        // Handle device selection
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            mSelectedPosition = position;
            mLeDeviceListAdapter.notifyDataSetChanged();
            updateButtonStyles();
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
        // Menu is no longer needed, buttons are on the layout
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Menu is no longer needed
        return false;
    }


    public void onScanButtonClick(View v) {
        mLeDeviceListAdapter.clear();
        scanLeDevice(true);
        updateButtonStyles();
    }

    public void onSelectButtonClick(View v) {
        if (mSelectedPosition == -1) {
            Toast.makeText(this, "Please select a device", Toast.LENGTH_SHORT).show();
            return;
        }
        final BluetoothDevice device = mLeDeviceListAdapter.getDevice(mSelectedPosition);
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

            Log.i(TAG, "Saved Device Name=" + device.getName() + " and Address=" + device.getAddress());
        } catch (SecurityException ex) {
            Log.e(TAG, "Error Saving Device Name and Address!");
            Toast.makeText(this, "Problem Saving Device Name and Address", Toast.LENGTH_SHORT).show();
        }
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences((this));
        Log.i(TAG, "Check of saved values - Name=" + SP.getString("BLE_Device_Name", "SET_FROM_XML") + ", Addr=" + SP.getString("BLE_Device_Addr", "SET_FROM_XML"));

        Log.i(TAG, "Returning to previous screen with selected device");
        finish();
    }

    public void onCancelButtonClick(View v) {
        Log.i(TAG, "Cancel button clicked - exiting without selecting device");
        finish();
    }

    private void updateButtonStyles() {
        if (mSelectedPosition != -1) {
            // An item is selected, "Select" is primary
            mSelectButtonFilled.setVisibility(View.VISIBLE);
            mSelectButtonOutlined.setVisibility(View.GONE);
            mSelectButtonFilled.setEnabled(true);

            mScanButtonFilled.setVisibility(View.GONE);
            mScanButtonOutlined.setVisibility(View.VISIBLE);
        } else {
            // No item selected
            // Select button is disabled and outlined in both sub-cases
            mSelectButtonFilled.setVisibility(View.GONE);
            mSelectButtonOutlined.setVisibility(View.VISIBLE);
            mSelectButtonOutlined.setEnabled(false);

            if (mScanning) {
                // Scanning, no primary action -> Scan is Outlined
                mScanButtonFilled.setVisibility(View.GONE);
                mScanButtonOutlined.setVisibility(View.VISIBLE);
            } else {
                // Not scanning, "Scan" is primary -> Scan is Filled
                mScanButtonFilled.setVisibility(View.VISIBLE);
                mScanButtonOutlined.setVisibility(View.GONE);
            }
        }
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
            mSelectedPosition = -1;
            updateButtonStyles();
            if (mBleScanStatusTv != null) mBleScanStatusTv.setText("Scanning...");

            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "scanLeDevice - timeout reached, stopping scan");
                    stopScan();
                    updateButtonStyles();
                    if (mBleScanStatusTv != null) mBleScanStatusTv.setText("Scan complete");
                    TextView tv = (TextView) (findViewById(R.id.empty));
                    if (mLeDeviceListAdapter.getCount() == 0) {
                        tv.setText("No devices found.");
                        tv.setVisibility(View.VISIBLE);
                    } else {
                        tv.setVisibility(View.GONE);
                    }
                }
            }, SCAN_PERIOD);

            startScan();
            tv = (TextView) (findViewById(R.id.empty));
            tv.setText("Scanning for devices...");
            tv.setVisibility(View.VISIBLE);


        } else {
            Log.i(TAG, "scanLeDevice - disabling scan");
            stopScan();
            updateButtonStyles();
            if (mBleScanStatusTv != null) mBleScanStatusTv.setText("Current Status: Not Scanning");
            tv = (TextView) (findViewById(R.id.empty));
            if (mLeDeviceListAdapter.getCount() == 0) {
                tv.setText("No devices found.");
                tv.setVisibility(View.VISIBLE);
            } else {
                tv.setVisibility(View.GONE);
            }
        }
    }

    // Adapter for holding devices found through scanning.
    private class LeDeviceListAdapter extends BaseAdapter {
        private ArrayList<BluetoothDevice> mLeDevices;
        private ArrayList<BluetoothDevice> mPineTimeDevices;
        private ArrayList<BluetoothDevice> mOtherDevices;
        private LayoutInflater mInflator;
        private ColorStateList mDefaultTextColors;

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
                if (deviceName != null) {
                    String lowerName = deviceName.toLowerCase();
                    if (lowerName.contains("pinetime")
                            || lowerName.contains("infinitime")
                            || lowerName.contains("bangle")) {
                        mPineTimeDevices.add(device);
                        // Insert PineTime devices at the beginning
                        mLeDevices.add(0, device); // Add to the very top directly for simplicity with the list structure
                    } else {
                        mOtherDevices.add(device);
                        // Add other devices at the end
                        mLeDevices.add(device);
                    }
                } else {
                    mOtherDevices.add(device);
                    // Add other devices at the end
                    mLeDevices.add(device);
                }

                // Re-sort the whole list to ensure PineTimes are always at the top
                // This is a bit inefficient but guarantees order:
                mLeDevices.clear();
                mLeDevices.addAll(mPineTimeDevices);
                mLeDevices.addAll(mOtherDevices);
            }
        }

        public BluetoothDevice getDevice(int position) {
            return mLeDevices.get(position);
        }

        public void clear() {
            mLeDevices.clear();
            mPineTimeDevices.clear();
            mOtherDevices.clear();
            mSelectedPosition = -1;
            notifyDataSetChanged();
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
            CheckedTextView checkedTextView;
            if (view == null) {
                view = mInflator.inflate(R.layout.list_item_device, viewGroup, false);
                checkedTextView = view.findViewById(android.R.id.text1);
                view.setTag(checkedTextView);

                // Save the default text colors from the inflated layout
                if (mDefaultTextColors == null) {
                    mDefaultTextColors = checkedTextView.getTextColors();
                }
            } else {
                checkedTextView = (CheckedTextView) view.getTag();
            }

            BluetoothDevice device = mLeDevices.get(i);
            String deviceName;
            String deviceAddress = device.getAddress();
            try {
                deviceName = device.getName();
            } catch (SecurityException e) {
                deviceName = "Unknown Device";
            }

            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = "Unknown Device";
            }

            // Set text color for "Unknown Device" entries to grey, others to default
            if ("Unknown Device".equals(deviceName)) {
                checkedTextView.setTextColor(Color.GRAY);
            } else {
                // Restore default text color captured from the layout
                if (mDefaultTextColors != null) {
                    checkedTextView.setTextColor(mDefaultTextColors);
                } else {
                    // Fallback should not be needed if hydration works, but just in case:
                    // If we failed to capture, default to BLACK which is safe for light themes
                    // (and usually visible on dark themes too unless background is black)
                    checkedTextView.setTextColor(Color.BLACK);
                }
            }

            checkedTextView.setText(deviceName + "\n" + deviceAddress);
            checkedTextView.setChecked(i == mSelectedPosition);

            return view;
        }
    }

    // Device scan callback.
    private ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mLeDeviceListAdapter.addDevice(result.getDevice());
                    mLeDeviceListAdapter.notifyDataSetChanged();
                }
            });
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        scanLeDevice(false);
        mLeDeviceListAdapter.clear();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG,"onDestroy()");
    }

}
