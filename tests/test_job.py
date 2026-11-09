"""Tests for running a job (src/engine.py run_job), with a stand-in for Kokoro.

Run with:  python3 tests/test_job.py   (or pytest). Needs numpy and soundfile;
skipped without them. The stand-in makes a tone per character, so lengths can
be checked: the pauses between parts, the edges, blended voices, misaki for
English, and bookmarks from an older version's parts.
"""
import os
import secrets
import shutil
import sys

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "src"))
import engine  # noqa: E402

try:
    import numpy as np
    import soundfile as sf
except ImportError:
    np = sf = None

RATE = 24000
PER_CHAR = 240           # samples of "speech" per character (10 ms)
STYLES = {"af_heart": 1.0, "af_bella": 3.0, "bf_emma": 5.0}


class FakeKokoro:
    """Speaks a steady tone, PER_CHAR samples a character, and remembers what it was given."""
    has_timings = False

    def __init__(self, model):
        self.model = model
        self.calls = []

    def get_voices(self):
        return list(STYLES)

    def get_voice_style(self, name):
        return np.full((510, 1, 256), STYLES[name], dtype=np.float32)

    def create(self, text, voice, speed=1.0, lang="en-us", is_phonemes=False):
        self.calls.append((text, voice, lang) + (("phonemes",) if is_phonemes else ()))
        return np.full(len(text) * PER_CHAR, 0.5, dtype=np.float32), RATE


def run(text, *, stream=True, out=None, g2p=None, jobdir=None, files=None, **job):
    """Run a job with FakeKokoro; return (jobdir, fake). FILES: more files for the job folder."""
    jobdir = jobdir or f"/tmp/kokoro.{secrets.token_hex(6)}"          # as kokoro names them (the engine checks)
    os.mkdir(jobdir)
    for name, content in {"in.txt": text, **(files or {})}.items():
        with open(os.path.join(jobdir, name), "w") as f:
            f.write(content)
    job.setdefault("voice", "af_heart")
    if stream:
        job["stream"] = "1"
    if out:
        job["out"] = out
    with open(os.path.join(jobdir, "job"), "w") as f:
        f.write("".join(f"{k}={v}\n" for k, v in job.items()))
    made = {}

    def fake_tts():
        made.setdefault(engine.MODEL, FakeKokoro(engine.MODEL))
        return made[engine.MODEL]

    # misaki for English: a stand-in, or none (text as given) to check the rest
    old = engine.tts, dict(engine._g2p)
    engine.tts = fake_tts
    engine._g2p.clear()
    engine._g2p.update({False: g2p, True: g2p})
    try:
        engine.run_job(os.path.join(jobdir, "job"))
    finally:
        engine.tts = old[0]
        engine._g2p.clear()
        engine._g2p.update(old[1])
    return jobdir, made


def wav(jobdir, i):
    audio, rate = sf.read(os.path.join(jobdir, "stream", f"{i:04d}.wav"), dtype="float32")
    assert rate == RATE
    return audio


def speech_and_pause(audio):
    """How many samples of speech, then of silence at the end."""
    loud = np.nonzero(np.abs(audio) > 1e-6)[0]
    return loud[-1] + 1, len(audio) - loud[-1] - 1


def needs_audio(fn):
    def wrapper():
        if np is None:
            return                                         # (needs numpy and soundfile)
        fn()
    wrapper.__name__ = fn.__name__
    return wrapper


@needs_audio
def test_parts_end_with_the_pause_their_boundary_asks_for():
    para = " ".join(["The road went on and on over the hills."] * 12)
    jobdir, _ = run(f"Hello there. {para}\nThe end.")
    try:
        parts = sorted(f for f in os.listdir(os.path.join(jobdir, "stream")) if f.endswith(".wav"))
        pauses = [speech_and_pause(wav(jobdir, i + 1))[1] / RATE for i in range(len(parts))]
        assert abs(pauses[0] - engine.PAUSE["sentence"]) < 0.002          # mpv plays them back to back
        assert abs(pauses[-2] - engine.PAUSE["paragraph"]) < 0.002
        assert abs(pauses[-1] - engine.PAUSE["chapter"]) < 0.002
    finally:
        shutil.rmtree(jobdir)


@needs_audio
def test_edges_are_softened():
    jobdir, _ = run("Hello there, this is a test.")
    try:
        audio = wav(jobdir, 1)
        assert audio[0] == 0 and 0 < audio[40] < 0.5 and audio[2000] == 0.5
    finally:
        shutil.rmtree(jobdir)


@needs_audio
def test_a_saved_file_has_pauses_between_parts_but_not_after_the_last():
    text = "One sentence here. " + " ".join(["Another sentence follows it."] * 20) + "\nLast line."
    jobdir, made = run(text, stream=False, out="out.wav")
    try:
        audio, _ = sf.read(os.path.join(jobdir, "out.wav"), dtype="float32")
        said = [t for t, _, _ in made[engine.MODEL].calls]
        speech = sum(len(t) for t in said) * PER_CHAR
        parts = engine.split_text(engine.normalise(text))
        gaps = sum(p for _, p in parts[:-1])
        assert abs(len(audio) - speech - gaps * RATE) < len(parts) * 2
        assert speech_and_pause(audio)[1] < RATE * 0.005    # it ends on the last word (and its fade)
    finally:
        shutil.rmtree(jobdir)


@needs_audio
def test_text_is_tidied_before_it_is_spoken():
    jobdir, made = run("Dr. Smith walked 5 km.² See https://example.com/x.")
    try:
        said = [t for t, _, _ in made[engine.MODEL].calls]
        assert " ".join(said) == "Doctor Smith walked 5 kilometers. See example.com."
        with open(os.path.join(jobdir, "stream", "0001.txt")) as f:
            assert f.read() == said[0]                     # the reader shows what's said
    finally:
        shutil.rmtree(jobdir)


@needs_audio
def test_blended_voices():
    jobdir, made = run("Hello.", voice="af_heart:70+bf_emma:30")
    try:
        style = made[engine.MODEL].calls[0][1]
        assert style.shape == (510, 1, 256) and np.allclose(style, 0.7 * 1 + 0.3 * 5)
    finally:
        shutil.rmtree(jobdir)
    jobdir, made = run("Hello.", voice="af_heart+af_bella")        # equal shares
    try:
        assert np.allclose(made[engine.MODEL].calls[0][1], 2.0)
    finally:
        shutil.rmtree(jobdir)
    jobdir, made = run("Hello.", voice="af_bella")                 # one voice: its name
    try:
        assert made[engine.MODEL].calls[0][1] == "af_bella"
    finally:
        shutil.rmtree(jobdir)


@needs_audio
def test_bad_voices_are_refused():
    for spec in ("xx_nobody", "af_heart:0+af_bella:0", "af_heart;rm", "af_heart+"):
        try:
            jobdir, _ = run("Hello.", voice=spec)
        except engine.JobError:
            continue
        shutil.rmtree(jobdir)
        raise AssertionError(f"{spec} was accepted")


@needs_audio
def test_a_bookmark_from_older_parts_lands_in_the_same_place():
    text = " ".join(f"Sentence {n} of the story goes here, and it carries on." for n in range(200))
    parts = len(engine.split_text(engine.normalise(text)))
    jobdir, _ = run(text, start="101", of="200")                   # halfway, in 200 old parts
    try:
        with open(os.path.join(jobdir, "stream", "first")) as f:
            first = int(f.read())
        assert abs(first - (1 + parts // 2)) <= 1
    finally:
        shutil.rmtree(jobdir)


class Token:
    def __init__(self, text, phonemes):
        self.text, self.phonemes = text, phonemes


class FakeMisaki:
    """Reads each word as its own name in capitals, splitting "don't" the way spaCy does."""
    def __call__(self, text):
        toks = []
        for w in text.replace("n't", " n't").split():
            core = w.strip(".,;?!")
            if core:
                toks.append(Token(core, core.upper()))
            for p in w[len(core):] if w.startswith(core) else "":
                toks.append(Token(p, p))
        return " ".join(t.phonemes for t in toks), toks


def test_dictionary_sounds_per_word_follow_the_highlighted_words():
    ps, per = engine.dictionary_phonemes(FakeMisaki(), "Well, don't read it.")
    assert ps.startswith("WELL") and per == ["WELL", "DON'T", "READ", "IT"]


@needs_audio
def test_english_goes_through_misaki_other_languages_through_espeak():
    jobdir, made = run("Well, read it.", g2p=FakeMisaki())
    shutil.rmtree(jobdir)
    assert made[engine.MODEL].calls[0][0] == "WELL , READ IT ." and made[engine.MODEL].calls[0][-1] == "phonemes"
    jobdir, made = run("Bonjour.", g2p=FakeMisaki(), lang="fr-fr")
    shutil.rmtree(jobdir)
    assert made[engine.MODEL].calls[0][0] == "Bonjour." and made[engine.MODEL].calls[0][-1] != "phonemes"


def with_cache(fn):
    """Run fn with the engine's cache in a fresh folder under /tmp (beside the job folders)."""
    def wrapper():
        if np is None:
            return
        old, engine.CACHE_ROOT = engine.CACHE_ROOT, f"/tmp/kokomux-cache-test-{secrets.token_hex(4)}"
        try:
            fn()
        finally:
            shutil.rmtree(engine.CACHE_ROOT, ignore_errors=True)
            engine.CACHE_ROOT = old
    wrapper.__name__ = fn.__name__
    return wrapper


BOOK = "0123456789abcdef"
LONG = " ".join(f"Sentence {n} of the story goes on for a little while." for n in range(30))


@with_cache
def test_parts_are_kept_and_reused():
    jobdir, made = run(LONG, cache=BOOK)
    first = len(made[engine.MODEL].calls)
    stream = os.path.join(jobdir, "stream")
    link = os.path.join(stream, "0001.wav")
    assert os.path.islink(link) and not os.readlink(link).startswith("/")    # relative: works on both sides
    kept = sorted(os.listdir(stream))
    shutil.rmtree(jobdir)
    jobdir, made = run(LONG, cache=BOOK)                                      # again: nothing to make
    try:
        assert first > 1 and made[engine.MODEL].calls == []
        assert sorted(os.listdir(os.path.join(jobdir, "stream"))) == kept
    finally:
        shutil.rmtree(jobdir)
    jobdir, made = run(LONG, cache=BOOK, voice="af_bella")                    # other settings: made afresh
    shutil.rmtree(jobdir)
    assert len(made[engine.MODEL].calls) == first


@with_cache
def test_a_changed_part_is_made_again():
    jobdir, _ = run(LONG, cache=BOOK)
    shutil.rmtree(jobdir)
    jobdir, made = run(LONG.replace("Sentence 0 ", "Line zero "), cache=BOOK)  # only the first part differs
    shutil.rmtree(jobdir)
    calls = made[engine.MODEL].calls
    assert len(calls) == 1 and calls[0][0].startswith("Line zero")


@with_cache
def test_a_file_from_kept_parts_is_the_same():
    jobdir, _ = run(LONG, stream=False, out="out.wav", cache=BOOK)
    a, _ = sf.read(os.path.join(jobdir, "out.wav"), dtype="float32")
    shutil.rmtree(jobdir)
    jobdir, made = run(LONG, stream=False, out="out.wav", cache=BOOK)
    b, _ = sf.read(os.path.join(jobdir, "out.wav"), dtype="float32")
    shutil.rmtree(jobdir)
    assert made[engine.MODEL].calls == []
    assert len(a) == len(b) and np.allclose(a, b, atol=1e-4)


@needs_audio
def test_a_saved_book_records_where_each_chapter_starts():
    jobdir = f"/tmp/kokoro.{secrets.token_hex(6)}"
    os.mkdir(jobdir)
    old = engine.read_source, engine.tts
    made = FakeKokoro(engine.MODEL)
    engine.read_source = lambda d, j: ("A Book", [("One", "First chapter text."), ("Two", "Second chapter, a bit longer.")])
    engine.tts = lambda: made
    engine._g2p.update({False: None, True: None})
    try:
        with open(os.path.join(jobdir, "job"), "w") as f:
            f.write("voice=af_heart\nout=out.wav\n")
        engine.run_job(os.path.join(jobdir, "job"))
        with open(os.path.join(jobdir, "chapters_ms")) as f:
            rows = [line.rstrip("\n").split("\t") for line in f]
        first_len = len("First chapter text.") * PER_CHAR / RATE + engine.PAUSE["chapter"]
        assert rows[0] == ["0", "One"] and rows[1][1] == "Two"
        assert abs(int(rows[1][0]) / 1000 - first_len) < 0.01
    finally:
        engine.read_source, engine.tts = old
        shutil.rmtree(jobdir)


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
