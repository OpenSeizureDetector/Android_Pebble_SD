BLE Data Source Specification
=============================

The BLE data source allows the use of devices which provide accelerometer and heart rate data as BLE services.

This document describes the services and characteristics that must be provided for the BLE data source
to work correclty.

Required Services and Characteristics
=====================================

| ID                 | UUID                                 | Description                                                                                                |
|--------------------|--------------------------------------|------------------------------------------------------------------------------------------------------------|
| SERV_OSD           | 000085e9-0000-1000-8000-00805f9b34fb | Bespoke OSD Service - contains several characteristics                                                     |
|--------------------|--------------------------------------|------------------------------------------------------------------------------------------------------------|
| CHAR_OSD_ACC_DATA  | 000085e9-0001-1000-8000-00805f9b34fb | Acceleration data - array of 20 bytes representing acceleration vector magnitude at 25 Hz sample frequency |
 | CHAR_OSD_BATT_DATA | 000085e9-0002-1000-8000-00805f9b34fb | Watch Battery remaining - one byte which is battery level in percent                                       |
| CHAR_OSD_WATCH_ID  | 000085e9-0003-1000-8000-00805f9b34fb | String identifier for watch (e.g. "BangleJs")                                                              |
| CHAR_OSD_WATCH_FW  | 000085e9-0004-1000-8000-00805f9b34fb | String identifier for watch firmware version (e.g. "V0.10.0")                                              |
|--------------------|--------------------------------------|------------------------------------------------------------------------------------------------------------|
 | SERV_HEART_RATE   | 0000180d-0000-1000-8000-00805f9b34fb | Generic Heart Rate Service |
|--------------------|--------------------------------------|------------------------------------------------------------------------------------------------------------|
 | CHAR_HEART_RATE_MEASUREMENT | 00002a37-0000-1000-8000-00805f9b34fb | Heart rate data.  2 bytes. First is ignored, second is heart rate in bpm  |
