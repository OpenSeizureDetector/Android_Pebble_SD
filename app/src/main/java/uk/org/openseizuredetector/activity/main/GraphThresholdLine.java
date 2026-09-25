package uk.org.openseizuredetector.activity.main;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.DataPointInterface;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.PointsGraphSeries;

/**
 * Helper shared by the OSD and Flap tabs: draws the alarm threshold on a spectrum graph
 * as a thin horizontal line, with the threshold value printed in the same colour just
 * above the right-hand end of the line (numbers only, no extra text).
 *
 * The line and its label are ordinary GraphView series, so they are removed together with
 * everything else by mChart.removeAllSeries() at the start of each redraw.
 */
final class GraphThresholdLine {
    /** Colour of the line and of its value label. */
    static final int COLOUR = Color.RED;
    private static final int LINE_THICKNESS_PX = 2;    // the spectrum lines use 4
    private static final float LABEL_TEXT_DP = 11f;
    private static final float LABEL_GAP_DP = 3f;      // gap between the line and the label
    private static final double LABEL_INSET_X = 0.1;   // keep the label inside the plot area

    private GraphThresholdLine() {
    }

    /**
     * Adds the threshold line to the chart.
     *
     * @param chart     the graph to draw on
     * @param threshold threshold in the same units as the Y axis; nothing is drawn if <= 0
     * @param minX      left edge of the visible X range
     * @param maxX      right edge of the visible X range
     */
    static void add(GraphView chart, long threshold, double minX, double maxX) {
        if (chart == null || threshold <= 0) {
            return;
        }

        LineGraphSeries<DataPoint> line = new LineGraphSeries<>(new DataPoint[]{
                new DataPoint(minX, threshold),
                new DataPoint(maxX, threshold)
        });
        line.setColor(COLOUR);
        line.setThickness(LINE_THICKNESS_PX);
        chart.addSeries(line);

        final float density = chart.getResources().getDisplayMetrics().density;
        final String label = String.valueOf(threshold);
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(COLOUR);
        textPaint.setTextSize(LABEL_TEXT_DP * density);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        final float gapPx = LABEL_GAP_DP * density;

        PointsGraphSeries<DataPoint> labelSeries = new PointsGraphSeries<>(new DataPoint[]{
                new DataPoint(maxX - LABEL_INSET_X, threshold)
        });
        labelSeries.setCustomShape(new PointsGraphSeries.CustomShape() {
            @Override
            public void draw(Canvas canvas, Paint paint, float x, float y, DataPointInterface dataPoint) {
                canvas.drawText(label, x, y - gapPx, textPaint);
            }
        });
        chart.addSeries(labelSeries);
    }
}
