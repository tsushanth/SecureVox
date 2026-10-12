"""Download the two speaker-label models into ./models, pinned to the app's size + SHA-256
(same values as android/.../speakers/ModelDownloader.kt). Resumes truncated downloads.
"""
import hashlib
import os
import sys
import urllib.request

FILES = [
    ("https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
     "segmentation.onnx", 5_992_913, "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"),
    ("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx",
     "embedding.onnx", 40_257_283, "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"),
]
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "models")
os.makedirs(out, exist_ok=True)
for url, name, size, sha in FILES:
    path = os.path.join(out, name)
    for _ in range(12):
        have = os.path.getsize(path) if os.path.exists(path) else 0
        if have == size:
            break
        req = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
        try:
            with urllib.request.urlopen(req, timeout=60) as r, open(path, "ab" if have and r.status == 206 else "wb") as f:
                while chunk := r.read(1 << 16):
                    f.write(chunk)
        except Exception as e:  # flaky connection: loop and resume
            print("retry", name, e)
    h = hashlib.sha256(open(path, "rb").read()).hexdigest()
    assert os.path.getsize(path) == size and h == sha, f"{name}: integrity check failed"
    print("ok", name)
