"""Tests for src/words.py: word timings from Kokoro's own per-phoneme timings.

Run with:  python3 tests/test_words.py   (or pytest)
Field names: s/e = start/end in ms, a/b = character range in the line.
The fixture holds real Kokoro output (timings and each word's own phonemes).
"""
import json
import os
import sys
from collections import namedtuple

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "src"))
from words import WORDS_MIN_CONFIDENCE, _tokens, byte_ranges, empty_result, player_rows, words_from_timings  # noqa: E402

Timing = namedtuple("Timing", "phoneme start end")
CASES = {c["text"]: c for c in json.load(open(os.path.join(HERE, "fixtures", "timings.json"), encoding="utf-8"))}


def run(text):
    case = CASES[text]
    timings = [Timing(*t) for t in case["timings"]]
    return words_from_timings(text, timings, lambda ws: [case["phonemes"][w] for w in ws])


def lit(text, result):
    return [(text[w["a"]:w["b"]], w["s"]) for w in result["words"]]


def test_tokens_trim_punctuation_in_any_script():
    t = "“Hello,” she said — fine. नमस्ते, आप। こんにちは、元気。"
    assert [t[a:b] for a, b in _tokens(t)] == ["Hello", "she", "said", "fine", "नमस्ते", "आप", "こんにちは", "元気"]


def test_words_run_together_by_espeak_still_get_their_own_times():
    # In a sentence espeak says "in the" as one word (ɪnðə); each still lights up.
    text = "Nobody in the village remembered who had built it, or why it ran inland."
    r = run(text)
    words = lit(text, r)
    assert [w for w, _ in words] == text.rstrip(".").replace(",", "").split()
    assert r["confidence"] >= 0.95
    starts = [s for _, s in words]
    assert starts == sorted(starts) and len(set(starts)) == len(starts)


def test_numbers_and_hyphens():
    text = "In 1999, the worn-out explorer walked sixty miles to Igdrasil."
    words = dict(lit(text, run(text)))
    assert "1999" in words and "worn-out" in words and "Igdrasil" in words
    assert words["1999"] < words["the"] < words["worn-out"] < words["explorer"]


def test_abbreviations_currency_and_ordinals():
    text = "Dr. Smith paid $42.50 for 3 books on 5th Avenue."
    r = run(text)
    assert [w for w, _ in lit(text, r)] == ["Dr", "Smith", "paid", "$42.50", "for", "3", "books", "on", "5th", "Avenue"]
    assert r["confidence"] >= 0.95


def test_other_scripts():
    hi = "नमस्ते, आप कैसे हैं? मैं ठीक हूँ।"
    assert [w for w, _ in lit(hi, run(hi))] == ["नमस्ते", "आप", "कैसे", "हैं", "मैं", "ठीक", "हूँ"]
    ja = "こんにちは、元気ですか。"
    assert [w for w, _ in lit(ja, run(ja))] == ["こんにちは", "元気ですか"]


def test_windows_in_order_never_overlap_never_empty():
    for text in CASES:
        prev = -1
        for w in run(text)["words"]:
            assert w["e"] > w["s"] >= prev
            prev = w["e"]


def test_nothing_spoken_or_nothing_written():
    assert words_from_timings("", [Timing("a", 0, 1)], lambda ws: ws) == empty_result("kokoro")
    assert words_from_timings("Hello.", [], lambda ws: ["həlˈoʊ"]) == empty_result("kokoro")


def test_unrelated_phonemes_score_low():
    timings = [Timing(c, i / 10, (i + 1) / 10) for i, c in enumerate("zzzz qqqq")]
    r = words_from_timings("Hello there.", timings, lambda ws: ["həlˈoʊ", "ðˈɛɹ"])
    assert r["confidence"] < WORDS_MIN_CONFIDENCE


def test_player_rows_light_a_confident_line_and_nothing_else():
    text = "Nobody in the village remembered who had built it, or why it ran inland."
    good = run(text)
    rows = player_rows(text, good).splitlines()
    assert rows[0].startswith("confidence\t") and len(rows) == len(good["words"]) + 1
    s, e, a, b = rows[1].split("\t")
    assert text.encode()[int(a):int(b)].decode() == "Nobody"
    poor = {**good, "confidence": WORDS_MIN_CONFIDENCE - 0.01}
    assert player_rows(text, poor).splitlines() == [f"confidence\t{poor['confidence']}"]


def test_byte_ranges_follow_utf8():
    text = "नमस्ते, आप कैसे हैं? मैं ठीक हूँ।"
    r = run(text)
    raw = text.encode("utf-8")
    for (a, b), w in zip(byte_ranges(text, r["words"]), r["words"]):
        assert raw[a:b].decode("utf-8") == text[w["a"]:w["b"]]


if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print(f"ok    {name}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL  {name}: {e}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
