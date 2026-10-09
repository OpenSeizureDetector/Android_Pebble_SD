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
import android.widget.TextView;
import androidx.appcompat.content.res.AppCompatResources;
import android.view.ViewGroup.LayoutParams;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.BarGraphSeries;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.ValueDependentColor;
import com.jjoe64.graphview.series.DataPoint;

public class FragmentOsdAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentOsdAlg";

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
    }

    @Override
    protected void updateUi() {
        //Log.d(TAG,"updateUi()");
        if (isBasicMode()) {
            return;
        }
        TextView tv;

        if (mConnection.mBound) {
            /////////////////////////////////////////////////////
            // Set ProgressBar to show margin to alarm (spectrum ratio).
            long specPc;
            if (mConnection.mSdServer.mSdData.specPower != 0 &&
                    mConnection.mSdServer.mSdData.alarmRatioThresh != 0)
                specPc = 100 * (mConnection.mSdServer.mSdData.roiPower * 10 /
                        mConnection.mSdServer.mSdData.specPower) /
                        mConnection.mSdServer.mSdData.alarmRatioThresh;
            else
                specPc = 0;

            long specRatio;
            if (mConnection.mSdServer.mSdData.specPower != 0) {
                specRatio = 10 * mConnection.mSdServer.mSdData.roiPower /
                        mConnection.mSdServer.mSdData.specPower;
            } else
                specRatio = 0;

            ((TextView) mRootView.findViewById(R.id.spectrumTv)).setText(getString(R.string.SpectrumRatioEquals) + specRatio +
                    " (" + getString(R.string.Threshold) + "=" + mConnection.mSdServer.mSdData.alarmRatioThresh + ")");

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


            ////////////////////////////////////////////////////////////
            // Produce bar graph of the 0-10 Hz spectrum using GraphView
            GraphView mChart = (GraphView) mRootView.findViewById(R.id.chart1);

            mChart.removeAllSeries();

            try {
                DataPoint[] dataPoints = new DataPoint[10];
                for (int i = 0; i < 10; i++) {
                    double v = (mConnection.mSdServer != null)
                            ? mConnection.mSdServer.mSdData.simpleSpec[i] : 0;
                    // Bin i covers i..i+1 Hz, so its bar is centred at i + 0.5
                    dataPoints[i] = new DataPoint(i + 0.5, v);
                }

                final int alarmFreqMin = (int) mConnection.mSdServer.mSdData.alarmFreqMin;
                final int alarmFreqMax = (int) mConnection.mSdServer.mSdData.alarmFreqMax;

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

            // CHANGED (issue #238): Y-axis maximum used to be hardcoded to 3000
            // (mChart.getViewport().setMaxY(3000)). It is now derived from the "Alarm
            // Threshold" setting plus 30% headroom, so the graph scales sensibly across
            // different hardware/OS combinations where the appropriate threshold varies
            // a lot (e.g. see issue #233). Falls back to the old value of 3000 only if
            // the threshold is unset/zero, to avoid a degenerate zero-height Y-axis.
            long alarmThresh = mConnection.mSdServer.mSdData.alarmThresh;
            // double maxY = alarmThresh > 0 ? alarmThresh * 1.3 : 3000;
            double maxY = alarmThresh > 0 ? alarmThresh * 100 : 3000;
            mChart.getViewport().setYAxisBoundsManual(true);
            mChart.getViewport().setMinY(0);
            mChart.getViewport().setMaxY(maxY);
            mChart.getViewport().setXAxisBoundsManual(true);
            mChart.getViewport().setMinX(0);
            mChart.getViewport().setMaxX(10);

            // Show where the alarm threshold sits on the Y axis (thin line + value).
            GraphThresholdLine.add(mChart, alarmThresh, 0.0, 10.0);

            mChart.getViewport().setScalable(false);
            mChart.getViewport().setScrollable(false);

            // Use theme-aware text color from base class
            // 11 labels over the 10-unit-wide X range (-0.5 .. 9.5) gives a label step of
            // exactly 1.0, so GraphView's "human rounding" keeps it at 1 (with 10 labels the
            // step was 1.11, which it rounded to 2 - hence only every second label showed).
            // Labels then land on the whole numbers 0..9, i.e. centred under each bar, and
            // each label also gets a vertical grid line.
            mChart.getGridLabelRenderer().setNumHorizontalLabels(11);
            // Don't draw the special thick line at the 0 value (it would be a heavy line
            // through the first bar); the red ROI segment marks the axis instead.
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
                        if (i == 0) {
                            return "Hz:";
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
        // OSD graph updates are tied to new data to avoid flicker.
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
