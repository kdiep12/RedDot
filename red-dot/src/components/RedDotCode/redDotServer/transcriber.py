"""Speech-to-text for whole notes, with an open Whisper model running on this machine.

    audio file --> faster-whisper --> segments --> text
                   |  vad_filter: cuts silence and wind out first, so they don't turn into
                   |    invented words (PRD R15)
                   +  initial_prompt: your vocabulary as a short list of words, which nudges
                        Whisper towards your spelling of names and jargon (PRD R16):
                        "send the map to Martin" becomes "send the map to Marten"

The model is loaded on first use (or right at startup with WHISPER_PRELOAD=1) and then kept
in memory. On an RTX 5070 Ti, large-v3-turbo loads in about a second once it is downloaded
(1.6 GB, the first time only) and transcribes a 7 s note in about 0.2 s; the very first
note after loading takes a few seconds longer while the GPU warms up.

faster-whisper is imported lazily, so server.py (and the WebSocket speech detector) still
starts on machines where it isn't installed or the HTTP API is switched off.
"""

from __future__ import annotations

import ctypes
import glob
import importlib.util
import logging
import os
import re
import threading
import time
from dataclasses import dataclass, field
from typing import BinaryIO

log = logging.getLogger("orb-server")

WHISPER_MODEL = os.getenv("WHISPER_MODEL", "large-v3-turbo")         # a faster-whisper model name, or a local path
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "auto")                 # "cuda", "cpu" or "auto" (the GPU if there is one)
WHISPER_COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "default")  # "default": float16 on a GPU, int8 on a CPU
BEAM_SIZE = 5                 # Whisper's search width: 5 is its standard; 1 is about twice as fast, a little worse
PROMPT_MAX_CHARS = 600        # Whisper reads at most ~224 tokens of prompt; ~600 characters stays well inside


@dataclass
class Segment:
    start: float   # seconds from the start of the audio
    end: float
    text: str


@dataclass
class Transcript:
    text: str
    language: str
    language_probability: float     # how sure Whisper was about the language, 0..1
    duration: float                 # seconds of audio received
    segments: list[Segment] = field(default_factory=list)
    model: str = WHISPER_MODEL
    seconds_taken: float = 0.0      # wall-clock time spent transcribing


def vocabulary_prompt(words: list[str], extra: str = "") -> str:
    """The prompt Whisper reads before it listens: the client's own prompt, then as many of
    the vocabulary words as fit into PROMPT_MAX_CHARS.

    Whisper treats the prompt as "the text that came just before", so a plain list of words
    written the way you want them is enough; it then prefers those spellings."""
    prompt = extra.strip()
    listed: list[str] = []
    for word in words:
        candidate = ", ".join([*listed, word])
        if len(prompt) + len(candidate) + 2 > PROMPT_MAX_CHARS:
            break
        listed.append(word)
    if listed:
        prompt = f"{prompt} {', '.join(listed)}." if prompt else f"{', '.join(listed)}."
    return prompt


def _words_of(text: str) -> list[str]:
    return re.findall(r"\w+", text.lower())


def echoes_prompt(text: str, prompt: str) -> bool:
    """True if the "transcript" is just the prompt read back.

    On unclear audio (a mumble, a glitch, wind) Whisper sometimes returns its prompt instead
    of what was said: with a vocabulary prompt that is a list of your words. A transcript
    whose words all come from the prompt is that failure, not speech."""
    said, prompted = _words_of(text), set(_words_of(prompt))
    return len(said) >= 2 and bool(prompted) and all(w in prompted for w in said)


def _preload_pip_cuda_libraries() -> None:
    """CTranslate2 (faster-whisper's engine) needs cuBLAS 12 and cuDNN 9. Installed from pip
    (requirements-gpu.txt) they sit inside .venv, where the dynamic loader doesn't look, and
    the first transcription fails with "libcublas.so.12 is not found". Loading them by path
    first makes them available to everything loaded afterwards.

    Libraries that depend on each other are retried, so their order doesn't matter."""
    for package in ("nvidia.cublas", "nvidia.cudnn"):
        try:
            spec = importlib.util.find_spec(package)
        except ModuleNotFoundError:
            spec = None
        if not spec or not spec.submodule_search_locations:
            continue  # not installed from pip: the system's libraries (if any) are used
        for lib_dir in spec.submodule_search_locations:
            pending = sorted(glob.glob(os.path.join(lib_dir, "lib", "lib*.so*")))
            for _ in range(3):
                failed = []
                for path in pending:
                    try:
                        ctypes.CDLL(path, mode=ctypes.RTLD_GLOBAL)
                    except OSError:
                        failed.append(path)
                pending = failed
                if not pending:
                    break


class Transcriber:
    """One Whisper model, loaded once, used by one request at a time (a personal server
    rarely gets two notes at once, and the GPU would only share its time between them)."""

    def __init__(self) -> None:
        self._model = None
        self._lock = threading.Lock()
        self.device = ""        # "cuda" or "cpu", once loaded
        self.error = ""         # why loading failed, for /healthz

    @property
    def ready(self) -> bool:
        return self._model is not None

    def load(self) -> None:
        with self._lock:
            self._load_locked()

    def _load_locked(self) -> None:
        if self._model is not None:
            return
        try:
            _preload_pip_cuda_libraries()
            import ctranslate2
            from faster_whisper import WhisperModel

            devices = [WHISPER_DEVICE]
            if WHISPER_DEVICE == "auto":
                devices = (["cuda"] if ctranslate2.get_cuda_device_count() > 0 else []) + ["cpu"]
            last_error: Exception | None = None
            for device in devices:
                compute_type = WHISPER_COMPUTE_TYPE
                if compute_type == "default":
                    compute_type = "float16" if device == "cuda" else "int8"
                started = time.monotonic()
                try:
                    model = WhisperModel(WHISPER_MODEL, device=device, compute_type=compute_type)
                    # One second of silence: GPU problems (missing libraries, an unsupported
                    # card) only show up when the model first runs, so find them now.
                    import numpy as np
                    list(model.transcribe(np.zeros(16000, dtype=np.float32), beam_size=1)[0])
                except Exception as err:  # noqa: BLE001: any failure means "try the next device"
                    last_error = err
                    log.warning("Whisper %s on %s failed: %s", WHISPER_MODEL, device, err)
                    continue
                self._model, self.device, self.error = model, device, ""
                log.info("Whisper %s ready on %s (%s) in %.1f s", WHISPER_MODEL, device, compute_type,
                         time.monotonic() - started)
                return
            raise RuntimeError(f"could not load Whisper {WHISPER_MODEL}: {last_error}")
        except Exception as err:
            self.error = str(err)
            raise

    def transcribe(self, audio: str | BinaryIO, language: str | None = None,
                   words: list[str] | None = None, prompt: str = "") -> Transcript:
        """Transcribe one whole note. `audio` is a path or an open file in any format FFmpeg
        reads (WAV, M4A, Ogg, WebM...); `language` is an ISO code such as "en", or None to
        let Whisper detect it."""
        with self._lock:
            self._load_locked()
            initial_prompt = vocabulary_prompt(words or [], prompt)
            started = time.monotonic()
            segments, info = self._model.transcribe(
                audio,
                language=language or None,
                beam_size=BEAM_SIZE,
                initial_prompt=initial_prompt or None,
                vad_filter=True,
                # Each 30 s window is transcribed on its own, not "continuing" the last one:
                # that is what makes Whisper repeat a phrase over and over in long notes.
                condition_on_previous_text=False,
            )
            kept = [Segment(round(s.start, 2), round(s.end, 2), s.text.strip()) for s in segments]
            text = " ".join(s.text for s in kept if s.text).strip()
            if initial_prompt and echoes_prompt(text, initial_prompt):
                log.info("dropped a transcript that only repeated the vocabulary prompt")
                kept, text = [], ""
            return Transcript(text=text, language=info.language, language_probability=round(info.language_probability, 2),
                              duration=round(info.duration, 2), segments=kept, model=WHISPER_MODEL,
                              seconds_taken=round(time.monotonic() - started, 2))
