package uk.org.openseizuredetector;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import uk.org.openseizuredetector.activity.onboarding.OnboardingActivity;

import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class OnboardingTest {

    private static final int TIMEOUT_MS = 5000;
    private UiDevice mDevice;
    private Context mContext;

    @Before
    public void setUp() {
        // Initialize UiDevice instance
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mContext = ApplicationProvider.getApplicationContext();

        // Pre-grant runtime permissions to avoid system dialogs
        grantRuntimePermissions();

        // Launch the onboarding activity
        Intent intent = new Intent(mContext, OnboardingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(intent);

        // Wait for the app to appear
        mDevice.wait(Until.hasObject(By.pkg(mContext.getPackageName())), TIMEOUT_MS);
    }

    private void grantRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String pkg = mContext.getPackageName();
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pm grant " + pkg + " android.permission.BODY_SENSORS");
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pm grant " + pkg + " android.permission.ACTIVITY_RECOGNITION");
            if (Build.VERSION.SDK_INT >= 33) {
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .executeShellCommand("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
            }
        }
    }

    @Test
    public void testOnboardingWizard() {
        // Wait for and verify the welcome screen
        UiObject2 welcomeTitle = mDevice.wait(
                Until.findObject(By.text("Welcome to OpenSeizureDetector")),
                TIMEOUT_MS);
        assertNotNull("Welcome title should be displayed", welcomeTitle);

        // Click "Next" button (by resource ID) to go to DataSource selection
        UiObject2 nextButton = mDevice.wait(
                Until.findObject(By.res(mContext.getPackageName(), "btn_next")),
                TIMEOUT_MS);
        assertNotNull("Next button should be present on welcome screen", nextButton);
        nextButton.click();

        // Wait for transition to complete
        mDevice.waitForIdle(2000);

        // Try to dismiss any dialog that might appear (ML configuration dialog)
        dismissDialogIfPresent();

        // Wait for DataSource screen - look for the radio button options or screen title
        mDevice.wait(Until.hasObject(By.res(mContext.getPackageName(), "radio_phone")), TIMEOUT_MS);

        // Click "Next" button to go to DataSource Config screen (Phone/Demo Mode info page)
        nextButton = mDevice.wait(
                Until.findObject(By.res(mContext.getPackageName(), "btn_next")),
                TIMEOUT_MS);
        assertNotNull("Next button should be present on DataSource screen", nextButton);
        nextButton.click();

        // Wait for transition to DataSource Config screen
        mDevice.waitForIdle(2000);
        dismissDialogIfPresent();

        // Click "Next" again to proceed from DataSource Config to Algorithms screen
        nextButton = mDevice.wait(
                Until.findObject(By.res(mContext.getPackageName(), "btn_next")),
                TIMEOUT_MS);
        assertNotNull("Next button should be present on DataSource Config screen", nextButton);
        nextButton.click();

        // Wait for transition to Algorithms screen
        mDevice.waitForIdle(2000);
        dismissDialogIfPresent();

        // Wait explicitly for the Algorithms screen to load - look for any checkbox
        mDevice.wait(Until.hasObject(By.res(mContext.getPackageName(), "check_ml_alg")), TIMEOUT_MS);

        // Try to find and scroll to the OSD algorithm checkbox
        UiObject2 osdCheckbox = mDevice.findObject(By.res(mContext.getPackageName(), "check_osd_alg"));

        // If not found, try scrolling down in case it's off-screen
        if (osdCheckbox == null) {
            // Scroll down to find it
            mDevice.swipe(mDevice.getDisplayWidth() / 2,
                         mDevice.getDisplayHeight() * 3 / 4,
                         mDevice.getDisplayWidth() / 2,
                         mDevice.getDisplayHeight() / 4,
                         20);
            mDevice.waitForIdle(500);
            osdCheckbox = mDevice.wait(
                    Until.findObject(By.res(mContext.getPackageName(), "check_osd_alg")),
                    TIMEOUT_MS);
        }

        assertNotNull("OSD algorithm checkbox should be present", osdCheckbox);

        // The OSD checkbox may be checked by default - ensure it's checked
        if (!osdCheckbox.isChecked()) {
            osdCheckbox.click();
            mDevice.waitForIdle(500);
        }

        // Click "Next" to proceed (this triggers the OSD configuration dialog)
        nextButton = mDevice.wait(
                Until.findObject(By.res(mContext.getPackageName(), "btn_next")),
                TIMEOUT_MS);
        assertNotNull("Next button should be present on Algorithms screen", nextButton);
        nextButton.click();

        // Wait for and dismiss the "OSD Algorithm" configuration dialog that appears
        mDevice.waitForIdle(1000);
        UiObject2 okButton = mDevice.wait(Until.findObject(By.text("OK")), TIMEOUT_MS);
        if (okButton != null) {
            System.out.println("Found and clicking OSD configuration dialog OK button");
            okButton.click();
            mDevice.waitForIdle(1500);
        } else {
            System.out.println("Warning: OSD configuration dialog OK button not found");
        }

        // Wait for transition to final Complete screen
        mDevice.waitForIdle(1000);

        // Click "Get Started" to finish onboarding
        // The btn_next button's text changes to "Get Started" on the final page
        UiObject2 getStartedButton = mDevice.wait(
                Until.findObject(By.res(mContext.getPackageName(), "btn_next")),
                TIMEOUT_MS);
        assertNotNull("Get Started button (btn_next) should be present on Complete screen", getStartedButton);
        getStartedButton.click();
        mDevice.waitForIdle(2000);
    }

    private void dismissDialogIfPresent() {
        // Try to find and dismiss any dialog (MaterialAlertDialog with various button texts)
        // Only search for specific positive button texts to avoid clicking Back/Skip/etc
        String[] buttonTexts = {"OK", "Ok", "ok", "OKAY", "Okay", "YES", "Yes", "CONFIRM", "Confirm"};

        for (String buttonText : buttonTexts) {
            UiObject2 button = mDevice.findObject(By.text(buttonText));
            if (button != null) {
                System.out.println("Found and clicking dialog button: " + buttonText);
                button.click();
                mDevice.waitForIdle(500);
                return; // Found and clicked, exit
            }
        }
    }
}
