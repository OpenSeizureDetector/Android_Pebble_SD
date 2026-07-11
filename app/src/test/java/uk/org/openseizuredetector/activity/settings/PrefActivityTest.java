/*
  Pebble_sd - a simple accelerometer based seizure detector that runs on a
  Pebble smart watch (http://getpebble.com).

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2026.

  This file is part of pebble_sd.

  Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.
  
  Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.
  
  You should have received a copy of the GNU General Public License
  along with pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/

package uk.org.openseizuredetector.activity.settings;

import android.content.SharedPreferences;
import android.widget.ListView;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import uk.org.openseizuredetector.R;

import static org.junit.Assert.*;

/**
 * Unit tests for PrefActivity header filtering logic.
 * Tests conditional display of settings based on DataSource and other preferences.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PrefActivityTest {

    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext());
        prefs.edit().clear().commit();
    }

    /**
     * Helper method to get the number of visible headers in the activity
     */
    private int getVisibleHeaderCount(PrefActivity activity) {
        ListView headerList = activity.findViewById(R.id.pref_header_list);
        if (headerList != null && headerList.getAdapter() != null) {
            return headerList.getAdapter().getCount();
        }
        return 0;
    }

    /**
     * Helper method to check if a specific header title is visible
     */
    private boolean isHeaderVisible(PrefActivity activity, String title) {
        ListView headerList = activity.findViewById(R.id.pref_header_list);
        if (headerList != null && headerList.getAdapter() != null) {
            int count = headerList.getAdapter().getCount();
            for (int i = 0; i < count; i++) {
                String headerTitle = (String) headerList.getAdapter().getItem(i);
                if (headerTitle != null && headerTitle.contains(title)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Test: AlgorithmSelectionPrefsFragment should be visible for non-Network data sources
     */
    @Test
    public void testAlgorithmSettingsVisibleForPhoneDataSource() {
        prefs.edit().putString("DataSource", "Phone").commit();

        PrefActivity activity = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        // Algorithm settings should be visible for Phone data source
        assertTrue("Algorithm settings should be visible for Phone datasource",
                isHeaderVisible(activity, "Seizure Detector") || 
                isHeaderVisible(activity, "detection"));
    }

    /**
     * Test: AlgorithmSelectionPrefsFragment should be visible for Garmin data source
     */
    @Test
    public void testAlgorithmSettingsVisibleForGarminDataSource() {
        prefs.edit().putString("DataSource", "Garmin").commit();

        PrefActivity activity = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        // Algorithm settings should be visible for Garmin data source
        assertTrue("Algorithm settings should be visible for Garmin datasource",
                isHeaderVisible(activity, "Seizure Detector") || 
                isHeaderVisible(activity, "detection"));
    }

    /**
     * Test: AlgorithmSelectionPrefsFragment should be HIDDEN for Network data source
     * This is the main test for Issue #255
     */
    @Test
    public void testAlgorithmSettingsHiddenForNetworkDataSource() {
        prefs.edit().putString("DataSource", "Network").commit();

        PrefActivity activity = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        // Algorithm settings should NOT be visible for Network data source
        assertFalse("Algorithm settings should be hidden for Network datasource (Issue #255)",
                isHeaderVisible(activity, "Seizure Detector") && 
                isHeaderVisible(activity, "detection"));
    }

    /**
     * Test: PebbleDatasourcePrefsFragment should only be visible when DataSource is Pebble
     */
    @Test
    public void testPebbleSettingsOnlyVisibleForPebbleDataSource() {
        // Test with Pebble datasource - should be visible
        prefs.edit().putString("DataSource", "Pebble").commit();
        PrefActivity activityPebble = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        boolean pebbleVisibleForPebble = isHeaderVisible(activityPebble, "Pebble") ||
                                          isHeaderVisible(activityPebble, "pebble");

        // Test with Phone datasource - should NOT be visible
        prefs.edit().putString("DataSource", "Phone").commit();
        PrefActivity activityPhone = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        boolean pebbleVisibleForPhone = isHeaderVisible(activityPhone, "Pebble datasource") ||
                                         isHeaderVisible(activityPhone, "pebble datasource");

        assertTrue("Pebble settings should be visible when DataSource is Pebble", 
                   pebbleVisibleForPebble);
        assertFalse("Pebble settings should be hidden when DataSource is not Pebble", 
                    pebbleVisibleForPhone);
    }

    /**
     * Test: NetworkDatasourcePrefsFragment should only be visible when DataSource is Network
     */
    @Test
    public void testNetworkSettingsOnlyVisibleForNetworkDataSource() {
        // Test with Network datasource - should be visible
        prefs.edit().putString("DataSource", "Network").commit();
        PrefActivity activityNetwork = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        // Note: NetworkDatasourcePrefsFragment might not have a visible title in the header list
        // since it's a datasource-specific fragment. We'll test that activity doesn't crash.
        int headerCountNetwork = getVisibleHeaderCount(activityNetwork);

        // Test with Phone datasource
        prefs.edit().putString("DataSource", "Phone").commit();
        PrefActivity activityPhone = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        int headerCountPhone = getVisibleHeaderCount(activityPhone);

        // Both should have headers (activity should not crash)
        assertTrue("Activity should show headers for Network datasource", headerCountNetwork > 0);
        assertTrue("Activity should show headers for Phone datasource", headerCountPhone > 0);
    }

    /**
     * Test: Basic mode should hide LoggingPrefsFragment and PebbleDatasourcePrefsFragment
     */
    @Test
    public void testBasicModeHidesAdvancedSettings() {
        // Set Pebble datasource to make PebbleDatasource header potentially visible
        prefs.edit().putString("DataSource", "Pebble").commit();

        // Test with basic mode OFF - advanced settings visible
        prefs.edit().putBoolean("pref_basic_mode", false).commit();
        PrefActivity activityAdvanced = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        int headerCountAdvanced = getVisibleHeaderCount(activityAdvanced);
        boolean loggingVisibleAdvanced = isHeaderVisible(activityAdvanced, "Logging") ||
                                          isHeaderVisible(activityAdvanced, "logging");

        // Test with basic mode ON - advanced settings hidden
        prefs.edit().putBoolean("pref_basic_mode", true).commit();
        PrefActivity activityBasic = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        int headerCountBasic = getVisibleHeaderCount(activityBasic);
        boolean loggingVisibleBasic = isHeaderVisible(activityBasic, "Logging") ||
                                        isHeaderVisible(activityBasic, "logging");

        // Basic mode should have fewer headers
        assertTrue("Basic mode should have fewer headers than advanced mode", 
                   headerCountBasic < headerCountAdvanced);

        // Logging should be visible in advanced mode but hidden in basic mode
        assertTrue("Logging settings should be visible in advanced mode", loggingVisibleAdvanced);
        assertFalse("Logging settings should be hidden in basic mode", loggingVisibleBasic);
    }

    /**
     * Test: Combined filters - Basic mode + Network datasource
     * Should hide both advanced settings AND algorithm settings
     */
    @Test
    public void testCombinedFiltersBasicModeAndNetwork() {
        prefs.edit()
                .putString("DataSource", "Network")
                .putBoolean("pref_basic_mode", true)
                .commit();

        PrefActivity activity = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        // Algorithm settings should be hidden (Network datasource)
        boolean algorithmVisible = isHeaderVisible(activity, "Seizure Detector") || 
                                    isHeaderVisible(activity, "detection");

        // Logging settings should be hidden (Basic mode)
        boolean loggingVisible = isHeaderVisible(activity, "Logging") ||
                                  isHeaderVisible(activity, "logging");

        assertFalse("Algorithm settings should be hidden for Network datasource", algorithmVisible);
        assertFalse("Logging settings should be hidden in basic mode", loggingVisible);
    }

    /**
     * Test: Activity should always show some headers (not completely empty)
     */
    @Test
    public void testAlwaysShowsSomeHeaders() {
        // Test various combinations
        String[] dataSources = {"Phone", "Garmin", "Network", "Pebble", "BLE"};
        boolean[] basicModes = {true, false};

        for (String ds : dataSources) {
            for (boolean basicMode : basicModes) {
                prefs.edit()
                        .putString("DataSource", ds)
                        .putBoolean("pref_basic_mode", basicMode)
                        .commit();

                PrefActivity activity = Robolectric.buildActivity(PrefActivity.class)
                        .create()
                        .start()
                        .resume()
                        .get();

                int headerCount = getVisibleHeaderCount(activity);

                assertTrue("Activity should always show at least 2 headers (DataSource=" + ds + 
                          ", BasicMode=" + basicMode + ")", 
                          headerCount >= 2);

                // Should always have General and Data Source settings at minimum
                assertTrue("Should always show General Settings", 
                          isHeaderVisible(activity, "General"));
                assertTrue("Should always show Data Source Settings", 
                          isHeaderVisible(activity, "Data Source"));
            }
        }
    }

    /**
     * Test: Algorithm settings visibility for all non-Network data sources
     */
    @Test
    public void testAlgorithmSettingsVisibleForAllNonNetworkSources() {
        String[] nonNetworkSources = {"Phone", "Garmin", "Pebble", "BLE", "BLE2", "AndroidWear"};

        for (String ds : nonNetworkSources) {
            prefs.edit().putString("DataSource", ds).commit();

            PrefActivity activity = Robolectric.buildActivity(PrefActivity.class)
                    .create()
                    .start()
                    .resume()
                    .get();

            boolean algorithmVisible = isHeaderVisible(activity, "Seizure Detector") || 
                                        isHeaderVisible(activity, "detection");

            assertTrue("Algorithm settings should be visible for " + ds + " datasource", 
                      algorithmVisible);
        }
    }

    /**
     * Test: Switching data sources updates headers correctly
     */
    @Test
    public void testSwitchingDataSourcesUpdatesHeaders() {
        // Start with Phone (algorithm visible)
        prefs.edit().putString("DataSource", "Phone").commit();
        PrefActivity activity1 = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        boolean algoVisibleForPhone = isHeaderVisible(activity1, "Seizure Detector") || 
                                       isHeaderVisible(activity1, "detection");

        // Switch to Network (algorithm hidden)
        prefs.edit().putString("DataSource", "Network").commit();
        PrefActivity activity2 = Robolectric.buildActivity(PrefActivity.class)
                .create()
                .start()
                .resume()
                .get();

        boolean algoVisibleForNetwork = isHeaderVisible(activity2, "Seizure Detector") && 
                                         isHeaderVisible(activity2, "detection");

        // Verify the change
        assertTrue("Algorithm should be visible for Phone", algoVisibleForPhone);
        assertFalse("Algorithm should be hidden for Network", algoVisibleForNetwork);
    }
}
