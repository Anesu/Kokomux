"""Word timings for spoken lines, from Whistle (Cactus Compute).

Runs inside the "kokoro" Debian container, next to engine.py.

  Recognise  Whistle transcribes a rendered line and returns each word it heard
             with start and end times.
  Match      Whistle is not a forced aligner, so its words are paired with the
             line's own words by an edit-distance alignment. Numbers match their
             spoken form ("60" <-> "sixty"), hyphenated words are matched as one
             ("worn-out" <-> "wornout"), and a respelled name takes the run of
             words the voice said for it ("Igdrasil" <-> "Igg Dressel").
  Store      Each line's result is cached as <audio_hash>.words.json:
             {v, engine, confidence, words: [{s, e, a, b, p}]} where s/e are the
             start and end in ms, a/b the character range in the line's text,
             and p the recogniser's confidence in that word.

The matching is a port of light-novel-studio's apps/server/src/words.ts.
"""
import hashlib
import json
import os
import re
import threading

LINE_WORDS_VERSION = 1
# Below this, a line's transcript disagrees with its text too much to trust:
# it gets no highlight rather than a wrong one.
WORDS_MIN_CONFIDENCE = 0.35
# Languages Whistle transcribes. Anything else would come back as a detected,
# useless transcript, so it isn't attempted.
SUPPORTED = {"en", "de", "fr", "es", "it", "nl", "pl"}
CACHE_DIR = os.environ.get("KOKORO_WORDS_CACHE", "/opt/kokoro/cache/words")
# cactus-compute 4.1 transcribes a 35 s line in one pass in ~4 s; its streaming API took
# ~40 s for the same line. Only lines past this go through streaming.
LIMIT_S = 120.0
CHUNK_S = 1.0    # the buffer size that API expects
RATE = 16000     # Whistle listens at 16 kHz

os.environ.setdefault("CACTUS_TELEMETRY", "0")   # kokomux talks to nobody it doesn't have to
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")


# ---------------------------------------------------------------- the words of a line

_TOKEN = re.compile(r"<[^>\n]*>|[^\W\d_][\w'’]*|\d+")


def word_tokens(text):
    """The line's words as (start, end, word), by character. Inline tags are not words,
    and neither is a hyphen: "worn-out" is two words, as it is to a recogniser."""
    return [(m.start(), m.end(), m.group()) for m in _TOKEN.finditer(text) if not m.group().startswith("<")]


_NUMBERS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
    "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
    "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19, "twenty": 20, "thirty": 30,
    "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90, "hundred": 100,
    "thousand": 1000,
}
_ORDINALS = {
    "first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8,
    "ninth": 9, "tenth": 10, "eleventh": 11, "twelfth": 12, "thirteenth": 13, "fourteenth": 14,
    "fifteenth": 15, "sixteenth": 16, "seventeenth": 17, "eighteenth": 18, "nineteenth": 19,
    "twentieth": 20, "thirtieth": 30, "fortieth": 40, "fiftieth": 50, "sixtieth": 60, "seventieth": 70,
    "eightieth": 80, "ninetieth": 90,
}


def word_key(word):
    """What a word means for matching: "60", "60." and "sixty" are all #60."""
    t = re.sub(r"[^a-z0-9]", "", word.lower())
    m = re.fullmatch(r"(\d{1,6})(?:st|nd|rd|th)?", t)
    if m:
        return f"#{int(m.group(1))}"
    if t in _NUMBERS:
        return f"#{_NUMBERS[t]}"
    if t in _ORDINALS:
        return f"#{_ORDINALS[t]}"
    return re.sub(r"[^\w']|_", "", word.lower().replace("’", "'"))


def align_pairs(written, spoken):
    """For each written word, the index of the spoken word it is, or None.

    Edit-distance alignment (match 0, substitute 1, skip 1) that prefers pairing
    over skipping, so one dropped or inserted word resyncs the rest of the line
    instead of sliding every later word."""
    n, m = len(written), len(spoken)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = i
    for j in range(1, m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            pair = dp[i - 1][j - 1] + (0 if written[i - 1] == spoken[j - 1] else 1)
            dp[i][j] = min(pair, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
    pairs = [None] * n
    i, j = n, m
    while i > 0 and j > 0:
        same = written[i - 1] == spoken[j - 1]
        if dp[i][j] == dp[i - 1][j - 1] + (0 if same else 1):
            pairs[i - 1] = j - 1   # keep a substituted word pinned to its own moment
            i, j = i - 1, j - 1
        elif dp[i][j] == dp[i - 1][j] + 1:
            i -= 1                 # a written word the recogniser never returned
        else:
            j -= 1                 # a spoken word with nothing written for it
    return pairs


def _units(written, text):
    """Hyphen-joined words matched as one unit ("worn-out" is "wornout" to a recogniser)."""
    out = []
    for i, (s, _e, _w) in enumerate(written):
        if out and text[written[i - 1][1]:s] == "-":
            out[-1].append(i)
        else:
            out.append([i])
    return out


def map_words(display, asr, engine):
    """A line's recogniser output as timings for the words in its text, or None.

    asr is Whistle's word list: [{"word", "start", "end", "probability"}], seconds.
    A recognised word takes its own times; the rest share out the time between the
    words around them, by length, with p = 0. Windows stay in order, never overlap
    and are never empty, so exactly one word is lit at a time."""
    written = word_tokens(display)
    if not written or not asr:
        return None
    groups = _units(written, display)

    def key(group):
        return "".join(word_key(written[i][2]) for i in group)

    spoken = [word_key(w["word"]) for w in asr]
    pairs = align_pairs([key(g) for g in groups], spoken)
    times = [None] * len(written)   # (from_ms, to_ms, p)

    def put(group, start, end, p):
        """Shares one window across the written words it covers, by length."""
        weight = sum(len(written[i][2]) for i in group) or 1
        cursor = start
        for i in group:
            share = (end - start) * len(written[i][2]) / weight
            times[i] = (cursor, cursor + share, p)
            cursor += share

    claimed = {j for j in pairs if j is not None}
    exact = matched = 0
    sure = 0.0
    for g, j in enumerate(pairs):
        if j is None:
            continue
        w = asr[j]
        first = last = j
        # A word the recogniser didn't quite agree with has usually swallowed its
        # neighbours ("Igdrasil" heard as "Igg Dressel"): it takes any spoken words
        # beside it that nothing else claimed.
        if key(groups[g]) != spoken[j]:
            while first > 0 and first - 1 not in claimed:
                first -= 1
                claimed.add(first)
            while last + 1 < len(asr) and last + 1 not in claimed:
                last += 1
                claimed.add(last)
        prob = float(w.get("probability", 0))
        put(groups[g], asr[first]["start"] * 1000, asr[last]["end"] * 1000, prob)
        matched += 1
        sure += prob
        if key(groups[g]) == spoken[j]:
            exact += 1
    if not matched:
        return None

    mean = sum(max(0.0, w["end"] - w["start"]) for w in asr) / len(asr) * 1000

    def spread(run, at, until):
        """Words between two recognised ones share the time between them."""
        weight = sum(len(key(g)) + 1 for g in run)
        room = max(0.0, until - at)
        cursor = at
        for g in run:
            share = room * (len(key(g)) + 1) / weight if room > 0 else mean
            put(g, cursor, cursor + max(40.0, share), 0.0)
            cursor += max(40.0, share)

    anchors = [g for g, j in enumerate(pairs) if j is not None]
    first, last = anchors[0], anchors[-1]
    if first > 0:
        spread(groups[:first], 0.0, times[groups[first][0]][0])
    for k in range(len(anchors) - 1):
        a, b = groups[anchors[k]], groups[anchors[k + 1]]
        spread(groups[anchors[k] + 1:anchors[k + 1]], times[a[0]][1], times[b[0]][0])
    if last < len(groups) - 1:
        start = times[groups[last][-1]][1]
        spread(groups[last + 1:], start, start + mean * (len(groups) - 1 - last))

    words = []
    floor = 0
    for i, (a, b, _w) in enumerate(written):
        t = times[i] or (floor, floor + mean, 0.0)
        s = max(floor, round(max(0.0, t[0])))
        nxt = times[i + 1][0] if i + 1 < len(written) and times[i + 1] else None
        ceiling = float("inf") if nxt is None else max(s + 1, round(nxt))
        e = min(max(s + 1, round(t[1])), ceiling)
        words.append({"s": int(s), "e": int(e), "a": a, "b": b, "p": round(t[2], 3)})
        floor = e

    return {
        "v": LINE_WORDS_VERSION,
        "engine": engine,
        "confidence": round(exact / len(groups) * (sure / matched), 3),
        "words": words,
    }


# ---------------------------------------------------------------- recognising

_model = None
_model_lock = threading.Lock()


def engine_name():
    try:
        import importlib.metadata
        return "whistle-" + importlib.metadata.version("cactus-compute")
    except Exception:
        return "whistle"


def available():
    import importlib.util
    return importlib.util.find_spec("cactus") is not None


def _whistle():
    """Whistle, loaded once. Offline first: once its engine and weights are cached
    (the installer fetches them), it needs no network, which a phone often lacks.
    Only if something isn't cached yet does it go online to fetch it."""
    global _model
    with _model_lock:
        if _model is None:
            import huggingface_hub.constants as hub
            from cactus.agent.whistle import Whistle
            saved = hub.HF_HUB_OFFLINE
            try:
                hub.HF_HUB_OFFLINE = True
                _model = Whistle()
            except Exception:
                hub.HF_HUB_OFFLINE = saved
                _model = Whistle()
            finally:
                hub.HF_HUB_OFFLINE = saved
    return _model


def to_16k(samples, rate):
    """Kokoro renders 24 kHz; Whistle listens at 16 kHz."""
    import numpy as np
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    if rate == RATE or not len(x):
        return x
    n = int(len(x) * RATE / rate)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)


def recognise(samples, rate, language):
    """Whistle's words for one line: [{"word", "start", "end", "probability"}]."""
    model = _whistle()
    x = to_16k(samples, rate)
    lang = language if language in SUPPORTED else None
    if len(x) / RATE <= LIMIT_S:
        return model.transcribe(x, language=lang, word_timestamps=True).get("words") or []
    words, step = [], int(RATE * CHUNK_S)
    for part in model.stream((x[i:i + step] for i in range(0, len(x), step)), language=lang):
        words.extend(part.get("words") or [])
    return words


# ---------------------------------------------------------------- the cache

def audio_hash(samples):
    import numpy as np
    return hashlib.sha256(np.asarray(samples, dtype=np.float32).tobytes()).hexdigest()[:20]


def cache_path(h):
    return os.path.join(CACHE_DIR, f"{h}.words.json")


def read_cached(h):
    try:
        with open(cache_path(h), encoding="utf-8") as fh:
            data = json.load(fh)
        return data if data.get("v") == LINE_WORDS_VERSION else None   # an old mapping is redone
    except (OSError, ValueError):
        return None


def write_cached(h, data):
    os.makedirs(CACHE_DIR, exist_ok=True)
    tmp = cache_path(h) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, separators=(",", ":"))
    os.replace(tmp, cache_path(h))


def line_words(text, samples, rate, language):
    """The cached or freshly made timings for one rendered line. A line whose
    transcript doesn't match is cached too (confidence 0), so it is never
    transcribed twice for the same answer."""
    h = audio_hash(samples)
    cached = read_cached(h)
    if cached is not None:
        return cached
    lang = (language or "")[:2].lower()
    if lang not in SUPPORTED:
        data = {"v": LINE_WORDS_VERSION, "engine": "none", "confidence": 0, "words": [],
                "note": f"Whistle doesn't transcribe {language}"}
    else:
        asr = recognise(samples, rate, lang)
        data = map_words(text, asr, engine_name()) or \
            {"v": LINE_WORDS_VERSION, "engine": engine_name(), "confidence": 0, "words": []}
    write_cached(h, data)
    return data


def byte_ranges(text, words):
    """The same words as UTF-8 byte ranges, which is what the bash player slices by."""
    out, pos, byte = [], 0, 0
    for w in words:
        byte += len(text[pos:w["a"]].encode("utf-8"))
        a = byte
        byte += len(text[w["a"]:w["b"]].encode("utf-8"))
        pos = w["b"]
        out.append((a, byte))
    return out
