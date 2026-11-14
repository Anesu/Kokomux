"""kokomux on Android: the text side, run by Chaquopy.

engine.py and words.py are copied in from src/ when the app is built, so the
phone reads, tidies, splits and pronounces text with the same code the Termux
version runs: read_source (EPUB, PDF, text, web pages and novel chapters),
normalise and split_text, misaki through g2p_for (espeak-ng for the words it
doesn't know, and for the other languages), and words.py for word timings.
Kokoro itself, the part cache and the player are Kotlin (kokomux.engine and
kokomux.app). Results go back to Kotlin as JSON.

Web novels: chapter() reads one chapter (engine.py's novel_chapter), toc() a
novel's list of chapters, and search() finds a novel on the sites it knows.
A site that wants a browser check before it shows a page (Cloudflare) is read
through the app's own browser (kokomux.app.Browser), and from then on so is
everything from that site.
"""
import json
import os
import re
import sys
import tempfile
import time
import types
from collections import namedtuple

# A sound and when it's spoken, as kokoro-onnx hands them to words.py.
Timing = namedtuple("Timing", "phoneme start end")
_VOCAB = frozenset()          # the sounds Kokoro knows (kokomux.engine.Vocab)
_BOOKS = {}                   # what read() last read, by source: a voice in another language reads it again
_BROWSER = set()              # sites that wanted a browser check: read through the app's browser


def setup(espeak_library, espeak_data, tmp, vocab):
    """Point phonemizer at the app's espeak-ng and its data, and engine.py at
    urllib for web pages (once, before anything else)."""
    global _VOCAB
    _VOCAB = frozenset(vocab)
    os.environ["TMPDIR"] = tmp
    tempfile.tempdir = tmp
    os.environ["JOBLIB_MULTIPROCESSING"] = "0"   # phonemizer's joblib: one process (Android has no semaphores for it)
    # phonemizer imports dlinfo, which needs glibc's dlinfo(); bionic has none.
    # It's only used for a library given by bare name, and this one has a path.
    stub = types.ModuleType("dlinfo")

    class DLInfo:
        def __init__(self, cdll):
            raise RuntimeError("dlinfo isn't available on Android")
    stub.DLInfo = DLInfo
    sys.modules.setdefault("dlinfo", stub)
    from phonemizer.backend.espeak.wrapper import EspeakWrapper
    EspeakWrapper.set_library(espeak_library)
    EspeakWrapper.set_data_path(espeak_data)
    import engine
    engine.fetch = fetch


def _browser(url, form=None):
    """The page as the app's browser shows it once the site's check has passed: (type, text)."""
    import engine
    from java import jclass
    html = jclass("kokomux.app.Browser").fetch(url, form)
    if html is None:
        raise engine.JobError("That site wants a browser check (Cloudflare) before it shows the page")
    if re.search(r"<title>\s*(?:404|Page not found|Not found)", html[:3000], re.I):
        raise engine.NotFound(f"There's no page at {url}")
    return "text/html", str(html)


def fetch(url, form=None):
    """engine.fetch without curl, which Android doesn't have: the same phone
    browser's user agent, the same limits, and the same answers and errors; a
    site that wants a browser check is read through the app's browser. With
    [form] (a dict), the page is asked for by posting it, as a search box does."""
    import gzip
    import ssl
    import urllib.error
    import urllib.request
    import zlib

    import urllib.parse

    import certifi
    import engine
    if not re.match(r"^https?://", url):
        raise engine.JobError(f"Not a web address: {url}")
    host = urllib.parse.urlsplit(url).netloc
    body = urllib.parse.urlencode(form) if form else None
    if host in _BROWSER:
        return _browser(url, body)
    req = urllib.request.Request(url, data=body.encode() if body else None,
                                 headers={"User-Agent": engine.USER_AGENT, "Accept-Encoding": "gzip, deflate"})
    context = ssl.create_default_context(cafile=certifi.where())
    try:
        try:
            r = urllib.request.urlopen(req, timeout=30, context=context)
        except urllib.error.HTTPError as e:
            r = e                                     # an error page still has a code and a body
        with r:
            code, ctype = str(r.status), r.headers.get("Content-Type", "")
            raw = r.read(5_000_001)
            encoding = r.headers.get("Content-Encoding", "")
    except (urllib.error.URLError, OSError) as e:
        raise engine.JobError(f"Couldn't fetch {url}: {getattr(e, 'reason', e)}") from None
    if len(raw) > 5_000_000:
        raise engine.JobError(f"Couldn't fetch {url}: the page is bigger than 5 MB")
    if encoding == "gzip":
        raw = gzip.decompress(raw)
    elif encoding == "deflate":
        raw = zlib.decompress(raw)
    m = re.search(r"charset=([\w-]+)", ctype)
    try:
        body = raw.decode(m.group(1) if m else "utf-8", errors="replace")
    except LookupError:                       # a charset Python doesn't know: read it as UTF-8
        body = raw.decode("utf-8", errors="replace")
    if code == "404":
        raise engine.NotFound(f"There's no page at {url}")
    if re.search(r"<title>\s*Just a moment", body[:3000]) or (code == "403" and "challenge-platform" in body):
        try:
            got = _browser(url, body)
        except ImportError:                    # (off the phone: no browser to ask)
            raise engine.JobError("That site wants a browser check (Cloudflare) before it shows the page") from None
        _BROWSER.add(host)
        return got
    if not code.startswith("2"):
        raise engine.JobError(f"Couldn't fetch {url}: the site answered {code}")
    return ctype.split(";")[0].strip().lower(), body


def read(source, name, lang):
    """A file (EPUB, PDF or text) or a web page, ready to make, as JSON:
    {"title", "parts": [[text, pause after it], ...], "chapters": [[first part, title], ...]},
    or {"error": "..."}. Split as engine.py's run_job splits it: each chapter on
    its own, with a chapter's pause after its last part."""
    import engine
    try:
        if source not in _BOOKS:
            novel = None
            if re.match(r"^https?://", source):
                title, text, chapter = engine.read_page(source)
                got = (title, [(chapter["label"] if chapter else title, text)])
                novel = _novel(chapter)
            else:
                got = engine.read_source(os.path.dirname(source), {"file": os.path.basename(source), "name": name})
            _BOOKS.clear()
            _BOOKS[source] = got + (novel,)
        title, chapters, novel = _BOOKS[source]
        parts, starts = [], []
        for heading, text in chapters:
            split = engine.split_text(engine.normalise(text, lang))
            if split:
                starts.append([len(parts) + 1, heading.replace("\t", " ").replace("\n", " ")[:80]])
                split[-1] = (split[-1][0], engine.PAUSE["chapter"])
                parts += split
        if not parts:
            raise engine.JobError("No text to read")
    except engine.JobError as e:
        return json.dumps({"error": str(e)})
    return json.dumps({"title": (title or "").replace("\n", " ")[:120], "parts": parts, "chapters": starts, "novel": novel})


def _novel(chapter):
    """What Kotlin keeps of a novel's chapter: the novel, the chapter, and the chapters either side."""
    if not chapter:
        return None
    return {k: chapter.get(k) for k in ("novel", "novel_url", "url", "label", "number", "next", "prev")}


def chapter(url):
    """A novel's chapter, read: {"novel": the _novel details, "text"}, {"none": true} when there's no
    such page (yet), or {"error"}. Its text starts with its label, as read() reads one."""
    import engine
    try:
        _, html = engine.fetch(url)
        got = engine.novel_chapter(html, url)
        if not got:
            return json.dumps({"error": "That page isn't a chapter of a novel"})
        return json.dumps({"novel": _novel(got), "text": got["text"]})
    except engine.NotFound:
        return json.dumps({"none": True})
    except engine.JobError as e:
        return json.dumps({"error": str(e)})


def toc(novel_url, any_chapter):
    """A novel's chapters, from its own page: [[number, label, address], ...] in order. The
    chapters a site doesn't list there (it shows only the latest, or pages its list) are filled in
    from the chapter addresses, which count: …/chapter-5. {"error"} if the page can't be read."""
    import engine
    try:
        _, html = engine.fetch(novel_url)
    except engine.JobError as e:
        return json.dumps({"error": str(e)})
    host = urllib_parse().urlsplit(novel_url).netloc
    path = urllib_parse().urlsplit(novel_url).path.rstrip("/")
    found = {}
    for m in re.finditer(r'<a\b[^>]*href="([^"#]+)"[^>]*>(.*?)</a>', html, re.S | re.I):
        href = urllib_parse().urljoin(novel_url, m.group(1).strip())
        u = urllib_parse().urlsplit(href)
        last = u.path.rstrip("/").rpartition("/")[2]
        n = engine.CHAPTER_URL_RE.search(last)
        if u.netloc != host or not u.path.startswith(path + "/") or not n:
            continue
        label = " ".join(re.sub(r"<[^>]+>", " ", m.group(2)).split())
        label = __import__("html").unescape(label)
        number = int(n.group(1))
        if number not in found or (len(label) > len(found[number][0]) and engine.CHAPTER_RE.search(label)):
            found[number] = (label or f"Chapter {number}", href.split("#")[0])
    any_chapter = any_chapter or (found[min(found)][1] if found else "")
    if any_chapter and engine.chapter_address(any_chapter, 1) and found:
        for number in range(1, max(found) + 1):
            if number not in found:
                found[number] = (f"Chapter {number}", engine.chapter_address(any_chapter, number))
    return json.dumps([[n, found[n][0], found[n][1]] for n in sorted(found)])


def urllib_parse():
    import urllib.parse
    return urllib.parse


# Where the novel sites kokomux knows look up a title: the search box's address and the name it
# sends the words under (posted, as the site's own search box does).
SEARCH = {
    "freewebnovel.com": ("https://freewebnovel.com/search/", "searchkey"),
}


def search(query, site="freewebnovel.com"):
    """Novels whose title has [query] in it, on [site]: [[title, address, latest chapter], ...];
    {"error"} if the site can't be read."""
    import engine
    url, field = SEARCH[site]
    try:
        _, html = fetch(url, {field: query.strip()})
    except engine.JobError as e:
        return json.dumps({"error": str(e)})
    return json.dumps(search_results(html, url))


def search_results(html, url):
    """A search page's novels: each result's link to a novel's own page (not a chapter), its
    title, and the latest chapter it mentions."""
    import engine
    host = urllib_parse().urlsplit(url).netloc
    out, seen = [], set()
    # One result per block that links to a novel: its first such link and the chapter link after it.
    for m in re.finditer(r'<a\b[^>]*href="([^"#]+)"[^>]*>(.*?)</a>', html, re.S | re.I):
        href = urllib_parse().urljoin(url, m.group(1).strip())
        u = urllib_parse().urlsplit(href)
        parts = [p for p in u.path.split("/") if p]
        text = " ".join(__import__("html").unescape(re.sub(r"<[^>]+>", " ", m.group(2))).split())
        title_attr = re.search(r'title="([^"]*)"', m.group(0))
        if u.netloc != host or len(parts) < 2 or parts[0] not in ("novel", "book", "series"):
            continue
        if len(parts) == 2:
            name = text or (__import__("html").unescape(title_attr.group(1)) if title_attr else "")
            if href not in seen and name:
                seen.add(href)
                out.append([name, href, ""])
        elif out and engine.CHAPTER_URL_RE.search(parts[-1]) and href.startswith(out[-1][1]) and not out[-1][2]:
            out[-1][2] = text
    return out[:21]


def parts(text, lang):
    """The parts a text is read in, as JSON: [[text, pause after it], ...]."""
    import engine
    return json.dumps(engine.split_text(engine.normalise(text, lang)))


def _espeak(text, lang):
    """A text's sounds from espeak, as kokoro-onnx phonemises them (for other
    languages, and English that misaki can't parse)."""
    import phonemizer
    ps = phonemizer.phonemize(text.strip(), lang, preserve_punctuation=True, with_stress=True)
    return "".join(p for p in ps if p in _VOCAB).strip()


def _espeak_words(words, lang):
    """Each word's sounds from espeak on its own, as engine.phonemize_many makes them."""
    import phonemizer
    out = phonemizer.phonemize([w.strip() for w in words], lang, preserve_punctuation=True, with_stress=True)
    return ["".join(ch for ch in p if ch in _VOCAB) for p in out]


def speakable(text, lang):
    """A part's sounds as engine.synthesize gets them, as JSON: {"ps", "per"}:
    misaki's for English, with each word's own sounds (per) for its timing;
    espeak's otherwise, with per null (word_rows asks espeak for them)."""
    import engine
    g2p = engine.g2p_for(lang)
    if g2p is not None:
        try:
            ps, per = engine.dictionary_phonemes(g2p, text)
            if ps:
                return json.dumps({"ps": ps, "per": per})
        except Exception as e:      # a sentence misaki can't parse: espeak reads it
            engine.log(f"misaki: {e.__class__.__name__}: {e}")
    return json.dumps({"ps": _espeak(text, lang), "per": None})


def sounds(text, lang):
    """A part's sounds, as engine.py makes them."""
    return json.loads(speakable(text, lang))["ps"]


def word_rows(text, lang, per, phonemes, starts, ends):
    """A part's word timings in the player's form (words.player_rows), from the
    sounds Kokoro spoke (phonemes, with when each started and ended) and each
    word's own sounds (per, from speakable; null: espeak's)."""
    import words
    per = json.loads(per)
    timings = [Timing(p, float(s), float(e)) for p, s, e in zip(phonemes, starts, ends)]
    marks = words.words_from_timings(text, timings, (lambda ws: per) if per is not None
                                     else (lambda ws: _espeak_words(ws, lang)))
    return words.player_rows(text, marks)


def warm(lang):
    """Load misaki and spaCy for this accent; returns the seconds it took."""
    t0 = time.time()
    sounds("Hello.", lang)
    return time.time() - t0


def check(expected):
    """Every case in pronunciation.json through misaki here, as JSON:
    {"cases": N, "seconds": S, "differ": [{lang, text, want, got}, ...]}."""
    cases = json.loads(expected)["cases"]
    t0 = time.time()
    differ = []
    for case in cases:
        got = sounds(case["text"], case["lang"])
        if got != case["sounds"]:
            differ.append({"lang": case["lang"], "text": case["text"], "want": case["sounds"], "got": got})
    return json.dumps({"cases": len(cases), "seconds": round(time.time() - t0, 2), "differ": differ})
