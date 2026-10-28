"""The HTTP API behind the Android app, and behind any app that speaks OpenAI's
transcription API (Polished Recognition, other voice keyboards, curl).

    POST /v1/audio/transcriptions   an audio file -> {"text": ...}           OpenAI-compatible
    GET  /v1/models                 the Whisper model in use                 OpenAI-compatible
    GET  /v1/vocabulary             {"words": [...], "replacements": [...]}
    POST /v1/vocabulary             {"add": [...], "remove": [...], "remove_replacements": [...]}
    POST /v1/corrections            {"original": ..., "corrected": ..., "note_id": ...}
                                    -> what RedDot learned from it (learning.py)

A note's way through the server:

    audio --> Whisper (transcriber.py), your vocabulary as hints
          --> replacements and spelling fixes (learning.py)
          --> {"text": ...} back to the phone
          --> with DICTATION_KEEP_AUDIO=1 also kept: DICTATION_DATA_DIR/notes/<day>/<id>.wav + .json

Off unless DICTATION_API=1. Who may call it: clients whose address is in DICTATION_NETWORKS
(by default this machine and Tailscale's address ranges, so only your own devices), and
anyone sending DICTATION_TOKEN as "Authorization: Bearer <token>". Behind a reverse proxy
every client seems to come from this machine: set a token and DICTATION_NETWORKS= (empty).

The transcription endpoint accepts OpenAI's `model` and `temperature` fields but ignores
them: the model is WHISPER_MODEL. Two extra fields are RedDot's own: `note_id` (the phone's
id for the note, so a later correction can refer to it) and `recorded_at` (ISO 8601).
"""

from __future__ import annotations

import ipaddress
import logging
import os
import re
import secrets
import threading
import uuid
from datetime import datetime
from io import BytesIO
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel

from learning import NOTE_ID, Learner
from transcriber import WHISPER_MODEL, Transcriber

log = logging.getLogger("orb-server")

DICTATION_API = os.getenv("DICTATION_API", "0") == "1"
DICTATION_TOKEN = os.getenv("DICTATION_TOKEN", "")
# 127/8 and ::1: this machine. 100.64/10 and fd7a:115c:a1e0::/48: the addresses Tailscale gives your devices.
DICTATION_NETWORKS = [ipaddress.ip_network(n.strip()) for n in
                      os.getenv("DICTATION_NETWORKS", "127.0.0.0/8,::1/128,100.64.0.0/10,fd7a:115c:a1e0::/48").split(",")
                      if n.strip()]
DICTATION_DATA_DIR = os.getenv("DICTATION_DATA_DIR", "dictation-data")
DICTATION_KEEP_AUDIO = os.getenv("DICTATION_KEEP_AUDIO", "0") == "1"
WHISPER_PRELOAD = os.getenv("WHISPER_PRELOAD", "0") == "1"
MAX_UPLOAD_BYTES = 25 * 1024 * 1024   # OpenAI's limit too; 25 MB of 16 kHz 16-bit WAV is about 13 minutes

transcriber = Transcriber()
learner = Learner(DICTATION_DATA_DIR)
router = APIRouter(prefix="/v1")


def allowed(request: Request) -> None:
    """A FastAPI dependency: refuses the request unless the client may use the API."""
    if DICTATION_TOKEN and secrets.compare_digest(request.headers.get("authorization", "").encode(),
                                                  f"Bearer {DICTATION_TOKEN}".encode()):
        return
    try:
        address = ipaddress.ip_address(request.client.host if request.client else "")
        if address.version == 6 and address.ipv4_mapped:   # "::ffff:100.101.102.103" on a dual-stack socket
            address = address.ipv4_mapped
    except ValueError:
        address = None
    if address is not None and any(address in network for network in DICTATION_NETWORKS):
        return
    raise HTTPException(status_code=401, detail="Not allowed: connect through Tailscale, or send DICTATION_TOKEN as a bearer token.")


def health() -> dict:
    """For /healthz: is the model loaded, and where."""
    return {"model": WHISPER_MODEL, "ready": transcriber.ready, "device": transcriber.device, "error": transcriber.error}


def start_preload() -> None:
    """Load Whisper in the background right after startup, so the first note isn't slow."""
    def load() -> None:
        try:
            transcriber.load()
        except Exception as err:  # noqa: BLE001: logged; the first request will try again
            log.error("could not preload Whisper: %s", err)
    if WHISPER_PRELOAD:
        threading.Thread(target=load, name="whisper-preload", daemon=True).start()


def _language(code: str) -> str | None:
    code = code.strip().lower()
    return None if code in ("", "auto") else code


@router.post("/audio/transcriptions", dependencies=[Depends(allowed)])
async def transcriptions(
    file: UploadFile = File(...),
    model: str = Form("whisper-1"),          # accepted for OpenAI compatibility, ignored
    language: str = Form(""),                 # "en", "vi", ...; empty or "auto": detect
    prompt: str = Form(""),                   # extra context for Whisper, before the vocabulary
    response_format: str = Form("json"),      # "json", "text" or "verbose_json"
    temperature: float = Form(0.0),           # accepted for OpenAI compatibility, ignored
    note_id: str = Form(""),
    recorded_at: str = Form(""),
):
    if response_format not in ("json", "text", "verbose_json"):
        raise HTTPException(status_code=400, detail=f"response_format {response_format!r} isn't supported: use json, text or verbose_json")
    audio = await file.read(MAX_UPLOAD_BYTES + 1)
    if len(audio) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="The audio is larger than 25 MB.")
    if not audio:
        raise HTTPException(status_code=400, detail="The audio file is empty.")
    note_id = note_id if NOTE_ID.fullmatch(note_id) else uuid.uuid4().hex

    try:
        result = await run_in_threadpool(transcriber.transcribe, BytesIO(audio), _language(language),
                                         learner.vocabulary(), prompt)
    except Exception as err:  # noqa: BLE001: report it to the client either way
        if not transcriber.ready:
            log.error("Whisper isn't available: %s", err)
            raise HTTPException(status_code=503, detail=f"Whisper isn't available on the server: {err}") from err
        log.warning("could not transcribe %d bytes of audio: %s", len(audio), err)
        raise HTTPException(status_code=400, detail=f"Could not transcribe this audio: {err}") from err

    text = learner.apply(result.text)
    log.info("transcribed %.1f s of audio in %.2f s (%s, %s): %d characters",
             result.duration, result.seconds_taken, result.language, transcriber.device, len(text))
    if DICTATION_KEEP_AUDIO and result.duration > 0:
        info = {"id": note_id, "recorded_at": recorded_at, "received_at": datetime.now().astimezone().isoformat(timespec="seconds"),
                "language": result.language, "duration": result.duration, "model": result.model,
                "whisper_text": result.text, "text": text}
        learner.keep_note(note_id, audio, Path(file.filename or "").suffix.lower()[:8] or ".wav", info)

    if response_format == "text":
        return PlainTextResponse(text)
    body = {"text": text, "id": note_id, "language": result.language, "duration": result.duration,
            "model": result.model, "whisper_text": result.text, "seconds_taken": result.seconds_taken}
    if response_format == "verbose_json":
        body = {"task": "transcribe", **body,
                "segments": [{"id": i, "start": s.start, "end": s.end, "text": s.text} for i, s in enumerate(result.segments)]}
    return body


@router.get("/models", dependencies=[Depends(allowed)])
async def models() -> dict:
    return {"object": "list", "data": [{"id": WHISPER_MODEL, "object": "model", "owned_by": "reddot"}]}


class VocabularyChange(BaseModel):
    add: list[str] = []
    remove: list[str] = []
    remove_replacements: list[str] = []   # by their "heard" side


def _vocabulary_body() -> dict:
    return {"words": learner.vocabulary(),
            "replacements": [{"heard": heard, "meant": meant} for heard, meant in learner.replacements()]}


@router.get("/vocabulary", dependencies=[Depends(allowed)])
async def get_vocabulary() -> dict:
    return _vocabulary_body()


@router.post("/vocabulary", dependencies=[Depends(allowed)])
async def change_vocabulary(change: VocabularyChange) -> dict:
    learner.add_words(change.add)
    learner.remove_words(change.remove)
    learner.remove_replacements(change.remove_replacements)
    return _vocabulary_body()


class Correction(BaseModel):
    original: str       # the text the server returned
    corrected: str      # what it should have been
    note_id: str = ""


@router.post("/corrections", dependencies=[Depends(allowed)])
async def corrections(correction: Correction) -> dict:
    learned = learner.learn(correction.original, correction.corrected,
                            correction.note_id if NOTE_ID.fullmatch(correction.note_id) else None)
    if learned.new_words or learned.new_replacements:
        log.info("learned %d new words and %d replacements from a correction",
                 len(learned.new_words), len(learned.new_replacements))
    return {"fixes": [{"heard": f.heard, "meant": f.meant, "learned": f.name_like} for f in learned.fixes],
            "new_words": learned.new_words,
            "new_replacements": [{"heard": heard, "meant": meant} for heard, meant in learned.new_replacements]}

