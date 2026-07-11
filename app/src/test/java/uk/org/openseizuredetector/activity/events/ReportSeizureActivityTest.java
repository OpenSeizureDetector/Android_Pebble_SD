package uk.org.openseizuredetector.activity.events;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for ReportSeizureActivity.
 * Tests the refactored activity with ServiceConnectedActivity base class.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReportSeizureActivityTest {

    private ReportSeizureActivity activity;
    private ActivityController<ReportSeizureActivity> controller;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(ReportSeizureActivity.class);
    }

    @Test
    public void testActivityCreated() {
        controller.create();
        activity = controller.get();
        
        assertNotNull("Activity should not be null", activity);
    }

    @Test
    public void testShutdownScenario() {
        controller.create();
        activity = controller.get();
        
        activity.finish();
        
        assertTrue("Activity should be finishing", activity.isFinishing());
    }

    @Test
    public void testDestroyBeforeServiceConnection() {
        controller.create();
        activity = controller.get();
        
        controller.destroy();
        
        assertTrue("Activity should be destroyed", activity.isDestroyed());
    }

    @Test
    public void testInheritsFromServiceConnectedActivity() {
        controller.create();
        activity = controller.get();
        
        assertTrue("ReportSeizureActivity should extend ServiceConnectedActivity",
                   activity instanceof uk.org.openseizuredetector.activity.ServiceConnectedActivity);
    }
}
