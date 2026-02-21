package uk.org.openseizuredetector.alg;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tflite.java.TfLite;

import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;
import org.tensorflow.lite.InterpreterApi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.CircBuf;
import uk.org.openseizuredetector.data.AlarmState;

/**
 * SdAlgMl - Multi-model ML algorithm that coordinates the evaluation of all installed ML models.
 * Replaces the single-model SdAlgNn.
 */
public class SdAlgMl extends SdAlgBase {
    private final static String TAG = "SdAlgMl";
    private MlModelManager mMm;

    private static class ModelInstance {
        String label; // Short label like ML1, ML2
        String name;  // Full descriptive name
        String framework;
        InterpreterApi tfliteInterpreter;
        Module pytorchModule;
        int inputFormat;
        int inputSize;
        double sdThresh;
        float weight = 1.0f;
        boolean isEnabled = true;
        CircBuf inputBuffer;

        public void close() {
            Log.i(TAG,"ModelInstance.close() - closing model");
            if (tfliteInterpreter != null) {
                tfliteInterpreter.close();
                tfliteInterpreter = null;
            }
            if (pytorchModule != null) {
                Log.i(TAG,"ModelInstance.close() - destroying PyTorch module");
                try {
                    pytorchModule.destroy();
                } catch (Exception e) {
                    Log.w(TAG, "Error destroying PyTorch module: " + e.getMessage());
                }
                pytorchModule = null;
            }
        }
    }

    private final List<ModelInstance> mModels = new ArrayList<>();

    public SdAlgMl(Context context) {
        super(context);
        Log.d(TAG, "SdAlgMl Constructor");
        mMm = new MlModelManager(mContext);

        // Initialize frameworks and load all installed models
        initializeAndLoadModels();
    }

    private void initializeAndLoadModels() {
        // Initialize TfLite (GMS Core version)
        Task<Void> initializeTask = TfLite.initialize(mContext);
        initializeTask.addOnSuccessListener(a -> {
            Log.d(TAG, "TfLite initialized successfully");
            loadAllActiveModels();
        }).addOnFailureListener(e -> {
            Log.e(TAG, "TfLite initialization failed: " + e.getMessage());
            // Attempt to load anyway (PyTorch models might still work)
            loadAllActiveModels();
        });
    }

    private void loadAllActiveModels() {
        // Load all active models from the manager
        List<MlModelManager.ModelLoadResult> results = mMm.loadAllActiveModels();
        Log.i(TAG, "loadAllActiveModels(): Found " + results.size() + " model(s)");

        for (int i = 0; i < results.size(); i++) {
            MlModelManager.ModelLoadResult res = results.get(i);
            try {
                ModelInstance model = new ModelInstance();
                model.label = "ML" + (i + 1);
                model.name = res.name;
                model.framework = res.framework;
                model.inputFormat = res.inputFormat;
                model.inputSize = res.inputSize;
                model.sdThresh = res.alarmThreshold;

                if ("tflite".equalsIgnoreCase(res.framework)) {
                    if (res.tfliteBuffer != null) {
                        model.tfliteInterpreter = InterpreterApi.create(res.tfliteBuffer,
                                new InterpreterApi.Options().setRuntime(
                                        InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY));
                        mModels.add(model);
                        Log.d(TAG, "Loaded TFLite model " + model.label + ": " + model.name);
                    } else {
                        Log.e(TAG, "Failed to load TFLite model " + res.name + " - buffer is null");
                    }
                } else if ("pytorch".equalsIgnoreCase(res.framework) || "executorch".equalsIgnoreCase(res.framework)) {
                    if (res.file != null && res.file.exists()) {
                        model.pytorchModule = Module.load(res.file.getAbsolutePath());
                        mModels.add(model);
                        Log.d(TAG, "Loaded PyTorch/ExecuTorch model " + model.label + ": " + model.name);
                    } else {
                        Log.e(TAG, "Failed to load PyTorch model " + res.name + " - file not found");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading model " + res.name + ": " + e.getMessage());
            }
        }
        
        if (mModels.isEmpty()) {
            Log.w(TAG, "No ML models were successfully loaded.");
        }
    }

    @Override
    public String getAlarmCause() {
        return "MlAlg";
    }

    /**
     * Evaluate all loaded models and return individual results for voting.
     */
    public List<AlgorithmResult> evaluateAllModels(SdData sdData) {
        List<AlgorithmResult> results = new ArrayList<>();

        for (ModelInstance model : mModels) {
            if (!model.isEnabled) continue;

            try {
                float pSeizure = evaluateModel(model, sdData);
                Log.v(TAG, "evaluateAllModels(): Model " + model.label + " pSeizure=" + pSeizure);

                // Use 0.5 as a standard probability threshold for alarm
                int alarmState = (pSeizure > 0.5) ? AlarmState.ALARM : AlarmState.OK;

                results.add(new AlgorithmResult(
                        model.label, // Use short label for voting results
                        alarmState,
                        pSeizure,
                        model.weight
                ));
            } catch (Exception e) {
                Log.e(TAG, "Error evaluating model " + model.label + ": " + e.getMessage());
            }
        }

        return results;
    }

    @Override
    public int processSdData(SdData sdData) {
        List<AlgorithmResult> results = evaluateAllModels(sdData);

        if (results.isEmpty()) {
            sdData.mPseizure = 0.0f;
            return 0;
        }

        boolean anyAlarm = false;
        float maxProbability = 0.0f;

        for (AlgorithmResult result : results) {
            if (result.alarmState == AlarmState.ALARM) {
                anyAlarm = true;
            }
            maxProbability = Math.max(maxProbability, result.confidence);
        }

        sdData.mPseizure = maxProbability;
        return anyAlarm ? AlarmState.ALARM : AlarmState.OK;
    }

    private float evaluateModel(ModelInstance model, SdData sdData) {
        // Only format 1 ("1d_mag") is currently supported
        if (model.inputFormat == 1) {
            return evaluateModelFmt1(model, sdData);
        } else {
            Log.e(TAG, "evaluateModel(): Unsupported input format " + model.inputFormat);
            return 0.0f;
        }
    }

    private float evaluateModelFmt1(ModelInstance model, SdData sdData) {
        if (model.inputBuffer == null) {
            model.inputBuffer = new CircBuf(model.inputSize, Double.NaN);
        }

        // Add each sample from rawData to the circular buffer
        for (int i = 0; i < sdData.rawData.length; i++) {
            model.inputBuffer.add(sdData.rawData[i] / 1000.0);
        }

        if (model.inputBuffer.getNumVals() < model.inputSize) {
            return 0.0f;
        }

        double[] inputData = model.inputBuffer.getVals();

        // Check movement threshold (Movement Standard Deviation)
        double stdDev = calcRawDataStd(sdData);
        if (stdDev < model.sdThresh) {
            return 0.0f;
        }

        if ("pytorch".equalsIgnoreCase(model.framework) || "executorch".equalsIgnoreCase(model.framework)) {
            return evaluatePyTorchModel(model, inputData);
        } else {
            return evaluateTfLiteModel(model, inputData);
        }
    }

    private float evaluatePyTorchModel(ModelInstance model, double[] inputData) {
        if (model.pytorchModule == null) return 0.0f;

        try {
            float[] inputVec = new float[model.inputSize];
            for (int i = 0; i < model.inputSize; i++) {
                inputVec[i] = (float) inputData[i];
            }

            Tensor inputTensor = Tensor.fromBlob(inputVec, new long[]{1, 1, model.inputSize});
            EValue[] outputs = model.pytorchModule.forward(EValue.from(inputTensor));

            if (outputs.length == 0) return 0.0f;

            float[] scores = outputs[0].toTensor().getDataAsFloatArray();
            if (scores.length >= 2) {
                return softmaxProb(scores, 1);
            }
            return 0.0f;
        } catch (Exception e) {
            Log.e(TAG, "evaluatePyTorchModel error: " + e.getMessage());
            return 0.0f;
        }
    }

    private float evaluateTfLiteModel(ModelInstance model, double[] inputData) {
        if (model.tfliteInterpreter == null) return 0.0f;

        try {
            float[][][] modelInput = new float[1][model.inputSize][1];
            float[][] modelOutput = new float[1][2];

            for (int j = 0; j < model.inputSize; j++) {
                modelInput[0][j][0] = (float) inputData[j];
            }

            model.tfliteInterpreter.run(modelInput, modelOutput);
            return modelOutput[0][1];
        } catch (Exception e) {
            Log.e(TAG, "evaluateTfLiteModel error: " + e.getMessage());
            return 0.0f;
        }
    }

    private static float softmaxProb(float[] scores, int index) {
        if (scores == null || scores.length < 2) return 0.0f;
        float s0 = scores[0];
        float s1 = scores[1];
        float max = Math.max(s0, s1);
        double e0 = Math.exp(s0 - max);
        double e1 = Math.exp(s1 - max);
        double denom = e0 + e1;
        if (denom == 0.0) return 0.0f;
        return (index == 1) ? (float) (e1 / denom) : (float) (e0 / denom);
    }

    private double calcRawDataStd(SdData sdData) {
        int length = Math.min(sdData.rawData.length, 125);
        double sum = 0.0;
        for (int j = 0; j < length; j++) sum += sdData.rawData[j];
        double mean = sum / length;
        double standardDeviation = 0.0;
        for (int j = 0; j < length; j++) standardDeviation += Math.pow(sdData.rawData[j] - mean, 2);
        return 100.0 * Math.sqrt(standardDeviation / length) / mean;
    }

    @Override
    public void close() {
        Log.d(TAG, "close()");
        super.close();
        for (ModelInstance model : mModels) {
            model.close();
        }
        mModels.clear();
        if (mMm != null) {
            mMm.close();
            mMm = null;
        }
    }
}
