package uk.org.openseizuredetector.activity.main;

import android.content.SharedPreferences;

import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import uk.org.openseizuredetector.R;

/**
 * Central definition of the sections shown, one below the other, on the main display when
 * "Advanced Mode" is switched on (these replace the old tabs).
 *
 * Section ids are stored in the "pref_advanced_section_order" preference as a comma separated
 * list, so NEVER renumber or reuse an existing id. OSD..FALL and SYSTEM_STATUS keep the numbers
 * of the old TAB_* constants of MainActivity2.
 *
 * TO ADD A SECTION: add an id below, add it to DEFAULT_ORDER, titleRes(), createFragment() and
 * (for an algorithm) isAlgorithm()/algorithmPrefKey().
 */
public final class AdvancedSections {
    public static final int OSD = 0;
    public static final int FLAP = 1;
    public static final int ML = 2;
    public static final int HR = 3;
    public static final int FALL = 4;
    /** Server status, data source and watch status texts (full width, text only). */
    public static final int SYSTEM_STATUS = 5;
    /** Watch signal strength history graph. */
    public static final int SIGNAL = 6;
    /** Watch battery history graph. */
    public static final int BATTERY = 7;

    /** Order used until the user changes it (the same order the old tabs had). */
    private static final int[] DEFAULT_ORDER = {OSD, FLAP, ML, HR, FALL, SYSTEM_STATUS, SIGNAL, BATTERY};

    public static final String PREF_ORDER = "pref_advanced_section_order";
    public static final String PREF_SHOW_ALL_ALGS = "pref_advanced_show_all_algs";
    public static final String PREF_GRAPH_10MIN = "pref_advanced_graph_10min";

    private AdvancedSections() {
    }

    public static boolean isAlgorithm(int id) {
        return id == OSD || id == FLAP || id == ML || id == HR || id == FALL;
    }

    private static boolean isKnown(int id) {
        return id >= OSD && id <= BATTERY;
    }

    /** Preference key (in the Algorithm Selection settings) that switches an algorithm on. */
    public static String algorithmPrefKey(int id) {
        switch (id) {
            case OSD:
                return "OsdAlarmActive";
            case FLAP:
                return "FlapAlarmActive";
            case ML:
                return "CnnAlarmActive";
            case HR:
                return "HRAlarmActive";
            case FALL:
                return "FallActive";
            default:
                return null;
        }
    }

    /** Name shown in the settings "section order" dialog. */
    public static int titleRes(int id) {
        switch (id) {
            case OSD:
                return R.string.osd;
            case FLAP:
                return R.string.flap;
            case ML:
                return R.string.section_ml;
            case HR:
                return R.string.heart_rate;
            case FALL:
                return R.string.section_fall;
            case SYSTEM_STATUS:
                return R.string.section_system_status;
            case SIGNAL:
                return R.string.section_signal;
            case BATTERY:
                return R.string.section_battery;
            default:
                return R.string.screen;
        }
    }

    public static Fragment createFragment(int id) {
        switch (id) {
            case OSD:
                return new FragmentOsdAlg();
            case FLAP:
                return new FragmentFlapAlg();
            case ML:
                return new FragmentMlAlg();
            case HR:
                return new FragmentHrAlg();
            case FALL:
                return new FragmentFallAlg();
            case SYSTEM_STATUS:
                return FragmentSystem.newInstance(FragmentSystem.PART_STATUS);
            case SIGNAL:
                return FragmentSystem.newInstance(FragmentSystem.PART_SIGNAL);
            case BATTERY:
                return FragmentSystem.newInstance(FragmentSystem.PART_BATTERY);
            default:
                return null;
        }
    }

    public static List<Integer> defaultOrder() {
        List<Integer> l = new ArrayList<>();
        for (int id : DEFAULT_ORDER) l.add(id);
        return l;
    }

    /**
     * The user's section order. Always contains every known section exactly once: unknown or
     * duplicate entries in the stored value are dropped, missing ones are appended in the
     * default order (so a section added in a later app version still shows up).
     */
    public static List<Integer> getOrder(SharedPreferences prefs) {
        Set<Integer> result = new LinkedHashSet<>();
        String stored = prefs.getString(PREF_ORDER, "");
        if (stored != null && !stored.trim().isEmpty()) {
            for (String part : stored.split(",")) {
                try {
                    int id = Integer.parseInt(part.trim());
                    if (isKnown(id)) result.add(id);
                } catch (NumberFormatException ignored) {
                    // skip garbage
                }
            }
        }
        for (int id : DEFAULT_ORDER) result.add(id);
        return new ArrayList<>(result);
    }

    public static void saveOrder(SharedPreferences prefs, List<Integer> order) {
        StringBuilder sb = new StringBuilder();
        for (int id : order) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs.edit().putString(PREF_ORDER, sb.toString()).apply();
    }

    public static void resetOrder(SharedPreferences prefs) {
        prefs.edit().remove(PREF_ORDER).apply();
    }

    /** True if algorithms the user has not selected should be shown as well. */
    public static boolean isShowAllAlgs(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_SHOW_ALL_ALGS, false);
    }

    /** True: graphs show the last 10 minutes. False: graphs show the latest 5 second data. */
    public static boolean isHistoryMode(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_GRAPH_10MIN, false);
    }
}
