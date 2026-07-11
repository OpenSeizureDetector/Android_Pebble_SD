package uk.org.openseizuredetector.activity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for ServiceConnectedActivity base class.
 * Tests the shutdown crash fix for issue #260.
 * 
 * These tests verify that activities extending ServiceConnectedActivity can handle
 * shutdown scenarios gracefully without crashing, which was the root cause of issue #260.
 * 
 * Note: Full service connection cannot be tested in Robolectric (service isn't available),
 * so these tests focus on verifying that lifecycle transitions don't cause crashes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ServiceConnectedActivityTest {

    private TestableServiceConnectedActivity activity;
    private ActivityController<TestableServiceConnectedActivity> controller;

    /**
     * Concrete implementation of abstract ServiceConnectedActivity for testing
     */
    public static class TestableServiceConnectedActivity extends ServiceConnectedActivity {
        public boolean onServiceConnectedCalled = false;
        public int serviceConnectedCallCount = 0;

        @Override
        protected void onServiceConnected(uk.org.openseizuredetector.data.logging.LogManager logManager) {
            onServiceConnectedCalled = true;
            serviceConnectedCallCount++;
        }

        // Expose protected methods for testing
        public boolean testIsActivityActive() {
            return isActivityActive();
        }
    }

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(TestableServiceConnectedActivity.class);
        activity = controller.get();
    }

    /**
     * Test 1: Activity can be created without crashing
     */
    @Test
    public void testActivityCreated() {
        controller.create();
        assertNotNull("Activity should be created", activity);
    }

    /**
     * Test 2: Shutdown during connection - the original issue #260
     * The key is that this doesn't crash, even if service connection is in progress
     */
    @Test
    public void testShutdownDuringConnection() {
        controller.create().start();
        
        // Immediately finish (simulates MainActivity shutdown)
        activity.finish();
        controller.pause().stop();
        
        // Key: This should not crash
        assertTrue("Activity should be finishing", activity.isFinishing());
    }

    /**
     * Test 3: Activity destroyed before service connects
     */
    @Test
    public void testDestroyedBeforeConnection() {
        controller.create().start();
        
        // Destroy immediately
        controller.pause().stop().destroy();
        
        // Should not crash
        assertTrue("Activity should be destroyed", activity.isDestroyed());
    }

    /**
     * Test 4: Rapid finish after start (race condition)
     */
    @Test
    public void testRapidFinishAfterStart() {
        controller.create().start();
        activity.finish();
        controller.pause().stop();
        
        // Should not crash 
        assertTrue("Test completed without crash", true);
    }

    /**
     * Test 5: Multiple lifecycle cycles
     */
    @Test
    public void testMultipleLifecycleCycles() {
        controller.create().start();
        controller.pause().stop();
        controller.start();
        
        // Should not crash
        assertNotNull("Activity should still exist", activity);
    }

    /**
     * Test 6: Full lifecycle without crashing
     */
    @Test
    public void testFullLifecycle() {
        controller.create().start().resume().pause().stop().destroy();
        assertTrue("Activity should be destroyed at end", activity.isDestroyed());
    }

    /**
     * Test 7: isActivityActive returns false when destroyed
     */
    @Test
    public void testIsActivityActiveWhenDestroyed() {
        controller.create().start().destroy();
        
        // After destroy, should not be active
        assertFalse("isActivityActive should return false when destroyed", 
                    activity.testIsActivityActive());
    }

    /**
     * Test 8: isActivityActive returns false when finishing
     */
    @Test
    public void testIsActivityActiveWhenFinishing() {
        controller.create();
        activity.finish();
        
        // After finish, should not be active
        assertFalse("isActivityActive should return false when finishing", 
                    activity.testIsActivityActive());
    }

    /**
     * Test 9: Activity can handle stop before service ready
     */
    @Test
    public void testStopBeforeServiceReady() {
        controller.create().start();
        controller.pause().stop();
        
        // Should not crash
        assertNotNull("Activity should still exist", activity);
    }
}
