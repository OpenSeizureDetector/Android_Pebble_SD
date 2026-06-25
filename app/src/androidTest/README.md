# Android Instrumentation Tests

This directory contains system-level UI tests for the OpenSeizureDetector Android app.

## Test Framework

The tests use:
- **UI Automator** for UI interactions (works reliably on Android 15/16+)
- **AndroidX Test** framework for test execution
- **JUnit 4** for test structure

## Available Tests

### OnboardingTest

Tests the first-run onboarding wizard flow:
1. Welcome screen display
2. DataSource selection (Phone/Demo Mode)
3. DataSource configuration screen
4. Algorithm selection (OSD Algorithm)
5. OSD configuration dialog handling
6. Completion screen

**Key Features:**
- Pre-grants runtime permissions (BODY_SENSORS, ACTIVITY_RECOGNITION, POST_NOTIFICATIONS)
- Handles asynchronous configuration dialogs
- Uses resource IDs for reliable element selection
- Includes scrolling support for off-screen elements

## Running Tests

### Run all instrumentation tests:
```bash
./gradlew connectedAndroidTest
```

### Run a specific test:
```bash
./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=uk.org.openseizuredetector.OnboardingTest#testOnboardingWizard
```

### Run on a specific device:
```bash
ANDROID_SERIAL=<device-id> ./gradlew connectedAndroidTest
```

## Requirements

### Emulator/Device
- **Android API 23+** (recommended: API 30+)
- For the current test setup, an **Android 15/16 (API 36)** emulator is running
- Physical devices should have developer options and USB debugging enabled

### Test Results
Test reports are generated at:
```
app/build/reports/androidTests/connected/debug/index.html
```

## Troubleshooting

### "InputManager.getInstance" NoSuchMethodException
- **Cause:** Old Espresso version incompatible with Android 15/16
- **Solution:** Tests now use UI Automator instead of Espresso (resolved)

### Dialog not dismissed
- Ensure dialogs have sufficient time to appear (tests use `Until.findObject()` with timeouts)
- Check that button text matches exactly ("OK" vs "Ok" vs "OKAY")

### Element not found
- Verify the element is visible (not off-screen or hidden behind dialog)
- Use resource IDs instead of text when possible for more reliable matching
- Check if scrolling is needed to bring element into view

## Adding New Tests

When creating new tests:

1. Extend the test class structure:
```java
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MyNewTest {
    private UiDevice mDevice;
    private Context mContext;
    
    @Before
    public void setUp() {
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mContext = ApplicationProvider.getApplicationContext();
    }
    
    @Test
    public void testSomething() {
        // Your test code
    }
}
```

2. Use UI Automator selectors:
- `By.res(packageName, "resource_id")` for resource IDs (most reliable)
- `By.text("Button Text")` for text matching
- `Until.findObject()` with timeouts for async elements

3. Handle waits properly:
- Use `mDevice.wait(Until.findObject(...), TIMEOUT_MS)` for elements that load asynchronously
- Use `mDevice.waitForIdle(milliseconds)` after actions like clicks
- Avoid `Thread.sleep()` when possible

4. Pre-grant permissions in `@Before` if needed:
```java
InstrumentationRegistry.getInstrumentation()
    .getUiAutomation()
    .executeShellCommand("pm grant " + packageName + " android.permission.PERMISSION_NAME");
```

## CI Integration

For continuous integration:

1. Use headless emulator:
```bash
emulator -avd test_avd -no-window -no-audio &
adb wait-for-device
./gradlew connectedAndroidTest
```

2. Parse test results from XML:
```
app/build/outputs/androidTest-results/connected/*.xml
```

## Notes

- Tests currently use the Phone DataSource to avoid external hardware dependencies
- Other previously failing tests (BLEScanActivityTest, StartupHttpIntegrationTest, SdServerCompatTest) are temporarily disabled with `@Ignore`
- The onboarding test completes the full wizard flow and verifies the app progresses to the main activity

