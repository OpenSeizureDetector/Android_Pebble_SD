package uk.org.openseizuredetector.activity.events;

import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for EditEventActivity.
 * Tests the refactored activity with ServiceConnectedActivity base class.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EditEventActivityTest {

    private EditEventActivity activity;
    private ActivityController<EditEventActivity> controller;

    @Before
    public void setUp() {
        Intent intent = new Intent();
        intent.putExtra("eventId", "test-event-123");
        
        controller = Robolectric.buildActivity(EditEventActivity.class, intent);
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
        
        assertTrue("EditEventActivity should extend ServiceConnectedActivity",
                   activity instanceof uk.org.openseizuredetector.activity.ServiceConnectedActivity);
    }

    @Test
    public void testMissingEventId() {
        Intent emptyIntent = new Intent();
        ActivityController<EditEventActivity> emptyController = 
            Robolectric.buildActivity(EditEventActivity.class, emptyIntent);
        
        emptyController.create();
        EditEventActivity emptyActivity = emptyController.get();
        
        assertNotNull("Activity should not be null", emptyActivity);
    }
}
