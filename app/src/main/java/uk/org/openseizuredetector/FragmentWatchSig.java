package uk.org.openseizuredetector;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.ValueFormatter;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


public class FragmentWatchSig extends FragmentOsdBaseClass {
    String TAG = "FragmentWatchSig";

    LineChart mLineChart;
    LineData lineData;
    LineDataSet lineDataSet;
    List<Entry> sigHistory = new ArrayList<>();
    List<String> hrHistoryStrings = new ArrayList<>();

    private TextView tvCurrSigStren;

    public FragmentWatchSig() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lineDataSet = new LineDataSet(new ArrayList<Entry>(), "Watch Signal Strength history");
        //lineDataSet.setColors(ColorTemplate.JOYFUL_COLORS);
        lineDataSet.setValueTextColor(Color.BLACK);
        lineDataSet.setValueTextSize(18f);
        lineDataSet.setDrawValues(false);
        lineDataSet.setCircleSize(0f);
        lineDataSet.setLineWidth(3f);
        //lineDataSetAverage = new LineDataSet(new ArrayList<Entry>(),"Heart rate history" );
        //lineDataSetAverage.setColors(ColorTemplate.JOYFUL_COLORS);
        //lineDataSetAverage.setValueTextColor(Color.BLACK);
        //lineDataSetAverage.setValueTextSize(18f);

    }

    @Override
    public void onResume() {
        super.onResume();
        mLineChart = mRootView.findViewById(R.id.sigStrengthLineChart);
        mLineChart.getLegend().setEnabled(false);
        XAxis xAxis = mLineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextSize(10f);
        xAxis.setDrawAxisLine(true);
        xAxis.setDrawLabels(true);
        // Note:  the default text colour is BLACK, so does not show up on black background!!!
        //  This took a lot of finding....
        xAxis.setTextColor(Color.WHITE);

        YAxis yAxis = mLineChart.getAxisLeft();
        yAxis.setAxisMaxValue(-50f);
        yAxis.setAxisMinValue(-100f);
        yAxis.setDrawGridLines(true);
        yAxis.setDrawLabels(true);
        yAxis.setTextColor(Color.WHITE);
        // Inhibit the decimal part of the y axis labels.
        yAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float v) {
                DecimalFormat format = new DecimalFormat("###");
                return format.format(v);
            }
        });

        YAxis yAxis2 = mLineChart.getAxisRight();
        yAxis2.setDrawGridLines(false);
        yAxis2.setEnabled(false);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_watch_sig, container, false);
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        tvCurrSigStren = (TextView) mRootView.findViewById(R.id.current_sig_strength_tv);
        if (mConnection.mBound) {
            if (Objects.nonNull(tvCurrSigStren))
                tvCurrSigStren.setText(String.valueOf((int) mConnection.mSdServer.mSdData.watchSignalStrength));
            double histArr[] = mConnection.mSdServer.mSdData.watchSignalStrengthBuff.getVals();
            int nHist = histArr.length;
            if (Objects.nonNull(histArr) && nHist > 0) {
                Log.v(TAG, "nHist=" + nHist);
                lineDataSet.clear();
                String xVals[] = new String[nHist];
                for (int i = 0; i < nHist; i++) {
                    //Log.d(TAG,"i="+i+", HR="+hrHistArr[i]);
                    xVals[i] = String.valueOf(i);
                    lineDataSet.addEntry(new Entry((float) histArr[i], i));
                }
                Log.d(TAG, "xVals=" + Arrays.toString(xVals) + ", lneDataSet=" + lineDataSet.toSimpleString());
                lineDataSet.setColors(new int[]{0xffff0000});
                LineData histLineData = new LineData(xVals, lineDataSet);


                mLineChart.setData(histLineData);
                mLineChart.getData().notifyDataChanged();
                mLineChart.notifyDataSetChanged();
                mLineChart.refreshDrawableState();
                float xSpan = (nHist * 5.0f) / 60.0f;   // time in minutes assuming one point every 5 seconds.
                mLineChart.setDescription("Signal Strength History "
                        + String.format("%.1f", xSpan)
                        + " " + getString(R.string.minutes));
                mLineChart.setDescriptionTextSize(12f);
                mLineChart.invalidate();
                //if (mConnection.mBound){
                //    lineChart.postInvalidate();
                //}
            }

        } else {
            Log.w(TAG,"not Bound to Server");
            return;
        }


    }

}
