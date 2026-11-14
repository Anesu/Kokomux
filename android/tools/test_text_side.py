#!/usr/bin/env python3
"""The app's text side (app/src/main/python/kokomux_android.py) on this machine.

Run with a Python that has app/requirements.txt installed (CI's mirror of the
phone's packages):  python test_text_side.py

- tests/test_novel.py again, with the app's fetch (urllib: Android has no curl)
- read(): a PDF, an EPUB, a text file and web pages served here, split as
  engine.py's run_job splits them
- speakable() and word_rows(): misaki for English, espeak for other
  languages, and word timings as words.py makes them
"""
import json
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path[:0] = [os.path.join(ROOT, "android", "app", "src", "main", "python"), os.path.join(ROOT, "src"),
                os.path.join(ROOT, "tests")]
import engine  # noqa: E402
import kokomux_android as app  # noqa: E402
import test_books  # noqa: E402
import test_novel  # noqa: E402
import test_web  # noqa: E402
import words  # noqa: E402


def vocab():
    """Kokoro's sounds, from the app's own table (engine/…/Vocab.kt)."""
    kt = open(os.path.join(ROOT, "android", "engine", "src", "main", "kotlin", "kokomux", "engine", "Vocab.kt"),
              encoding="utf-8").read()
    symbols = re.search(r'SYMBOLS = "(.*)"', kt).group(1)
    return re.sub(r"\\u([0-9a-f]{4})", lambda m: chr(int(m.group(1), 16)), symbols)


def setup():
    import espeakng_loader
    tmp = tempfile.mkdtemp(prefix="kokomux-test.")
    app.setup(espeakng_loader.get_library_path(), espeakng_loader.get_data_path(), tmp, vocab())


def test_the_novel_tests_pass_with_the_apps_fetch():
    assert engine.fetch is app.fetch
    failed = []
    for name, fn in sorted(vars(test_novel).items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
            except AssertionError as e:
                failed.append(f"{name}: {e}")
    assert not failed, failed


def test_a_pdf_is_one_chapter_with_a_chapters_pause_at_the_end():
    book = json.loads(app.read(os.path.join(ROOT, "tests", "fixtures", "caravan.pdf"), "caravan", "en-us"))
    assert book["title"] == "The Caravan", book["title"]
    assert book["chapters"] == [[1, "The Caravan"]]
    assert book["parts"][-1][1] == engine.PAUSE["chapter"]
    assert all(p == engine.PAUSE["paragraph"] or p == engine.PAUSE["sentence"] for _, p in book["parts"][:-1])
    assert "traveller" in " ".join(t for t, _ in book["parts"])


def test_an_epub_keeps_its_chapters():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "book.epub")
        test_books.make_epub(path)
        book = json.loads(app.read(path, "book", "en-gb"))
    assert book["title"] == "The Long Road"
    assert [t for _, t in book["chapters"]] == ["Chapter One", "Chapter Two"]
    second = book["chapters"][1][0]
    assert book["parts"][second - 2][1] == engine.PAUSE["chapter"]      # the end of chapter one
    assert book["parts"][second - 1][0].startswith("Chapter Two")


def test_a_text_file_is_tidied_and_split_as_run_job_does():
    text = "Dr. Smith arrived at 5 p.m. The road was long.\nCHAPTER IV\nIt was late."
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "notes.txt")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        book = json.loads(app.read(path, "notes", "en-us"))
    want = engine.split_text(engine.normalise(text, "en-us"))
    want[-1] = (want[-1][0], engine.PAUSE["chapter"])
    assert book["title"] == "notes" and book["chapters"] == [[1, "notes"]]
    assert [tuple(p) for p in book["parts"]] == want
    assert book["parts"][0][0] == "Doctor Smith arrived at 5 PM."


def test_a_web_page_and_a_missing_one():
    base = test_novel.site().split("/novel/")[0]
    chapter = json.loads(app.read(f"{base}/novel/the-long-road/chapter-2", "", "en-us"))
    assert chapter["chapters"][0][1].startswith("Chapter 2"), chapter["chapters"]
    gone = json.loads(app.read(f"{base}/novel/the-long-road/chapter-9", "", "en-us"))
    assert gone == {"error": f"There's no page at {base}/novel/the-long-road/chapter-9"}, gone


def test_english_sounds_are_misakis_with_each_words_own():
    text = "I read the book yesterday."
    got = json.loads(app.speakable(text, "en-us"))
    ps, per = engine.dictionary_phonemes(engine.g2p_for("en-us"), text)
    assert got == {"ps": ps, "per": per}


def test_other_languages_go_to_espeak():
    got = json.loads(app.speakable("Hola, ¿cómo estás?", "es"))
    assert got["per"] is None and got["ps"] and all(ch in vocab() for ch in got["ps"])


def test_word_timings_as_words_py_makes_them():
    golden = json.load(open(os.path.join(HERE, "golden.json"), encoding="utf-8"))[0]
    text, lang = golden["text"], golden["lang"]
    per = json.loads(app.speakable(text, lang))["per"]
    phonemes = "".join(t[0] for t in golden["timings"])
    starts = [t[1] for t in golden["timings"]]
    ends = [t[2] for t in golden["timings"]]
    rows = app.word_rows(text, lang, json.dumps(per), phonemes, starts, ends)
    want = words.player_rows(text, words.words_from_timings(
        text, [app.Timing(*t) for t in golden["timings"]], lambda ws: per))
    assert rows == want
    assert rows.startswith("confidence\t") and len(rows.splitlines()) == len(words._tokens(text)) + 1


def test_the_web_page_tests_pass_with_these_packages():
    failed = []
    for name, fn in sorted(vars(test_web).items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
            except AssertionError as e:
                failed.append(f"{name}: {e}")
    assert not failed, failed


if __name__ == "__main__":
    setup()
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
