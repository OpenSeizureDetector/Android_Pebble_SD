package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import uk.org.openseizuredetector.data.logging.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import androidx.appcompat.content.res.AppCompatResources;
import android.view.ViewGroup.LayoutParams;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.BarGraphSeries;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.ValueDependentColor;
import com.jjoe64.graphview.series.DataPoint;

import uk.org.openseizuredetector.data.SdData;

/**
 * Displays the "OSD" section - the OSD algorithm's spectrum and its margin to alarm.
 *
 * Two display modes, chosen by the Advanced Mode option "graphs over 10 minutes":
 *  - real time (default): bar graph of the 0-10 Hz spectrum of the latest 5 second period
 *  - 10 minutes: line graph of the spectrum ratio over the last 10 minutes (see RatioHistoryChart)
 * In both modes the graph can be zoomed by pinching. The viewport bounds are only set when the
 * mode (or the Y-axis scale) changes, never on every redraw, so a zoom is not undone every 5 s.
 */
public class FragmentOsdAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentOsdAlg";

    /** Mode the chart is currently configured for (null = not configured yet). */
    private Boolean mChartInHistoryMode = null;
    /** Y-axis maximum last applied in spectrum mode (so it is only changed when needed). */
    private double mSpectrumYMaxApplied = -1;

    public FragmentOsdAlg() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_osdalg, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        GraphView chart = view.findViewById(R.id.chart1);
        adjustChartHeightForMode(chart);
        // The views are new, so the chart has to be configured again.
        mChartInHistoryMode = null;
    }

    @Override
    protected void updateUi() {
        //Log.d(TAG,"updateUi()");
        if (isBasicMode()) {
            return;
        }

        if (mConnection.mBound) {
            SdData sdData = mConnection.mSdServer.mSdData;

            // Shown because of "show all algorithms" but not selected by the user.
            if (!applyAlgorithmState(sdData.dataTimeMillis == 0 || sdData.mOsdAlarmActive, isShowAllAlgs())) {
                return;
            }

            /////////////////////////////////////////////////////
            // Set ProgressBar to show margin to alarm (spectrum ratio).
            long specPc;
            if (sdData.specPower != 0 &&
                    sdData.alarmRatioThresh != 0)
                specPc = 100 * (sdData.roiPower * 10 /
                        sdData.specPower) /
                        sdData.alarmRatioThresh;
            else
                specPc = 0;

            long specRatio;
            if (sdData.specPower != 0) {
                specRatio = 10 * sdData.roiPower /
                        sdData.specPower;
            } else
                specRatio = 0;

            ((android.widget.TextView) mRootView.findViewById(R.id.spectrumTv)).setText(getString(R.string.SpectrumRatioEquals) + specRatio +
                    " (" + getString(R.string.Threshold) + "=" + sdData.alarmRatioThresh + ")");

            ProgressBar pb;
            Drawable pbDrawable;
            pb = ((ProgressBar) mRootView.findViewById(R.id.spectrumProgressBar));
            pb.setMax(100);
            pb.setProgress((int) specPc);
            pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_blue);
            if (specPc > 75)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_yellow);
            if (specPc > 100)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            GraphView mChart = (GraphView) mRootView.findViewById(R.id.chart1);
            if (mChart == null) return;

            // (Re)configure the graph when the display mode has been changed in the settings.
            boolean history = isHistoryMode();
            if (mChartInHistoryMode == null || mChartInHistoryMode != history) {
                if (history) {
                    RatioHistoryChart.configure(mChart, okTextColour, getString(R.string.time_minutes_ago));
                } else {
                    configureSpectrumChart(mChart);
                }
                mChartInHistoryMode = history;
            }

            if (history) {
                drawRatioHistory(mChart, sdData);
            } else {
                drawSpectrum(mChart, sdData);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Real time mode: spectrum of the latest 5 second period
    // -------------------------------------------------------------------------

    /** Bounds, labels and gestures of the spectrum graph. Called once per switch to this mode. */
    private void configureSpectrumChart(GraphView mChart) {
        mChart.removeAllSeries();
        mChart.setTitle("");
        mChart.getGridLabelRenderer().setHorizontalAxisTitle("");

        mSpectrumYMaxApplied = 3000;
        mChart.getViewport().setYAxisBoundsManual(true);
        mChart.getViewport().setMinY(0);
        mChart.getViewport().setMaxY(mSpectrumYMaxApplied);
        mChart.getViewport().setXAxisBoundsManual(true);
        mChart.getViewport().setMinX(0);
        mChart.getViewport().setMaxX(10);

        // The user can pinch to zoom in on part of the 0-10 Hz range.
        mChart.getViewport().setScalable(true);
        mChart.getViewport().setScrollable(true);

        // 11 labels over the 10-unit-wide X range (0 .. 10) gives a label step of exactly 1.0,
        // which GraphView's "human rounding" leaves alone (a step of 1.11 would be rounded to 2
        // and only every second label would show).
        // Keep human rounding ON (the GraphView default, as before). With it off GraphView
        // derives each new label step from the previous one, and because the Y maximum here
        // changes at run time the steps collapsed into dozens of grid lines and labels.
        mChart.getGridLabelRenderer().setHumanRounding(true);
        mChart.getGridLabelRenderer().setNumHorizontalLabels(11);
        // No special heavy line at the 0 value; the red ROI segment marks the axis.
        mChart.getGridLabelRenderer().setHighlightZeroLines(false);
        mChart.getGridLabelRenderer().setNumVerticalLabels(5);
        mChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
        mChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
        mChart.getGridLabelRenderer().setGridColor(Color.GRAY);

        mChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    int i = (int) Math.round(value);
                    // When zoomed in, labels fall between the whole numbers: leave those out.
                    if (Math.abs(value - i) > 0.05) {
                        return "";
                    }
                    if (i == 0) {
                        return getString(R.string.frequency_hertz);
                    }
                    if (i > 0 && i <= 10) {
                        return String.valueOf(i);
                    }
                    return "";
                } else {
                    return super.formatLabel(value, isValueX);
                }
            }
        });

        mChart.getLegendRenderer().setVisible(false);
    }

    private void drawSpectrum(GraphView mChart, SdData sdData) {
        mChart.removeAllSeries();

        try {
            DataPoint[] dataPoints = new DataPoint[10];
            for (int i = 0; i < 10; i++) {
                double v = sdData.simpleSpec[i];
                // Bin i covers i..i+1 Hz, so its bar is centred at i + 0.5
                dataPoints[i] = new DataPoint(i + 0.5, v);
            }

            final int alarmFreqMin = (int) sdData.alarmFreqMin;
            final int alarmFreqMax = (int) sdData.alarmFreqMax;

            // Bar graph: one bar per 1 Hz bin. Bars inside the alarm band
            // (AlarmFreqMin up to, not including, AlarmFreqMax) are red, the rest gray.
            // The bars are slightly translucent because GraphView draws the grid
            // lines BEFORE the series - opaque bars would hide the horizontal grid
            // lines behind them.
            final int barRed = Color.argb(190, 255, 0, 0);
            final int barGray = Color.argb(190, 128, 128, 128);
            BarGraphSeries<DataPoint> barSeries = new BarGraphSeries<>(dataPoints);
            barSeries.setDataWidth(1.0);   // bins are 1.0 apart on the X axis
            barSeries.setSpacing(20);      // 20% of each 1.0 slot is a gap => bar width 0.8
            barSeries.setValueDependentColor(new ValueDependentColor<DataPoint>() {
                @Override
                public int get(DataPoint data) {
                    int bin = (int) Math.floor(data.getX());   // x = bin + 0.5
                    return (bin >= alarmFreqMin && bin < alarmFreqMax) ? barRed : barGray;
                }
            });
            mChart.addSeries(barSeries);

            // Mark the region of interest on the bottom X axis in red, even when the
            // bars are zero-height (a bar of value 0 draws nothing).
            double roiStart = Math.max(0, Math.min(10, alarmFreqMin));
            double roiEnd = Math.max(roiStart, Math.min(10, alarmFreqMax));
            if (roiEnd > roiStart) {
                LineGraphSeries<DataPoint> roiAxisSeries = new LineGraphSeries<>(new DataPoint[]{
                        new DataPoint(roiStart, 0),
                        new DataPoint(roiEnd, 0)});
                roiAxisSeries.setColor(Color.RED);
                roiAxisSeries.setThickness(6);
                roiAxisSeries.setDrawDataPoints(false);
                mChart.addSeries(roiAxisSeries);
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception creating spectrum graph: " + e.getMessage());
        }

        // Y-axis maximum is derived from the "Alarm Threshold" setting plus 30% headroom (issue #238),
        // falling back to 3000 if the threshold is unset. Only touched when it changes, so that
        // the viewport (and any zoom) is left alone on ordinary redraws.
        long alarmThresh = sdData.alarmThresh;
        double maxY = alarmThresh > 0 ? alarmThresh * 1.3 : 3000;
        if (maxY != mSpectrumYMaxApplied) {
            mChart.getViewport().setMaxY(maxY);
            mSpectrumYMaxApplied = maxY;
        }

        // Show where the alarm threshold sits on the Y axis (thin line + value).
        GraphThresholdLine.add(mChart, alarmThresh, 0.0, 10.0);
    }

    // -------------------------------------------------------------------------
    // 10 minute mode: spectrum ratio history
    // -------------------------------------------------------------------------

    private void drawRatioHistory(GraphView mChart, SdData sdData) {
        try {
            int n = mConnection.mSdServer.mSdDataHistory.mOsdRatioHistBuf.getNumVals();
            double[] ratios = mConnection.mSdServer.mSdDataHistory.mOsdRatioHistBuf.getVals();
            int lineColour;
            try {
                lineColour = mContext.getResources().getColor(R.color.colorAccent, null);
            } catch (Exception e) {
                lineColour = Color.RED;
            }
            float xSpan = (n * 5.0f) / 60.0f;   // minutes
            RatioHistoryChart.draw(mChart, ratios, n, sdData.alarmRatioThresh, lineColour,
                    getString(R.string.osd_ratio_history, xSpan));
            mChart.setTitleColor(okTextColour);
        } catch (Exception e) {
            Log.e(TAG, "Exception creating ratio history graph: " + e.getMessage());
        }
    }

    @Override
    protected void updateUiOnNewData() {
        updateUi();
    }

    @Override
    protected void updateUiFast() {
        if (isBasicMode()) {
            return;
        }
        // Graph updates are tied to new data to avoid flicker.
    }

    private void adjustChartHeightForMode(GraphView chart) {
        if (chart == null || isBasicMode()) {
            return;
        }
        LayoutParams params = chart.getLayoutParams();
        if (params == null || params.height <= 0) return;
        int shrinkPx = Math.round(20 * getResources().getDisplayMetrics().density);
        params.height = Math.max(params.height - shrinkPx, Math.round(150 * getResources().getDisplayMetrics().density));
        chart.setLayoutParams(params);
    }
 }
