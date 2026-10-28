"""How RedDot learns your words: from a word list, and from your corrections.

Everything lives in plain files in DICTATION_DATA_DIR that you, the Android app (through
/v1/vocabulary and /v1/corrections) and Claude Code can all read and edit:

    vocabulary.txt     one word or phrase per line: names, jargon, species, site codes.
                       Whisper gets them as hints before it listens (transcriber.py), and
                       wrongly cased copies in a transcript are fixed: "pyqgis" -> "PyQGIS".
    replacements.txt   "heard => meant", one per line, applied to every transcript.
    corrections.jsonl  every correction: what Whisper wrote and what it should have been.
                       Together with the kept audio (notes/), the start of a training set
                       for tuning Whisper to your voice.
    learned.json       bookkeeping: how often each fix was seen, which ones were promoted.

How one correction teaches RedDot:

    what Whisper wrote:  "Open the bio scope backend and ping Martin."
    what you meant:      "Open the BioScope backend and ping Marten."
              |  word-by-word comparison
              v
    bio scope => BioScope      both results look like names (a capital that doesn't just
    Martin    => Marten        start a sentence, a digit, or a - _ . / inside a word), so
              |                BioScope and Marten join vocabulary.txt right away
              |  the same fix seen LEARN_AFTER times
              v
    replacements.txt           from then on it is applied to every transcript

Fixes that only produce ordinary lowercase words ("there" => "their") are grammar, not
mishearings: replacing them everywhere would do more harm than good, so they are logged in
corrections.jsonl but never learned.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
import threading
from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path

LEARN_AFTER = 2          # the same fix this many times becomes an automatic replacement
MAX_PHRASE_WORDS = 4     # a change of more words than this is a rewrite, not a mishearing
MIN_SIMILARITY = 0.5     # share of words two texts have in common (0..1); below it, rewritten

VOCABULARY_HEADER = (
    "# Words and phrases RedDot should know, one per line, written the way you want them.\n"
    "# Whisper gets them as hints before it listens. Lines starting with # are comments.\n"
)
REPLACEMENTS_HEADER = (
    "# heard => meant, one per line, applied to every transcript (case doesn't matter on the left).\n"
    "# RedDot adds a line after the same correction was made twice; delete a line to undo it.\n"
)

_EDGES = re.compile(r"^\W+|\W+$")            # punctuation around a word: "(RedDot)," -> "RedDot"
_INNER_MARK = re.compile(r"\w[-_./]\w")      # faster-whisper, site_3, v1.2, km/h
_SENTENCE_END = re.compile(r"[.!?…][\"')\]”’]*$")
NOTE_ID = re.compile(r"[A-Za-z0-9-]{1,64}")  # note ids become file names: nothing else gets through


@dataclass
class _Word:
    core: str              # the word without the punctuation around it
    key: str               # what the comparison sees: the first letter lowercased, so sentence
                           # case ("The" / "the") never counts as a correction
    starts_sentence: bool


def _words(text: str) -> list[_Word]:
    words: list[_Word] = []
    at_start = True
    for raw in text.split():
        core = _EDGES.sub("", raw)
        if core:
            words.append(_Word(core, core[:1].lower() + core[1:], at_start))
            at_start = False
        if _SENTENCE_END.search(raw):
            at_start = True
    return words


@dataclass(frozen=True)
class Fix:
    heard: str        # what Whisper wrote, e.g. "bio scope"
    meant: str        # what it should have been, e.g. "BioScope"
    name_like: bool   # whether RedDot learns it (see the module docstring)


def _name_like(words: list[_Word]) -> bool:
    for w in words:
        letters = w.core[1:] if w.starts_sentence else w.core
        if any(c.isupper() for c in letters) or any(c.isdigit() for c in w.core) or _INNER_MARK.search(w.core):
            return True
    return False


def _distinctive(term: str) -> bool:
    """A capital after the first letter, a digit or an inner mark: "PyQGIS", "S3", "Wispr Flow".
    Wrongly cased copies of such terms are safe to fix. "Rust" or "Marten" are not: their
    lowercase forms are ordinary words."""
    return any(c.isupper() for c in term[1:]) or any(c.isdigit() for c in term) or bool(_INNER_MARK.search(term))


def find_fixes(original: str, corrected: str) -> list[Fix]:
    """The small word changes between a transcript and its correction."""
    a, b = _words(original), _words(corrected)
    if not a or not b:
        return []
    short = len(a) <= MAX_PHRASE_WORDS and len(b) <= MAX_PHRASE_WORDS
    similar = SequenceMatcher(None, [w.key.lower() for w in a], [w.key.lower() for w in b], autojunk=False)
    if not short and similar.ratio() < MIN_SIMILARITY:
        return []  # rewritten, not corrected
    fixes = []
    matcher = SequenceMatcher(None, [w.key for w in a], [w.key for w in b], autojunk=False)
    for op, i1, i2, j1, j2 in matcher.get_opcodes():
        # Only "these words were heard as those": words that were added or deleted are edits
        # of the content, not mishearings.
        if op != "replace" or i2 - i1 > MAX_PHRASE_WORDS or j2 - j1 > MAX_PHRASE_WORDS:
            continue
        fixes.append(Fix(" ".join(w.core for w in a[i1:i2]), " ".join(w.core for w in b[j1:j2]),
                         _name_like(b[j1:j2])))
    return fixes


def _phrase_pattern(phrase: str) -> re.Pattern[str]:
    """Matches the phrase as whole words, any case, any whitespace between its words."""
    body = r"\s+".join(re.escape(part) for part in phrase.split())
    return re.compile(rf"(?<!\w){body}(?!\w)", re.IGNORECASE)


def _read_lines(path: Path) -> list[str]:
    """Non-empty lines that aren't comments, each once (case-insensitive), in file order."""
    if not path.exists():
        return []
    seen: set[str] = set()
    lines = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = " ".join(line.split())
        if line and not line.startswith("#") and line.lower() not in seen:
            seen.add(line.lower())
            lines.append(line)
    return lines


def _write_atomic(path: Path, text: str) -> None:
    """Write via a temporary file, so a crash never leaves half a file behind."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


@dataclass
class Learned:
    fixes: list[Fix] = field(default_factory=list)
    new_words: list[str] = field(default_factory=list)                      # added to vocabulary.txt
    new_replacements: list[tuple[str, str]] = field(default_factory=list)   # added to replacements.txt


class Learner:
    """The files in one data directory. Safe to use from several threads."""

    def __init__(self, data_dir: str | Path) -> None:
        self.dir = Path(data_dir)
        self._lock = threading.RLock()

    @property
    def vocabulary_file(self) -> Path:
        return self.dir / "vocabulary.txt"

    @property
    def replacements_file(self) -> Path:
        return self.dir / "replacements.txt"

    # ---------------- vocabulary ----------------
    def vocabulary(self) -> list[str]:
        return _read_lines(self.vocabulary_file)

    def add_words(self, words: Iterable[str]) -> list[str]:
        """Append the words that aren't in the list yet; returns those."""
        with self._lock:
            known = {w.lower() for w in self.vocabulary()}
            added = []
            for word in words:
                word = " ".join(word.split())
                if word and not word.startswith("#") and word.lower() not in known:
                    known.add(word.lower())
                    added.append(word)
            if added:
                old = self.vocabulary_file.read_text(encoding="utf-8") if self.vocabulary_file.exists() else VOCABULARY_HEADER
                _write_atomic(self.vocabulary_file, old + ("" if old.endswith("\n") else "\n") + "\n".join(added) + "\n")
            return added

    def remove_words(self, words: Iterable[str]) -> list[str]:
        """Delete these words (any case); comments and the order of the rest stay."""
        return self._remove_lines(self.vocabulary_file, {" ".join(w.split()).lower() for w in words}, key=lambda line: line)

    # ---------------- replacements ----------------
    def replacements(self) -> list[tuple[str, str]]:
        rules = []
        for line in _read_lines(self.replacements_file):
            heard, arrow, meant = line.partition("=>")
            if arrow and heard.strip() and meant.strip():
                rules.append((heard.strip(), meant.strip()))
        return rules

    def add_replacement(self, heard: str, meant: str) -> bool:
        with self._lock:
            heard, meant = " ".join(heard.split()), " ".join(meant.split())
            if not heard or not meant or any(h.lower() == heard.lower() for h, _ in self.replacements()):
                return False
            old = self.replacements_file.read_text(encoding="utf-8") if self.replacements_file.exists() else REPLACEMENTS_HEADER
            _write_atomic(self.replacements_file, old + ("" if old.endswith("\n") else "\n") + f"{heard} => {meant}\n")
            return True

    def remove_replacements(self, heards: Iterable[str]) -> list[str]:
        return self._remove_lines(self.replacements_file, {" ".join(h.split()).lower() for h in heards},
                                  key=lambda line: line.partition("=>")[0])

    def _remove_lines(self, path: Path, drop: set[str], key) -> list[str]:
        with self._lock:
            if not drop or not path.exists():
                return []
            kept, removed = [], []
            for line in path.read_text(encoding="utf-8").splitlines():
                if not line.startswith("#") and " ".join(key(line).split()).lower() in drop:
                    removed.append(line.strip())
                else:
                    kept.append(line)
            if removed:
                _write_atomic(path, "\n".join(kept) + "\n")
            return removed

    # ---------------- using and learning ----------------
    def apply(self, text: str) -> str:
        """A transcript, with the replacements applied and distinctive vocabulary terms in
        the right case."""
        for heard, meant in sorted(self.replacements(), key=lambda r: len(r[0]), reverse=True):
            text = _phrase_pattern(heard).sub(lambda _m, meant=meant: meant, text)
        for term in self.vocabulary():
            if _distinctive(term):
                text = _phrase_pattern(term).sub(lambda _m, term=term: term, text)
        return text

    def learn(self, original: str, corrected: str, note_id: str | None = None) -> Learned:
        """Remember a correction of `original` (what the server returned) to `corrected`."""
        with self._lock:
            fixes = find_fixes(original, corrected)
            state = self._state()
            result = Learned(fixes=fixes)
            for fix in fixes:
                if not fix.name_like:
                    continue
                result.new_words += self.add_words([fix.meant])
                key = f"{fix.heard.lower()}\t{fix.meant}"
                state["seen"][key] = state["seen"].get(key, 0) + 1
                # "promoted" also remembers rules you deleted, so they don't come back.
                if state["seen"][key] >= LEARN_AFTER and key not in state["promoted"]:
                    state["promoted"].append(key)
                    if self.add_replacement(fix.heard.lower(), fix.meant):
                        result.new_replacements.append((fix.heard.lower(), fix.meant))
            if fixes:
                _write_atomic(self.dir / "learned.json", json.dumps(state, indent=1, ensure_ascii=False) + "\n")
            entry = {"at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "note_id": note_id,
                     "original": original, "corrected": corrected,
                     "fixes": [{"heard": f.heard, "meant": f.meant, "learned": f.name_like} for f in fixes]}
            self.dir.mkdir(parents=True, exist_ok=True)
            with open(self.dir / "corrections.jsonl", "a", encoding="utf-8") as log_file:
                log_file.write(json.dumps(entry, ensure_ascii=False) + "\n")
            if note_id:
                self._mark_corrected(note_id, corrected)
            return result

    def _state(self) -> dict:
        path = self.dir / "learned.json"
        state = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        return {"seen": dict(state.get("seen", {})), "promoted": list(state.get("promoted", []))}

    # ---------------- kept notes (DICTATION_KEEP_AUDIO=1) ----------------
    def keep_note(self, note_id: str, audio: bytes, suffix: str, info: dict) -> Path:
        """Keep a note's audio and its transcript: notes/<day>/<id>.wav + <id>.json."""
        if not NOTE_ID.fullmatch(note_id):
            raise ValueError(f"not a note id: {note_id!r}")
        day = str(info.get("recorded_at") or info.get("received_at") or "")[:10] or "undated"
        folder = self.dir / "notes" / re.sub(r"[^0-9-]", "", day)
        folder.mkdir(parents=True, exist_ok=True)
        (folder / f"{note_id}{suffix}").write_bytes(audio)
        _write_atomic(folder / f"{note_id}.json", json.dumps(info, indent=1, ensure_ascii=False) + "\n")
        return folder / f"{note_id}{suffix}"

    def _mark_corrected(self, note_id: str, corrected: str) -> None:
        if not NOTE_ID.fullmatch(note_id):
            return
        for path in self.dir.glob(f"notes/*/{note_id}.json"):
            info = json.loads(path.read_text(encoding="utf-8"))
            info["corrected"] = corrected
            _write_atomic(path, json.dumps(info, indent=1, ensure_ascii=False) + "\n")
