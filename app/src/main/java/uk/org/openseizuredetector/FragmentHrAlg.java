package uk.org.openseizuredetector;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.google.android.gms.wearable.Node;

public class FragmentHrAlg extends FragmentOsdBaseClass {
    Context context = getContext();
    Looper looper = null;
    Handler mHandler = null;
    TextView tv = null;
    TextView tvCurrent = null;


    LineChart lineChart;
    LineData lineData;
    LineDataSet lineDataSet;
    List<Entry> hrHistory = new ArrayList<>();
    List<Entry> hrAverages = new ArrayList<>();
    List<String> hrHistoryStrings = new ArrayList<>();
    List<String> hrAveragesStrings = new ArrayList<>();
    String TAG = "FragmentOsdAlg";
    private List<Entry> listToDisplay;
    private List<String> listToDisplayStrings;
    private SwitchCompat switchAverages;


    public FragmentHrAlg() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Objects.isNull(looper) && Objects.nonNull((Context) this.getActivity()))
            looper = ((Context) this.getActivity()).getMainLooper();
        if (Objects.isNull(mHandler) && Objects.nonNull(looper))
            mHandler = new Handler(looper);
        if (mUtil.isServerRunning()) {
            mUtil.writeToSysLogFile("MainActivity.onStart - Binding to Server");
            if (Objects.nonNull(mConnection)) {
                if (!mConnection.mBound)
                    mUtil.bindToServer((Context) this.getActivity(), mConnection);
                mUtil.waitForConnection(mConnection);
                connectUiLiveDataRunner();

            }
        } else {
            Log.i(TAG, "onStart() - Server Not Running");
            mUtil.writeToSysLogFile("MainActivity.onStart - Server Not Running");
        }
        if (Objects.nonNull(mRootView)) {
            if (Objects.isNull(tv)) {
                tvCurrent = mRootView.findViewById(R.id.hrAlgTv);
            }

        }
    }

    void connectUiLiveDataRunner() {
        if (mConnection.mBound && Objects.nonNull(mConnection.mSdServer)) {
            if (!mConnection.mSdServer.uiLiveData.isListeningInContext(this)) {
                mConnection.mSdServer.uiLiveData.observe(this, this::onChangedObserver);
                mConnection.mSdServer.uiLiveData.observeForever(this::onChangedObserver);
                mConnection.mSdServer.uiLiveData.addToListening(this);
                switchAverages = mRootView.findViewById(R.id.switch1);
                switchAverages.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        updateUi();
                    }
                });
                updateUi();
            }
        } else {
            mHandler.postDelayed(this::connectUiLiveDataRunner, 100);
        }
    }


    private void onChangedObserver(Object o) {
        updateUi();
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
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
            tvCurrent = mRootView.findViewById(R.id.textView2);
            if (Objects.nonNull(tvCurrent)) {
                tvCurrent.setText(new StringBuilder()
                        .append("Current heartrate: ")
                        .append(mConnection.mSdServer.mSdData.mHR)
                        .append("\nCurrent average heartrate: ")
                        .append(mConnection.mSdServer.mSdData.mAverageHrAverage)
                        .append("\nand current adaptive heartrate: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAverage)
                        .append("\nResult of checks: Adaptive Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .append("\nAverage Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .toString());
                if (Objects.isNull(hrAverages)) hrAverages = new ArrayList<>();
                if (Objects.isNull(hrHistory)) hrHistory = new ArrayList<>();

                for (double heartRateEntry : mConnection.mSdServer.mSdData.heartRates) {
                    hrHistory.add(new Entry((float) heartRateEntry, hrHistory.size()));
                }
                hrAverages.add(new Entry((float) mConnection.mSdServer.mSdData.mAverageHrAverage, hrAverages.size()));
                hrAveragesStrings.add(String.valueOf((short) mConnection.mSdServer.mSdData.mAverageHrAverage));
                hrHistory.add(new Entry((float) mConnection.mSdServer.mSdData.mHR, hrAverages.size()));
                hrHistoryStrings.add(String.valueOf((short) mConnection.mSdServer.mSdData.mHR));
                switchAverages = mRootView.findViewById(R.id.switch1);
                listToDisplay = switchAverages.isChecked() ? hrAverages : hrHistory;
                listToDisplayStrings = switchAverages.isChecked() ? hrAveragesStrings : hrHistoryStrings;
                if (Objects.nonNull(listToDisplay)) {
                    if (listToDisplay.size() > 0) {

                        lineChart = mRootView.findViewById(R.id.lineChart);

                        if (lineChart.getData() != null &&
                                lineChart.getData().getDataSetCount() > 0) {
                            lineDataSet = (LineDataSet) lineChart.getData().getDataSetByIndex(0);
                        }
                        else {
                            lineDataSet = new LineDataSet(listToDisplay, "Heart rate history" + (switchAverages.isChecked() ?" averages" : "" ));
                        }
                        lineData = new LineData(listToDisplayStrings, lineDataSet);
                        lineChart.setData(lineData);
                        lineDataSet.setColors(ColorTemplate.JOYFUL_COLORS);
                        lineDataSet.setValueTextColor(Color.BLACK);
                        lineDataSet.setValueTextSize(18f);
                        lineChart.getData().notifyDataChanged();
                        lineChart.notifyDataSetChanged();
                        lineChart.refreshDrawableState();
                        lineChart.postInvalidate();
                    }

                }
            } else {
                tv.setText("****NOT BOUND TO SERVER***");
                return;
            }


        }
    }
}
