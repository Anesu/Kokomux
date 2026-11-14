#!/usr/bin/env python3
"""kokomux engine. Runs inside the "kokoro" Debian container.

The `kokoro` command in Termux writes a job folder under /tmp/kokoro.XXXXXX
(Termux's $PREFIX/tmp, shared with the container) and runs it either one-off
or through the background server:

  engine.py job /tmp/kokoro.XXXXXX/job      run one job
  engine.py serve [--port N] [--idle SECS]  keep the model loaded, serve jobs
  engine.py voices                          list voice names
  engine.py download                        fetch the model and voices (resumable)
  engine.py bench                           time Kokoro each way it can run here
  (job and serve also take --threads N --block N: how onnxruntime runs)

A job folder holds `job` and `in.txt` (the text, unless url is set). `job` is
key=value lines:

  voice   a voice, or a blend: af_heart:70+bf_emma:30     speed  0.5 to 2.0
  lang    en-us, en-gb, es, fr-fr, it, pt-br, hi, ja, cmn  name   what it's called
  url     read this page instead of in.txt               out    out.<ext>: save a file
  stream  1: one part at a time, for listening           words  1: word timings too
  start   the part to begin at                          of     the bookmark's count of parts
  cache   the book's id: keep parts in /tmp/kokomux-cache/<id>/, reuse them
  meta    1: write the EPUB's author and cover (for an audiobook)

What it writes into the folder: `title`, `progress` ("part total"), `chapters`
("first-part<TAB>title" per line), `plan` (every part, one line each:
"characters-up-to-its-end<TAB>its text"), `rate` ("characters-a-second speed",
from the parts made so far), `result` ("seconds-of-audio seconds-taken
parts"), `out.<ext>` and `chapters_ms` when saving, and with stream=1
`stream/NNNN.txt` (the part's text), `stream/NNNN.words` (its word timings),
`stream/NNNN.wav` (its audio, with the pause after it; written last), `stream/first`
(where it began) and `stream/end` once everything is made. With a cache, the
stream files are relative links into it.

While listening (stream=1, no out), the engine makes only AHEAD seconds of
speech ahead of the part the player is on (the player writes its number into
`at`), so a long book doesn't keep the phone's CPU busy for hours; parts
already kept in the cache are linked at once.

A web novel's chapter (url, with stream=1) is read on from chapter to chapter:
the engine fetches the next one when the player reaches the last one made (the
player writes the part it's on into `at`). It writes `novel` (title=, url=,
prev=: the chapter before this listen's first) and `serial` ("first-part<TAB>
number<TAB>address", in step with `chapters`). Parts are numbered across the
whole listen; a job that picks up mid-listen says where its chapter starts
(base=N) and has the earlier `chapters` and `serial` lines in its folder.
skip=1 starts at the chapter after the one at url.
"""
import argparse
import hmac
import os
import re
import secrets
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile

APP = os.path.dirname(os.path.abspath(__file__))
if APP not in sys.path:
    sys.path.insert(0, APP)   # words.py, text.py and sources.py live beside this file
from sources import (USER_AGENT, JobError, epub_meta, log, novel_next, read_page,  # noqa: E402
                     read_source)
from text import PAUSE, normalise, split_text  # noqa: E402

MODELS = os.path.join(APP, "models")
# Kokoro 82M v1.0 at full precision, exported with a "durations" output: it reports
# when each sound is spoken, which is what lights up each word. Pinned to a commit.
MODEL = os.path.join(MODELS, "kokoro-v1.0-timestamped.fp32.onnx")
MODEL_SRC = ("https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped/resolve/"
             "dd4401a9add81ac692d20e240d22ec9dda82cc29/onnx/model.onnx")
# A voice, or several blended: "af_heart", "af_heart+af_bella", "af_heart:70+af_bella:30"
VOICE_SPEC = re.compile(r"^[a-z]{2}_[a-z]+(?::\d{1,3})?(?:\+[a-z]{2}_[a-z]+(?::\d{1,3})?){0,3}$")
VOICES = os.path.join(MODELS, "voices-v1.0.bin")
VOICES_SRC = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"
SERVER_DIR = "/tmp/kokoro-server"
JOB_DIR_RE = re.compile(r"^/tmp/kokoro\.[A-Za-z0-9]+$")
FORMATS = ("wav", "mp3", "ogg", "flac")
SERVING = False


# ------------------------------------------------------------------ model

_tts = None
_tts_lock = threading.Lock()
# One synthesis at a time, across every job the server is running: espeak isn't
# thread-safe, and the phone has only so much CPU. Held per part, not per job, so
# a book playing for hours doesn't block a quick command.
SYNTH_LOCK = threading.Lock()


# How onnxruntime runs the model: threads (0: its own choice) and the dynamic block
# base (0: off), which spreads work across cores of different speeds. kokoro
# --benchmark finds the fastest on the phone; serve and job take them as set.
TUNING = {"threads": 0, "block": 0}


def load_kokoro(threads=0, block=0):
    """Kokoro on a CPU session tuned this way."""
    import onnxruntime as rt
    from kokoro_onnx import Kokoro
    so = rt.SessionOptions()
    so.graph_optimization_level = rt.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.inter_op_num_threads = 1
    if threads:
        so.intra_op_num_threads = threads
    if block:
        so.add_session_config_entry("session.dynamic_block_base", str(block))
    return Kokoro.from_session(rt.InferenceSession(MODEL, so, providers=["CPUExecutionProvider"]), VOICES)


def tts():
    """Kokoro, loaded once."""
    global _tts
    with _tts_lock:
        if _tts is None:
            _tts = load_kokoro(TUNING["threads"], TUNING["block"])
            # kokoro-onnx looks for an output called "duration"; this export calls it
            # "durations". It's the second output either way, which is what it reads.
            _tts.has_timings = len(_tts.sess.get_outputs()) > 1
        return _tts


def voice_style(engine, spec):
    """What to hand Kokoro for a voice: its name, or for a blend ("af_heart:70+af_bella:30")
    the voices' styles mixed in those proportions. Weights left out are shared equally."""
    if not VOICE_SPEC.match(spec or ""):
        raise JobError(f"'{spec}' isn't a voice. See: kokoro --list-voices")
    known = set(engine.get_voices())
    parts = []
    for item in spec.split("+"):
        name, _, weight = item.partition(":")
        if name not in known:
            raise JobError(f"Unknown voice '{name}'. See: kokoro --list-voices")
        parts.append((name, int(weight) if weight else None))
    if len(parts) == 1:
        return parts[0][0]
    given = sum(w for _, w in parts if w is not None)
    unset = [n for n, w in parts if w is None]
    share = max(0, 100 - given) / len(unset) if unset else 0
    weights = [(n, w if w is not None else share) for n, w in parts]
    total = sum(w for _, w in weights)
    if total <= 0:
        raise JobError(f"The voices in '{spec}' need some weight, e.g. af_heart:70+af_bella:30")
    import numpy as np
    style = sum((w / total) * np.asarray(engine.get_voice_style(n), dtype=np.float32) for n, w in weights)
    return style.astype(np.float32)


# --- pronunciation ------------------------------------------------------------
# English is turned into sounds by misaki, the dictionary-based tool Kokoro was
# trained with: it reads "I read it yesterday", "record the record" and "$42.50"
# the way people say them. Words it doesn't know, and other languages, go to espeak.
_g2p = {}
_g2p_lock = threading.Lock()


def g2p_for(lang):
    """misaki's English G2P (loaded once per accent), or None for other languages."""
    if lang not in ("en-us", "en-gb"):
        return None
    british = lang == "en-gb"
    with _g2p_lock:
        if british not in _g2p:
            from misaki import en, espeak
            # Words it doesn't know go to espeak, the copy kokoro-onnx already set up.
            _g2p[british] = en.G2P(trf=False, british=british, fallback=espeak.EspeakFallback(british=british))
        return _g2p[british]


def dictionary_phonemes(g2p, text):
    """A part's sounds from misaki, and each highlighted word's own sounds, taken from the
    same reading of the sentence (so "read" is "red" in both), for lining up its timing."""
    import words
    ps, tokens = g2p(text)
    spans, pos = [], 0
    for t in tokens:                    # where misaki's tokens ("Do", "n't") sit in the text
        at = text.find(t.text, pos)
        if at < 0:
            continue
        spans.append((at, at + len(t.text), t.phonemes or ""))
        pos = at + len(t.text)
    per = ["".join(p for s, e, p in spans if s < b and e > a) for a, b in words._tokens(text)]
    return (ps or "").replace("❓", "").strip(), per


def phonemize_many(words, lang, engine=None):
    """Each word's phonemes on its own, the way Kokoro phonemises (call under SYNTH_LOCK)."""
    import phonemizer
    from kokoro_onnx.tokenizer import Tokenizer
    vocab = (engine or tts()).tokenizer.vocab
    out = phonemizer.phonemize([Tokenizer.normalize_text(w) for w in words], lang,
                               preserve_punctuation=True, with_stress=True)
    return ["".join(ch for ch in p if ch in vocab) for p in out]


# ------------------------------------------------------------------ jobs

def read_job(path):
    real = os.path.realpath(path)
    jobdir = os.path.dirname(real)
    if not JOB_DIR_RE.match(jobdir) or os.path.basename(real) != "job":
        raise JobError(f"Refusing job outside /tmp/kokoro.*: {path}")
    job = {}
    with open(real, encoding="utf-8") as fh:
        for line in fh:
            key, sep, val = line.rstrip("\n").partition("=")
            if sep:
                job[key] = val
    return jobdir, job


CACHE_ROOT = "/tmp/kokomux-cache"   # Termux's $PREFIX/tmp/kokomux-cache: made parts, kept per book


def cache_dir(book, voice, speed, lang):
    """Where a book's parts are kept for these settings (None: not kept)."""
    if not re.fullmatch(r"[0-9a-f]{8,64}", book or ""):
        return None
    import hashlib
    key = hashlib.sha1(f"{voice}|{speed:.2f}|{lang}|{os.path.basename(MODEL)}".encode()).hexdigest()[:12]
    d = os.path.join(CACHE_ROOT, book, key)
    os.makedirs(d, exist_ok=True)
    return d


def cached_part(cache, name, text):
    """A kept part's audio, if it was made from exactly this text."""
    wav, txt = os.path.join(cache, f"{name}.wav"), os.path.join(cache, f"{name}.txt")
    try:
        with open(txt, encoding="utf-8") as fh:
            same = fh.read() == text
    except OSError:
        return None
    return wav if same and os.path.exists(wav) else None


def _link(src, dest):
    """A relative link, so it resolves both in the container (/tmp) and in Termux ($PREFIX/tmp)."""
    tmp = dest + ".lnk"
    if os.path.lexists(tmp):
        os.remove(tmp)
    os.symlink(os.path.relpath(src, os.path.dirname(dest)), tmp)
    os.replace(tmp, dest)


def synthesize(engine, g2p, chunk, voice, speed, lang, timed, i):
    """One part's audio, and its word timings when timed (else None)."""
    import words
    with SYNTH_LOCK:
        ps, per = None, None
        if g2p is not None:
            try:
                ps, per = dictionary_phonemes(g2p, chunk)
            except Exception as e:      # a sentence misaki can't parse: espeak reads it
                log(f"misaki: part {i}: {e.__class__.__name__}: {e}")
                ps = None
        if timed:
            if ps:
                samples, rate, timings = engine.create_timed(ps, voice=voice, speed=speed, lang=lang, is_phonemes=True)
                return samples, rate, words.words_from_timings(chunk, timings, lambda ws: per)
            samples, rate, timings = engine.create_timed(chunk, voice=voice, speed=speed, lang=lang)
            return samples, rate, words.words_from_timings(chunk, timings, lambda ws: phonemize_many(ws, lang, engine))
        if ps:
            samples, rate = engine.create(ps, voice=voice, speed=speed, lang=lang, is_phonemes=True)
        else:
            samples, rate = engine.create(chunk, voice=voice, speed=speed, lang=lang)
        return samples, rate, None


def edges(samples, rate):
    """Soften a part's first and last few milliseconds, so a trimmed edge can't click."""
    import numpy as np
    n = min(len(samples) // 4, int(rate * 0.004)), min(len(samples) // 4, int(rate * 0.012))
    samples = np.array(samples, dtype=np.float32, copy=True)
    if n[0]:
        samples[:n[0]] *= np.linspace(0, 1, n[0], dtype=np.float32)
    if n[1]:
        samples[-n[1]:] *= np.linspace(1, 0, n[1], dtype=np.float32)
    return samples


def silence(seconds, rate):
    import numpy as np
    return np.zeros(max(0, int(seconds * rate)), dtype=np.float32)


def _novel_listed(jobdir, base):
    """The chapters already in this listen, before part BASE: (chapters lines, serial lines).
    (A jump or a new speed starts a new job folder, with these copied into it.)"""
    got = []
    for name in ("chapters", "serial"):
        try:
            with open(os.path.join(jobdir, name), encoding="utf-8") as fh:
                got.append([ln.rstrip("\n") for ln in fh if ln.strip() and int(ln.split("\t", 1)[0]) < base])
        except (OSError, ValueError):
            got.append([])
    if len(got[0]) != len(got[1]):
        return [], []
    return got[0], got[1]


# While listening, how much speech is made ahead of the player: about ten minutes
# (a Fibonacci number of seconds), plenty to ride out a busy or throttled phone.
AHEAD = 610


def _listener_at(jobdir):
    """The part the player is on (it writes it into `at`); 0 before it starts."""
    try:
        with open(os.path.join(jobdir, "at")) as fh:
            return int(fh.read().strip() or 0)
    except (OSError, ValueError):
        return 0


def _keep_ahead(jobdir, made):
    """Wait until less than AHEAD seconds of speech is ready ahead of the player.
    MADE: each part made in this job, and its length in seconds."""
    while True:
        if not os.path.isdir(jobdir):
            raise JobError("Cancelled")
        at = _listener_at(jobdir)
        if sum(secs for part, secs in made.items() if part >= at) < AHEAD:
            return
        time.sleep(0.5)


def _write_plan(jobdir, chunks):
    """`plan`: every part, one line each (line N is part N): the characters read up to the
    end of it, a tab, and its text. The player searches it and tells the time left from it.
    (A novel's listen that picks up mid-way keeps the earlier lines, copied in.)"""
    path = os.path.join(jobdir, "plan")
    old = []
    if any(c is None for c in chunks):
        try:
            with open(path, encoding="utf-8") as fh:
                old = [ln.rstrip("\n").partition("\t")[2] for ln in fh]
        except OSError:
            pass
    lines, total = [], 0
    for k, c in enumerate(chunks):
        text = c[0] if c else (old[k] if k < len(old) else "")
        total += len(text) + 1
        lines.append(f"{total}\t{text}")
    _write_atomic(path, "\n".join(lines) + "\n")


def _settings(engine, job, jobdir):
    """The job's voice, speed and language, checked; and where its file goes (None: no file)."""
    voice = voice_style(engine, job.get("voice", "af_heart"))
    try:
        speed = float(job.get("speed", "1.0"))
    except ValueError:
        raise JobError("Speed must be a number, e.g. 1.2") from None
    if not 0.5 <= speed <= 2.0:
        raise JobError("Speed must be between 0.5 and 2.0")
    out = job.get("out")
    if out:
        if "/" in out or out.rsplit(".", 1)[-1] not in FORMATS:
            raise JobError(f"Output must be one of: {', '.join(FORMATS)}")
        out = os.path.join(jobdir, out)
    return voice, speed, job.get("lang", "en-us"), out


def _number(job, key, default):
    """A whole number from the job (DEFAULT if it's missing or isn't one)."""
    try:
        return int(job.get(key, default))
    except ValueError:
        return default


class Listen:
    """What a job reads, split into parts: chunks ([(text, pause)], part N at N-1),
    starts (the chapters: "first<TAB>title"), and for a web novel the chapter being
    read (novel) and serial ("first<TAB>number<TAB>address")."""

    def __init__(self, jobdir, job, lang, stream):
        self.jobdir, self.lang = jobdir, lang
        self.chunks, self.starts, self.serial, self.novel = [], [], [], None
        if job.get("url") and stream:
            log(f"Fetching {job['url']}")
            self.title, text, self.novel = read_page(job["url"])
            if self.novel and job.get("skip") == "1":       # on from a chapter already heard
                heard, self.novel = self.novel, novel_next(self.novel)
                if not self.novel:
                    raise JobError(f"No new chapter after {heard['label']} yet")
            chapters = [] if self.novel else [(self.title, text)]
        else:
            self.title, chapters = read_source(jobdir, job)
        if self.novel:
            base = max(1, _number(job, "base", 1))          # this chapter's first part, in the whole listen
            self.starts, self.serial = _novel_listed(jobdir, base)
            self.chunks = [None] * (base - 1)
            self.add_chapter(self.novel, new=base == 1 or not self.serial)
        # Each chapter is split on its own, so a part never runs across chapters, and
        # where each chapter starts is written down for the player's chapter list.
        for heading, text in chapters:
            parts = split_text(normalise(text, lang))
            if parts:
                self.starts.append(f"{len(self.chunks) + 1}\t{heading.replace(chr(9), ' ').replace(chr(10), ' ')[:80]}")
                parts[-1] = (parts[-1][0], PAUSE["chapter"])         # a longer breath between chapters
                self.chunks += parts
        if not self.chunks:
            raise JobError("No text to read")
        if not self.novel:
            if len(self.starts) > 1:
                _write_atomic(os.path.join(jobdir, "chapters"), "\n".join(self.starts) + "\n")
            _write_plan(jobdir, self.chunks)

    def add_chapter(self, chapter, new=False):
        """Add a novel's chapter to the listen: its parts, its line in `chapters` and in `serial`."""
        parts = split_text(normalise(chapter["text"], self.lang))
        if not parts:
            return
        parts[-1] = (parts[-1][0], PAUSE["chapter"])
        first = len(self.chunks) + 1
        self.starts.append(f"{first}\t{chapter['label'].replace(chr(9), ' ')[:80]}")
        self.serial.append(f"{first}\t{chapter['number']}\t{chapter['url']}")
        self.chunks += parts
        _write_atomic(os.path.join(self.jobdir, "chapters"), "\n".join(self.starts) + "\n")
        _write_atomic(os.path.join(self.jobdir, "serial"), "\n".join(self.serial) + "\n")
        _write_plan(self.jobdir, self.chunks)
        if new:                                     # the novel, and the chapter before this listen's first
            _write_atomic(os.path.join(self.jobdir, "novel"),
                          f"title={chapter['novel']}\nurl={chapter['novel_url']}\nprev={chapter['prev'] or ''}\n")

    def more(self):
        """A novel's next chapter, added once the listener has reached the last one made (so one
        chapter is always ready ahead, and no more). False at the end of what's out."""
        last = int(self.serial[-1].split("\t", 1)[0]) if self.serial else 0
        while True:
            if not os.path.isdir(self.jobdir):
                raise JobError("Cancelled")
            if _listener_at(self.jobdir) >= last:
                break
            time.sleep(0.5)
        log(f"Fetching {self.novel['next']}")
        nxt = novel_next(self.novel)
        if not nxt:
            return False
        self.novel = nxt
        self.add_chapter(nxt)
        return True

    def first(self, job):
        """The part to begin at: the job's start, mapped onto these parts if the bookmark
        counted them differently; not before a novel's chapter, nor past the end."""
        first, was = _number(job, "start", 1), _number(job, "of", 0)
        if was > 1 and was != len(self.chunks):
            # Made with another version's parts: the same place, as a fraction of the text.
            first = 1 + round((first - 1) * len(self.chunks) / was)
        if self.novel:
            return max(first, int(self.serial[-1].split("\t", 1)[0]))
        return first if 1 <= first <= len(self.chunks) else 1   # a bookmark past the end: start again


def _write_meta(jobdir, job):
    """For an audiobook: who wrote the EPUB, and its cover (`author`, `cover.<ext>`)."""
    src = os.path.join(jobdir, job.get("file", "in.txt"))
    if job.get("url") or not zipfile.is_zipfile(src):
        return
    author, cover, ext = epub_meta(src)
    _write_atomic(os.path.join(jobdir, "author"), author.replace("\n", " ")[:200] + "\n")
    if cover:
        with open(os.path.join(jobdir, f"cover.{ext}"), "wb") as fh:
            fh.write(cover)


def _keep(home, name, chunk, samples, rate, marks):
    """Write a made part into HOME (the cache, or the stream folder): its text and word
    timings first, so the player has them when the audio lands, then the audio."""
    import soundfile as sf
    import words
    _write_atomic(os.path.join(home, f"{name}.txt"), chunk)
    if marks is not None:
        _write_atomic(os.path.join(home, f"{name}.words"), words.player_rows(chunk, marks))
    tmp = os.path.join(home, f"{name}.tmp.wav")
    sf.write(tmp, samples, rate)
    os.replace(tmp, os.path.join(home, f"{name}.wav"))


def run_job(path):
    import numpy as np
    import soundfile as sf

    t0 = time.time()
    jobdir, job = read_job(path)
    engine = tts()
    voice, speed, lang, out = _settings(engine, job, jobdir)
    g2p = g2p_for(lang)

    stream = None
    timed = False
    if job.get("stream") == "1":
        stream = os.path.join(jobdir, "stream")
        os.makedirs(stream, exist_ok=True)
        # Word timings come from Kokoro itself, with the audio.
        timed = job.get("words") == "1" and engine.has_timings
        if job.get("words") == "1" and not timed:
            _write_atomic(os.path.join(stream, "words-off"), "this model reports no timings\n")

    writer = None
    total = 0.0
    try:
        book = Listen(jobdir, job, lang, stream)
        _write_atomic(os.path.join(jobdir, "title"), (book.title or "").replace("\n", " ")[:120] + "\n")
        if job.get("meta") == "1":
            _write_meta(jobdir, job)
        first = book.first(job)
        if stream:
            _write_atomic(os.path.join(stream, "first"), f"{first}\n")

        # (a novel's parts aren't kept: each listen numbers them from where it began)
        cache = None if book.novel else cache_dir(job.get("cache", ""), job.get("voice", "af_heart"), speed, lang)
        if cache and engine.has_timings:
            timed = True                    # kept parts carry their word timings, whatever this run shows
        chapter_at = {int(line.split("\t", 1)[0]): line.split("\t", 1)[1] for line in book.starts}
        chapter_times, written = [], 0
        made = {}                           # part -> seconds of speech, for keeping just ahead of the listener
        heard_chars = 0                     # the characters in what's made, for the speech rate
        i = first - 1
        while True:
            i += 1
            if i > len(book.chunks) and not (book.novel and book.more()):
                break
            chunk, pause = book.chunks[i - 1]
            # The kokoro command deletes its job folder when it exits or is
            # stopped, so a vanished folder means nobody is waiting any more.
            if not os.path.isdir(jobdir) or (stream and not os.path.isdir(stream)):
                raise JobError("Cancelled")
            _write_atomic(os.path.join(jobdir, "progress"), f"{i} {len(book.chunks)}\n")
            name = f"{i:04d}"
            got = cached_part(cache, name, chunk) if cache else None
            if not got and stream and not out:
                _keep_ahead(jobdir, made)       # (a kept part costs nothing: it's linked at once)
            if got:
                info = sf.info(got)
                rate, length = info.samplerate, info.frames
                samples = sf.read(got, dtype="float32")[0] if out else None
            else:
                samples, rate, marks = synthesize(engine, g2p, chunk, voice, speed, lang, timed, i)
                samples = np.concatenate([edges(samples, rate), silence(pause, rate)])   # mpv plays them back to back
                length = len(samples)
                if cache or stream:
                    _keep(cache or stream, name, chunk, samples, rate, marks)
            if stream and cache:
                for ext in ("txt", "words", "wav"):                  # (the audio last: the player waits for it)
                    src = os.path.join(cache, f"{name}.{ext}")
                    if os.path.exists(src):
                        _link(src, os.path.join(stream, f"{name}.{ext}"))
            if chapter_at.get(i) is not None and out:
                chapter_times.append((round(written / rate * 1000), chapter_at[i]))
            total += length / rate
            made[i] = length / rate
            heard_chars += len(chunk) + 1
            # How fast this voice reads, in characters a second, and at what speed: the player tells the time left by it.
            _write_atomic(os.path.join(jobdir, "rate"), f"{heard_chars / total:.3f} {speed:g}\n")
            if out:
                # Written as it's made, so a whole book never has to fit in memory.
                if writer is None:
                    writer = sf.SoundFile(out, "w", samplerate=rate, channels=1)
                if i == len(book.chunks):
                    samples = samples[:max(0, len(samples) - int(pause * rate))]   # no pause after the end
                writer.write(samples)
                written += len(samples)
        if out:
            total = written / rate if written else total     # (no pause after the last part)
        if out and chapter_times:
            _write_atomic(os.path.join(jobdir, "chapters_ms"),
                          "".join(f"{ms}\t{title}\n" for ms, title in chapter_times))
    finally:
        if writer is not None:
            writer.close()
        if stream and os.path.isdir(stream):
            open(os.path.join(stream, "end"), "w").close()

    elapsed = time.time() - t0
    _write_atomic(os.path.join(jobdir, "result"), f"{total:.1f} {elapsed:.1f} {len(book.chunks)}\n")
    return f"Made {total:.1f}s of audio in {elapsed:.1f}s"


def _write_atomic(path, text):
    try:
        with open(path + ".tmp", "w") as fh:
            fh.write(text)
        os.replace(path + ".tmp", path)
    except OSError:
        pass  # the job folder vanished (cancelled); the loop will notice


# ------------------------------------------------------------------ server

def serve(port, idle):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from urllib.parse import parse_qs, urlparse

    global SERVING
    SERVING = True
    log("Loading model...")
    tts()
    g2p_for("en-us")                    # (a few seconds: done before the first job)
    token = secrets.token_hex(16)
    state = {"last": time.time(), "busy": 0}

    class Handler(BaseHTTPRequestHandler):
        def _reply(self, code, text):
            body = (text + "\n").encode()
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self):
            given = self.headers.get("X-Kokoro-Token", "")
            if hmac.compare_digest(given, token):
                return True
            self._reply(403, "bad token")
            return False

        def do_GET(self):
            if urlparse(self.path).path == "/health" and self._authorized():
                self._reply(200, "ok")
            elif urlparse(self.path).path != "/health":
                self._reply(404, "not found")

        def do_POST(self):
            if not self._authorized():
                return
            url = urlparse(self.path)
            if url.path == "/shutdown":
                self._reply(200, "bye")
                threading.Thread(target=httpd.shutdown, daemon=True).start()
                return
            if url.path != "/job":
                self._reply(404, "not found")
                return
            path = parse_qs(url.query).get("path", [""])[0]
            state["busy"] += 1
            try:
                msg = run_job(path)
                self._reply(200, msg)
            except JobError as e:
                self._reply(400, f"Error: {e}")
            except Exception as e:  # noqa: BLE001 - send the error to the client
                self._reply(500, f"Error: {type(e).__name__}: {e}")
            finally:
                state["busy"] -= 1
                state["last"] = time.time()

        def log_message(self, fmt, *args):
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.daemon_threads = True

    # Publish the token only once we're listening. $PREFIX/tmp is private to
    # Termux, so other apps on the phone can't use the server.
    os.makedirs(SERVER_DIR, exist_ok=True)
    tok = os.path.join(SERVER_DIR, "token")
    with open(tok + ".tmp", "w") as fh:
        fh.write(f"{port} {token}\n")
    os.chmod(tok + ".tmp", 0o600)
    os.replace(tok + ".tmp", tok)

    def watch_idle():
        while True:
            time.sleep(15)
            if idle and not state["busy"] and time.time() - state["last"] > idle:
                log(f"Idle for {idle // 60} min, stopping")
                httpd.shutdown()
                return

    threading.Thread(target=watch_idle, daemon=True).start()
    log(f"Ready on 127.0.0.1:{port}")
    try:
        httpd.serve_forever()
    finally:
        try:
            with open(tok) as fh:
                if token in fh.read():
                    os.remove(tok)
        except OSError:
            pass


# ------------------------------------------------------------------ benchmark and download

BENCH_TEXT = ("The road left the village at first light and climbed into the hills, where the fields "
              "gave way to heather. For three days she walked without meeting anyone, sleeping in the "
              "lee of stone walls and waking to the sound of sheep.")


def benchmark():
    """Time Kokoro on this phone with each way of running it, and say which is fastest.
    Prints "@@bench THREADS BLOCK SECONDS SPEED" per try, then "@@best THREADS BLOCK SPEED"."""
    cpus = os.cpu_count() or 4
    tries = [(0, 0)] + [(t, b) for t in sorted({2, 4, 6, 8, cpus}) if t <= cpus for b in (0, 4)]
    best = None
    for threads, block in tries:
        k = load_kokoro(threads, block)
        k.create(BENCH_TEXT, voice="af_heart")                    # warm up
        took = []
        for _ in range(2):
            t0 = time.time()
            audio, rate = k.create(BENCH_TEXT, voice="af_heart")
            took.append(time.time() - t0)
        secs = min(took)
        speed = len(audio) / rate / secs
        print(f"@@bench {threads} {block} {secs:.2f} {speed:.1f}", flush=True)
        if best is None or speed > best[2]:
            best = (threads, block, speed)
        del k
    print(f"@@best {best[0]} {best[1]} {best[2]:.1f}", flush=True)


def download():
    os.makedirs(MODELS, exist_ok=True)
    for dest, src, label in ((MODEL, MODEL_SRC, "model"), (VOICES, VOICES_SRC, "voices")):
        name = os.path.basename(dest)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"@@progress {label} 100 0 0")
            print(f"{name}: already downloaded", flush=True)
            continue
        part = dest + ".part"
        for attempt in range(1, 6):
            have = os.path.getsize(part) if os.path.exists(part) else 0
            req = urllib.request.Request(src, headers={"User-Agent": USER_AGENT})
            if have:
                req.add_header("Range", f"bytes={have}-")
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    if have and r.status != 206:
                        have = 0  # server ignored the range; start over
                    size = have + int(r.headers.get("Content-Length", 0))
                    with open(part, "ab" if have else "wb") as fh:
                        done, shown = have, -1
                        while block := r.read(1 << 18):
                            fh.write(block)
                            done += len(block)
                            pct = done * 100 // size if size else 0
                            if pct != shown:
                                shown = pct
                                # "@@progress LABEL PCT DONE_MB TOTAL_MB"
                                print(f"@@progress {label} {pct} {done / 2**20:.0f} {size / 2**20:.0f}", flush=True)
                os.replace(part, dest)
                break
            except OSError as e:
                print(f"{name}: download interrupted ({e}), retry {attempt}/5", flush=True)
                time.sleep(2 * attempt)
        else:
            sys.exit(f"Couldn't download {name}. Try again: it picks up where it stopped.")


# ------------------------------------------------------------------ voices

LANGS = {"a": ("American English", "en-us"), "b": ("British English", "en-gb"),
         "e": ("Spanish", "es"), "f": ("French", "fr-fr"), "h": ("Hindi", "hi"),
         "i": ("Italian", "it"), "j": ("Japanese", "ja"),
         "p": ("Brazilian Portuguese", "pt-br"), "z": ("Mandarin Chinese", "cmn")}


def sgr(params, fallback):
    """An SGR escape from kokoro's theme ("38;2;182;156;255"), or the fallback."""
    return f"\033[{params}m" if params and all(c in "0123456789;" for c in params) else fallback


def print_voices(names, width, default, accent="", muted=""):
    """Group voices by language and gender, wrapped to the terminal width.
    Colour only when stdout is a terminal, in kokoro's theme colours."""
    tty = (sys.stdout.isatty() or "--color" in sys.argv) and not os.environ.get("NO_COLOR")
    bold, dim, acc, off = ("\033[1m", sgr(muted, "\033[2m"), sgr(accent, "\033[38;5;141m"), "\033[0m") if tty else ("",) * 4
    width = max(30, min(width, 100))
    groups = {}
    for n in names:
        groups.setdefault(n[0], {}).setdefault(n[1] if len(n) > 1 else "?", []).append(n)
    for code in sorted(groups, key=lambda c: list(LANGS).index(c) if c in LANGS else 99):
        title, lang = LANGS.get(code, (f"Other ({code})", ""))
        note = " (basic pronunciation)" if code in "jz" else ""
        print(f"{bold}{title}{off}{dim}{note}" + (f" -l {lang}" if lang else "") + off)
        for g, label in (("f", "female"), ("m", "male")):
            vs = groups[code].get(g)
            if not vs:
                continue
            line, cur = [], 9
            out = []
            for v in vs:
                shown = f"{acc}{v}*{off}" if v == default else v
                w = len(v) + (1 if v == default else 0) + 1
                if cur + w > width and line:
                    out.append(" ".join(line))
                    line, cur = [], 9
                line.append(shown)
                cur += w
            out.append(" ".join(line))
            print(f"  {dim}{label:<7}{off}" + f"\n  {'':<7}".join(out))
        print()
    if default:
        other = "bm_george" if default != "bm_george" else "af_heart"
        print(f"{dim}* your default{off}")
        print(f"{dim}Try another: kokoro -v {other} -p \"Hi\"{off}")


# ------------------------------------------------------------------ main

def main():
    p = argparse.ArgumentParser(prog="engine.py")
    sub = p.add_subparsers(dest="cmd", required=True)
    j = sub.add_parser("job")
    j.add_argument("path")
    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=8880)
    s.add_argument("--idle", type=int, default=1800, help="stop after this many idle seconds (0 = never)")
    for sp in (s, j):
        sp.add_argument("--threads", type=int, default=0, help="onnxruntime threads (0: its choice)")
        sp.add_argument("--block", type=int, default=0, help="onnxruntime dynamic block base (0: off)")
    v = sub.add_parser("voices")
    v.add_argument("--plain", action="store_true", help="one name per line")
    v.add_argument("--width", type=int, default=60)
    v.add_argument("--default", default="")
    v.add_argument("--color", action="store_true", help="colour even when piped")
    v.add_argument("--accent", default="", help="SGR parameters for the accent colour")
    v.add_argument("--muted", default="", help="SGR parameters for secondary text")
    sub.add_parser("download")
    sub.add_parser("bench", help="time Kokoro each way it can run here")
    a = p.parse_args()

    if a.cmd in ("job", "serve"):
        TUNING.update(threads=max(0, a.threads), block=max(0, a.block))
    if a.cmd == "job":
        try:
            log(run_job(a.path))
        except JobError as e:
            sys.exit(f"Error: {e}")
    elif a.cmd == "serve":
        serve(a.port, a.idle)
    elif a.cmd == "voices":
        names = sorted(tts().get_voices())
        if a.plain:
            print("\n".join(names))
        else:
            print_voices(names, a.width, a.default, a.accent, a.muted)
    elif a.cmd == "download":
        download()
    elif a.cmd == "bench":
        benchmark()


if __name__ == "__main__":
    main()
