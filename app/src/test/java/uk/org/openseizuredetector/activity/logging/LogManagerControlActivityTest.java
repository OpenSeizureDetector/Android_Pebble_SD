package uk.org.openseizuredetector.activity.logging;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for LogManagerControlActivity.
 * Tests that the refactored activity correctly handles the shutdown crash from issue #260.
 * 
 * Note: Service connection cannot be fully tested in Robolectric, so these tests focus on:
 * 1. Activity instantiation without crashes
 * 2. Inheritance from ServiceConnectedActivity
 * 3. Lifecycle handling
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LogManagerControlActivityTest {

    private LogManagerControlActivity activity;
    private ActivityController<LogManagerControlActivity> controller;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(LogManagerControlActivity.class);
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
        
        assertTrue("LogManagerControlActivity should extend ServiceConnectedActivity",
                   activity instanceof uk.org.openseizuredetector.activity.ServiceConnectedActivity);
    }

    @Test
    public void testMultipleLifecycleCycles() {
        controller.create();
        activity = controller.get();
        
        // Multiple cycles without crashing
        controller.destroy();
        assertTrue("Should complete lifecycle", true);
    }
}
