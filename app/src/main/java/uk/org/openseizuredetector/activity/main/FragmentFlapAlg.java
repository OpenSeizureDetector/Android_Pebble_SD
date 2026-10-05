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
import com.jjoe64.graphview.ValueDependentColor;
import com.jjoe64.graphview.series.BarGraphSeries;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.DataPoint;

/**
 * Displays the "Flap" tab - a graph of the Flap algorithm's spectrum, identical in
 * structure to the "OSD" tab (FragmentOsdAlg) but driven by the Flap algorithm's own
 * data (sdData.flap*) and "Flap Alarm Threshold" setting (see issue #238).
 */
public class FragmentFlapAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentFlapAlg";

    public FragmentFlapAlg() {
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
        return inflater.inflate(R.layout.fragment_flapalg, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        GraphView chart = view.findViewById(R.id.flapChart1);
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
            if (mConnection.mSdServer.mSdData.flapSpecPower != 0 &&
                    mConnection.mSdServer.mSdData.flapAlarmRatioThresh != 0)
                specPc = 100 * (mConnection.mSdServer.mSdData.flapRoiPower * 10 /
                        mConnection.mSdServer.mSdData.flapSpecPower) /
                        mConnection.mSdServer.mSdData.flapAlarmRatioThresh;
            else
                specPc = 0;

            long specRatio;
            if (mConnection.mSdServer.mSdData.flapSpecPower != 0) {
                specRatio = 10 * mConnection.mSdServer.mSdData.flapRoiPower /
                        mConnection.mSdServer.mSdData.flapSpecPower;
            } else
                specRatio = 0;

            ((TextView) mRootView.findViewById(R.id.flapSpectrumTv)).setText(getString(R.string.SpectrumRatioEquals) + specRatio +
                    " (" + getString(R.string.Threshold) + "=" + mConnection.mSdServer.mSdData.flapAlarmRatioThresh + ")");

            ProgressBar pb;
            Drawable pbDrawable;
            pb = ((ProgressBar) mRootView.findViewById(R.id.flapSpectrumProgressBar));
            pb.setMax(100);
            pb.setProgress((int) specPc);
            pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_blue);
            if (specPc > 75)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_yellow);
            if (specPc > 100)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);


            ////////////////////////////////////////////////////////////
            // Produce bar graph of the 0-10 Hz Flap spectrum using GraphView
            GraphView mChart = (GraphView) mRootView.findViewById(R.id.flapChart1);

            mChart.removeAllSeries();

            try {
                DataPoint[] dataPoints = new DataPoint[10];
                for (int i = 0; i < 10; i++) {
                    double v = (mConnection.mSdServer != null)
                            ? mConnection.mSdServer.mSdData.flapSimpleSpec[i] : 0;
                    // Bin i covers i..i+1 Hz, so its bar is centred at i + 0.5
                    dataPoints[i] = new DataPoint(i + 0.5, v);
                }

                final int alarmFreqMin = (int) mConnection.mSdServer.mSdData.flapAlarmFreqMin;
                final int alarmFreqMax = (int) mConnection.mSdServer.mSdData.flapAlarmFreqMax;

                // Bar graph: one bar per 1 Hz bin. Bars inside the alarm band
                // (flapAlarmFreqMin up to, not including, flapAlarmFreqMax) are red, the
                // rest gray. The bars are slightly translucent because GraphView draws the
                // grid lines BEFORE the series - opaque bars would hide the horizontal
                // grid lines behind them.
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

            // Y-axis maximum is derived from the "Flap Alarm Threshold" setting plus 30%
            // headroom, mirroring the OSD tab's Y-axis behaviour (see issue #238).
            long flapAlarmThresh = mConnection.mSdServer.mSdData.flapAlarmThresh;
            double maxY = flapAlarmThresh > 0 ? flapAlarmThresh * 1.3 : 3000;
            mChart.getViewport().setYAxisBoundsManual(true);
            mChart.getViewport().setMinY(0);
            mChart.getViewport().setMaxY(maxY);
            mChart.getViewport().setXAxisBoundsManual(true);
            mChart.getViewport().setMinX(0);
            mChart.getViewport().setMaxX(10);

            // Show where the Flap alarm threshold sits on the Y axis (thin line + value).
            GraphThresholdLine.add(mChart, flapAlarmThresh, 0.0, 10.0);

            mChart.getViewport().setScalable(false);
            mChart.getViewport().setScrollable(false);

            // Use theme-aware text color from base class
            // 11 labels over the 10-unit-wide X range (0 .. 10) gives a label step of exactly
            // 1.0, which GraphView's "human rounding" leaves alone (a step of 1.11 would be
            // rounded to 2 and only every second label would show). Labels land on the
            // bin edges 0..10 and each label also gets a vertical grid line.
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
        // Flap graph updates are tied to new data to avoid flicker.
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
