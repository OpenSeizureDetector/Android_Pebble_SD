package uk.org.openseizuredetector;

import android.util.Log;

import java.util.ArrayList;

public class CircBuf {
    /*
     * A circular buffer used to calculate rolling averages
     * Based loosely on https://gist.github.com/hardik-vala/dc2d19fa7c5108536fbbff96b4fcf105
     */
    private final static String TAG = "CircBuf";

    private double[] mBuff;
    private double mErrVal;
    private int mBuffLen;
    private int mHead;
    private int mTail;
    private boolean mIsFull;

    public CircBuf(int n, double errVal) {
        /**
         * Create a circular buffer of doubles, of length n members.
         */
        Log.i(TAG, "CircBuf Constructor");
        mBuff = new double[n];
        mBuffLen = n;
        mErrVal = errVal;
        mHead = 0;
        mTail = 0;
        mIsFull = false;
    }

    public void add(double val) {
        /**
         * Add value val to the circular buffer.
         */
        Log.d(TAG, "add() - before: mHead=" + mHead + ", mTail=" + mTail);
        //System.out.println(TAG+" add() - before: mHead="+mHead+", mTail="+mTail);
        if (mIsFull)
            mHead = (mHead + 1) % mBuffLen;

        mBuff[mTail] = val;
        mTail = (mTail + 1) % mBuffLen;
        if (mTail == mHead)
            mIsFull = true;
        Log.d(TAG, "add() -  after: mHead=" + mHead + ", mTail=" + mTail);
        //System.out.println(TAG+" add() - before: mHead="+mHead+", mTail="+mTail);
    }

    public int getNumVals() {
        /**
         * Returns the total count of values stored in the buffer (including error values).
         */
        int numElements;
        if (mIsFull) {
            numElements = mBuffLen;
        } else {
            // Not sure if this is necessary - why would mHead be greater than mTail if the buffer is not full?
            if (mHead > mTail) {
                numElements = (mTail + mBuffLen) - mHead;
            } else {
                numElements = mTail - mHead;
            }
        }
        return numElements;
    }

    /**
     * Returns a double array of buffer items in order of their insertion time
     *
     * @return double[] of buffer items
     */
    public double[] getVals() {
        int numElements = getNumVals();
        System.out.println(TAG + " getVals() - numElements=" + numElements);
        double[] retArr = new double[numElements];
        for (int i = 0; i < numElements; i++) {
            retArr[i] = mBuff[(mHead + i) % mBuffLen];
        }
        return retArr;
    }

    public double getAverageVal() {
        /**
         * Returns the average of the values stored in the buffer, which do not equal the error value mErrVal.
         * Error values are ignored.
         */
        double hrSum = 0.;
        int hrCount = 0;
        double valArr[] = getVals();
        double retVal;
        for (int n = 0; n < valArr.length; n++) {
            if (valArr[n] != mErrVal) {
                hrSum += valArr[n];
                hrCount++;
            }
        }
        if (hrCount > 0) {
            retVal = hrSum / hrCount;
        } else {
            retVal = -1;
        }
        return (retVal);
    }

}