package uk.org.openseizuredetector;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tflite.java.TfLite;
//import com.google.android.gms.tflite.java.TfLite;

import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.InterpreterApi;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class SdAlgNn {
    private final static String TAG = "SdAlgNn";
    private final static String MODEL_PATH = "cnn_v0.24.tflite";
    private String mUrlBase = "https://osdApi.ddns.net";
    private InterpreterApi interpreter;
    private Context mContext;
    private MlModelManager mMm;
    RequestQueue mQueue;

    private double mSdThresh;  // Acceleration Standard Deviation Threshold required to activate analysis (%)
    private int mModelId;   // ID of ML Model to be used (refers to information in MlModels.json for details).
    private int mInputFormat; // ID of input format required for model (populated from MlModels.json).


    public SdAlgNn(Context context) {
        Log.d(TAG, "SdAlgNn Constructor");
        mContext = context;
        mMm = new MlModelManager(mContext);

        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        try {
            String threshStr = SP.getString("CnnAlarmThreshold", "5");
            mSdThresh = Double.parseDouble(threshStr);
            Log.v(TAG, "SdAlgNn Constructor mSdThresh = " + mSdThresh);
            threshStr = SP.getString("CnnModelId", "1");
            mModelId = Integer.parseInt(threshStr);
            Log.v(TAG, "SdAlgNn Constructor mModelId = " + mModelId);
        } catch (Exception ex) {
            Log.v(TAG, "SdAlgNn Constructor - problem parsing preferences. " + ex.toString(), ex);
            Toast toast = Toast.makeText(mContext, "Problem Parsing ML Algorithm Preferences", Toast.LENGTH_SHORT);
            toast.show();
        }

        mInputFormat = 1; // FIXME - this needs to be determined from the model ID specified by retrieving a configuration file.

        Task<Void> initializeTask = TfLite.initialize(mContext);

        initializeTask.addOnSuccessListener(a -> {
                    MappedByteBuffer modelBuffer;
                    try {
                        Log.d(TAG, "onSuccessListener - loading model");
                        modelBuffer = FileUtil.loadMappedFile(context, MODEL_PATH);
                        Log.d(TAG, "onSuccessListener - model loaded");
                    } catch (IOException e) {
                        Log.e(TAG, "Error Loading Model File");
                        return;
                    }
                    Log.d(TAG, "onSuccessListener - creating interpreter");
                    interpreter = InterpreterApi.create(modelBuffer,
                            new InterpreterApi.Options().setRuntime(
                                    InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY));
                    Log.d(TAG, "onSuccessListener - interpreter created ok");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, String.format("Cannot initialize interpreter: %s",
                            e.getMessage()));
                });
        // FIXME - Add hardware acceleration - see https://www.tensorflow.org/lite/android/play_services
        Log.d(TAG, "constructor finished - Note, NOT using hardware acceleration yet!!!!");
    }

    public void close() {
        Log.d(TAG, "close()");
        if (interpreter != null) {
            interpreter.close();
        }
    }

    /**
     * getPseizureFmt1 - calculate probability of sdData representing seizure-like movement
     * using a model with input format #1, which is a simple vector of 125 accelerometer vector
     * magnitude readings.
     * @param sdData - seizure detector data as input to the model
     * @return probability of data representing seizure-like movement.
     */
    private float getPseizureFmt1(SdData sdData) {
        int i;
        float[][][] modelInput = new float[1][125][1];
        float[][] modelOutput = new float[1][2];
        for (int j = 0; j < 125; j++) {
            modelInput[0][j][0] = (float) sdData.rawData[j];
        }
        if (interpreter == null) {
            Log.d(TAG, "getPSeizure() - interpreter is null - returning zero seizure probability");
            return (0.0f);
        }
        interpreter.run(modelInput, modelOutput);
        Log.d(TAG, "run - pSeizure=" + modelOutput[0][1]);
        return (modelOutput[0][1]);
    }

    public float getPseizure(SdData sdData) {
        int i;

        // First check that we have enough movement to analyse by comparing the acceleration standard deviation to a threshold.
        double stdDev;
        stdDev = calcRawDataStd(sdData);
        if (stdDev < mSdThresh) {
            Log.d(TAG, "getPseizure - acceleration stdev below movement threshold: std="+stdDev+", thresh="+mSdThresh);
            return (0);
        }

        float pSeizure;
        switch (mModelId) {
            case 1:
                pSeizure = getPseizureFmt1(sdData);
                break;
            default:
                Log.e(TAG,"getPSeizure - invalid model ID "+mModelId);
                pSeizure = 0;
        }

        return(pSeizure);
    }

    private double calcRawDataStd(SdData sdData) {
        /**
         * Calculate the standard deviation in % of the rawData array in the SdData instance provided.
         * It assumes that rawdata will contain 125 samples.
         * Returns the standard deviation in %.
         */
        // FIXME - assumes length of rawdata array is 125 data points
        int j;
        double sum = 0.0;
        for (j = 0; j < 125; j++) { // FIXME - assumed length!
            sum += sdData.rawData[j];
        }
        double mean = sum / 125;

        double standardDeviation = 0.0;
        for (j = 0; j < 125; j++) { // FIXME - assumed length!
            standardDeviation += Math.pow(sdData.rawData[j] - mean, 2);
        }
        standardDeviation = Math.sqrt(standardDeviation / 125);  // FIXME - assumed length!

        // Convert standard deviation from milli-g to %
        standardDeviation = 100. * standardDeviation / mean;
        return (standardDeviation);
    }
}
