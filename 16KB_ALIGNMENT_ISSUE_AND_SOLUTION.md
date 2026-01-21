=== 16KB Alignment Issue and Solution ===

## Problem

The APK contains native libraries that are NOT aligned to 16KB boundaries:
- lib/arm64-v8a/libc++_shared.so  
- lib/arm64-v8a/libfbjni.so
- lib/arm64-v8a/libpytorch_jni.so

These libraries come from PyTorch (org.pytorch:pytorch_android) and are pre-compiled.

## Root Cause

Android Gradle Plugin 8.13.2 SHOULD automatically apply 16KB alignment to native libraries,
but it's NOT working for some reason. This could be due to:

1. AGP not detecting these libraries need alignment
2. The libraries being packaged before alignment step
3. Configuration issue preventing automatic alignment

## Solutions

### Solution 1: Manual APK Alignment (IMMEDIATE FIX)

After building the APK, manually align it using zipalign:

```bash
# Find zipalign (adjust path to your Android SDK)
ZIPALIGN=~/Android/Sdk/build-tools/34.0.0/zipalign

# Align the APK
$ZIPALIGN -f -p 16384 \
  app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/debug/app-debug-16kb-aligned.apk

# Verify alignment
$ZIPALIGN -c -p 16384 app/build/outputs/apk/debug/app-debug-16kb-aligned.apk

# For release builds, you'll need to re-sign after alignment:
# 1. Align the unsigned APK
# 2. Sign with apksigner
```

### Solution 2: Gradle Task for Automatic Alignment

Add this task to app/build.gradle to automatically align after building:

```groovy
android {
    // ...existing configuration...
    
    applicationVariants.all { variant ->
        variant.outputs.all { output ->
            variant.assembleProvider.get().doLast {
                def apkFile = output.outputFile
                if (apkFile.name.endsWith('.apk')) {
                    def alignedApk = new File(apkFile.parentFile, 
                        apkFile.name.replace('.apk', '-16kb-aligned.apk'))
                    
                    // Find zipalign
                    def sdkDir = android.sdkDirectory
                    def zipalign = new File(sdkDir, 
                        "build-tools/${android.buildToolsVersion}/zipalign")
                    
                    if (zipalign.exists()) {
                        exec {
                            commandLine zipalign, '-f', '-p', '16384', 
                                apkFile.absolutePath, alignedApk.absolutePath
                        }
                        println "✅ Created 16KB-aligned APK: ${alignedApk.name}"
                    }
                }
            }
        }
    }
}
```

### Solution 3: Update PyTorch (ATTEMPTED - didn't help)

We updated from PyTorch 1.13.1 to 2.1.0, but the libraries are still not aligned.
This means PyTorch doesn't ship 16KB-aligned libraries yet.

### Solution 4: Accept the Warning (TEMPORARY)

For now, you can:
- Ship the app with unaligned libraries
- Google Play will show the warning but should still accept the app
- The app WILL work on 16KB devices, just may not be optimal
- Impact: Slightly higher memory usage, slightly slower load times

## Recommended Approach

**For Development**: Accept the warning for now

**For Production Release**: Manually align the release APK before signing:

```bash
# 1. Build release APK (unsigned)
./gradlew assembleRelease

# 2. Align it
zipalign -f -p 16384 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/app-release-unsigned-aligned.apk

# 3. Sign it
apksigner sign --ks your-keystore.jks \
  --out app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned-aligned.apk
```

## Why AGP 8.13 Isn't Working

AGP 8.1+ is supposed to handle this automatically, but it's failing because:

1. **useLegacyPackaging=false** is correct but not sufficient
2. PyTorch libraries are HUGE (70MB) and may bypass automatic alignment
3. AGP may only align compressed libraries, but these are uncompressed
4. There may be a bug or configuration issue in AGP 8.13

## Verification

To check if an APK is 16KB aligned:

```python
import zipfile

PAGE_SIZE_16KB = 16384
apk_path = "app-debug.apk"

with zipfile.ZipFile(apk_path, 'r') as apk:
    for info in apk.infolist():
        if info.filename.endswith('.so'):
            offset = info.header_offset + len(info.FileHeader())
            if offset % PAGE_SIZE_16KB != 0:
                print(f"❌ {info.filename}: NOT aligned")
            else:
                print(f"✅ {info.filename}: aligned")
```

Or use zipalign:
```bash
zipalign -c -p 16384 app-debug.apk
```

## Status

- ✅ PyTorch updated to 2.1.0 (latest)
- ❌ Libraries still not 16KB aligned
- ⚠️ Manual alignment required for now
- 📋 Gradle task option available

## Next Steps

1. **Test if manual alignment works** - Run fix_16kb_alignment.sh
2. **Decide on approach**:
   - Accept warning for now (easiest)
   - Add Gradle task for automatic alignment (best)
   - Manually align before each release (most control)

## References

- Android 16KB Page Sizes: https://developer.android.com/guide/practices/page-sizes
- zipalign documentation: https://developer.android.com/tools/zipalign
- AGP 8.1 release notes: https://developer.android.com/build/releases/gradle-plugin

