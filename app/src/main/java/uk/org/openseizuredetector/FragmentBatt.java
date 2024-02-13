package uk.org.openseizuredetector;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

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


public class FragmentBatt extends FragmentOsdBaseClass {
    String TAG = "FragmentBatt";

    LineChart mLineChart;
    LineData lineData;
    LineDataSet lineDataSet;
    LineDataSet lineDataSetPhone;
    List<Entry> watchHistory = new ArrayList<>();
    List<Entry> phoneHistory = new ArrayList<>();
    List<String> hrHistoryStrings = new ArrayList<>();
    List<String> hrAveragesStrings = new ArrayList<>();
    private List<Entry> listToDisplay;
    private List<String> listToDisplayStrings;
    private SwitchCompat phoneWatchSwitch;


    public FragmentBatt() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lineDataSet = new LineDataSet(new ArrayList<Entry>(), "Battery history");
        lineDataSetPhone = new LineDataSet(new ArrayList<Entry>(), "Battery history");
        //lineDataSet.setColors(ColorTemplate.JOYFUL_COLORS);
        lineDataSet.setValueTextColor(Color.BLACK);
        lineDataSet.setValueTextSize(18f);
        lineDataSet.setDrawValues(false);
        lineDataSet.setCircleSize(0f);
        lineDataSet.setLineWidth(3f);
        lineDataSetPhone.setValueTextColor(Color.BLACK);
        lineDataSetPhone.setValueTextSize(18f);
        lineDataSetPhone.setDrawValues(false);
        lineDataSetPhone.setCircleSize(0f);
        lineDataSetPhone.setLineWidth(3f);
        //lineDataSetAverage = new LineDataSet(new ArrayList<Entry>(),"Heart rate history" );
        //lineDataSetAverage.setColors(ColorTemplate.JOYFUL_COLORS);
        //lineDataSetAverage.setValueTextColor(Color.BLACK);
        //lineDataSetAverage.setValueTextSize(18f);

    }

    @Override
    public void onResume() {
        super.onResume();
        phoneWatchSwitch = mRootView.findViewById(R.id.switchToPowerGraph);
        phoneWatchSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Objects.nonNull(mLineChart)) {
                mLineChart.clear();
                mLineChart.postInvalidate();
            }
            mUtil.runOnUiThread(()->updateUi());
        });
        mLineChart = mRootView.findViewById(R.id.lineChartBattHist);
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
        return inflater.inflate(R.layout.fragment_batt, container, false);
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        if (Objects.isNull(mRootView)||!isAdded()||!isVisible()) return;
        if (mConnection.mBound) {

            int nWatchBattArr = mConnection.mSdServer.mSdData.watchBattBuff.getNumVals();
            double watchBattArr[] = mConnection.mSdServer.mSdData.watchBattBuff.getVals();   // This gives us a simple vector of hr values to plot.
            int nPhoneBattArr = mConnection.mSdServer.mSdData.phoneBattBuff.getNumVals();
            double phoneBattArr[] = mConnection.mSdServer.mSdData.phoneBattBuff.getVals();
            if (Objects.nonNull(mConnection.mSdServer.mSdData.watchBattBuff) && nWatchBattArr > 0 && nPhoneBattArr > 0) {
                Log.v(TAG, "hrWatchBattBuff.getNumVals=" + nWatchBattArr);
                lineDataSet.clear();
                lineDataSetPhone.clear();
                String xVals[] = new String[nWatchBattArr];
                for (int i = 0; i < nWatchBattArr; i++) {
                    //Log.d(TAG,"i="+i+", HR="+hrHistArr[i]);
                    xVals[i] = String.valueOf(i);
                    lineDataSet.addEntry(new Entry((float) watchBattArr[i], i));
                }
                String xValsPhone[] = new String[nPhoneBattArr];
                for (int i = 0; i < nPhoneBattArr; i++) {
                    //Log.d(TAG,"i="+i+", HR="+hrHistArr[i]);
                    xValsPhone[i] = String.valueOf(i);
                    lineDataSetPhone.addEntry(new Entry((float) phoneBattArr[i], i));
                }
                Log.d(TAG, "xVals=" + Arrays.toString(xVals) + ", lneDataSet=" + lineDataSet.toSimpleString());
                lineDataSet.setColors(new int[]{0xfffff000});
                LineData battHistLineDataWatch = new LineData(xVals, lineDataSet);
                Log.d(TAG, "xVals=" + Arrays.toString(xValsPhone) + ", lneDataSet=" + lineDataSetPhone.toSimpleString());
                lineDataSet.setColors(new int[]{0xffff0000});
                LineData battHistLineDataPhone = new LineData(xValsPhone, lineDataSetPhone);


                mLineChart.setData(phoneWatchSwitch.isChecked()?battHistLineDataPhone:battHistLineDataWatch);
                mLineChart.getData().notifyDataChanged();
                mLineChart.notifyDataSetChanged();
                mLineChart.refreshDrawableState();
                float xSpan = (phoneWatchSwitch.isChecked()?nPhoneBattArr:nWatchBattArr * 5.0f) / 60.0f;   // time in minutes assuming one point every 5 seconds.
                mLineChart.setDescription(getString(R.string.watch_batt_hist)
                        + String.format("%.1f", xSpan)
                        + " " + getString(R.string.minutes));
                mLineChart.setDescriptionTextSize(12f);
                mLineChart.invalidate();
                //if (mConnection.mBound){
                //    lineChart.postInvalidate();
                //}
            }
        }
    }
}
