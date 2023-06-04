package uk.org.openseizuredetector;

import junit.framework.TestCase;

public class CircBufTest extends TestCase {
    private CircBuf mCb;

    public void setUp() throws Exception {
        super.setUp();
        mCb = new CircBuf(10, -1);
    }

    public void tearDown() throws Exception {
    }

    public void printArr(double[] arr) {
        for (int n=0; n<arr.length; n++) {
            System.out.println("arr["+n+"] = "+arr[n]);
        }
    }

    public void testAdd() {
        double[] retArr;

        // Add a single value = we should get a single element array back.
        mCb.add(1);
        retArr = mCb.getVals();
        assertEquals(1,retArr.length);

        // Add 9 more elements - we should get a 10 element array back.
        for (int i=0; i<9;i++) {
            mCb.add(i);
        }
        retArr = mCb.getVals();
        assertEquals(10,retArr.length);


    }

    public void testGetVals() {
        double[] retArr;
        // Add 10 more elements - we should get a 10 element array back.
        for (int i=0; i<10;i++) {
            mCb.add(i);
        }
        retArr = mCb.getVals();
        assertEquals(0.0, retArr[0]);
        assertEquals(9.0, retArr[9]);

        //add one more element
        mCb.add(10.0);
        retArr = mCb.getVals();
        printArr(retArr);
        assertEquals(1.0, retArr[0]);
        assertEquals(10.0, retArr[9]);

        //add one more element
        mCb.add(10.0);
        retArr = mCb.getVals();
        printArr(retArr);
        assertEquals(2.0, retArr[0]);
        assertEquals(10.0, retArr[9]);

    }

    public void testGetAverageVal() {
        double[] retArr;
        double avVal;

        // empty array;
        avVal = mCb.getAverageVal();
        assertEquals(-1.0, avVal, 1e-5);



        // A trivial example
        for (int i=0; i<10;i++) {
            mCb.add(1.0);
        }
        retArr = mCb.getVals();
        avVal = mCb.getAverageVal();
        assertEquals(1.0, avVal,1e-5);

        // Real calculation
        double sum = 0.0;
        for (int i=0; i<10;i++) {
            mCb.add(i);
            sum += i;
        }
        avVal = mCb.getAverageVal();
        assertEquals(sum/10, avVal, 1e-5);

        // Error value in array - should now be average of 9 values between 1 and 9 (because zero value is removed when -1 is added to end).
        sum = 0.0;
        for(int i=1;i<10;i++)
            sum+= i;

        mCb.add(-1.0);
        avVal = mCb.getAverageVal();
        assertEquals(sum/9, avVal, 1e-5);

    }
}