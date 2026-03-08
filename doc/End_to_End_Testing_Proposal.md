# End-to-End (E2E) Testing Proposal for OpenSeizureDetector

## 1. Objective
To implement an automated testing suite that validates the complete system flow—from data reception to UI updates, Alarm generation, and Remote Logging. This is intended to catch regressions where core logic (like Alarm handling blocks) might be accidentally modifying or removed during refactoring.

## 2. Testing Strategy
We will implement "Black Box" style tests where possible, treating the App logic as the system under test and mocking only the external boundaries (Sensor Hardware and Remote Web API).

### 2.1 Tooling Stack
*   **Espresso:** For verifying internal UI state (Check TextViews, Colors, Buttons).
*   **MockWebServer (OkHttp):** For mocking the Remote OSD Web API (Login, Event Uploads).
*   **UI Automator (Optional):** For verifying system-level interactions like Notifications appearing in the status bar.
*   **JUnit 4/5:** Value assertions.

## 3. Data Injection Strategy (Simulating Sensors)

To test the system, we need to inject known `SdData` packets (Normal, Fault, Alarm) and observe the reaction.

### Approach A: Leveraging `SdDataSourceGarmin` (Preferred for Integration)
The `SdDataSourceGarmin` class already implements a local web server (using `NanoHTTPD` or similar) to receive JSON data from Garmin watches via HTTP POST.
*   **Mechanism:** The Test environment acts as the "Watch". It sends HTTP POST requests with crafted JSON payloads to the IP/Port the app is listening on.
*   **Pros:** This tests the *real* data ingestion pipeline, including JSON parsing, `SdDataSource` processing (timestamps, etc.), and delivery to `SdServer`. It requires zero architectural changes to the core app, only configuration to select "Garmin" as the data source during testing.
*   **Cons:** Only covers the format expected by the Garmin implementation. Other data sources (BLE, Pebble) might populate fields differently.

### Approach B: Mock Data Source (Dependency Injection)
For direct control independent of network stacks:
*   **Mechanism:** Refactor `SdServer` to accept an `SdDataSource` interface. Inject a `MockDataSource` during tests that allows direct method calls like `mockSource.pushData(sdData)`.
*   **Pros:** Immediate, synchronous data injection. Useful if testing specific race conditions or specific fields not supported by the Garmin JSON format.

## 4. Remote API Mocking
To validate that Alarms are uploaded correctly without spamming the real production server:
*   **Mechanism:** Use `MockWebServer` running within the test instrumentation.
*   **Configuration:** During testing, inject the `MockWebServer` URL (e.g., `http://localhost:8080`) into the App's preferences (`OSDUrl`).
*   **Validation:** The test script can assert: "Did the app send a POST request? Did the JSON body contain `alarmState: 2`?"

## 5. Proposed Test Scenarios

### Scenario 1: Normal Operation
1.  **Setup:** Configure Data Source = Garmin. Start App. Verify UI shows "Connecting...".
2.  **Action:** POST "OK" data packet (Heart Rate: 70, Accel: 0) to local listener.
3.  **Verify:**
    *   Main UI Status changes to **"OK"** (Green).
    *   Heart Rate display shows "70 bpm".

### Scenario 2: Fault Handling (Regression Catching)
1.  **Setup:** System in "OK" state.
2.  **Action:** POST data packet with `alarmState: FAULT` (or simulate network timeout).
    *   *Note:* Can also be done by simulating "No Data" for > 1 minute.
3.  **Verify:**
    *   Main UI Status changes to **"FAULT"**.
    *   UI Status Text contains specific reason (e.g., **"FAULT : Data Source Fault"**). _(This catches the regression found on Mar 8, 2026)_.
    *   Notification appears in status bar.

### Scenario 3: Alarm & Upload
1.  **Setup:** System in "OK" state.
2.  **Action:** POST data packet with `alarmState: ALARM`.
3.  **Verify:**
    *   Main UI Status changes to **"ALARM"** (Red).
    *   **MockWebServer** receives a request to `/events`.
    *   verify payload contains `alarmState: 2` and correct timestamp.

## 6. Implementation Steps
1.  **Dependencies:** Add `espresso-core`, `mockwebserver` to `build.gradle`.
2.  **Test Config:** Create a custom `TestRunner` or helper class to set SharedPreferences (DataSource="Garmin", OSDUrl="localhost") before Actvity launch.
3.  **Garmin JSON Generator:** Create a helper utility in the Test project to generate valid Garmin-format JSON strings for injection.
4.  **Write Tests:** Implement the scenarios above in `androidTest`.

