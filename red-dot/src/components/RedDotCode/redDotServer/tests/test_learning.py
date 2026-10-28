"""learning.py: which corrections RedDot learns from, and what it does with them.
Run from redDotServer/:  .venv/bin/python -m pytest tests"""

from __future__ import annotations

import json

import pytest

from learning import LEARN_AFTER, Fix, Learner, find_fixes
from transcriber import echoes_prompt, vocabulary_prompt


def test_misheard_names_are_found_and_count_as_names():
    fixes = find_fixes("Open the bio scope backend and ping Martin.", "Open the BioScope backend and ping Marten.")
    assert fixes == [Fix("bio scope", "BioScope", True), Fix("Martin", "Marten", True)]


def test_a_case_change_inside_a_word_is_a_fix():
    assert find_fixes("check the pyqgis layer", "check the PyQGIS layer") == [Fix("pyqgis", "PyQGIS", True)]


def test_sentence_case_and_punctuation_are_not_fixes():
    assert find_fixes("the creek is low, the water clear", "The creek is low. The water clear!") == []


def test_grammar_fixes_are_found_but_not_name_like():
    assert find_fixes("I think there going to the site", "I think they're going to the site") == [
        Fix("there", "they're", False)]


def test_a_capital_that_only_starts_a_sentence_is_not_a_name():
    assert find_fixes("ok. their going now", "ok. There going now") == [Fix("their", "There", False)]


def test_a_rewrite_is_not_learned_from():
    assert find_fixes("two kookaburras on the fence near site three today",
                      "Weather turned bad, we packed up early and drove back to Canberra") == []


def test_added_or_deleted_words_are_content_edits_not_fixes():
    assert find_fixes("send the map", "send the new map to Marten") == []


def test_learning_adds_words_now_and_replacements_after_repeats(tmp_path):
    learner = Learner(tmp_path)
    first = learner.learn("ping Martin about the bio scope", "ping Marten about the BioScope")
    assert first.new_words == ["Marten", "BioScope"]
    assert first.new_replacements == []
    assert learner.apply("ask Martin") == "ask Martin"     # once is not enough

    for _ in range(LEARN_AFTER - 1):
        again = learner.learn("ping Martin about the bio scope", "ping Marten about the BioScope")
    assert again.new_words == []                           # already known
    assert again.new_replacements == [("martin", "Marten"), ("bio scope", "BioScope")]
    assert learner.apply("Ask martin to open the Bio  Scope app.") == "Ask Marten to open the BioScope app."


def test_grammar_fixes_are_never_promoted(tmp_path):
    learner = Learner(tmp_path)
    for _ in range(LEARN_AFTER + 1):
        learned = learner.learn("I think there going", "I think they're going")
    assert learned.new_words == [] and learned.new_replacements == []
    assert learner.replacements() == [] and learner.vocabulary() == []


def test_a_deleted_replacement_stays_deleted(tmp_path):
    learner = Learner(tmp_path)
    for _ in range(LEARN_AFTER):
        learner.learn("call Martin", "call Marten")
    assert learner.replacements() == [("martin", "Marten")]
    assert learner.remove_replacements(["Martin"]) == ["martin => Marten"]
    for _ in range(LEARN_AFTER):
        learner.learn("call Martin", "call Marten")
    assert learner.replacements() == []


def test_replacements_match_whole_words_only(tmp_path):
    learner = Learner(tmp_path)
    learner.add_replacement("red dot", "RedDot")
    assert learner.apply("the red dot app, not the red dotted line") == "the RedDot app, not the red dotted line"


def test_only_distinctive_vocabulary_fixes_case(tmp_path):
    learner = Learner(tmp_path)
    learner.add_words(["PyQGIS", "Rust", "S3"])
    assert learner.apply("open pyqgis, check the rust on gate s3") == "open PyQGIS, check the rust on gate S3"


def test_vocabulary_edits_keep_comments_and_order(tmp_path):
    learner = Learner(tmp_path)
    assert learner.add_words(["BioScope", "bioscope", "  Wispr   Flow ", "PyQGIS"]) == ["BioScope", "Wispr Flow", "PyQGIS"]
    assert learner.remove_words(["wispr flow"]) == ["Wispr Flow"]
    text = learner.vocabulary_file.read_text()
    assert text.startswith("# Words and phrases")
    assert learner.vocabulary() == ["BioScope", "PyQGIS"]


def test_corrections_are_logged_and_kept_notes_updated(tmp_path):
    learner = Learner(tmp_path)
    learner.keep_note("abc-123", b"RIFF....", ".wav", {"recorded_at": "2026-10-28T09:15:00+11:00", "text": "ping Martin"})
    learner.learn("ping Martin", "ping Marten", note_id="abc-123")
    entry = json.loads((tmp_path / "corrections.jsonl").read_text().splitlines()[-1])
    assert entry["note_id"] == "abc-123" and entry["fixes"] == [{"heard": "Martin", "meant": "Marten", "learned": True}]
    kept = json.loads((tmp_path / "notes" / "2026-10-28" / "abc-123.json").read_text())
    assert kept["corrected"] == "ping Marten"


def test_note_ids_cannot_escape_the_data_directory(tmp_path):
    learner = Learner(tmp_path)
    with pytest.raises(ValueError):
        learner.keep_note("../../evil", b"x", ".wav", {})
    learner.learn("ping Martin", "ping Marten", note_id="../*")   # ignored, no error


def test_vocabulary_prompt_fits_and_lists_words_in_order():
    assert vocabulary_prompt(["BioScope", "PyQGIS"]) == "BioScope, PyQGIS."
    assert vocabulary_prompt(["BioScope"], "Field notes.") == "Field notes. BioScope."
    long = vocabulary_prompt([f"Term{i}" for i in range(500)])
    assert len(long) <= 600 and long.startswith("Term0, Term1")


def test_a_transcript_made_only_of_prompt_words_is_an_echo():
    prompt = vocabulary_prompt(["BioScope", "PyQGIS", "Marten", "RedDot"])
    assert echoes_prompt("PyQGIS, Marten, RedDot.", prompt)
    assert not echoes_prompt("Send the PyQGIS map to Marten.", prompt)
    assert not echoes_prompt("RedDot", prompt)   # a single word can be a real note
