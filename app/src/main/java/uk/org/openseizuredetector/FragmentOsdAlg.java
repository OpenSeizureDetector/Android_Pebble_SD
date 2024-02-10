package uk.org.openseizuredetector;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.renderer.YAxisRenderer;
import com.github.mikephil.charting.utils.ValueFormatter;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Objects;

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
        viewCreated = true;
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onDestroyView() {
        viewCreated = false;
        super.onDestroyView();
    }

    @Override
    protected void updateUi() {
        //Log.d(TAG,"updateUi()");
        TextView tv;


        /////////////////////////////////////////////////////
        // Set ProgressBars to show margin to alarm.
        if (viewCreated && Objects.nonNull(mConnection) && mConnection.mBound){
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
            pbDrawable = ContextCompat.getDrawable(getActivity(), R.drawable.progress_bar_blue);
            if (powerPc > 75)
                pbDrawable = ContextCompat.getDrawable(getActivity(), R.drawable.progress_bar_yellow);
            if (powerPc > 100)
                pbDrawable = ContextCompat.getDrawable(getActivity(), R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            ((TextView) mRootView.findViewById(R.id.spectrumTv)).setText(getString(R.string.SpectrumRatioEquals) + specRatio +
                    " (" + getString(R.string.Threshold) + "=" + mConnection.mSdServer.mSdData.alarmRatioThresh + ")");

            pb = ((ProgressBar) mRootView.findViewById(R.id.spectrumProgressBar));
            pb.setMax(100);
            pb.setProgress((int) specPc);
            pbDrawable = ContextCompat.getDrawable(getActivity(), R.drawable.progress_bar_blue);
            if (specPc > 75)
                pbDrawable = ContextCompat.getDrawable(getActivity(), R.drawable.progress_bar_yellow);
            if (specPc > 100)
                pbDrawable = ContextCompat.getDrawable(getActivity(), R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            ////////////////////////////////////////////////////////////
            // Produce graph
            BarChart mChart = (BarChart) mRootView.findViewById(R.id.chart1);
            mChart.setDrawBarShadow(false);
            mChart.setNoDataTextDescription("You need to provide data for the chart.");
            mChart.setDescription("");

            // X and Y Values
            ArrayList<String> xVals = new ArrayList<String>();
            ArrayList<BarEntry> yBarVals = new ArrayList<BarEntry>();
            for (int i = 0; i < 10; i++) {
                xVals.add(i + "-" + (i + 1) + " Hz");
                if (mConnection.mSdServer != null) {
                    yBarVals.add(new BarEntry(mConnection.mSdServer.mSdData.simpleSpec[i], i));
                } else {
                    yBarVals.add(new BarEntry(i, i));
                }
            }

            // create a dataset and give it a type
            BarDataSet barDataSet = new BarDataSet(yBarVals, "Spectrum");
            try {
                int[] barColours = new int[10];
                for (int i = 0; i < 10; i++) {
                    if ((i < mConnection.mSdServer.mSdData.alarmFreqMin) ||
                            (i > mConnection.mSdServer.mSdData.alarmFreqMax)) {
                        barColours[i] = Color.GRAY;
                    } else {
                        barColours[i] = Color.RED;
                    }
                }
                barDataSet.setColors(barColours);
            } catch (NullPointerException e) {
                Log.e(TAG, "Null pointer exception setting bar colours");
            }
            barDataSet.setBarSpacePercent(20f);
            barDataSet.setBarShadowColor(Color.WHITE);
            barDataSet.setValueTextColor(R.color.okTextColor);
            BarData barData = new BarData(xVals, barDataSet);
            barData.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float v) {
                    DecimalFormat format = new DecimalFormat("####");
                    return format.format(v);
                }
            });
            mChart.setData(barData);
            mChart.setDescriptionColor(Color.WHITE);
            Legend legendOfChart = mChart.getLegend();
            legendOfChart.setTextColor(Color.WHITE);

            // format the axes
            XAxis xAxis = mChart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setTextSize(10f);
            xAxis.setDrawAxisLine(true);
            xAxis.setDrawLabels(true);
            // Note:  the default text colour is BLACK, so does not show up on black background!!!
            //  This took a lot of finding....
            xAxis.setTextColor(Color.WHITE);
            xAxis.setDrawGridLines(false);

            YAxis yAxis = mChart.getAxisLeft();
            yAxis.setAxisMinValue(0f);
            yAxis.setAxisMaxValue(3000f);
            yAxis.setDrawGridLines(true);
            yAxis.setDrawLabels(true);
            yAxis.setTextColor(Color.WHITE);
            yAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float v) {
                    DecimalFormat format = new DecimalFormat("#####");
                    return format.format(v);
                }
            });

            YAxis yAxis2 = mChart.getAxisRight();
            yAxis2.setDrawGridLines(false);
            yAxis2.setTextColor(Color.WHITE);

            try {
                mChart.getLegend().setEnabled(false);
            } catch (NullPointerException e) {
                Log.e(TAG, "Null Pointer Exception setting legend");
            }

            if (mConnection.mSdServer.mBound) {
                mChart.postInvalidate();
            }
        }

    }

}
