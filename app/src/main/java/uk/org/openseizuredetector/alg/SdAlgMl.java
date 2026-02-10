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

/**
 * SdAlgMl - Multi-model ML algorithm that can manage and evaluate multiple ML models.
 * Replaces SdAlgNn for multi-model support.
 */
public class SdAlgMl extends SdAlgBase {
    private final static String TAG = "SdAlgMl";
    private MlModelManager mMm;

    // Individual model instance
    private static class ModelInstance {
        String name;
        String framework;
        InterpreterApi tfliteInterpreter;
        Module pytorchModule;
        int inputFormat;
        int inputSize;
        double sdThresh;
        float weight;
        boolean isEnabled;
        CircBuf inputBuffer;

        public void close() {
            if (tfliteInterpreter != null) {
                tfliteInterpreter.close();
                tfliteInterpreter = null;
            }
            if (pytorchModule != null) {
                try {
                    pytorchModule.destroy();
                } catch (Exception e) {
                    Log.w(TAG, "Error destroying PyTorch module: " + e.getMessage());
                }
                pytorchModule = null;
            }
        }
    }

    private List<ModelInstance> mModels = new ArrayList<>();
    private int mNumModels = 1; // Default to 1 model

    public SdAlgMl(Context context) {
        super(context);
        Log.d(TAG, "SdAlgMl Constructor");
        mMm = new MlModelManager(mContext);

        // Load number of models to use
        mNumModels = mSP.getInt("MlNumModels", 1);
        if (mNumModels < 1) mNumModels = 1;
        if (mNumModels > MlModelManager.MAX_MODELS) mNumModels = MlModelManager.MAX_MODELS;

        Log.d(TAG, "SdAlgMl: Loading " + mNumModels + " model(s)");

        // Load each model
        for (int i = 0; i < mNumModels; i++) {
            loadModel(i);
        }

        Log.d(TAG, "SdAlgMl Constructor finished - " + mModels.size() + " model(s) loaded");
    }

    private void loadModel(int modelIndex) {
        String prefix = "MlModel" + (modelIndex + 1);

        // Check if this model slot is enabled
        boolean isEnabled = mSP.getBoolean(prefix + "Enabled", modelIndex == 0); // First model enabled by default
        if (!isEnabled) {
            Log.d(TAG, "loadModel(): Model " + (modelIndex + 1) + " is disabled, skipping");
            return;
        }

        ModelInstance model = new ModelInstance();
        model.isEnabled = true;
        model.name = mSP.getString(prefix + "Name", "Model " + (modelIndex + 1));
        model.framework = mSP.getString(prefix + "Framework", "tflite");
        model.weight = mSP.getFloat(prefix + "Weight", 1.0f);

        try {
            String threshStr = mSP.getString(prefix + "AlarmThreshold", "2");
            model.sdThresh = Double.parseDouble(threshStr);

            String inputFmtStr = mSP.getString(prefix + "InputFormatStr", null);
            int legacyInputFmt = mSP.getInt(prefix + "InputFormat", 1);
            model.inputFormat = mapInputFormat(inputFmtStr, legacyInputFmt);
            model.inputSize = mSP.getInt(prefix + "InputSize", 125);

            Log.v(TAG, "loadModel(" + modelIndex + "): name=" + model.name +
                       ", framework=" + model.framework +
                       ", inputFormat=" + model.inputFormat +
                       ", inputSize=" + model.inputSize +
                       ", weight=" + model.weight);
        } catch (Exception ex) {
            Log.e(TAG, "loadModel(" + modelIndex + "): Error parsing preferences - " + ex.toString());
            showToast("Problem loading ML Model " + (modelIndex + 1), Toast.LENGTH_SHORT);
            return;
        }

        // Get model file path
        String modelFilePath = mSP.getString(prefix + "File", null);

        // Load the model
        if ("pytorch".equalsIgnoreCase(model.framework) || "executorch".equalsIgnoreCase(model.framework)) {
            loadPyTorchModel(model, modelFilePath, modelIndex);
        } else {
            loadTfLiteModel(model, modelFilePath, modelIndex);
        }
    }

    private void loadPyTorchModel(ModelInstance model, String modelFilePath, int modelIndex) {
        if (modelFilePath == null) {
            Log.w(TAG, "loadPyTorchModel(" + modelIndex + "): No model file path specified");
            return;
        }

        File modelFile = new File(modelFilePath);
        if (!modelFile.exists()) {
            Log.w(TAG, "loadPyTorchModel(" + modelIndex + "): Model file not found: " + modelFilePath);
            return;
        }

        if (!modelFilePath.endsWith(".pte")) {
            Log.e(TAG, "loadPyTorchModel(" + modelIndex + "): ExecuTorch requires .pte format: " + modelFilePath);
            showToast("Model " + (modelIndex + 1) + " requires .pte format", Toast.LENGTH_LONG);
            return;
        }

        try {
            model.pytorchModule = Module.load(modelFilePath);
            mModels.add(model);
            Log.d(TAG, "loadPyTorchModel(" + modelIndex + "): ExecuTorch model loaded: " + model.name);
        } catch (Exception e) {
            Log.e(TAG, "loadPyTorchModel(" + modelIndex + "): Failed to load - " + e.getMessage());
            showToast("Failed to load Model " + (modelIndex + 1), Toast.LENGTH_SHORT);
        }
    }

    private void loadTfLiteModel(ModelInstance model, String modelFilePath, int modelIndex) {
        Task<Void> initializeTask = TfLite.initialize(mContext);

        initializeTask.addOnSuccessListener(a -> {
            Log.d(TAG, "loadTfLiteModel(" + modelIndex + "): TfLite initialized");

            // Use MlModelManager to load the model
            mMm.loadModel(mSP, result -> {
                if (result == null) {
                    Log.e(TAG, "loadTfLiteModel(" + modelIndex + "): Failed to load - result is null");
                    return;
                }

                if (!"tflite".equalsIgnoreCase(result.framework)) {
                    Log.w(TAG, "loadTfLiteModel(" + modelIndex + "): Expected TFLite but got " + result.framework);
                    return;
                }

                if (result.tfliteBuffer == null) {
                    Log.e(TAG, "loadTfLiteModel(" + modelIndex + "): TfLite buffer is null");
                    return;
                }

                try {
                    model.tfliteInterpreter = InterpreterApi.create(result.tfliteBuffer,
                            new InterpreterApi.Options().setRuntime(
                                    InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY));
                    mModels.add(model);
                    Log.d(TAG, "loadTfLiteModel(" + modelIndex + "): TFLite interpreter created: " + model.name);
                } catch (Exception e) {
                    Log.e(TAG, "loadTfLiteModel(" + modelIndex + "): Failed to create interpreter - " + e.getMessage());
                }
            });
        }).addOnFailureListener(e -> {
            Log.e(TAG, "loadTfLiteModel(" + modelIndex + "): TfLite initialization failed - " + e.getMessage());
        });
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

        for (int i = 0; i < mModels.size(); i++) {
            ModelInstance model = mModels.get(i);
            if (!model.isEnabled) continue;

            try {
                float pSeizure = evaluateModel(model, sdData);
                Log.v(TAG, "evaluateAllModels(): Model " + model.name + " pSeizure=" + pSeizure);

                // Convert probability to alarm state
                int alarmState = (pSeizure > 0.5) ? 2 : 0;

                results.add(new AlgorithmResult(
                        model.name,
                        alarmState,
                        pSeizure,  // confidence is the probability
                        model.weight
                ));
            } catch (Exception e) {
                Log.e(TAG, "evaluateAllModels(): Error evaluating model " + model.name + " - " + e.getMessage());
            }
        }

        return results;
    }

    @Override
    public int processSdData(SdData sdData) {
        // Get results from all models
        List<AlgorithmResult> results = evaluateAllModels(sdData);

        if (results.isEmpty()) {
            Log.w(TAG, "processSdData(): No model results available");
            return 0;
        }

        // For single model or default behavior, just check if any model reports alarm
        boolean anyAlarm = false;
        float maxProbability = 0.0f;

        for (AlgorithmResult result : results) {
            if (result.alarmState == 2) {
                anyAlarm = true;
                maxProbability = Math.max(maxProbability, result.confidence);
            }
        }

        sdData.mPseizure = maxProbability;

        return anyAlarm ? 2 : 0;
    }

    private float evaluateModel(ModelInstance model, SdData sdData) {
        switch (model.inputFormat) {
            case 1:
                return evaluateModelFmt1(model, sdData);
            default:
                Log.e(TAG, "evaluateModel(): Unsupported input format " + model.inputFormat);
                return 0.0f;
        }
    }

    private float evaluateModelFmt1(ModelInstance model, SdData sdData) {
        // Always use circular buffer to accumulate data
        if (model.inputBuffer == null) {
            model.inputBuffer = new CircBuf(model.inputSize, Double.NaN);
        }

        // Add each sample from rawData to the circular buffer
        for (int i = 0; i < sdData.rawData.length; i++) {
            model.inputBuffer.add(sdData.rawData[i] / 1000.0);
        }

        if (model.inputBuffer.getNumVals() < model.inputSize) {
            Log.v(TAG, "evaluateModelFmt1(): Buffer not full: " +
                       model.inputBuffer.getNumVals() + "/" + model.inputSize);
            return 0.0f;
        }

        double[] inputData = model.inputBuffer.getVals();

        // Check movement threshold
        double stdDev = calcRawDataStd(sdData);
        if (stdDev < model.sdThresh) {
            Log.v(TAG, "evaluateModelFmt1(): Movement below threshold: std=" +
                       stdDev + ", thresh=" + model.sdThresh);
            return 0.0f;
        }

        // Invoke appropriate framework
        if ("pytorch".equalsIgnoreCase(model.framework) || "executorch".equalsIgnoreCase(model.framework)) {
            return evaluatePyTorchModel(model, inputData);
        } else {
            return evaluateTfLiteModel(model, inputData);
        }
    }

    private float evaluatePyTorchModel(ModelInstance model, double[] inputData) {
        if (model.pytorchModule == null) {
            Log.w(TAG, "evaluatePyTorchModel(): Module is null");
            return 0.0f;
        }

        try {
            float[] inputVec = new float[model.inputSize];
            for (int i = 0; i < model.inputSize; i++) {
                inputVec[i] = (float) inputData[i];
            }

            Tensor inputTensor = Tensor.fromBlob(inputVec, new long[]{1, 1, model.inputSize});
            EValue[] outputs = model.pytorchModule.forward(EValue.from(inputTensor));

            if (outputs.length == 0) {
                Log.e(TAG, "evaluatePyTorchModel(): Empty output array");
                return 0.0f;
            }

            float[] scores = outputs[0].toTensor().getDataAsFloatArray();
            if (scores.length >= 2) {
                return softmaxProb(scores, 1);
            }

            Log.e(TAG, "evaluatePyTorchModel(): Unexpected output size: " + scores.length);
            return 0.0f;
        } catch (Exception e) {
            Log.e(TAG, "evaluatePyTorchModel(): Error - " + e.getMessage());
            return 0.0f;
        }
    }

    private float evaluateTfLiteModel(ModelInstance model, double[] inputData) {
        if (model.tfliteInterpreter == null) {
            Log.w(TAG, "evaluateTfLiteModel(): Interpreter is null");
            return 0.0f;
        }

        try {
            float[][][] modelInput = new float[1][model.inputSize][1];
            float[][] modelOutput = new float[1][2];

            for (int j = 0; j < model.inputSize; j++) {
                modelInput[0][j][0] = (float) inputData[j];
            }

            model.tfliteInterpreter.run(modelInput, modelOutput);
            return modelOutput[0][1];
        } catch (Exception e) {
            Log.e(TAG, "evaluateTfLiteModel(): Error - " + e.getMessage());
            return 0.0f;
        }
    }

    private static float softmaxProb(float[] scores, int index) {
        if (scores == null || scores.length < 2 || index < 0 || index >= scores.length) {
            return 0.0f;
        }

        float s0 = scores[0];
        float s1 = scores[1];
        float sum = s0 + s1;

        // Check if already probabilities
        if (s0 >= 0.0f && s1 >= 0.0f && Math.abs(sum - 1.0f) < 0.01f) {
            return scores[index];
        }

        // Numerically-stable softmax
        float max = Math.max(s0, s1);
        double e0 = Math.exp(s0 - max);
        double e1 = Math.exp(s1 - max);
        double denom = e0 + e1;

        if (denom == 0.0) return 0.0f;

        double p1 = e1 / denom;
        return (index == 1) ? (float) p1 : (float) (e0 / denom);
    }

    private double calcRawDataStd(SdData sdData) {
        int length = Math.min(sdData.rawData.length, 125);
        double sum = 0.0;

        for (int j = 0; j < length; j++) {
            sum += sdData.rawData[j];
        }
        double mean = sum / length;

        double standardDeviation = 0.0;
        for (int j = 0; j < length; j++) {
            standardDeviation += Math.pow(sdData.rawData[j] - mean, 2);
        }
        standardDeviation = Math.sqrt(standardDeviation / length);
        standardDeviation = 100.0 * standardDeviation / mean;

        return standardDeviation;
    }

    private int mapInputFormat(String fmtStr, int legacyDefault) {
        if (fmtStr == null || fmtStr.isEmpty()) {
            return legacyDefault;
        }
        if (fmtStr.equalsIgnoreCase("1d_mag")) {
            return 1;
        }
        return legacyDefault;
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


