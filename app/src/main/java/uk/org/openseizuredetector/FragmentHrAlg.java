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
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


public class FragmentHrAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentOsdAlg";

    LineChart lineChart;
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
        lineDataSet = new LineDataSet(new ArrayList<Entry>(),"Heart rate history" );
        lineDataSet.setColors(ColorTemplate.JOYFUL_COLORS);
        lineDataSet.setValueTextColor(Color.BLACK);
        lineDataSet.setValueTextSize(18f);
        lineDataSet.setDrawValues(false);
        //lineDataSetAverage = new LineDataSet(new ArrayList<Entry>(),"Heart rate history" );
        //lineDataSetAverage.setColors(ColorTemplate.JOYFUL_COLORS);
        //lineDataSetAverage.setValueTextColor(Color.BLACK);
        //lineDataSetAverage.setValueTextSize(18f);

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
                    tvHr.setText( String.valueOf((short)mConnection.mSdServer.mSdData.mHR));
                if (Objects.nonNull(tvAvgAHr))
                    tvAvgAHr.setText( String.valueOf((short)mConnection.mSdServer.mSdData
                            .mAdaptiveHrAverage));
                tvCurrent.setText(new StringBuilder()
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAverage)
                        .append("\nResult of checks: Adaptive Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .append("\nAverage Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .toString());

                switchAverages = mRootView.findViewById(R.id.hr_average_switch);

                if (Objects.nonNull(mConnection.mSdServer.mSdDataSource.mSdAlgHr)) {
                    Log.v(TAG,"mSdAlgHr si not null");
                    CircBuf hrHist = mConnection.mSdServer.mSdDataSource.mSdAlgHr.getHrHistBuff();
                    int nHistArr = hrHist.getNumVals();
                    double hrHistArr[] = hrHist.getVals();   // This gives us a simple vector of hr values to plot.
                    if (Objects.nonNull(hrHist) && nHistArr > 0) {
                        Log.v(TAG, "hrHist.getNumVals="+nHistArr);
                        lineDataSet.clear();
                        String xVals[] = new String[nHistArr];
                        for (int i = 0; i<nHistArr; i++) {
                            Log.d(TAG,"i="+i+", HR="+hrHistArr[i]);
                            xVals[i] = String.valueOf(i);
                            lineDataSet.addEntry(new Entry((float)hrHistArr[i], i));
                        }
                        Log.d(TAG,"xVals="+ Arrays.toString(xVals)+ ", lneDataSet="+lineDataSet.toSimpleString());
                        LineData hrHistLineData = new LineData(xVals, lineDataSet);

                        lineChart = mRootView.findViewById(R.id.lineChart);
                        lineChart.setData(hrHistLineData);

                        lineChart.getData().notifyDataChanged();
                        lineChart.notifyDataSetChanged();
                        lineChart.refreshDrawableState();
                        lineChart.invalidate();
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
}
