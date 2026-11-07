package uk.org.openseizuredetector.activity.main;

import android.graphics.Color;

import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.Locale;

/**
 * Helper shared by the OSD and Flap fragments for their "10 minutes" mode: a line graph of the
 * spectrum ratio (ROI power / spectrum power, the same quantity the progress bar above the
 * graph shows) over the last 10 minutes, with the alarm ratio threshold as a blue line.
 */
final class RatioHistoryChart {
    /**
     * The X axis is in minutes (0 = 10 minutes ago, 10 = now): 11 labels over 0 .. 10 give a
     * label step of exactly 1.0, which GraphView's human rounding leaves alone. (Seconds with
     * human rounding turned off broke the label steps, see FragmentOsdAlg.)
     */
    static final double X_MAX_MINUTES = 10.0;      // 120 samples x 5 s

    private RatioHistoryChart() {
    }

    /** Sets bounds, labels and zoom/scroll behaviour. Call when switching to this mode. */
    static void configure(GraphView chart, int textColour, String axisTitle) {
        if (chart == null) return;
        chart.removeAllSeries();
        chart.setTitle("");
        chart.getViewport().setYAxisBoundsManual(true);
        chart.getViewport().setMinY(0);
        chart.getViewport().setMaxY(10);
        chart.getViewport().setXAxisBoundsManual(true);
        chart.getViewport().setMinX(0);
        chart.getViewport().setMaxX(X_MAX_MINUTES);
        chart.getViewport().setScalable(true);
        chart.getViewport().setScrollable(true);

        chart.getGridLabelRenderer().setHumanRounding(true);
        chart.getGridLabelRenderer().setHighlightZeroLines(true);
        chart.getGridLabelRenderer().setNumHorizontalLabels(11);
        chart.getGridLabelRenderer().setNumVerticalLabels(5);
        chart.getGridLabelRenderer().setHorizontalLabelsColor(textColour);
        chart.getGridLabelRenderer().setVerticalLabelsColor(textColour);
        chart.getGridLabelRenderer().setGridColor(Color.GRAY);
        chart.getGridLabelRenderer().setHorizontalAxisTitle(axisTitle);
        chart.getGridLabelRenderer().setHorizontalAxisTitleColor(textColour);
        chart.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    double minutesAgo = X_MAX_MINUTES - value;
                    long whole = Math.round(minutesAgo);
                    if (Math.abs(minutesAgo - whole) < 0.05 && whole >= 0 && whole <= X_MAX_MINUTES) {
                        return String.format(Locale.getDefault(), "%d", whole);
                    }
                    return "";
                }
                return super.formatLabel(value, false);
            }
        });
        chart.getLegendRenderer().setVisible(false);
    }

    /**
     * Redraws the graph. Only the Y maximum is changed here (to fit the data and threshold);
     * the X range is left alone so the user's zoom survives.
     *
     * @param ratios    history, oldest first, one value per 5 s; negative values are skipped
     * @param threshold alarm ratio threshold (drawn if &gt; 0)
     */
    static void draw(GraphView chart, double[] ratios, int length, double threshold,
                     int lineColour, String title) {
        if (chart == null) return;
        chart.removeAllSeries();

        int n = Math.min(length, ratios == null ? 0 : ratios.length);
        double maxVal = 0;
        DataPoint[] pts = new DataPoint[n];
        int valid = 0;
        for (int i = 0; i < n; i++) {
            if (ratios[i] >= 0.0) {
                pts[valid++] = new DataPoint(i * 5.0 / 60.0, ratios[i]);   // minutes
                maxVal = Math.max(maxVal, ratios[i]);
            }
        }
        if (valid > 0) {
            DataPoint[] ok = new DataPoint[valid];
            System.arraycopy(pts, 0, ok, 0, valid);
            LineGraphSeries<DataPoint> series = new LineGraphSeries<>(ok);
            series.setColor(lineColour);
            series.setThickness(4);
            series.setDrawDataPoints(false);
            chart.addSeries(series);
        }

        double yMax = Math.max(Math.max(threshold * 1.3, maxVal * 1.1), 10.0);
        chart.getViewport().setMaxY(yMax);

        if (threshold > 0) {
            GraphThresholdLine.add(chart, Math.round(threshold), 0.0, X_MAX_MINUTES);
        }

        chart.setTitle(title);
        chart.setTitleTextSize(36f);
    }
}
