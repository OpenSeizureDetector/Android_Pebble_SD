package uk.org.openseizuredetector.alg;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.CircBuf;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tflite.java.TfLite;

import org.tensorflow.lite.InterpreterApi;
// ExecuTorch imports (PyTorch's new mobile runtime)
import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;

public class SdAlgNn extends SdAlgBase {
    private final static String TAG = "SdAlgNn";
    private InterpreterApi interpreter;
    private MlModelManager mMm;

    private double mSdThresh;  // Acceleration Standard Deviation Threshold required to activate analysis (%)
    private int mInputFormat; // ID of input format required for model (populated from SharedPreferences)
    private int mInputSize; // Length of input vector expected by model
    private String mFramework;
    private Module mPtModule;

    // Circular buffer for accumulating data when inputSize > rawData.length
    private CircBuf mInputBuffer;

    private static float softmaxProb(float[] scores, int index) {
        // Many model exports output logits (unbounded). Convert to probability.
        // If the output already looks like probabilities (non-negative and sums to ~1),
        // return it as-is to avoid double-softmax.
        if (scores == null || scores.length < 2 || index < 0 || index >= scores.length) {
            return 0.0f;
        }
        float s0 = scores[0];
        float s1 = scores[1];
        float sum = s0 + s1;
        if (s0 >= 0.0f && s1 >= 0.0f && Math.abs(sum - 1.0f) < 0.01f) {
            return scores[index];
        }

        // Numerically-stable softmax for 2-class output.
        float max = Math.max(s0, s1);
        double e0 = Math.exp(s0 - max);
        double e1 = Math.exp(s1 - max);
        double denom = e0 + e1;
        if (denom == 0.0) {
            return 0.0f;
        }
        double p1 = e1 / denom;
        return (index == 1) ? (float) p1 : (float) (e0 / denom);
    }


    public SdAlgNn(Context context) {
        super(context);
        Log.d(TAG, "SdAlgNn Constructor");
        mMm = new MlModelManager(mContext);

        try {
            String threshStr = mSP.getString("CnnAlarmThreshold", "2");
            mSdThresh = Double.parseDouble(threshStr);
            Log.v(TAG, "SdAlgNn Constructor mSdThresh = " + mSdThresh);
            String inputFmtStr = mSP.getString("CnnInputFormatStr", null);
            int legacyInputFmt = mSP.getInt("CnnInputFormat", 1);
            mInputFormat = mapInputFormat(inputFmtStr, legacyInputFmt);
            mInputSize = mSP.getInt("CnnInputSize", 125);
            mFramework = mSP.getString("CnnFramework", "tflite");
            Log.v(TAG, "SdAlgNn Constructor inputFormat=" + mInputFormat + ", inputSize=" + mInputSize + ", framework=" + mFramework);
        } catch (Exception ex) {
            Log.v(TAG, "SdAlgNn Constructor - problem parsing preferences. " + ex.toString());
            showToast("Problem Parsing ML Algorithm Preferences", Toast.LENGTH_SHORT);
        }

        // Load model depending on selected framework
        if ("pytorch".equalsIgnoreCase(mFramework) || "executorch".equalsIgnoreCase(mFramework)) {
            mMm.loadModel(mSP, result -> {
                if (result == null) {
                    Log.e(TAG, "Failed to load any model - result is null");
                    showToast("Problem Loading Model", Toast.LENGTH_SHORT);
                    return;
                }
                mFramework = result.framework;
                if (!"pytorch".equalsIgnoreCase(result.framework) && !"executorch".equalsIgnoreCase(result.framework)) {
                    Log.w(TAG, "Expected PyTorch/ExecuTorch model but got " + result.framework + " - skipping load");
                    return;
                }
                if (result.file == null) {
                    Log.e(TAG, "ExecuTorch model file missing");
                    showToast("ExecuTorch model file not found", Toast.LENGTH_LONG);
                    return;
                }
                String filePath = result.file.getAbsolutePath();

                // Check file format - ExecuTorch REQUIRES .pte format
                if (!filePath.endsWith(".pte")) {
                    String errorMsg;
                    if (filePath.endsWith(".ptl")) {
                        errorMsg = "ExecuTorch requires .pte format. Your .ptl file must be converted. " +
                                   "Use TFLite models instead, or convert PyTorch models to .pte format.";
                        Log.e(TAG, "Cannot load .ptl file with ExecuTorch: " + filePath);
                    } else {
                        errorMsg = "ExecuTorch requires .pte (ExecuTorch Program) format files.";
                        Log.e(TAG, "Invalid file format for ExecuTorch: " + filePath);
                    }
                    showToast(errorMsg, Toast.LENGTH_LONG);
                    return;
                }

                try {
                    mPtModule = Module.load(filePath);
                    Log.d(TAG, "ExecuTorch model loaded successfully from: " + filePath);
                    //showToast("ExecuTorch model loaded successfully", Toast.LENGTH_SHORT);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load ExecuTorch model: " + e.getMessage());
                    String errorMsg = "Failed to load ExecuTorch model. ";
                    if (e.getMessage() != null && e.getMessage().contains("ET12")) {
                        errorMsg += "File is not in ExecuTorch .pte format. Use TFLite models instead.";
                    } else if (e.getMessage() != null && e.getMessage().contains("Invalid argument")) {
                        errorMsg += "Model file is corrupted or wrong format. Expected .pte file.";
                    } else {
                        errorMsg += e.getMessage();
                    }
                    showToast(errorMsg, Toast.LENGTH_LONG);
                }
            });
        } else {
            Task<Void> initializeTask = TfLite.initialize(mContext);

            initializeTask.addOnSuccessListener(a -> {
                        Log.d(TAG, "TfLite initialized, loading model...");
                        // Use MlModelManager to load the model (handles both downloaded and bundled models)
                        mMm.loadModel(mSP, result -> {
                            if (result == null) {
                                Log.e(TAG, "Failed to load any model - result is null");
                                return;
                            }
                            mFramework = result.framework;
                            if ("pytorch".equalsIgnoreCase(result.framework) || "executorch".equalsIgnoreCase(result.framework)) {
                                Log.w(TAG, "Loaded PyTorch/ExecuTorch model while expecting TFLite - not creating TFLite interpreter");
                                return;
                            }
                            if (result.tfliteBuffer == null) {
                                Log.e(TAG, "Failed to load TfLite model buffer");
                                return;
                            }
                            Log.d(TAG, "creating interpreter");
                            interpreter = InterpreterApi.create(result.tfliteBuffer,
                                    new InterpreterApi.Options().setRuntime(
                                            InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY));
                            Log.d(TAG, "interpreter created ok");
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, String.format("Cannot initialize interpreter: %s",
                                e.getMessage()));
                    });
        }
        // FIXME - Add hardware acceleration - see https://www.tensorflow.org/lite/android/play_services
        Log.d(TAG, "constructor finished - Note, NOT using hardware acceleration yet!!!!");
    }

    @Override
    public boolean isActive() {
        return mSP.getBoolean("CnnAlgActive", false);
    }

    @Override
    public String getAlarmCause() {
        return "CnnAlg";
    }

    @Override
    public int processSdData(SdData sdData) {
        if (!isActive()) {
            return -1;
        }

        try {
            float pSeizure = getPseizure(sdData);
            Log.d(TAG, "processSdData() - nnResult=" + pSeizure);
            sdData.mPseizure = pSeizure;

            if (pSeizure > 0.5) {
                return 2;  // ALARM
            }
        } catch (Exception e) {
            Log.e(TAG, "processSdData() - Error running Analysis - " + e.getMessage());
        }

        return 0;  // OK
    }

    public void close() {
        Log.d(TAG, "close()");
        super.close();

        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }

        if (mPtModule != null) {
            try {
                mPtModule.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error destroying ExecuTorch module: " + e.getMessage());
            }
            mPtModule = null;
        }

        // Close MlModelManager if it exists
        if (mMm != null) {
            mMm.close();
            mMm = null;
        }
    }

    /**
     * getPseizureFmt1 - calculate probability of sdData representing seizure-like movement
     * using a model with input format #1, which is a simple vector of accelerometer vector
     * magnitude readings. Always uses a circular buffer to accumulate data to the required inputSize.
     *
     * @param sdData - seizure detector data as input to the model
     * @return probability of data representing seizure-like movement (0 if buffer not yet full).
     */
    private float getPseizureFmt1(SdData sdData) {
        // Always use circular buffer to accumulate data
        if (mInputBuffer == null) {
            mInputBuffer = new CircBuf(mInputSize, Double.NaN);
        }

        // Add each sample from rawData to the circular buffer, converting from milli-g to g units
        for (int i = 0; i < sdData.rawData.length; i++) {
            mInputBuffer.add(sdData.rawData[i] / 1000.);
        }

        if (mInputBuffer.getNumVals() < mInputSize) {
            Log.d(TAG, "getPseizureFmt1() - buffer not full: " + mInputBuffer.getNumVals() + "/" + mInputSize);
            return 0.0f;
        }

        double[] inputData = mInputBuffer.getVals();

        // Now invoke the appropriate framework with the prepared input data
        if ("pytorch".equalsIgnoreCase(mFramework) || "executorch".equalsIgnoreCase(mFramework)) {
            if (mPtModule == null) {
                Log.d(TAG, "getPseizureFmt1() - ExecuTorch module is null - returning zero seizure probability");
                return 0.0f;
            }

            float[] inputVec = new float[mInputSize];
            for (int i = 0; i < mInputSize; i++) {
                inputVec[i] = (float) inputData[i];
            }
            // Create tensor with shape [batch=1, channels=1, sequence_length=mInputSize]
            // The model code does torch.unsqueeze(x, 2) which adds a dimension at position 2,
            // so it expects input shape [batch, channels, sequence_length]
            Tensor inputTensor = Tensor.fromBlob(inputVec, new long[]{1, 1, mInputSize});
            // ExecuTorch forward() returns EValue[] array, get first element
            EValue[] outputs = mPtModule.forward(EValue.from(inputTensor));
            if (outputs.length == 0) {
                Log.e(TAG, "ExecuTorch returned empty output array");
                return 0.0f;
            }
            float[] scores = outputs[0].toTensor().getDataAsFloatArray();
            if (scores.length >= 2) {
                float pSeizure = softmaxProb(scores, 1);
                Log.d(TAG, "ExecuTorch run - pSeizure=" + pSeizure + " (raw[0]=" + scores[0] + ", raw[1]=" + scores[1] + ")");
                return pSeizure;
            }
            Log.e(TAG, "ExecuTorch output size unexpected: " + scores.length);
            return 0.0f;
        }

        // TFLite path
        float[][][] modelInput = new float[1][mInputSize][1];
        float[][] modelOutput = new float[1][2];
        for (int j = 0; j < mInputSize; j++) {
            modelInput[0][j][0] = (float) inputData[j];
        }
        if (interpreter == null) {
            Log.d(TAG, "getPSeizure() - interpreter is null - returning zero seizure probability");
            return 0.0f;
        }
        interpreter.run(modelInput, modelOutput);
        Log.d(TAG, "run - pSeizure=" + modelOutput[0][1]);
        return modelOutput[0][1];
    }

    public float getPseizure(SdData sdData) {
        int i;

        float pSeizure;
        switch (mInputFormat) {
            case 1:
                pSeizure = getPseizureFmt1(sdData);
                break;
            default:
                Log.e(TAG, "getPseizure - unsupported input format " + mInputFormat);
                pSeizure = 0;
        }

        // Now check that we have enough movement to justify this being a seizure by comparing the acceleration standard deviation to a threshold.
        double stdDev;
        stdDev = calcRawDataStd(sdData);
        if (stdDev < mSdThresh) {
            Log.d(TAG, "getPseizure - acceleration stdev below movement threshold: std=" + stdDev + ", thresh=" + mSdThresh);
            return (0);
        }
        return (pSeizure);
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

    private int mapInputFormat(String fmtStr, int legacyDefault) {
        if (fmtStr == null || fmtStr.isEmpty()) {
            return legacyDefault;
        }
        // Map known string identifiers to existing numeric formats to preserve behaviour
        if (fmtStr.equalsIgnoreCase("1d_mag")) {
            return 1;
        }
        // Unknown format - fall back to legacy default
        return legacyDefault;
    }
}
