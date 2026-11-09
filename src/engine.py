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
("first-part<TAB>title" per line), `result` ("seconds-of-audio seconds-taken
parts"), `out.<ext>` and `chapters_ms` when saving, and with stream=1
`stream/NNNN.txt` (the part's text), `stream/NNNN.words` (its word timings),
`stream/NNNN.wav` (its audio, with the pause after it; written last), `stream/first`
(where it began) and `stream/end` once everything is made. With a cache, the
stream files are relative links into it.

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
from html.parser import HTMLParser

APP = os.path.dirname(os.path.abspath(__file__))
if APP not in sys.path:
    sys.path.insert(0, APP)   # words.py lives beside this file
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
# A phone browser's: some sites (behind Cloudflare, say) turn other clients away.
USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
MAX_CHUNK = 360  # characters per part: whole sentences, under Kokoro's 510 sounds
SERVING = False


class JobError(Exception):
    """A problem with the request itself (bad voice, empty text...)."""


def log(msg):
    print(msg, file=sys.stderr, flush=True)


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


# ------------------------------------------------------------------ text

# Pauses between parts, in seconds. Inside a part Kokoro pauses 0.25 s at a
# full stop; between parts the same, longer after a paragraph and a chapter,
# in golden-ratio steps (0.25 × φ² and × φ³).
PAUSE = {"sentence": 0.25, "paragraph": 0.65, "chapter": 1.06}

# --- tidying text before it's spoken -----------------------------------------
# Kokoro reads what it's given, so things meant for the eye are made speakable
# first: ligatures, footnote marks, links, markdown, dashes, Roman numerals in
# headings, ALL-CAPS headings, and (in English) abbreviations and units. The
# reader shows the tidied text, so what's lit is what's said.

_INVISIBLE = dict.fromkeys(map(ord, "­​‌‍⁠﻿"))
_LIGATURES = {"ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl",
              "ﬅ": "st", "ﬆ": "st", "Ĳ": "IJ", "ĳ": "ij"}
_ROMAN = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}
_HEADING_WORDS = (r"Chapter|Part|Book|Volume|Vol\.|Act|Scene|Section|Canto|Letter|Appendix|"
                  r"Chapitre|Partie|Livre|Capítulo|Capitulo|Parte|Libro|Capitolo|Kapitel|Teil|Buch")
# "Chapter IV" or "CHAPTER IV", where the numeral ends the phrase ("the part I liked" stays)
_HEADING_RE = re.compile(r"\b((?:" + "|".join(f"{w}|{w.upper()}" for w in _HEADING_WORDS.split("|")) +
                         r"))[ \t]+([IVXLCDM]+)(?=[ \t]*(?:\n|$|[.:,;)–—-])|[ \t]+[A-Z\"“'‘])", re.M)
_TITLES = {"Mr": "Mister", "Mrs": "Missus", "Ms": "Miz", "Dr": "Doctor", "Prof": "Professor",
           "St": "Saint", "Gen": "General", "Capt": "Captain", "Lt": "Lieutenant",
           "Sgt": "Sergeant", "Col": "Colonel", "Rev": "Reverend", "Hon": "Honourable"}
_PHRASES = [(r"\be\.[ \t]?g\.", "for example"), (r"\bi\.[ \t]?e\.", "that is"),
            (r"\betc\.", "etcetera."), (r"\bvs\.?(?=[ \t])", "versus"), (r"\bapprox\.", "approximately"),
            (r"\bNo\.(?=[ \t]*\d)", "number"), (r"\bFig\.(?=[ \t]*\d)", "figure"),
            (r"\bpp\.(?=[ \t]*\d)", "pages"), (r"\bp\.(?=[ \t]*\d)", "page")]
# unit: (singular, plural), spoken after a number; (American, British) spellings
_UNITS = [("km/h", ("kilometer per hour", "kilometers per hour"), ("kilometre per hour", "kilometres per hour")),
          ("mph", ("mile per hour", "miles per hour"), None),
          ("km", ("kilometer", "kilometers"), ("kilometre", "kilometres")),
          ("cm", ("centimeter", "centimeters"), ("centimetre", "centimetres")),
          ("mm", ("millimeter", "millimeters"), ("millimetre", "millimetres")),
          ("kg", ("kilogram", "kilograms"), None),
          ("mg", ("milligram", "milligrams"), None),
          ("ml", ("milliliter", "milliliters"), ("millilitre", "millilitres")),
          ("lbs?", ("pound", "pounds"), None),
          ("ft", ("foot", "feet"), None),
          ("GB", ("gigabyte", "gigabytes"), None), ("MB", ("megabyte", "megabytes"), None),
          ("KB", ("kilobyte", "kilobytes"), None), ("kB", ("kilobyte", "kilobytes"), None)]


_TITLE_RE = re.compile(r"\b(" + "|".join(_TITLES) + r")\.(?=[ \t]+[A-Z])")
_UNIT_WORDS = {u: (us, gb) for u, us, gb in _UNITS}
_UNIT_RE = re.compile(r"(?<![\w.])(\d+(?:[.,]\d+)?)[ \t]?(" + "|".join(u for u, _, _ in _UNITS) + r")\b")


def roman(s):
    """A Roman numeral's value, or None if it isn't one ("MMXXIV" -> 2024)."""
    s = s.upper()
    if not s or not re.fullmatch(r"M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})", s):
        return None
    total = 0
    for i, ch in enumerate(s):
        v = _ROMAN[ch]
        total += -v if i + 1 < len(s) and _ROMAN[s[i + 1]] > v else v
    return total


def _caps_heading(line):
    """ "CHAPTER ONE: THE ROAD" -> "Chapter One: The Road" (only whole lines in capitals)."""
    letters = [c for c in line if c.isalpha()]
    words = re.findall(r"[^\W\d_]{2,}", line)
    if len(letters) < 4 or len(words) < 2 or not all(c.isupper() for c in letters):
        return line
    return re.sub(r"[^\W\d_]+", lambda m: m.group(0)[0] + m.group(0)[1:].lower(), line)


def _english(text, lang):
    text = _TITLE_RE.sub(lambda m: _TITLES[m.group(1)], text)   # titles before a name: "Dr. Smith"
    for pat, rep in _PHRASES:
        text = re.sub(pat, rep, text)
    text = re.sub(r"(?<=\d)[ \t]?([ap])\.[ \t]?m\.", lambda m: f" {m.group(1).upper()}M" +   # 5 p.m. -> 5 PM
                  ("." if re.match(r"[ \t]+[\"“]?[A-Z]|[ \t]*(?:\n|$)", m.string[m.end():]) else ""),
                  text, flags=re.I)
    text = re.sub(r"(?<=\d)[ \t]?°[ \t]?C\b", " degrees Celsius", text)
    text = re.sub(r"(?<=\d)[ \t]?°[ \t]?F\b", " degrees Fahrenheit", text)
    text = re.sub(r"(?<=\d)[ \t]?°", " degrees", text)
    british = lang == "en-gb"

    def unit(m):
        us, gb = _UNIT_WORDS[m.group(2) if m.group(2) not in ("lb", "lbs") else "lbs?"]
        one, many = gb if british and gb else us
        return f"{m.group(1)} {one if m.group(1) == '1' else many}"
    text = _UNIT_RE.sub(unit, text)
    text = re.sub(r"(?<=[ \t])&(?=[ \t])", "and", text)
    return text


def normalise(text, lang="en-us"):
    """Make text speakable (see above). Line breaks, which mark paragraphs, are kept.
    Each rule runs once over the whole text, so a long book stays quick."""
    text = text.translate(_INVISIBLE)
    for k, v in _LIGATURES.items():
        text = text.replace(k, v)
    text = re.sub(r"[¹²³⁰-⁹]+", "", text)                 # footnote marks¹²
    text = re.sub(r"\[(?:\d+(?:\s*[,–-]\s*\d+)*|[a-z]|note \d+|citation needed|clarification needed)\]", "", text)
    text = re.sub(r"\b(?:https?://|www\.)(?:www\.)?([\w-]+(?:\.[\w-]+)+)[^\s)\]]*?(?=[.,;:!?)\]]?(?:\s|$))",
                  r"\1", text)                                                         # links: their site
    text = re.sub(r"(?m)^[ \t]*(?:#{1,6}|>+|[-*+•·▪◦‣])[ \t]+", "", text)            # markdown marks, bullets
    text = re.sub(r"(?<![\w*])(\*\*\*|___|\*\*|__|\*|_)(?=\S)([^\n]+?)(?<=\S)\1(?![\w*])", r"\2", text)
    text = re.sub(r"(?<=\d)[ \t]?[–—][ \t]?(?=\d)", " to ", text)                     # 1990–1995
    text = re.sub(r"[ \t]*(?:—|–|--)[ \t]*", ", ", text)                             # dashes: a pause
    text = re.sub(r"([!?])\1+", r"\1", text)
    text = re.sub(r"(?m)^[ \t]+|[ \t]+$", "", text)
    text = re.sub(r"(?m)^([IVXLCDM]+)([.:]?)$",                                       # a heading: "IV."
                  lambda m: f"{roman(m.group(1))}{m.group(2) or '.'}" if roman(m.group(1)) else m.group(0), text)
    text = _HEADING_RE.sub(lambda m: f"{m.group(1)} {roman(m.group(2))}" if roman(m.group(2)) else m.group(0),
                           text)                                                       # "Chapter IV"
    text = re.sub(r"(?m)^[^\na-z]*[A-Z][^\na-z]*$", lambda m: _caps_heading(m.group(0)), text)
    if lang.startswith("en"):
        text = _english(text, lang)
    return re.sub(r"[ \t]{2,}", " ", text)


# --- splitting into parts ----------------------------------------------------
_ABBREV = re.compile(r"(?:\b(?:[A-Z]|Mr|Mrs|Ms|Dr|St|Prof|Sr|Sra|Jr|Mt|Gen|Capt|Lt|Sgt|Col|Rev|Hon|"
                     r"Inc|Ltd|Co|vs|etc|approx|No|Fig|M|Mme|Mlle|Hr|Fr|Dra|Sig|Sto)\.)$")
_SENTENCE_END = re.compile(r"(?<=[.!?…])[\"”’»)\]]*\s+")


def sentences(paragraph):
    """A paragraph's sentences, not broken after an initial or abbreviation ("J. R. R.", "Mme.")
    or before a word in lower case ("approx. five")."""
    out = []
    for piece in (p for p in re.split(r"((?<=[.!?…])[\"”’»)\]]*)\s+", paragraph)):
        if piece is None:
            continue
        if out and not out[-1].endswith(" ") and re.fullmatch(r"[\"”’»)\]]*", piece):
            out[-1] += piece + " "                  # closing quotes stay with their sentence
            continue
        if out and (_ABBREV.search(out[-1].rstrip()) or (piece[:1].islower())):
            out[-1] = out[-1].rstrip() + " " + piece
        else:
            out.append(piece)
    return [s.strip() for s in out if re.search(r"\w", s)]


def _clauses(sentence, limit):
    """A sentence longer than the limit, cut at commas, semicolons and colons."""
    parts, cur = [], ""
    for bit in re.split(r"(?<=[,;:])\s+", sentence):
        if cur and len(cur) + len(bit) + 1 > limit:
            parts.append(cur)
            cur = bit
        else:
            cur = f"{cur} {bit}" if cur else bit
    return parts + [cur] if cur else parts


def split_text(text):
    """Split into parts: (text, pause after it). The first part is short, so playback
    starts quickly; then whole sentences up to MAX_CHUNK characters. A part never
    runs across a paragraph (a line break), and each paragraph ends with a longer
    pause. The last part's pause is a paragraph's; the caller makes it a chapter's."""
    chunks = []
    for para in text.split("\n"):
        cur = ""
        for sent in sentences(para.strip()):
            for bit in (_clauses(sent, MAX_CHUNK) if len(sent) > 2 * MAX_CHUNK else [sent]):
                limit = 40 if not chunks else MAX_CHUNK
                if cur and len(cur) + 1 + len(bit) > limit:
                    chunks.append((cur, PAUSE["sentence"]))
                    cur = bit
                else:
                    cur = f"{cur} {bit}" if cur else bit
        if cur:
            chunks.append((cur, PAUSE["paragraph"]))
    return chunks


class _ChapterText(HTMLParser):
    """An EPUB chapter's readable text: paragraphs, headings, list items. Skips
    navigation, tables and citations, prefers <article>/<main> content when there's
    enough of it, and stops at "References" etc. (Web pages go through trafilatura.)"""
    SKIP_TAGS = {"script", "style", "noscript", "nav", "header", "footer", "aside",
                 "form", "svg", "button", "select", "template", "iframe", "table",
                 "figure", "sup", "math"}
    # Whole words from class/id/role names that mark non-article parts.
    SKIP_WORDS = {
        "nav", "navbar", "navbox", "navigation", "menu", "footer", "sidebar",
        "comment", "comments", "share", "sharing", "social", "related",
        "reference", "references", "reflist", "infobox", "toc", "hatnote",
        "metadata", "editsection", "ad", "ads", "advert", "advertisement",
        "cookie", "cookies", "banner", "breadcrumb", "breadcrumbs", "subscribe",
        "newsletter", "caption", "thumb", "promo", "popup", "modal", "noprint"}
    NEVER_SKIP = {"html", "body", "main", "article"}
    VOID = {"br", "img", "input", "meta", "link", "hr", "source", "wbr", "area", "col"}
    BLOCK = {"p", "h1", "h2", "h3", "h4", "li", "blockquote", "pre", "dd"}
    HEADINGS = {"h1", "h2", "h3", "h4"}
    END_SECTIONS = {"references", "see also", "external links", "further reading",
                    "notes", "bibliography", "sources", "citations", "footnotes",
                    "related articles", "comments"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.skip_tag, self.skip_depth = None, 0
        self.article = 0
        self.block = None
        self.buf = []
        self.blocks = []  # (in_article, text)
        self.heading = ""  # the first heading: the chapter's title
        self.done = False

    def handle_starttag(self, tag, attrs):
        if self.skip_tag:
            if tag == self.skip_tag:
                self.skip_depth += 1
            return
        if tag in self.VOID:
            if tag == "br":
                self.buf.append(" ")
            return
        a = dict(attrs)
        hint = f"{a.get('class') or ''} {a.get('id') or ''} {a.get('role') or ''}"
        words = set(re.split(r"[^a-z]+", hint.lower()))
        if tag in self.SKIP_TAGS or (tag not in self.NEVER_SKIP and words & self.SKIP_WORDS):
            self.skip_tag, self.skip_depth = tag, 1
        elif tag in ("article", "main"):
            self.article += 1
        elif tag in self.BLOCK:
            self._flush()
            self.block = tag

    def handle_endtag(self, tag):
        if self.skip_tag:
            if tag == self.skip_tag:
                self.skip_depth -= 1
                if self.skip_depth == 0:
                    self.skip_tag = None
            return
        if tag in ("article", "main") and self.article:
            self.article -= 1
        elif tag in self.BLOCK:
            self._flush()

    def handle_data(self, data):
        if not self.skip_tag and self.block and not self.done:
            self.buf.append(data)

    def _flush(self):
        t = re.sub(r"\[\s*(?:\d+|[a-z]|edit|citation needed|note \d+)\s*\]", "", "".join(self.buf), flags=re.I)
        t = re.sub(r"\s+", " ", t.replace("¶", "")).strip()
        if t and not self.done:
            if self.block in self.HEADINGS and t.lower().strip(" :") in self.END_SECTIONS:
                self.done = True
            else:
                self.blocks.append((self.article > 0, t))
                if self.block in self.HEADINGS and not self.heading:
                    self.heading = t
        self.buf = []
        self.block = None

    def text(self):
        self._flush()
        art = [t for a, t in self.blocks if a]
        use = art if sum(map(len, art)) > 500 else [t for _, t in self.blocks]
        # Headings and list items often lack a full stop; end each block so it
        # gets its own pause.
        use = [t if t[-1] in ".!?…:;" else t + "." for t in use]
        return "\n".join(use)


class NotFound(JobError):
    """The address answered 404: no such page (a chapter that isn't out yet)."""


def fetch(url):
    """Fetch a web page with curl, as a phone browser would: (content type, text)."""
    import subprocess
    import tempfile
    if not re.match(r"^https?://", url):
        raise JobError(f"Not a web address: {url}")
    with tempfile.NamedTemporaryFile(prefix="kokomux-page.") as tmp:
        r = subprocess.run(["curl", "-sS", "-L", "--compressed", "--max-time", "30", "--max-filesize", "5000000",
                            "-A", USER_AGENT, "-o", tmp.name, "-w", "%{http_code}\t%{content_type}", url],
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise JobError(f"Couldn't fetch {url}: {r.stderr.strip() or f'curl error {r.returncode}'}")
        code, _, ctype = r.stdout.partition("\t")
        raw = tmp.read()
    m = re.search(r"charset=([\w-]+)", ctype)
    try:
        body = raw.decode(m.group(1) if m else "utf-8", errors="replace")
    except LookupError:                       # a charset Python doesn't know: read it as UTF-8
        body = raw.decode("utf-8", errors="replace")
    if code == "404":
        raise NotFound(f"There's no page at {url}")
    if re.search(r"<title>\s*Just a moment", body[:3000]):
        raise JobError("That site wants a browser check (Cloudflare) before it shows the page")
    if not code.startswith("2"):
        raise JobError(f"Couldn't fetch {url}: the site answered {code}")
    return ctype.split(";")[0].strip().lower(), body


def read_page(url):
    """A web page: (title, text, chapter), where chapter has a novel chapter's details, or is None."""
    ctype, body = fetch(url)
    if ctype == "text/plain":
        return url, body, None
    if "html" not in ctype and "xml" not in ctype:
        raise JobError(f"That link is {ctype}, not a web page")
    chapter = novel_chapter(body, url)
    if chapter:
        return chapter["novel"], chapter["text"], chapter
    title, text = page_text(body, url)
    return title, text, None


# Where an article ends: from a heading like these on it's links, not reading.
_PAGE_END = set(_ChapterText.END_SECTIONS) | {
    "more recent articles", "recent articles", "recent posts", "related", "related posts", "related stories",
    "read more", "more from", "more stories", "you might also like", "you may also like", "popular",
    "most popular", "most read", "share this", "share", "tags", "about the author", "newsletter", "sign up"}


def page_text(html, url):
    """A web page's (title, text to read), found by trafilatura: the article, not the menus,
    sidebars, ads or comments, and stopping where the links start ("See also", "Recent posts")."""
    import trafilatura
    text = "\n".join(article_lines(html, url))
    if len(text.split()) < 5:                # a menu or a "Home" link is not an article
        raise JobError("Couldn't find any readable text on that page")
    meta = trafilatura.extract_metadata(html, default_url=url)
    title = (meta.title if meta is not None and meta.title else "") or url
    short = title.split(" - ")[0].split(" | ")[0].strip()
    if short and short not in text.split("\n", 1)[0]:
        text = f"{short}.\n{text}"           # say what it is first
    return title, text


def article_lines(html, url):
    """The article on a page, as lines (paragraphs), by trafilatura."""
    import trafilatura
    # Two line breaks in a row are a paragraph break (older pages lay text out that way).
    html = re.sub(r"(?i)(?:<br\s*/?>\s*){2,}", "</p><p>", html)
    md = trafilatura.extract(html, url=url, output_format="markdown", include_comments=False,
                             include_images=False, include_links=False, include_tables=False) or ""
    lines = []
    for line in md.splitlines():
        line = re.sub(r"<[^>]+>", "", line.replace("\\", "")).strip()       # markdown escapes, stray tags
        head = re.match(r"^#{1,6}\s+(.*)$", line)
        if head:
            name = re.sub(r"[^\w\s]", "", head.group(1)).strip().lower()
            if name in _PAGE_END:
                break
            line = head.group(1)
            if not re.search(r"[.!?:…]$", line):
                line += "."                   # a heading gets its own pause
        line = re.sub(r"^(?:[-*+]|\d+[.)])\s+", "", line)         # list items
        if line and line != "[edit]":
            lines.append(line)
    return lines


# ------------------------------------------------------------------ novels
# A chapter page of a web novel (freewebnovel.com and the like): the novel's
# title, the chapter's number and title, its text, and the links to the
# chapters either side. Listening goes on from chapter to chapter.

CHAPTER_RE = re.compile(r"(?i)\b(?:chapter|chap\.?|ch\.)\s*(\d+)")
CHAPTER_URL_RE = re.compile(r"(?i)chapter[-_]?(\d+)")
# Where novel sites put the chapter's text.
_TEXT_IDS = {"article", "chapter-content", "chr-content", "chapter-c", "chaptercontent", "content", "booktxt"}
_TEXT_CLASSES = {"txt", "chapter-content", "chr-c", "reading-content", "chapter-body", "text-left", "entry-content",
                 "chapter-inner", "read-content"}
_NAV_LINE = re.compile(r"(?i)^(?:<<|>>|«|»)?\s*(?:prev(?:ious)?|next)(?: chapter)?\s*(?:<<|>>|«|»)?$|"
                       r"^(?:index|table of contents|chapter list|report( chapter)?|bookmark|comments?)$")


class _ChapterPage(HTMLParser):
    """Reads what a chapter page says about itself: title, headings, links and the chapter's text."""
    SKIP = {"script", "style", "noscript", "button", "select"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title, self.site, self.heads, self.links, self.rel = "", "", [], [], {}
        self.paras, self._text_depth, self._depth, self._skip = [], 0, 0, 0
        self._tag, self._buf, self._a, self._para = None, [], None, []

    def handle_starttag(self, tag, attrs):
        a = {k: (v or "") for k, v in attrs}
        if tag in self.SKIP:
            self._skip += 1
        if tag in ("title", "h1", "h2", "h3"):
            self._tag, self._buf = tag, []
        elif tag == "meta" and a.get("property") == "og:site_name":
            self.site = a.get("content", "")
        elif tag == "link" and a.get("rel", "").lower() in ("next", "prev", "previous"):
            self.rel.setdefault(a["rel"].lower()[:4], a.get("href", ""))
        elif tag == "a":
            self._a = {"href": a.get("href", ""), "rel": a.get("rel", "").lower(), "text": [],
                       "meta": " ".join((a.get("id", ""), a.get("class", ""), a.get("title", ""))).lower()}
        if tag in ("div", "article", "section", "main", "td"):
            self._depth += 1
            ident = a.get("id", "").lower()
            classes = set(a.get("class", "").lower().split())
            if not self._text_depth and (ident in _TEXT_IDS or classes & _TEXT_CLASSES):
                self._text_depth = self._depth
        if self._text_depth and tag in ("p", "br", "div"):
            self._end_para()

    def handle_endtag(self, tag):
        if tag in self.SKIP and self._skip:
            self._skip -= 1
        if tag == self._tag:
            text = " ".join("".join(self._buf).split())
            if tag == "title":
                self.title = self.title or text
            elif text:
                self.heads.append(text)
            self._tag = None
        elif tag == "a" and self._a is not None:
            self._a["text"] = " ".join("".join(self._a["text"]).split()).lower()
            self.links.append(self._a)
            self._a = None
        if self._text_depth and tag in ("p", "div", "article", "section", "main", "td"):
            self._end_para()
        if tag in ("div", "article", "section", "main", "td"):
            if self._text_depth == self._depth:
                self._text_depth = 0
            self._depth -= 1

    def handle_data(self, data):
        if self._tag:
            self._buf.append(data)
        if self._a is not None:
            self._a["text"].append(data)
        if self._text_depth and not self._skip:
            self._para.append(data)

    def _end_para(self):
        text = " ".join("".join(self._para).split())
        if text:
            self.paras.append(text)
        self._para = []


def novel_url_of(url):
    """The novel a chapter belongs to: its address up to the "chapter…" part of the path
    (or without the last part). kokomux keeps one bookmark per novel, by this."""
    u = urllib.parse.urlsplit(url)
    parts = [p for p in u.path.split("/") if p]
    for k, p in enumerate(parts):
        if p.lower().startswith(("chapter", "chap-", "ch-")):
            parts = parts[:k]
            break
    else:
        parts = parts[:-1]
    return urllib.parse.urlunsplit((u.scheme, u.netloc, "/" + "/".join(parts), "", ""))


def chapter_address(url, number):
    """The address of chapter NUMBER of the novel URL is a chapter of, where its address
    counts chapters (…/chapter-5); None where it doesn't."""
    u = urllib.parse.urlsplit(url)
    head, _, last = u.path.rstrip("/").rpartition("/")
    if not CHAPTER_URL_RE.search(last):
        return None
    last = CHAPTER_URL_RE.sub(lambda m: m.group(0)[:-len(m.group(1))] + str(number), last, count=1)
    return urllib.parse.urlunsplit((u.scheme, u.netloc, f"{head}/{last}", "", ""))


def _nav_link(page, url, novel_url, word):
    """The link to the next (or previous) chapter: rel=next, or a link that says so."""
    host = urllib.parse.urlsplit(url).netloc
    best, score = None, 0
    cands = [{"href": page.rel.get(word, ""), "rel": word, "text": "", "meta": ""}] + page.links
    for a in cands:
        href = urllib.parse.urljoin(url, a["href"]).split("#")[0]
        if not a["href"] or not href.startswith("http") or urllib.parse.urlsplit(href).netloc != host:
            continue
        if href.rstrip("/") in (url.split("#")[0].rstrip("/"), novel_url.rstrip("/")):
            continue
        said = f"{a['text']} {a['meta']}"
        if word == "prev":
            says = re.search(r"\bprev|previous|prev_|prevchap", said)
        else:
            says = re.search(r"\bnext|next_|nextchap", said)
        n = (3 if word in a["rel"] else 0) + (2 if says else 0)
        if n == 0:
            continue
        n += 1 if CHAPTER_URL_RE.search(href) or "chapter" in href.lower() else 0
        if n > score:
            best, score = href, n
    return best


def novel_chapter(html, url):
    """A novel's chapter page, read: a dict with novel (its title), novel_url, label
    ("Chapter 5: Weapon Haggling"), number, text (to speak: the label, then the chapter),
    next and prev (addresses or None), and guessed (next was worked out from the address,
    not linked). None if the page isn't a chapter of something."""
    page = _ChapterPage()
    try:
        page.feed(html)
        page.close()
    except Exception:  # noqa: BLE001 - a page this parser can't follow isn't a chapter page to us
        return None
    last = urllib.parse.urlsplit(url).path.rstrip("/").rpartition("/")[2]
    m_url = CHAPTER_URL_RE.search(last)
    # The page title: "Novel - Chapter 5: Title | Site", or "Chapter 5 - Novel - Site".
    parts = [p.strip() for p in re.split(r"\s+\|\s+", page.title) if p.strip()]
    with_ch = [p for p in parts if CHAPTER_RE.search(p)]
    novel, label = "", ""
    if with_ch:
        t = with_ch[0]
        m = CHAPTER_RE.search(t)
        novel, label = t[:m.start()].strip(" -–—:|"), t[m.start():].strip()
        if not novel:
            bits = re.split(r"\s+[-–—]\s+", label)
            label, novel = bits[0], (bits[1] if len(bits) > 1 else "")
    for h in page.heads:                       # a heading names the chapter better than the title
        if CHAPTER_RE.search(h) and len(h) < 150 and (not label or len(h) >= len(label)):
            label = h if not novel or not h.startswith(novel) else h[len(novel):].strip(" -–—:|")
            break
    number = int(CHAPTER_RE.search(label).group(1)) if CHAPTER_RE.search(label) else (
        int(m_url.group(1)) if m_url else None)
    if number is None:
        return None
    novel_url = novel_url_of(url)
    if not novel:
        novel = next((h for h in page.heads if not CHAPTER_RE.search(h)), "") or \
            urllib.parse.urlsplit(novel_url).path.rstrip("/").rpartition("/")[2].replace("-", " ").title()
    label = label or f"Chapter {number}"
    nxt, prev = _nav_link(page, url, novel_url, "next"), _nav_link(page, url, novel_url, "prev")
    guessed = False
    if not nxt:
        nxt, guessed = chapter_address(url, number + 1), True
    if not (nxt or prev or m_url):
        return None

    # The text: from where these sites keep it, else whatever trafilatura finds.
    lines = page.paras if len(" ".join(page.paras).split()) >= 50 else article_lines(html, url)
    site = re.sub(r"[^a-z0-9]", "", (page.site or urllib.parse.urlsplit(url).netloc.split(".")[-2]).lower())
    names = {re.sub(r"\W", "", x.lower()) for x in (label, novel, f"chapter {number}")}
    keep = []
    for k, line in enumerate(lines):
        bare = re.sub(r"\W", "", line.lower())
        if k < 4 and not keep and (bare in names or (CHAPTER_RE.match(line.strip()) and len(line) < 100)):
            continue                                   # the heading again (it's said first anyway)
        if site and len(site) > 4 and site in bare:
            continue                                   # "Read the latest chapters at freewebnovel.com"
        if _NAV_LINE.match(line.strip()):
            continue
        keep.append(line)
    if len(" ".join(keep).split()) < 20:
        return None
    said = label if re.search(r"[.!?:…]$", label) else label + "."
    return {"novel": novel, "novel_url": novel_url, "url": url, "label": label, "number": number,
            "text": said + "\n" + "\n".join(keep), "next": nxt, "prev": prev, "guessed": guessed}


def novel_next(chapter):
    """The chapter after this one, read, or None if there isn't one (yet)."""
    if not chapter.get("next"):
        return None
    try:
        _, html = fetch(chapter["next"])
    except NotFound:
        return None
    nxt = novel_chapter(html, chapter["next"])
    if not nxt or nxt["novel_url"] != chapter["novel_url"] or nxt["number"] <= chapter["number"]:
        return None                                    # the "next" link left the novel, or went back
    return nxt


# ------------------------------------------------------------------ books

def read_epub(path, name):
    """An EPUB's (title, [(chapter title, text)]): its chapters in reading order."""
    import posixpath
    import xml.etree.ElementTree as ET

    def local(tag):
        return tag.rsplit("}", 1)[-1]

    parts = []
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if "META-INF/encryption.xml" in names and b"EncryptedData" in z.read("META-INF/encryption.xml"):
                raise JobError("That EPUB is DRM-protected, so its text can't be read")
            container = ET.fromstring(z.read("META-INF/container.xml"))
            opf_path = next(el.get("full-path") for el in container.iter() if local(el.tag) == "rootfile")
            opf = ET.fromstring(z.read(opf_path))
            title = next((el.text for el in opf.iter() if local(el.tag) == "title" and el.text), "")
            manifest = {el.get("id"): el.get("href") for el in opf.iter() if local(el.tag) == "item"}
            spine = [el.get("idref") for el in opf.iter()
                     if local(el.tag) == "itemref" and el.get("linear", "yes") != "no"]
            base = posixpath.dirname(opf_path)
            for idref in spine:
                href = manifest.get(idref)
                if not href:
                    continue
                inner = posixpath.normpath(posixpath.join(base, urllib.parse.unquote(href.split("#")[0])))
                if inner not in names:
                    continue
                ex = _ChapterText()
                ex.feed(z.read(inner).decode("utf-8", "replace"))
                chapter = ex.text()
                if chapter.strip():
                    parts.append((ex.heading or f"Part {len(parts) + 1}", chapter))
    except JobError:
        raise
    except (KeyError, StopIteration, zipfile.BadZipFile, ET.ParseError) as e:
        raise JobError(f"Couldn't read that EPUB ({type(e).__name__})") from None
    if not parts:
        raise JobError("That EPUB has no readable text")
    return (title or "").strip() or name, parts


def epub_meta(path):
    """An EPUB's author and cover image, as (author, cover bytes, extension); blanks if it has none."""
    import posixpath
    import xml.etree.ElementTree as ET

    def local(tag):
        return tag.rsplit("}", 1)[-1]
    try:
        with zipfile.ZipFile(path) as z:
            container = ET.fromstring(z.read("META-INF/container.xml"))
            opf_path = next(el.get("full-path") for el in container.iter() if local(el.tag) == "rootfile")
            opf = ET.fromstring(z.read(opf_path))
            author = next((el.text.strip() for el in opf.iter() if local(el.tag) == "creator" and el.text), "")
            items = [el for el in opf.iter() if local(el.tag) == "item"]
            cover_id = next((el.get("content") for el in opf.iter()
                             if local(el.tag) == "meta" and el.get("name") == "cover"), None)
            item = next((el for el in items if "cover-image" in (el.get("properties") or "").split()), None) or \
                next((el for el in items if el.get("id") == cover_id), None)
            if item is None or not (item.get("media-type") or "").startswith("image/"):
                return author, b"", ""
            inner = posixpath.normpath(posixpath.join(posixpath.dirname(opf_path),
                                                      urllib.parse.unquote(item.get("href", ""))))
            ext = {"image/jpeg": "jpg", "image/png": "png"}.get(item.get("media-type"), "")
            return author, (z.read(inner) if ext else b""), ext
    except (KeyError, StopIteration, zipfile.BadZipFile, ET.ParseError):
        return "", b"", ""


def read_pdf(path, name):
    """A PDF's (title, text), with its lines joined back into sentences."""
    from pypdf import PdfReader
    try:
        reader = PdfReader(path)
        if reader.is_encrypted and not reader.decrypt(""):
            raise JobError("That PDF is password-protected")
        pages = [page.extract_text() or "" for page in reader.pages]
        title = (reader.metadata.title if reader.metadata else None) or name
    except JobError:
        raise
    except Exception as e:  # noqa: BLE001 - pypdf raises many kinds of error for broken files
        raise JobError(f"Couldn't read that PDF ({type(e).__name__})") from None
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", "\n".join(pages))        # words split across lines
    lines = [ln.strip() for ln in text.splitlines()]
    lines = [ln for ln in lines if ln and not re.fullmatch(r"\d{1,4}", ln)]   # bare page numbers
    sentences, buf = [], ""
    for ln in lines:   # a line that doesn't end a sentence carries on into the next
        buf = f"{buf} {ln}" if buf else ln
        if re.search(r"[.!?:;\"”’)]$", ln):
            sentences.append(buf)
            buf = ""
    if buf:
        sentences.append(buf)
    if not sentences:
        raise JobError("That PDF has no text to read (is it a scan?)")
    title = str(title).strip() or name
    return title, [(title, "\n".join(sentences))]


def read_source(jobdir, job):
    """What a job reads, as (title, [(chapter title, text)]): a web page, or a file
    that is an EPUB, a PDF or plain text. Only an EPUB has more than one chapter."""
    name = job.get("name", "")
    if job.get("url"):
        log(f"Fetching {job['url']}")
        title, text, chapter = read_page(job["url"])
        return title, [(chapter["label"] if chapter else title, text)]
    file = job.get("file", "in.txt")
    if "/" in file:
        raise JobError(f"Bad input file name: {file}")
    path = os.path.join(jobdir, file)
    with open(path, "rb") as fh:
        head = fh.read(64)
    if head.startswith(b"%PDF"):
        return read_pdf(path, name)
    if head.startswith(b"PK") and zipfile.is_zipfile(path):
        return read_epub(path, name)   # already chapters
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    bad = text.count("\ufffd") + text.count("\x00")
    if text and bad > max(3, len(text) // 100):
        raise JobError("That doesn't look like text, an EPUB or a PDF")
    return name, [(name, text)]


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


def _novel_add(jobdir, chapter, chunks, starts, serial, lang, new=False):
    """Add a novel's chapter to the listen: its parts, and where it starts in the chapter
    list (chapters: "first<TAB>label") and the novel's (serial: "first<TAB>number<TAB>address")."""
    parts = split_text(normalise(chapter["text"], lang))
    if not parts:
        return
    parts[-1] = (parts[-1][0], PAUSE["chapter"])
    first = len(chunks) + 1
    starts.append(f"{first}\t{chapter['label'].replace(chr(9), ' ')[:80]}")
    serial.append(f"{first}\t{chapter['number']}\t{chapter['url']}")
    chunks += parts
    _write_atomic(os.path.join(jobdir, "chapters"), "\n".join(starts) + "\n")
    _write_atomic(os.path.join(jobdir, "serial"), "\n".join(serial) + "\n")
    if new:                                     # the novel, and the chapter before this listen's first
        _write_atomic(os.path.join(jobdir, "novel"),
                      f"title={chapter['novel']}\nurl={chapter['novel_url']}\nprev={chapter['prev'] or ''}\n")


def _novel_more(jobdir, chapter, chunks, starts, serial, lang):
    """The next chapter, added once the listener has reached the last one made (so one
    chapter is always ready ahead, and no more). None at the end of what's out."""
    last = int(serial[-1].split("\t", 1)[0]) if serial else 0
    while True:
        if not os.path.isdir(jobdir):
            raise JobError("Cancelled")
        try:
            with open(os.path.join(jobdir, "at")) as fh:
                at = int(fh.read().strip() or 0)
        except (OSError, ValueError):
            at = 0
        if at >= last:
            break
        time.sleep(0.5)
    log(f"Fetching {chapter['next']}")
    nxt = novel_next(chapter)
    if nxt:
        _novel_add(jobdir, nxt, chunks, starts, serial, lang)
    return nxt


def run_job(path):
    import numpy as np
    import soundfile as sf
    import words

    t0 = time.time()
    jobdir, job = read_job(path)
    engine = tts()

    voice = voice_style(engine, job.get("voice", "af_heart"))
    try:
        speed = float(job.get("speed", "1.0"))
    except ValueError:
        raise JobError("Speed must be a number, e.g. 1.2") from None
    if not 0.5 <= speed <= 2.0:
        raise JobError("Speed must be between 0.5 and 2.0")
    lang = job.get("lang", "en-us")
    g2p = g2p_for(lang)

    out = job.get("out")
    if out:
        if "/" in out or out.rsplit(".", 1)[-1] not in FORMATS:
            raise JobError(f"Output must be one of: {', '.join(FORMATS)}")
        out = os.path.join(jobdir, out)

    stream = None
    timed = False
    if job.get("stream") == "1":
        stream = os.path.join(jobdir, "stream")
        os.makedirs(stream, exist_ok=True)
        # Word timings come from Kokoro itself, with the audio.
        timed = job.get("words") == "1" and engine.has_timings
        if job.get("words") == "1" and not timed:
            _write_atomic(os.path.join(stream, "words-off"), "this model reports no timings (kokoro --update)\n")

    writer = None
    total = 0.0
    try:
        novel = None        # a web novel: read on from chapter to chapter, as the listener gets there
        if job.get("url") and stream:
            log(f"Fetching {job['url']}")
            title, text, novel = read_page(job["url"])
            if novel and job.get("skip") == "1":       # on from a chapter already heard
                heard, novel = novel, novel_next(novel)
                if not novel:
                    raise JobError(f"No new chapter after {heard['label']} yet")
                text = novel["text"]
            chapters = [(novel["label"] if novel else title, text)]
        else:
            title, chapters = read_source(jobdir, job)
        _write_atomic(os.path.join(jobdir, "title"), (title or "").replace("\n", " ")[:120] + "\n")
        if job.get("meta") == "1":            # for an audiobook: who wrote it, and its cover
            src = os.path.join(jobdir, job.get("file", "in.txt"))
            if not job.get("url") and zipfile.is_zipfile(src):
                author, cover, ext = epub_meta(src)
                _write_atomic(os.path.join(jobdir, "author"), author.replace("\n", " ")[:200] + "\n")
                if cover:
                    with open(os.path.join(jobdir, f"cover.{ext}"), "wb") as fh:
                        fh.write(cover)
        # Each chapter is split on its own, so a part never runs across chapters, and
        # where each chapter starts is written down for the player's chapter list.
        chunks, starts, serial = [], [], []
        if novel:
            try:
                base = max(1, int(job.get("base", "1")))   # this chapter's first part, in the whole listen
            except ValueError:
                base = 1
            starts, serial = _novel_listed(jobdir, base)
            chunks = [None] * (base - 1)
            _novel_add(jobdir, novel, chunks, starts, serial, lang, new=base == 1 or not serial)
            chapters = []
        for heading, text in chapters:
            parts = split_text(normalise(text, lang))
            if parts:
                starts.append(f"{len(chunks) + 1}\t{heading.replace(chr(9), ' ').replace(chr(10), ' ')[:80]}")
                parts[-1] = (parts[-1][0], PAUSE["chapter"])         # a longer breath between chapters
                chunks += parts
        if not chunks:
            raise JobError("No text to read")
        if len(starts) > 1 and not novel:
            _write_atomic(os.path.join(jobdir, "chapters"), "\n".join(starts) + "\n")
        try:
            first = int(job.get("start", "1"))
        except ValueError:
            first = 1
        try:
            was = int(job.get("of", "0"))       # the bookmark's count of parts, if it had one
        except ValueError:
            was = 0
        if was > 1 and was != len(chunks):
            # Made with another version's parts: the same place, as a fraction of the text.
            first = 1 + round((first - 1) * len(chunks) / was)
        if novel:
            first = max(first, int(serial[-1].split("\t", 1)[0]))   # (not before this chapter)
        elif not 1 <= first <= len(chunks):
            first = 1   # a bookmark past the end: start again
        if stream:
            _write_atomic(os.path.join(stream, "first"), f"{first}\n")

        # (a novel's parts aren't kept: each listen numbers them from where it began)
        cache = None if novel else cache_dir(job.get("cache", ""), job.get("voice", "af_heart"), speed, lang)
        if cache and engine.has_timings:
            timed = True                    # kept parts carry their word timings, whatever this run shows
        chapter_at = {int(line.split("\t", 1)[0]): line.split("\t", 1)[1] for line in starts}
        chapter_times, written = [], 0
        i = first - 1
        while True:
            i += 1
            if i > len(chunks) and not (novel and (novel := _novel_more(jobdir, novel, chunks, starts,
                                                                        serial, lang))):
                break
            chunk, pause = chunks[i - 1]
            # The kokoro command deletes its job folder when it exits or is
            # stopped, so a vanished folder means nobody is waiting any more.
            if not os.path.isdir(jobdir) or (stream and not os.path.isdir(stream)):
                raise JobError("Cancelled")
            _write_atomic(os.path.join(jobdir, "progress"), f"{i} {len(chunks)}\n")
            name = f"{i:04d}"
            got = cached_part(cache, name, chunk) if cache else None
            if got:
                info = sf.info(got)
                rate, length = info.samplerate, info.frames
                samples = sf.read(got, dtype="float32")[0] if out else None
            else:
                samples, rate, marks = synthesize(engine, g2p, chunk, voice, speed, lang, timed, i)
                samples = np.concatenate([edges(samples, rate), silence(pause, rate)])   # mpv plays them back to back
                length = len(samples)
                home = cache or stream
                if home:
                    # The text and word timings first, so the player has them when the audio lands.
                    _write_atomic(os.path.join(home, f"{name}.txt"), chunk)
                    if marks is not None:
                        _write_atomic(os.path.join(home, f"{name}.words"), words.player_rows(chunk, marks))
                    tmp = os.path.join(home, f"{name}.tmp.wav")
                    sf.write(tmp, samples, rate)
                    os.replace(tmp, os.path.join(home, f"{name}.wav"))
            if stream and cache:
                for ext in ("txt", "words", "wav"):                  # (the audio last: the player waits for it)
                    src = os.path.join(cache, f"{name}.{ext}")
                    if os.path.exists(src):
                        _link(src, os.path.join(stream, f"{name}.{ext}"))
            if chapter_at.get(i) is not None and out:
                chapter_times.append((round(written / rate * 1000), chapter_at[i]))
            total += length / rate
            if out:
                # Written as it's made, so a whole book never has to fit in memory.
                if writer is None:
                    writer = sf.SoundFile(out, "w", samplerate=rate, channels=1)
                if i == len(chunks):
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
    _write_atomic(os.path.join(jobdir, "result"), f"{total:.1f} {elapsed:.1f} {len(chunks)}\n")
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
