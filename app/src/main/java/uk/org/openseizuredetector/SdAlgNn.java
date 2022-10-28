package uk.org.openseizuredetector;

import android.content.Context;
import android.util.Log;

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
    RequestQueue mQueue;


    public SdAlgNn(Context context) {
        Log.d(TAG, "SdAlgNn Constructor");
        mContext = context;
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
        Log.d(TAG,"close()");
        interpreter.close();
    }

    public float getPseizure(SdData sdData) {
        int i;
        float[][][] modelInput = new float[1][125][1];
        float[][] modelOutput = new float[1][2];
        for (int j = 0; j < 125; j++) {
            modelInput[0][j][0] = (float)sdData.rawData[j];
        }
        interpreter.run(modelInput, modelOutput);
        Log.d(TAG,"run - pSeizure="+modelOutput[0][1]);
        return(modelOutput[0][1]);
    }
}
