"""Tests for src/words.py, ported from light-novel-studio's words.test.ts.

Run with:  python3 tests/test_words.py   (or pytest)
Field names follow kokomux: s/e = start/end in ms, a/b = character range.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
from words import (WORDS_MIN_CONFIDENCE, align_pairs, byte_ranges, empty_result, map_words,  # noqa: E402
                   player_rows, quiet_cuts, supported, word_key, word_tokens)


def asr(*words):
    """A recogniser result, written as (word, start s, end s[, probability])."""
    return [{"word": w[0], "start": w[1], "end": w[2], "probability": w[3] if len(w) > 3 else 0.95} for w in words]


def mapped(display, words):
    return map_words(display, words, "whistle-test")


def test_tokens_carry_their_own_ranges():
    text = "<shout>Max! Lay low in trenches you bastard, or else you are getting killed!"
    tokens = word_tokens(text)
    assert [t[2] for t in tokens] == ["Max", "Lay", "low", "in", "trenches", "you", "bastard", "or", "else",
                                      "you", "are", "getting", "killed"]
    for a, b, w in tokens:
        assert text[a:b] == w
    assert tokens[0][0] == 7   # past "<shout>"


def test_hyphen_is_two_words_apostrophe_is_not():
    assert [t[2] for t in word_tokens("A worn-out army, don’t you think?")] == \
        ["A", "worn", "out", "army", "don’t", "you", "think"]


def test_numbers_match_their_spoken_form():
    assert word_key("60") == word_key("sixty")
    assert word_key("60.") == word_key("sixty")
    assert word_key("3rd") == word_key("third")
    assert word_key("five") != word_key("four")
    assert word_key("Max!") == "max"


def test_pairing_preferred_and_drop_resyncs():
    pairs = align_pairs(["worn", "army", "uniform", "pressed"], ["wornout", "army", "uniform", "pressed"])
    assert pairs == [0, 1, 2, 3]


def test_inserted_spoken_word_is_skipped():
    assert align_pairs(["the", "trench", "was", "deep"], ["the", "uh", "trench", "was", "deep"]) == [0, 2, 3, 4]


def test_times_in_ms_inside_the_line():
    m = mapped("<shout>Max! Lay low", asr(("Max,", 0.4, 0.72), ("lay", 1.28, 1.44), ("low", 1.44, 1.68)))
    assert [(w["s"], w["e"]) for w in m["words"]] == [(400, 720), (1280, 1440), (1440, 1680)]
    assert m["engine"] == "whistle-test"
    assert m["confidence"] > 0.9


def test_hyphenated_compound_shares_one_spoken_word():
    m = mapped("A worn-out army", asr(("A", 0.0, 0.2), ("wornout", 0.2, 0.9), ("army", 1.0, 1.4)))
    a, worn, out, army = m["words"]
    assert (a["s"], a["e"]) == (0, 200)
    assert abs(worn["s"] - 200) < 1
    assert abs(out["e"] - 900) < 1
    assert out["s"] > worn["s"]
    assert (army["s"], army["e"]) == (1000, 1400)


def test_respelled_name_takes_the_whole_run():
    m = mapped("Igdrasil rose.", asr(("IG", 0.0, 0.3), ("druh", 0.3, 0.5), ("sil", 0.5, 0.9), ("rose", 1.0, 1.3)))
    assert len(m["words"]) == 2
    assert (m["words"][0]["a"], m["words"][0]["b"]) == (0, 8)
    assert (m["words"][0]["s"], m["words"][0]["e"]) == (0, 900)
    assert (m["words"][1]["s"], m["words"][1]["e"]) == (1000, 1300)


def test_unrecognised_word_spreads_across_the_silence():
    one, two, three = mapped("one two three", asr(("one", 0.0, 0.4), ("three", 1.0, 1.4)))["words"]
    assert (one["s"], one["e"]) == (0, 400)
    assert two["s"] >= 400 and two["e"] <= 1000
    assert (three["s"], three["e"]) == (1000, 1400)
    assert two["p"] == 0


def test_windows_in_order_never_overlap_never_empty():
    m = mapped("you are getting killed", asr(("you", 2.32, 2.32), ("are", 2.32, 2.32),
                                             ("getting", 3.52, 3.76), ("killed", 3.76, 4.24)))
    previous_end = -1
    for w in m["words"]:
        assert w["e"] > w["s"]
        assert w["s"] >= previous_end
        previous_end = w["e"]


def test_disagreeing_transcript_is_refused():
    m = mapped("Leonel shouted as he wiped his sweat off his forehead.",
               asr(("something", 0, 1), ("entirely", 1, 2), ("else", 2, 3), ("here", 3, 4)))
    assert m is None or m["confidence"] < WORDS_MIN_CONFIDENCE


def test_nothing_recognised_or_written_is_no_mapping():
    assert mapped("Words.", []) is None
    assert mapped("", asr(("words", 0, 1))) is None


def test_gap_with_no_room_is_still_filled():
    m = mapped("one two three four", asr(("one", 0.0, 0.5), ("four", 0.4, 0.9)))
    assert len(m["words"]) == 4
    for w in m["words"]:
        assert w["e"] > w["s"]


def test_real_whistle_output_for_a_kokoro_line():
    # Whistle's actual transcript of Kokoro reading this line (cactus-compute 4.1.0).
    text = "In 1999, the worn-out explorer walked sixty miles to Igdrasil. She was tired, but happy."
    words = [("In", 0.08, 0.24, .996), ("1999,", 0.24, 1.2, .853), ("the", 1.68, 1.76, .999),
             ("Warnout", 1.76, 2.24, .691), ("Explorer", 2.24, 2.88, .933), ("walked", 2.88, 3.2, .999),
             ("60", 3.2, 3.52, .985), ("miles", 3.52, 3.84, .998), ("to", 3.84, 4.16, 1.0),
             ("Igg", 4.16, 4.32, .654), ("Dressel.", 4.32, 4.72, .688), ("She", 5.2, 5.36, 1.0),
             ("was", 5.36, 5.6, 1.0), ("tired,", 5.6, 6.0, 1.0), ("but", 6.24, 6.4, 1.0), ("happy.", 6.4, 6.72, 1.0)]
    m = mapped(text, asr(*words))
    by_word = {text[w["a"]:w["b"]]: w for w in m["words"]}
    assert by_word["sixty"]["s"] == 3200                                       # "60" <-> "sixty"
    assert by_word["Igdrasil"]["s"] == 4160 and by_word["Igdrasil"]["e"] == 4720   # took "Igg Dressel"
    assert by_word["worn"]["s"] == 1760 and by_word["out"]["e"] == 2240        # one spoken "Warnout"
    assert m["confidence"] >= WORDS_MIN_CONFIDENCE


def test_byte_ranges_follow_utf8():
    text = "Café au lait — très bien."
    m = mapped(text, asr(("Café", 0, .3), ("au", .3, .4), ("lait", .4, .7), ("très", .8, 1), ("bien", 1, 1.3)))
    raw = text.encode("utf-8")
    for (a, b), w in zip(byte_ranges(text, m["words"]), m["words"]):
        assert raw[a:b].decode("utf-8") == text[w["a"]:w["b"]]


def test_supported_languages():
    assert supported("en-us") == "en" and supported("en-gb") == "en"
    assert supported("fr-fr") == "fr" and supported("es") == "es"
    assert supported("pt-br") is None and supported("hi") is None and supported("") is None


def test_player_rows_light_a_confident_line_and_nothing_else():
    text = "Max! Lay low"
    good = mapped(text, asr(("Max", 0.4, 0.72), ("lay", 1.28, 1.44), ("low", 1.44, 1.68)))
    rows = player_rows(text, good).splitlines()
    assert rows[0].startswith("confidence\t") and len(rows) == 4
    assert rows[1].split("\t") == ["400", "720", "0", "3"]
    poor = {**good, "confidence": WORDS_MIN_CONFIDENCE - 0.01}
    assert player_rows(text, poor).splitlines() == [f"confidence\t{poor['confidence']}"]
    assert player_rows(text, empty_result("none")) == "confidence\t0\n"


def test_quiet_cuts_split_long_audio_at_a_pause():
    import numpy as np
    rate = 16000
    rng = np.random.default_rng(1)
    # 150 s of "speech" (noise) with a 0.4 s pause every 7 s.
    x = rng.normal(0, 0.3, rate * 150).astype(np.float32)
    pauses = list(range(7, 150, 7))
    for p in pauses:
        x[p * rate:int((p + 0.4) * rate)] = 0
    cuts = quiet_cuts(x, longest=60.0)
    assert cuts[0] == 0 and cuts[-1] == len(x) and len(cuts) == 4    # three pieces
    for a, b in zip(cuts, cuts[1:]):
        assert b - a <= 60 * rate                                     # each fits one pass
    for c in cuts[1:-1]:
        assert any(p * rate <= c <= (p + 0.4) * rate for p in pauses)  # and is cut in a pause
    assert quiet_cuts(x[:rate * 30]) == [0, rate * 30]               # short audio: one piece


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
