package uk.org.openseizuredetector;

import android.graphics.Color;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
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


public class FragmentHrAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentHrAlg";

    LineChart mLineChart;
    LineData lineData;
    LineDataSet lineDataSet;
    List<Entry> hrHistory = new ArrayList<>();
    List<Entry> hrAverages = new ArrayList<>();
    List<String> hrHistoryStrings = new ArrayList<>();
    List<String> hrAveragesStrings = new ArrayList<>();
    private List<Entry> listToDisplay;
    private List<String> listToDisplayStrings;

    private TextView tvAvgAHr;
    private TextView tvHr;
    private TextView tv;
    private TextView tvCurrent;
    private SwitchCompat switchAverages;

    public FragmentHrAlg() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lineDataSet = new LineDataSet(new ArrayList<Entry>(), "Heart rate history");
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
        mLineChart = mRootView.findViewById(R.id.lineChart);
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
        yAxis.setAxisMinValue(40f);
        yAxis.setAxisMaxValue(240f);
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
        return inflater.inflate(R.layout.fragment_hr_alg, container, false);
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        tv = (TextView) mRootView.findViewById(R.id.fragment_hr_alg_tv1);
        tvHr = (TextView) mRootView.findViewById(R.id.current_hr_tv);
        tvAvgAHr = (TextView) mRootView.findViewById(R.id.adaptive_avg_hr_tv);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");

            tvCurrent = mRootView.findViewById(R.id.textView2);
            if (Objects.nonNull(tvCurrent)) {
                if (Objects.nonNull(tvHr))
                    tvHr.setText(String.valueOf((short) mConnection.mSdServer.mSdData.mHR));
                if (Objects.nonNull(tvAvgAHr))
                    tvAvgAHr.setText(String.valueOf((short) mConnection.mSdServer.mSdData
                            .mAdaptiveHrAverage));
                tvCurrent.setText(new StringBuilder()
                        .append("\nResult of checks: Adaptive Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .append("\nAverage Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .toString());

                //switchAverages = mRootView.findViewById(R.id.hr_average_switch);

                if (Objects.nonNull(mConnection.mSdServer.mSdDataSource.mSdAlgHr)) {
                    //Log.v(TAG,"mSdAlgHr is not null");
                    CircBuf hrHist = mConnection.mSdServer.mSdDataSource.mSdAlgHr.getHrHistBuff();
                    int nHistArr = hrHist.getNumVals();
                    double hrHistArr[] = hrHist.getVals();   // This gives us a simple vector of hr values to plot.
                    if (Objects.nonNull(hrHist) && nHistArr > 0) {
                        Log.v(TAG, "hrHist.getNumVals=" + nHistArr);
                        lineDataSet.clear();
                        String xVals[] = new String[nHistArr];
                        for (int i = 0; i < nHistArr; i++) {
                            //Log.d(TAG,"i="+i+", HR="+hrHistArr[i]);
                            xVals[i] = String.valueOf(i);
                            lineDataSet.addEntry(new Entry((float) hrHistArr[i], i));
                        }
                        Log.d(TAG, "xVals=" + Arrays.toString(xVals) + ", lneDataSet=" + lineDataSet.toSimpleString());
                        lineDataSet.setColors(new int[]{0xffff0000});
                        LineData hrHistLineData = new LineData(xVals, lineDataSet);


                        mLineChart.setData(hrHistLineData);
                        mLineChart.getData().notifyDataChanged();
                        mLineChart.notifyDataSetChanged();
                        mLineChart.refreshDrawableState();
                        float xSpan = (nHistArr * 5.0f) / 60.0f;   // time in minutes assuming one point every 5 seconds.
                        mLineChart.setDescription(getString(R.string.heart_rate_history_bpm)
                                + String.format("%.1f", xSpan)
                                + " " + getString(R.string.minutes));
                        mLineChart.setDescriptionTextSize(12f);
                        mLineChart.invalidate();
                        //if (mConnection.mBound){
                        //    lineChart.postInvalidate();
                        //}
                    }

                }
            } else {
                tv.setText("****NOT BOUND TO SERVER***");
                return;
            }


        }
    }


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (Objects.nonNull(mConnection))
           mUtil.setBound(true,mConnection);
    }

    @Override
    public void onDetach() {
        super.onDetach();

        if (Objects.nonNull(mConnection))
            mUtil.setBound(false,mConnection);
    }
}
