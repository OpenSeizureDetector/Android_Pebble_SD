package uk.org.openseizuredetector.activity.main;

import android.graphics.Color;

import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.ValueDependentColor;
import com.jjoe64.graphview.series.BarGraphSeries;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

/**
 * Helper for the "real time" version of the HR, Fall and ML graphs: a bar graph with one bar
 * per value of the latest 5 second analysis period (the same kind of snapshot the OSD and Flap
 * spectrum graphs show), with an optional threshold marker over each bar.
 *
 * Call configure() whenever the graph is switched to this mode (or the number of bars changes),
 * then draw() on every new data period. configure() sets the viewport bounds once, so that a
 * zoom made by the user is not undone by the next redraw.
 */
final class SnapshotBars {
    private static final int THRESHOLD_COLOUR = Color.BLUE;

    private SnapshotBars() {
    }

    static void configure(GraphView chart, final String[] labels, double yMin, double yMax,
                          int textColour) {
        if (chart == null) return;
        final int n = labels.length;
        chart.removeAllSeries();
        chart.setTitle("");
        chart.getViewport().setYAxisBoundsManual(true);
        chart.getViewport().setMinY(yMin);
        chart.getViewport().setMaxY(yMax);
        chart.getViewport().setXAxisBoundsManual(true);
        chart.getViewport().setMinX(0);
        chart.getViewport().setMaxX(n + 1);
        chart.getViewport().setScalable(true);
        chart.getViewport().setScrollable(true);

        // Human rounding stays on: n + 2 labels over n + 1 units is a step of exactly 1.
        chart.getGridLabelRenderer().setHumanRounding(true);
        // n + 2 labels over a range of n + 1 units gives one label per whole number 0..n+1.
        chart.getGridLabelRenderer().setNumHorizontalLabels(n + 2);
        chart.getGridLabelRenderer().setNumVerticalLabels(6);
        chart.getGridLabelRenderer().setHorizontalLabelsColor(textColour);
        chart.getGridLabelRenderer().setVerticalLabelsColor(textColour);
        chart.getGridLabelRenderer().setGridColor(Color.GRAY);
        chart.getGridLabelRenderer().setHorizontalAxisTitle("");
        chart.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (!isValueX) {
                    return super.formatLabel(value, false);
                }
                int i = (int) Math.round(value);
                if (Math.abs(value - i) > 0.05 || i < 1 || i > n) {
                    return "";
                }
                return labels[i - 1];
            }
        });
        chart.getLegendRenderer().setVisible(false);
    }

    /**
     * @param values     one value per bar
     * @param colours    one colour per bar
     * @param thresholds one threshold per bar (drawn as a short blue line over the bar), or null.
     *                   Entries &lt;= 0 are not drawn.
     */
    static void draw(GraphView chart, double[] values, final int[] colours, double[] thresholds) {
        if (chart == null) return;
        chart.removeAllSeries();

        DataPoint[] points = new DataPoint[values.length];
        for (int i = 0; i < values.length; i++) {
            points[i] = new DataPoint(i + 1, Math.max(0.0, values[i]));
        }
        BarGraphSeries<DataPoint> bars = new BarGraphSeries<>(points);
        bars.setDataWidth(1.0);
        bars.setSpacing(30);
        bars.setValueDependentColor(new ValueDependentColor<DataPoint>() {
            @Override
            public int get(DataPoint data) {
                int i = (int) Math.round(data.getX()) - 1;
                return (i >= 0 && i < colours.length) ? colours[i] : Color.GRAY;
            }
        });
        chart.addSeries(bars);

        if (thresholds != null) {
            for (int i = 0; i < thresholds.length && i < values.length; i++) {
                if (thresholds[i] <= 0) continue;
                LineGraphSeries<DataPoint> line = new LineGraphSeries<>(new DataPoint[]{
                        new DataPoint(i + 0.5, thresholds[i]),
                        new DataPoint(i + 1.5, thresholds[i])});
                line.setColor(THRESHOLD_COLOUR);
                line.setThickness(4);
                chart.addSeries(line);
            }
        }
    }
}
