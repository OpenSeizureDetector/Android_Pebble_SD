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
import com.jjoe64.graphview.series.LineGraphSeries;
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
            // Set ProgressBars to show margin to alarm.
            long powerPc;
            if (mConnection.mSdServer.mSdData.alarmThresh != 0)
                powerPc = mConnection.mSdServer.mSdData.roiPower * 100 /
                        mConnection.mSdServer.mSdData.alarmThresh;
            else
                powerPc = 0;

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

            ((TextView) mRootView.findViewById(R.id.powerTv)).setText(getString(R.string.PowerEquals) + mConnection.mSdServer.mSdData.roiPower +
                    " (" + getString(R.string.Threshold) + "=" + mConnection.mSdServer.mSdData.alarmThresh + ")");

            ProgressBar pb;
            Drawable pbDrawable;
            pb = ((ProgressBar) mRootView.findViewById(R.id.powerProgressBar));
            pb.setMax(100);
            pb.setProgress((int) powerPc);
            pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_blue);
            if (powerPc > 75)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_yellow);
            if (powerPc > 100)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            ((TextView) mRootView.findViewById(R.id.spectrumTv)).setText(getString(R.string.SpectrumRatioEquals) + specRatio +
                    " (" + getString(R.string.Threshold) + "=" + mConnection.mSdServer.mSdData.alarmRatioThresh + ")");

            pb = ((ProgressBar) mRootView.findViewById(R.id.spectrumProgressBar));
            pb.setMax(100);
            pb.setProgress((int) specPc);
            pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_blue);
            if (specPc > 75)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_yellow);
            if (specPc > 100)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            long pSeizurePc;
            pSeizurePc = (long) (mConnection.mSdServer.mSdData.mPseizure * 100);

            ((TextView) mRootView.findViewById(R.id.pSeizureTvM2)).setText(getString(R.string.seizure_probability) + " : " + pSeizurePc + "%");

            pb = ((ProgressBar) mRootView.findViewById(R.id.pSeizureProgressBarM2));
            pb.setMax(100);
            pb.setProgress((int) pSeizurePc);
            pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_blue);
            if (pSeizurePc > 30)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_yellow);
            if (pSeizurePc > 50)
                pbDrawable = AppCompatResources.getDrawable(mContext, R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            ////////////////////////////////////////////////////////////
            // Produce graph using GraphView with smoothed line
            GraphView mChart = (GraphView) mRootView.findViewById(R.id.chart1);

            mChart.removeAllSeries();

            try {
                DataPoint[] dataPoints = new DataPoint[10];
                for (int i = 0; i < 10; i++) {
                    if (mConnection.mSdServer != null) {
                        dataPoints[i] = new DataPoint(i, mConnection.mSdServer.mSdData.simpleSpec[i]);
                    } else {
                        dataPoints[i] = new DataPoint(i, 0);
                    }
                }

                int alarmFreqMin = (int) mConnection.mSdServer.mSdData.alarmFreqMin;
                int alarmFreqMax = (int) mConnection.mSdServer.mSdData.alarmFreqMax;

                if (alarmFreqMin > 0) {
                    DataPoint[] graySegmentBefore = new DataPoint[alarmFreqMin + 1];
                    for (int i = 0; i <= alarmFreqMin; i++) {
                        graySegmentBefore[i] = dataPoints[i];
                    }
                    com.jjoe64.graphview.series.LineGraphSeries<DataPoint> graySeriesBefore =
                        new com.jjoe64.graphview.series.LineGraphSeries<>(graySegmentBefore);
                    graySeriesBefore.setColor(Color.GRAY);
                    graySeriesBefore.setThickness(4);
                    graySeriesBefore.setDrawDataPoints(true);
                    graySeriesBefore.setDataPointsRadius(6);
                    mChart.addSeries(graySeriesBefore);
                }

                int alarmRangeSize = alarmFreqMax - alarmFreqMin + 1;
                DataPoint[] redSegment = new DataPoint[alarmRangeSize];
                for (int i = 0; i < alarmRangeSize; i++) {
                    redSegment[i] = dataPoints[alarmFreqMin + i];
                }
                com.jjoe64.graphview.series.LineGraphSeries<DataPoint> redSeries =
                    new com.jjoe64.graphview.series.LineGraphSeries<>(redSegment);
                redSeries.setColor(Color.RED);
                redSeries.setThickness(4);
                redSeries.setDrawDataPoints(true);
                redSeries.setDataPointsRadius(6);
                mChart.addSeries(redSeries);

                if (alarmFreqMax < 9) {
                    int grayAfterSize = 10 - alarmFreqMax;
                    DataPoint[] graySegmentAfter = new DataPoint[grayAfterSize];
                    for (int i = 0; i < grayAfterSize; i++) {
                        graySegmentAfter[i] = dataPoints[alarmFreqMax + i];
                    }
                    com.jjoe64.graphview.series.LineGraphSeries<DataPoint> graySeriesAfter =
                        new com.jjoe64.graphview.series.LineGraphSeries<>(graySegmentAfter);
                    graySeriesAfter.setColor(Color.GRAY);
                    graySeriesAfter.setThickness(4);
                    graySeriesAfter.setDrawDataPoints(true);
                    graySeriesAfter.setDataPointsRadius(6);
                    mChart.addSeries(graySeriesAfter);
                }

            } catch (Exception e) {
                Log.e(TAG, "Exception creating spectrum graph: " + e.getMessage());
            }

            mChart.getViewport().setYAxisBoundsManual(true);
            mChart.getViewport().setMinY(0);
            mChart.getViewport().setMaxY(3000);
            mChart.getViewport().setXAxisBoundsManual(true);
            mChart.getViewport().setMinX(-0.5);
            mChart.getViewport().setMaxX(9.5);

            mChart.getViewport().setScalable(false);
            mChart.getViewport().setScrollable(false);

            // Use theme-aware text color from base class
            mChart.getGridLabelRenderer().setNumHorizontalLabels(10);
            mChart.getGridLabelRenderer().setNumVerticalLabels(5);
            mChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
            mChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
            mChart.getGridLabelRenderer().setGridColor(Color.GRAY);

            mChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
                @Override
                public String formatLabel(double value, boolean isValueX) {
                    if (isValueX) {
                        int i = (int) Math.round(value);
                        if (i >= 0 && i < 10) {
                            return i + "-" + (i + 1) + "Hz";
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
