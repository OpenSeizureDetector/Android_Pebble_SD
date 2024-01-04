package uk.org.openseizuredetector;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import android.util.Log;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class SdAlgHr {
    private final static String TAG = "SdAlgHr";
    private Context mContext;
    protected boolean mSimpleHrAlarmActive;
    private double mSimpleHrAlarmThreshMin;
    private double mSimpleHrAlarmThreshMax;

    protected boolean mAdaptiveHrAlarmActive;
    private double mAdaptiveHrAlarmWindowSecs;
    private int mAdaptiveHrAlarmWindowDp;
    private int mAHistoricHrAlarmWindowDp;
    private double mAdaptiveHrAlarmThresh;
    protected boolean mAverageHrAlarmActive;
    private double mAverageHrAlarmWindowSecs;
    private int mAverageHrAlarmWindowDp;
    private double mAverageHrAlarmThreshMin;
    private double mAverageHrAlarmThreshMax;
    private List<Entry> mHistoricHrBuff;


    private CircBuf mAdaptiveHrBuff;
    private List<Entry> mAverageHrBuff;
    private LineData lineData = new LineData();
    private LineData lineDataAverage = new LineData();
    private LineDataSet lineDataSet ;
    private LineDataSet lineDataSetAverage ;
    List<String> hrHistoryStrings = new ArrayList<>();
    List<String> hrHistoryStringsAverage = new ArrayList<>();

    public SdAlgHr(Context context) {
        Log.i(TAG, "SdAlgHr Constructor");
        mContext = context;
        updatePrefs();
        mHistoricHrBuff = new ArrayList<>(mAHistoricHrAlarmWindowDp);
        mAdaptiveHrBuff = new CircBuf(mAdaptiveHrAlarmWindowDp, -1.0);
        mAverageHrBuff = new ArrayList<>(mAverageHrAlarmWindowDp);
        lineDataSet = new LineDataSet(new ArrayList<Entry>(),"Heart rate history" );
        lineDataSetAverage = new LineDataSet(new ArrayList<Entry>(),"Heart rate history" );
    }

    public void close() {
        Log.d(TAG, "close()");
    }

    public float getAlarmState(SdData sdData) {
        return (0);
    }

    private double readDoublePref(SharedPreferences SP, String prefName, String defVal) {
        String prefValStr;
        double retVal = -1;
        try {
            prefValStr = SP.getString(prefName, defVal);
            retVal = Double.parseDouble(prefValStr);
        } catch (Exception ex) {
            Log.v(TAG, "readDoublePref() - Problem with preference!");
            //mUtil.showToast(TAG+":"+mContext.getString(R.string.problem_parsing_preferences));
        }
        return retVal;
    }

    private void updatePrefs() {
        /**
         * updatePrefs() - update basic settings from the SharedPreferences
         * - defined in res/xml/prefs.xml
         */
        Log.i(TAG, "updatePrefs()");

        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mSimpleHrAlarmActive = SP.getBoolean("HRAlarmActive", false);
        mSimpleHrAlarmThreshMin = readDoublePref(SP, "HRThreshMin", "20");
        mSimpleHrAlarmThreshMax = readDoublePref(SP, "HRThreshMax", "150");
        Log.d(TAG,"updatePrefs(): mSimpleHrAlarmActive="+mSimpleHrAlarmActive);
        Log.d(TAG,"updatePrefs(): mSimpleHrAlarmThreshMin="+mSimpleHrAlarmThreshMin);
        Log.d(TAG,"updatePrefs(): mSimpleHrAlarmThreshMax="+mSimpleHrAlarmThreshMax);

        mAHistoricHrAlarmWindowDp = (int)Math.round(OsdUtil.convertTimeUnit(9, TimeUnit.HOURS,TimeUnit.SECONDS)/5.0);
        Log.d(TAG,"updatePrefs(): mAHistoricHrAlarmWindowDp="+mAHistoricHrAlarmWindowDp + " \nSetting for 9Hrs for playback");
        mAdaptiveHrAlarmActive = SP.getBoolean("HRAdaptiveAlarmActive", false);
        mAdaptiveHrAlarmWindowSecs = readDoublePref(SP, "HRAdaptiveAlarmWindowSecs", "30");
        mAdaptiveHrAlarmWindowDp = (int)Math.round(mAdaptiveHrAlarmWindowSecs/5.0);
        mAdaptiveHrAlarmThresh = readDoublePref(SP, "HRAdaptiveAlarmThresh", "20");
        Log.d(TAG,"updatePrefs(): mAdaptiveHrAlarmActive="+mAdaptiveHrAlarmActive);
        Log.d(TAG,"updatePrefs(): mAdaptiveHrWindowSecs="+mAdaptiveHrAlarmWindowSecs);
        Log.d(TAG,"updatePrefs(): mAdaptiveHrWindowDp="+mAdaptiveHrAlarmWindowDp);
        Log.d(TAG,"updatePrefs(): mAdaptiveHrAlarmThresh="+mAdaptiveHrAlarmThresh);

        mAverageHrAlarmActive = SP.getBoolean("HRAverageAlarmActive", false);
        mAverageHrAlarmWindowSecs = readDoublePref(SP, "HRAverageAlarmWindowSecs", "120");
        mAverageHrAlarmWindowDp = (int)Math.round(mAverageHrAlarmWindowSecs/5.0);
        mAverageHrAlarmThreshMin = readDoublePref(SP, "HRAverageAlarmThreshMin", "40");
        mAverageHrAlarmThreshMax = readDoublePref(SP, "HRAverageAlarmThreshMax", "120");
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmActive="+mAverageHrAlarmActive);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmWindowSecs="+mAverageHrAlarmWindowSecs);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmWindowDp="+mAverageHrAlarmWindowDp);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmThreshMin="+mAverageHrAlarmThreshMin);
        Log.d(TAG,"updatePrefs(): mAverageHrAlarmThreshMax="+mAverageHrAlarmThreshMax);

    }



    private boolean checkSimpleHr(double hrVal) {
        /**
         * Check heart rate value against simple thresholds
         */
        boolean retVal = false;
        if (mSimpleHrAlarmActive) {
            if ((hrVal > mSimpleHrAlarmThreshMax)
                    || (hrVal <mSimpleHrAlarmThreshMin)) {
                retVal = true;
            }
        }
        return(retVal);
    }


    /**
     * Returns the simple average heart rate being used by the Adaptive heart rate algorithm
     * @return simple Average Heart rate in bpm.
     */
    public double getSimpleHrAverage() {
        return OsdUtil.getAverageValueFromListOfEntry(lineDataSet);
    }
    /**
     * Returns the average heart rate being used by the Adaptive heart rate algorithm
     * @return Average Heart rate in bpm.
     */
    public double getAdaptiveHrAverage() {
        return mAdaptiveHrBuff.getAverageVal();
    }

    public void addLineDataSetAverage(Float newValue) {
        int currentLineDataSetSize =lineDataSetAverage.getYVals().size();
        lineDataSetAverage.addEntry(new Entry(newValue , currentLineDataSetSize));
        hrHistoryStringsAverage.add(Calendar.getInstance(TimeZone.getDefault()).toString());
    }

    public List<Entry> getmHistoricHrBuff() {
        return mHistoricHrBuff;
    }

    public List<Entry> getAverageHrBuff() {
        return mAverageHrBuff;
    }

    public CircBuf getAdaptiveHrBuff() {
        return mAdaptiveHrBuff;
    }

    /**
     * Returns the average heart rate being used by the Average heart rate algorithm
     * @return Average Heart rate in bpm.
     */
    public double getAverageHrAverage() {
        return OsdUtil.getAverageValueFromListOfEntry(lineDataSetAverage);
    }

    public LineDataSet getLineDataSet(boolean isAverage){
        return isAverage?lineDataSetAverage :lineDataSet;
    }

    public LineData getLineData(boolean isAverage){
        return new LineData(isAverage?hrHistoryStringsAverage:hrHistoryStrings,getLineDataSet(isAverage));
    }

    private boolean checkAdaptiveHr(double hrVal) {
        boolean retVal;
        double hrThreshMin;
        double hrThreshMax;
        double avHr = getAdaptiveHrAverage();
        hrThreshMin = avHr - mAdaptiveHrAlarmThresh;
        hrThreshMax = avHr + mAdaptiveHrAlarmThresh;

        retVal = false;
        if (hrVal < hrThreshMin) {
            retVal = true;
        }
        if (hrVal > hrThreshMax) {
            retVal = true;
        }
        Log.d(TAG, "checkAdaptiveHr() - hrVal="+hrVal+", avHr="+avHr+", thresholds=("+hrThreshMin+", "+hrThreshMax+"): Alarm="+retVal);

        return(retVal);
    }

    private boolean checkAverageHr(double hrVal) {
        boolean retVal;
        double avHr = getAverageHrAverage();

        retVal = false;
        if (avHr < mAverageHrAlarmThreshMin) {
            retVal = true;
        }
        if (avHr > mAverageHrAlarmThreshMax) {
            retVal = true;
        }
        Log.d(TAG, "checkAverageHr() - hrVal="+hrVal+", avHr="+avHr+", thresholds=("+mAverageHrAlarmThreshMin+", "+mAverageHrAlarmThreshMin+"): Alarm="+retVal);
        return(retVal);
    }



    public ArrayList<Boolean> checkHr(double hrVal) {
        /**
         * Checks the current Heart Rate reading hrVal against the
         * three possible heart rate alarm algorithms (simple, adaptive, average)
         * and returns an ArrayList of the alarm status of each algorithm in the above order.
         * true=ALARM, false=OK.
         */
        Log.v(TAG, "checkHr("+hrVal+")");
        mAdaptiveHrBuff.add(hrVal);
        int mAverageHrBuffSize = lineDataSet.getYVals().size();
        int mHistoricHrBuffSize = mHistoricHrBuff.size();
        mAverageHrBuff.add(mAverageHrBuffSize,new Entry(mAverageHrBuffSize,OsdUtil.getAverageValueFromListOfEntry(lineDataSet)));
        hrHistoryStrings.add(mHistoricHrBuffSize, Calendar.getInstance(TimeZone.getDefault()).getTime().toString());
        mHistoricHrBuff.add(new Entry(mHistoricHrBuff.size(),(int)hrVal));
        lineDataSet.addEntry(new Entry((float) hrVal,mHistoricHrBuffSize));

        ArrayList<Boolean> retVal = new ArrayList<Boolean>();
        retVal.add(checkSimpleHr(hrVal));
        retVal.add(checkAdaptiveHr(hrVal));
        retVal.add(checkAverageHr(hrVal));
        return(retVal);
    }

}
