# ML Model JSON Format

This document describes the JSON format expected for machine-learning model metadata.

It covers two related formats:

1. **Server index format** (`index.json`): an array of model objects downloaded from the model server.
2. **Installed model format**: the same object after install, enriched with local fields such as `localPath`.

## Top-level structure

`index.json` should be a JSON array:

```json
[
  {
    "name": "OSD PyTorch v1",
    "fname": "osd_v1.ptl",
    "framework": "pytorch"
  }
]
```

## Essential fields (server index)

These are required for a model entry to be accepted:

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | Yes | Human-readable model name. Must be non-empty. |
| `fname` | string | Yes | Model file name to download. Must be non-empty. |

If either is missing/empty, the model is filtered out.

## Optional fields with defaults

| Field | Type | Default if missing | Used for |
|---|---|---|---|
| `framework` | string | `"tflite"` | Runtime loader + compatibility checks. |
| `input_size` | integer | `125` | Input window size. |
| `input_format_val` | integer | `1` | Numeric input format used at runtime. |
| `input_format` | string | `"1d_mag"` | UI/update metadata path. |
| `alarm_threshold` | number | `2.0` | Legacy movement threshold fallback / alias. |
| `recommended` | boolean | `false` | Whether update checks prefer this model. |

## Optional compatibility and UI metadata

| Field | Type | Default | Notes |
|---|---|---|---|
| `min_cpu_features` | array of strings | none | If present, all listed CPU features must exist on device. |
| `description` | string | `"No description available."` (UI fallback) | Shown in model picker dialog. |
| `version` | string | `"?"` (UI fallback) | Shown in installed model list. |
| `size` | string | `"?"` (UI fallback) | Shown in installed model list. |

Additionally, if `framework` is `"pytorch"`, the device must be 64-bit.

## Threshold recommendation fields

When a model is installed, the app reads only these recommendation fields:

| Field | Type | Required for recommendation seeding | Notes |
|---|---|---|---|
| `accel_std_threshold_pct` | number | Yes | Movement threshold recommendation in percent. |
| `seizure_probability_threshold_pct` | number | Yes | Seizure probability threshold recommendation in percent. |

Behavior:

- Only the exact field names above are supported.
- Values are clamped to `0..100`.
- For seizure probability only, values `<= 1.0` are interpreted as fractions and converted to percent (for example `0.65 -> 65`).
- Recommended values are applied only if the user has not already explicitly set that preference.

## Installed model format (internal)

After download/install, the app stores the model object in preferences and adds:

| Field | Type | Required internally | Notes |
|---|---|---|---|
| `localPath` | string | Yes (post-install) | Absolute local file path for runtime loading. |

If `localPath` is absent but `fname` exists, the loader falls back to:

`<app files dir>/models/<fname>`

## Recommended complete example

```json
{
  "name": "OSD PyTorch v3",
  "fname": "osd_pytorch_v3.ptl",
  "framework": "pytorch",
  "input_format": "1d_mag",
  "input_format_val": 1,
  "input_size": 125,
  "recommended": true,
  "version": "3.1.0",
  "size": "2.4 MB",
  "description": "General-purpose seizure detection model",
  "min_cpu_features": ["asimd"],
  "accel_std_threshold_pct": 5,
  "seizure_probability_threshold_pct": 50
}
```

## Minimal valid example

```json
{
  "name": "My Model",
  "fname": "my_model.ptl",
  "framework": "pytorch"
}
```


