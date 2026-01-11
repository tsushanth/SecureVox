# Whisper CoreML Models

## Model Setup

The VoiceNotesOndevice app uses Whisper speech recognition models converted to CoreML format.

### Obtaining Models

The Whisper models are not included in this repository due to their size. You have two options:

#### Option 1: Use whisper.cpp CoreML Export (Recommended)

1. Clone [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
2. Follow their CoreML export instructions
3. Place the exported `.mlmodelc` in this directory

```bash
# Example using whisper.cpp
git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp
./models/download-ggml-model.sh tiny
./models/generate-coreml-model.sh tiny
```

#### Option 2: Convert from OpenAI Whisper

1. Install Python dependencies:
```bash
pip install openai-whisper coremltools
```

2. Run conversion script:
```python
import whisper
import coremltools as ct

# Load model
model = whisper.load_model("tiny")

# Convert to CoreML
# (Full conversion script requires additional handling)
```

### Model Variants

| Model | File Size | RAM Usage | Quality |
|-------|-----------|-----------|---------|
| whisper-tiny.mlmodelc | ~75 MB | ~200 MB | Basic |
| whisper-base.mlmodelc | ~150 MB | ~400 MB | Good |
| whisper-small.mlmodelc | ~500 MB | ~1 GB | Great |

### Directory Structure

```
CoreML/
├── Models/
│   ├── whisper-tiny.mlmodelc/    # Bundled with app
│   ├── whisper-base.mlmodelc/    # On-Demand Resource
│   └── whisper-small.mlmodelc/   # On-Demand Resource
├── WhisperModelLoader.swift
├── AudioPreprocessor.swift
├── ChunkProcessor.swift
├── VADFilter.swift
└── README.md
```

### On-Demand Resources

For larger models (base, small), configure as On-Demand Resources in Xcode:

1. Select model in Project Navigator
2. Open File Inspector
3. Under "On Demand Resource Tags", add tag (e.g., "whisper-base")
4. Set "Resource Tags" in project settings

### Performance Notes

- Always prefer Neural Engine (ANE) for inference
- Unload models when app enters background
- Monitor memory usage with `os_signpost`
- Use autoreleasepool for batch processing

### Troubleshooting

**Model won't load:**
- Verify .mlmodelc is compiled (not .mlmodel)
- Check code signing settings
- Ensure sufficient device memory

**Poor transcription quality:**
- Verify audio is 16kHz mono PCM
- Check for audio clipping
- Try larger model variant

**Slow performance:**
- Ensure Neural Engine is being used
- Check thermal state
- Reduce concurrent operations
