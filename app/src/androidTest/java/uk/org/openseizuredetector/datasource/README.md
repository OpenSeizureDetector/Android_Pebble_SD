# SdDataSourceBLE2 Instrumented Tests

This folder contains device-side instrumentation tests for `SdDataSourceBLE2`.

## Tests

- `SdDataSourceBLE2Test`
  - Verifies 8-bit accelerometer scaling to mg.
  - Verifies 16-bit little-endian parsing.

## Additional Tests

- `SdDataSourceBLETest`
  - Verifies 8-bit accelerometer scaling to mg.
  - Verifies 16-bit little-endian parsing.

## Run

```bash
/home/graham/osd/Android_Pebble_SD/gradlew :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=uk.org.openseizuredetector.datasource.SdDataSourceBLE2Test
```

```bash
/home/graham/osd/Android_Pebble_SD/gradlew :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=uk.org.openseizuredetector.datasource.SdDataSourceBLETest
```
