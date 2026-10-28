#!/usr/bin/env python3
"""Plays an audio file into the Android emulator's microphone: testing RedDot without a phone.

Needs the emulator started with `-grpc 8554`, ffmpeg, and `pip install grpcio grpcio-tools`.

    python3 inject_audio.py note.wav [seconds of silence before] [seconds of silence after]

The emulator only takes audio while an app is recording, and this blocks until it has taken
all of it: start it, then hold the dot (`adb shell input swipe X Y X Y 9000` holds for 9 s).
It uses the emulator's own gRPC API (EmulatorController.injectAudio), compiled on the fly
from the .proto file that ships with the emulator.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import time


def load_api():
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk"))
    proto_dir = os.path.join(sdk, "emulator", "lib")
    out = tempfile.mkdtemp(prefix="emulator-grpc-")
    subprocess.run([sys.executable, "-m", "grpc_tools.protoc", f"-I{proto_dir}", f"--python_out={out}",
                    f"--grpc_python_out={out}", os.path.join(proto_dir, "emulator_controller.proto")], check=True)
    sys.path.insert(0, out)
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as rpc
    return pb, rpc


def pcm_16k_mono(path: str) -> bytes:
    """Any audio file -> raw 16 kHz 16-bit mono, like a phone microphone records for RedDot."""
    return subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "s16le", "-ac", "1", "-ar", "16000", "-"],
                          capture_output=True, check=True).stdout


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    before = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    after = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5
    pb, rpc = load_api()
    import grpc

    audio_format = pb.AudioFormat(samplingRate=16000, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16)
    data = b"\0" * int(before * 32000) + pcm_16k_mono(sys.argv[1]) + b"\0" * int(after * 32000)

    def packets():
        for i in range(0, len(data), 640):   # 20 ms each
            yield pb.AudioPacket(format=audio_format, timestamp=int(time.time() * 1e6), audio=data[i:i + 640])

    started = time.time()
    rpc.EmulatorControllerStub(grpc.insecure_channel("localhost:8554")).injectAudio(packets())
    print(f"played {sys.argv[1]} into the emulator's microphone ({time.time() - started:.1f} s)")


if __name__ == "__main__":
    main()
