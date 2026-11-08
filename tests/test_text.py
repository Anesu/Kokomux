"""Tests for preparing text to be spoken (src/engine.py): tidying it, and
splitting it into parts with the right pauses between them.

Run with:  python3 tests/test_text.py   (or pytest). The audio tests need numpy.
"""
import os
import sys

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "src"))
from engine import MAX_CHUNK, PAUSE, edges, normalise, roman, sentences, silence, split_text  # noqa: E402


def test_roman_numerals():
    assert [roman(s) for s in ("I", "IV", "IX", "XIV", "XL", "MMXXIV", "IIII", "VX", "")] == \
        [1, 4, 9, 14, 40, 2024, None, None, None]


def test_headings():
    assert normalise("CHAPTER IV: THE LONG ROAD") == "Chapter 4: The Long Road"
    assert normalise("Chapter XII") == "Chapter 12"
    assert normalise("Act II, Scene III") == "Act 2, Scene 3"
    assert normalise("IX.") == "9."
    assert normalise("THE END") == "The End"
    assert normalise("Chapitre III", "fr-fr") == "Chapitre 3"


def test_ordinary_sentences_are_left_alone():
    for t in ("I went home.", "the part I liked best", "Henry VIII was king.", "NASA launched it.",
              "She paid $42.50 for 3 books.", "It's 3:30 now.", "Isn't it?", "Mix it well."):
        assert normalise(t) == t, t


def test_marks_for_the_eye():
    assert normalise("The map was ﬁne.² It works.[3][12–14]") == "The map was fine. It works."
    assert normalise("See https://www.example.com/a/b?x=1. Or www.kokomux.org.") == "See example.com. Or kokomux.org."
    assert normalise("## A heading\n- one\n* two\n> said") == "A heading\none\ntwo\nsaid"
    assert normalise("It was **very** _quiet_, 2*3 is 6.") == "It was very quiet, 2*3 is 6."
    assert normalise("Wait — no -- yes – fine") == "Wait, no, yes, fine"
    assert normalise("From 1990–1995!!!") == "From 1990 to 1995!"
    assert normalise("soft­hyphen and zero​width") == "softhyphen and zerowidth"


def test_english_abbreviations_and_units():
    assert normalise("Dr. Smith met Mrs. Jones on St. Patrick's day at 5 p.m.") == \
        "Doctor Smith met Missus Jones on Saint Patrick's day at 5 PM."
    assert normalise("At 9 a.m. we left, at 5 p.m. Bob came.") == "At 9 AM we left, at 5 PM. Bob came."
    assert normalise("Tools, e.g. a saw, i.e. sharp ones, etc. All set.") == \
        "Tools, for example a saw, that is sharp ones, etcetera. All set."
    assert normalise("See Fig. 3 on p. 12, No. 5 vs. No. 6.") == "See figure 3 on page 12, number 5 versus number 6."
    assert normalise("It was 1 km away, 12 km at 30 mph, 21 °C, 5 kg & 2 ft.") == \
        "It was 1 kilometer away, 12 kilometers at 30 miles per hour, 21 degrees Celsius, 5 kilograms and 2 feet."
    assert normalise("12 km", "en-gb") == "12 kilometres"
    assert normalise("Elm Dr. was quiet.") == "Elm Dr. was quiet."      # not before a name


def test_english_rules_only_in_english():
    t = "M. Dupont marche 5 km avec le Dr. Martin."
    assert normalise(t, "fr-fr") == t


def test_paragraphs_survive():
    assert normalise("One.\n\nTwo.").split("\n") == ["One.", "", "Two."]


def test_sentences_keep_abbreviations_initials_and_quotes():
    assert sentences('"Stop!" she cried. "Now." He did.') == ['"Stop!" she cried.', '"Now."', "He did."]
    assert sentences("Then J. R. R. Tolkien wrote. Mme. Blanc agreed.") == \
        ["Then J. R. R. Tolkien wrote.", "Mme. Blanc agreed."]
    assert sentences("It was approx. five. Then six.") == ["It was approx. five.", "Then six."]


def test_parts_end_at_sentences_and_paragraphs():
    para = " ".join(f"Sentence number {n} is here and it carries on a little." for n in range(1, 21))
    parts = split_text("A short start. " + para + "\nA new paragraph.")
    texts = [t for t, _ in parts]
    assert texts[0] == "A short start."                          # quick to start playing
    assert all(len(t) <= MAX_CHUNK for t in texts)
    assert all(t.endswith(".") for t in texts)                   # whole sentences
    assert texts[-1] == "A new paragraph."                       # never across a paragraph
    pauses = [p for _, p in parts]
    assert pauses[-1] == PAUSE["paragraph"] and pauses[-2] == PAUSE["paragraph"]
    assert set(pauses[:-2]) == {PAUSE["sentence"]}
    assert " ".join(texts[:-1]) == "A short start. " + para      # nothing lost


def test_a_very_long_sentence_is_cut_at_clauses():
    sent = ", ".join(f"clause {n} goes on for a while" for n in range(40)) + "."
    parts = split_text(sent)
    assert len(parts) > 1 and all(len(t) <= MAX_CHUNK for t, _ in parts)
    assert " ".join(t for t, _ in parts) == sent


def test_pauses_follow_the_golden_ratio():
    phi = (1 + 5 ** 0.5) / 2
    assert abs(PAUSE["paragraph"] / PAUSE["sentence"] - phi ** 2) < 0.03
    assert abs(PAUSE["chapter"] / PAUSE["sentence"] - phi ** 3) < 0.03


def test_edges_fade_and_silence():
    try:
        import numpy as np
    except ImportError:
        return                                                    # (needs numpy)
    audio = np.ones(24000, dtype=np.float32)
    out = edges(audio, 24000)
    assert out[0] == 0 and out[-1] == 0 and out[12000] == 1     # only the ends are touched
    assert np.all(np.diff(out[:96]) >= 0) and audio[0] == 1      # a ramp, on a copy
    assert len(silence(0.25, 24000)) == 6000 and len(silence(-0.1, 24000)) == 0


if __name__ == "__main__":
    fails = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("ok  ", name)
            except AssertionError as e:
                fails += 1
                print("FAIL", name, e)
    sys.exit(1 if fails else 0)
