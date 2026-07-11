package uk.org.openseizuredetector.activity.remote;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for RemoteDbActivity.
 * Tests the refactored activity with ServiceConnectedActivity base class.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RemoteDbActivityTest {

    private RemoteDbActivity activity;
    private ActivityController<RemoteDbActivity> controller;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(RemoteDbActivity.class);
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
        
        assertTrue("RemoteDbActivity should extend ServiceConnectedActivity",
                   activity instanceof uk.org.openseizuredetector.activity.ServiceConnectedActivity);
    }
}
