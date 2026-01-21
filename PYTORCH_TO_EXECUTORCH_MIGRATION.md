# PyTorch Mobile → ExecuTorch Migration Analysis

## Date: January 21, 2026
## Status: ✅ **STABLE VERSION AVAILABLE - MIGRATION RECOMMENDED**

## Background

Based on PyTorch forum discussion: https://discuss.pytorch.org/t/android-build-native-libraries-not-16-kb-page-aligned-play-store-rejecting-apps/223243

## Key Information

### PyTorch Mobile Status
- **PyTorch Mobile** (`org.pytorch:pytorch_android`) is being **deprecated**
- Last version: 2.1.0 (from 2023)
- No new releases addressing 16KB alignment issues
- Libraries are 70MB+ and not 16KB aligned
- Officially moving to ExecuTorch as the mobile solution

### ExecuTorch ✅ **NOW STABLE**
- **ExecuTorch** is PyTorch's **new mobile runtime**
- **Available: `org.pytorch:executorch-android:1.0.1` (STABLE!)** 
- Also available: 1.0.0 (stable), 0.7.0 (stable)
- Designed specifically for mobile/edge devices
- Smaller, faster, more efficient than PyTorch Mobile
- Should have better 16KB alignment support (being actively developed)

## Current Implementation

### Our Usage (SdAlgNn.java)
```java
// Current imports
import org.pytorch.Module;
import org.pytorch.IValue;
import org.pytorch.Tensor;

// Current dependency (app/build.gradle)
implementation 'org.pytorch:pytorch_android:2.1.0'  // 70MB, not 16KB aligned

// Current code
mPtModule = Module.load(filePath);  // Load .ptl model
Tensor inputTensor = Tensor.fromBlob(inputData, new long[]{1, mInputSize});
Tensor outputTensor = mPtModule.forward(IValue.from(inputTensor)).toTensor();
float pSeizure = outputTensor.getDataAsFloatArray()[0];
```

### Model Format
- Current: `.ptl` (TorchScript Mobile format)
- ExecuTorch: `.pte` (ExecuTorch Program format)
- **Models will need to be re-exported** for ExecuTorch

## Migration Effort Assessment

### ✅ PROS of Migrating
1. **16KB Alignment**: ExecuTorch is actively developed and likely has proper alignment
2. **Smaller Size**: Much smaller than PyTorch Mobile (potentially 10-20MB vs 70MB)
3. **Better Performance**: Optimized for mobile inference
4. **Future-Proof**: This is PyTorch's official mobile direction
5. **Active Development**: Will get updates, bug fixes, security patches
6. **Reduce APK Size**: Significant reduction in app size

### ❌ CONS of Migrating
1. **Model Re-export**: All `.ptl` models must be converted to `.pte` format
2. **API Changes**: Code changes required in SdAlgNn.java
3. **RC Version**: 0.7.0-rc1 is Release Candidate, not stable yet
4. **Testing Required**: Full regression testing needed
5. **User Impact**: Need to re-download models or bundle new formats
6. **Documentation**: Limited compared to PyTorch Mobile
7. **Risk**: Early adoption of new technology

## Migration Complexity: **MEDIUM**

### Code Changes Required

#### 1. build.gradle
```groovy
// REMOVE
implementation 'org.pytorch:pytorch_android:2.1.0'

// ADD
implementation 'org.pytorch:executorch-android:0.7.0-rc1'
```

#### 2. SdAlgNn.java Imports
```java
// REMOVE
import org.pytorch.Module;
import org.pytorch.IValue;
import org.pytorch.Tensor;

// ADD (ExecuTorch equivalents)
import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;
```

#### 3. Model Loading Code
```java
// OLD (PyTorch Mobile)
mPtModule = Module.load(filePath);

// NEW (ExecuTorch)
mPtModule = Module.load(filePath);  // Similar but different Module class
```

#### 4. Inference Code
```java
// OLD (PyTorch Mobile)
Tensor inputTensor = Tensor.fromBlob(inputData, new long[]{1, mInputSize});
Tensor outputTensor = mPtModule.forward(IValue.from(inputTensor)).toTensor();
float pSeizure = outputTensor.getDataAsFloatArray()[0];

// NEW (ExecuTorch) - API may differ slightly
Tensor inputTensor = Tensor.fromBlob(inputData, new long[]{1, mInputSize});
EValue output = mPtModule.forward(EValue.from(inputTensor));
float pSeizure = output.toTensor().getDataAsFloatArray()[0];
```

### Model Changes Required

#### Current Models
- Format: `.ptl` (TorchScript Mobile)
- Server: https://osdapi.org.uk/static/ml_models/

#### New Models
- Format: `.pte` (ExecuTorch Program)
- Need to re-export ALL models:
  ```python
  # Python conversion script needed
  import torch
  from executorch.exir import to_edge
  
  # Load original PyTorch model
  model = torch.jit.load("model.ptl")
  
  # Convert to ExecuTorch
  edge_program = to_edge(model, example_inputs)
  edge_program.save("model.pte")
  ```

#### Server Update
- Upload new `.pte` models to server
- Update `index.json` to include new format:
  ```json
  {
    "name": "deep_cnn_01",
    "fname": "deep_cnn_v0.1.pte",  // Changed extension
    "framework": "executorch",      // New framework name
    "input_format": "1d_mag",
    "input_size": 750
  }
  ```

## Recommended Approach

### Option 1: Migrate Now (RECOMMENDED FOR FUTURE)
**Timeline**: 2-3 weeks
1. Update ExecuTorch dependency
2. Update SdAlgNn.java code
3. Re-export all models to `.pte` format
4. Update server with new models
5. Update MlModelManager to handle both formats during transition
6. Test thoroughly
7. Release with new models

**Benefits**:
- Solves 16KB issue permanently
- Smaller APK size
- Future-proof
- Better performance

**Risks**:
- RC version might have bugs
- Significant testing required
- Model re-export process

### Option 2: Wait for Stable Release (RECOMMENDED NOW)
**Timeline**: Monitor for stable 1.0.0 release
1. Keep current PyTorch 2.1.0
2. Use manual zipalign workaround for 16KB (current solution)
3. Monitor ExecuTorch releases
4. Migrate when 1.0.0 stable is released

**Benefits**:
- Less risk (stable version)
- Current solution works (with manual alignment)
- More time for ExecuTorch ecosystem to mature
- Better documentation will be available

**Risks**:
- Continued 16KB manual alignment needed
- Larger APK size continues
- Eventually must migrate anyway

### Option 3: Hybrid Approach (BEST COMPROMISE)
**Timeline**: Prepare now, migrate when ready
1. Keep current PyTorch 2.1.0 + manual zipalign
2. Start preparing ExecuTorch models (export .pte versions)
3. Add ExecuTorch support alongside PyTorch (detect format)
4. Gradual migration as models are ready
5. Eventually remove PyTorch Mobile

**Benefits**:
- Backwards compatible
- Can test ExecuTorch with subset of users
- Gradual rollout reduces risk
- Models can be migrated incrementally

**Risks**:
- More complex code (both frameworks)
- Larger APK temporarily (both libraries)
- More testing scenarios

## Current Status Assessment

### Should We Migrate NOW?

**YES - Migrate Now!** ✅

**Reasons**:
1. ✅ **ExecuTorch 1.0.1 is STABLE** - No longer RC, production-ready
2. ✅ **PyTorch version never released** - No backwards compatibility needed!
3. ✅ **Perfect timing** - Can start with the better solution from day one
4. ✅ **Solves 16KB issue** - Likely properly aligned (to be verified)
5. ✅ **50-65% smaller APK** - Huge benefit for users
6. ✅ **Better performance** - Optimized for mobile from the start
7. ✅ **No migration pain** - No existing users on PyTorch Mobile

### Migration is NOW RECOMMENDED

Since PyTorch version was never released to users:
- **No backward compatibility concerns**
- **No user migration** required
- **Clean start** with the modern solution
- **Best practices** from day one

**Estimated Timeline**: 1-2 weeks (shorter since no backward compatibility needed)

## Immediate Actions

### 1. Document the Plan ✅
This document serves as the migration plan.

### 2. Update 16KB Resolution Document
Add note about ExecuTorch being the long-term solution.

### 3. Monitor ExecuTorch Releases
Check monthly for stable 1.0.0 release:
```bash
curl -s "https://repo1.maven.org/maven2/org/pytorch/executorch-android/maven-metadata.xml" | grep version
```

### 4. Prepare for Future Migration
- Keep model export scripts ready
- Document current PyTorch Mobile usage
- Plan testing strategy
- Reserve time in future sprints

### 5. No Immediate Code Changes
Continue using:
- PyTorch Mobile 2.1.0
- Manual zipalign for 16KB
- Current `.ptl` models

## Testing Plan (When Migration Happens)

### Phase 1: Development
1. Update dependency
2. Update code
3. Export one test model to `.pte`
4. Test with single model
5. Verify 16KB alignment of ExecuTorch libraries

### Phase 2: Model Conversion
1. Convert all models to `.pte` format
2. Upload to test server
3. Verify model accuracy matches originals
4. Test download/load/inference for each

### Phase 3: Integration Testing
1. Full app testing with new models
2. Performance benchmarking
3. Memory usage profiling
4. Battery impact testing
5. Crash testing

### Phase 4: Beta Release
1. Release to beta testers
2. Monitor crash reports
3. Gather performance feedback
4. Fix any issues

### Phase 5: Production Release
1. Release to all users
2. Monitor metrics
3. Keep fallback to TFLite if issues occur

## APK Size Impact

### Current
- PyTorch Mobile: ~70MB per architecture
- Total native libs: ~280MB (all architectures)
- APK size: ~306MB debug

### Expected with ExecuTorch
- ExecuTorch: ~10-20MB per architecture (estimated)
- Total native libs: ~40-80MB (all architectures)
- APK size: ~100-150MB debug (estimated)

**Savings**: ~150-200MB (50-65% reduction in native library size)

## Risks and Mitigation

### Risk 1: ExecuTorch Bugs
**Mitigation**: Wait for stable 1.0.0, thorough testing, keep TFLite as fallback

### Risk 2: Model Accuracy Changes
**Mitigation**: Validate model outputs match PyTorch Mobile versions

### Risk 3: Performance Regression
**Mitigation**: Benchmark before/after, optimize if needed

### Risk 4: 16KB Still Not Aligned
**Mitigation**: Verify alignment before full migration, can still use zipalign

### Risk 5: Breaking Changes
**Mitigation**: Pin to specific version, test thoroughly

## Decision Matrix

| Criteria | PyTorch Mobile | ExecuTorch (Now) | ExecuTorch (Stable) |
|----------|----------------|------------------|---------------------|
| 16KB Aligned | ❌ No (manual fix) | ⚠️ Unknown (RC) | ✅ Likely |
| APK Size | ❌ Large (70MB) | ✅ Small (10-20MB) | ✅ Small |
| Stability | ✅ Stable | ⚠️ RC version | ✅ Stable |
| Support | ❌ Deprecated | ⚠️ Early adopter | ✅ Official |
| Migration Effort | ✅ None needed | ❌ High | ❌ High |
| Testing Needed | ✅ None | ❌ Extensive | ⚠️ Moderate |
| Risk | ✅ Low | ❌ High | ⚠️ Medium |
| **Recommendation** | **Use now** | **Wait** | **Migrate then** |

## Conclusion

### Current Decision: **MIGRATE TO EXECUTORCH 1.0.1 NOW** ✅

**Rationale**:
1. ExecuTorch 1.0.1 is **STABLE** (not RC)
2. **PyTorch version NEVER released** to users - no backward compatibility!
3. Perfect timing to use the modern solution from day one
4. Solves 16KB issue permanently (likely)
5. 50-65% smaller APK from the start
6. Future-proof (official PyTorch direction)

### Migration Plan: **IMMEDIATE**

**Steps**:
1. ✅ Update dependency to ExecuTorch 1.0.1
2. ✅ Update SdAlgNn.java code for ExecuTorch API
3. 📋 Export models to `.pte` format
4. 📋 Update server with new models
5. 🧪 Test thoroughly
6. ✅ Release with ExecuTorch (never release PyTorch version)

### Immediate Actions:
1. ✅ Migrate to ExecuTorch 1.0.1 NOW
2. ✅ Remove PyTorch Mobile dependency
3. ✅ Update all PyTorch code to ExecuTorch
4. 📋 Convert models to `.pte` format
5. 🧪 Test and verify 16KB alignment

## References

- PyTorch Forum: https://discuss.pytorch.org/t/android-build-native-libraries-not-16-kb-page-aligned-play-store-rejecting-apps/223243
- ExecuTorch Maven: https://mvnrepository.com/artifact/org.pytorch/executorch-android
- ExecuTorch GitHub: https://github.com/pytorch/executorch
- ExecuTorch Docs: https://pytorch.org/executorch/

## Updates

- **2026-01-21**: Initial analysis - decision to wait for stable release
- **Next review**: 2026-04-01 (check for ExecuTorch 1.0.0)

