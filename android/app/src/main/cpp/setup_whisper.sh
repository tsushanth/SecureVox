#!/bin/bash

# Setup script to download whisper.cpp for Android build

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WHISPER_DIR="$SCRIPT_DIR/whisper.cpp"

# whisper.cpp version to use
WHISPER_VERSION="v1.7.2"

if [ -d "$WHISPER_DIR" ]; then
    echo "whisper.cpp already exists at $WHISPER_DIR"
    echo "To update, delete the folder and run this script again."
    exit 0
fi

echo "Downloading whisper.cpp $WHISPER_VERSION..."

# Clone whisper.cpp
git clone --depth 1 --branch $WHISPER_VERSION https://github.com/ggerganov/whisper.cpp.git "$WHISPER_DIR"

if [ $? -ne 0 ]; then
    echo "Failed to clone whisper.cpp"
    exit 1
fi

echo "whisper.cpp downloaded successfully!"
echo ""
echo "Next steps:"
echo "1. Download a Whisper model (e.g., ggml-tiny.bin or ggml-base.bin)"
echo "   from: https://huggingface.co/ggerganov/whisper.cpp/tree/main"
echo "2. Place the model in app/src/main/assets/"
echo ""
echo "Recommended models for mobile:"
echo "  - ggml-tiny.bin (~40MB) - Fastest, good for real-time"
echo "  - ggml-base.bin (~150MB) - Better accuracy"
echo "  - ggml-small.bin (~500MB) - Best accuracy for mobile"
