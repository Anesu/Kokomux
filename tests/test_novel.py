"""Tests for web novels (src/engine.py): reading a chapter page, and listening on
from chapter to chapter.

Run with:  python3 tests/test_novel.py   (or pytest). Needs curl and trafilatura;
the chapter-to-chapter tests also need numpy and soundfile. A made-up novel is
served from this machine, laid out the way novel sites lay out a chapter.
"""
import http.server
import os
import shutil
import sys
import threading
import time

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "src"))
sys.path.insert(0, HERE)
import engine  # noqa: E402

try:
    import trafilatura  # noqa: F401
except ImportError:
    trafilatura = None
try:
    from test_job import np, run
except ImportError:
    np = run = None

os.environ["no_proxy"] = os.environ["NO_PROXY"] = "127.0.0.1,localhost"   # (the novel is served here)

NAMES = {1: "Departure", 2: "The Ridge", 3: "The River"}
PARA = ("She walked on through the long afternoon, past the walls and the empty fields, "
        "and thought about the road ahead and the people she had left behind her.")


def chapter_page(n):
    nav = (f'<div class="ul-list7"><a id="prev_url" title="Prev Chapter" href="{f"/novel/the-long-road/chapter-{n - 1}" if n > 1 else "#"}">Prev Chapter</a>'
           f' <a href="/novel/the-long-road">Index</a>'
           + (f' <a id="next_url" title="Next Chapter" href="/novel/the-long-road/chapter-{n + 1}">Next Chapter</a>' if n < 3 else "")
           + "</div>")
    paras = "".join(f"<p>{PARA} This is paragraph {k} of chapter {n}.</p>" for k in range(1, 6))
    return f"""<!DOCTYPE html><html><head>
<title>The Long Road - Chapter {n}: {NAMES[n]} | Free Web Novel</title>
<meta property="og:site_name" content="Free Web Novel"></head><body>
<div class="header"><a href="/">Home</a> <a href="/genre">Genres</a> <a href="/novel/the-long-road">The Long Road</a></div>
<div class="m-read"><div class="top"><h1 class="tit"><a href="/novel/the-long-road">The Long Road</a></h1>
<span class="chapter">Chapter {n}: {NAMES[n]}</span></div>
{nav}
<div class="txt"><div id="article"><h4>Chapter {n}: {NAMES[n]}</h4>{paras}
<p>Read the latest chapters at freewebnovel.com</p><script>showAds();</script></div></div>
{nav}
<div id="comments"><h3>Comments</h3><p>Great chapter, thanks for the update!</p></div>
</div></body></html>"""


class Site(http.server.BaseHTTPRequestHandler):
    hits = []

    def do_GET(self):
        Site.hits.append(self.path)
        path = self.path.rstrip("/")
        n = path.rsplit("-", 1)[-1]
        ctype = "text/html; charset=utf-8"
        if path.endswith("/odd-charset"):          # a charset no one has heard of
            body, code, ctype = "<html><body><p>Café au lait.</p></body></html>".encode(), 200, "text/html; charset=x-made-up"
        elif path.startswith("/novel/the-long-road/chapter-") and n.isdigit() and int(n) in NAMES:
            body, code = chapter_page(int(n)).encode(), 200
        else:
            body, code = b"<html><body>Not found</body></html>", 404
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


_server = None


def site():
    global _server
    if _server is None:
        _server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Site)
        threading.Thread(target=_server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{_server.server_address[1]}/novel/the-long-road"


def needs(*mods):
    def deco(fn):
        def wrapper():
            if trafilatura is None or ("audio" in mods and np is None) or not shutil.which("curl"):
                return
            fn()
        wrapper.__name__ = fn.__name__
        return wrapper
    return deco


def test_a_page_in_an_unknown_charset_is_read_as_utf8():
    if not shutil.which("curl"):
        return
    ctype, body = engine.fetch(site() + "/odd-charset")
    assert ctype == "text/html" and "Café au lait." in body


def test_addresses():
    url = "https://freewebnovel.com/novel/starting-with-an-sss-rank-goddess-summon/chapter-5"
    assert engine.novel_url_of(url) == "https://freewebnovel.com/novel/starting-with-an-sss-rank-goddess-summon"
    assert engine.chapter_address(url, 12).endswith("/starting-with-an-sss-rank-goddess-summon/chapter-12")
    assert engine.novel_url_of("https://x.org/fiction/21/mother/chapter/456/a-title") == "https://x.org/fiction/21/mother"
    assert engine.chapter_address("https://x.org/blog/a-post", 3) is None


@needs()
def test_a_chapter_page_is_read_as_a_chapter():
    base = site()
    title, text, ch = engine.read_page(f"{base}/chapter-2")
    assert ch["novel"] == title == "The Long Road"
    assert ch["label"] == "Chapter 2: The Ridge" and ch["number"] == 2
    assert ch["novel_url"] == base
    assert ch["next"] == f"{base}/chapter-3" and ch["prev"] == f"{base}/chapter-1" and not ch["guessed"]
    lines = text.split("\n")
    assert lines[0] == "Chapter 2: The Ridge."                       # said first, once
    assert len(lines) == 6 and lines[-1].endswith("paragraph 5 of chapter 2.")
    assert "freewebnovel" not in text.lower() and "Great chapter" not in text and "Next" not in text


def test_lines_that_mention_the_chapter_are_kept():
    page = chapter_page(1).replace(PARA, "She walked on.")                # short lines, "…of chapter 1."
    ch = engine.novel_chapter(page, "https://example.org/novel/the-long-road/chapter-1")
    assert ch["text"].split("\n")[1:] == [f"She walked on. This is paragraph {k} of chapter 1." for k in range(1, 6)]


@needs()
def test_the_last_chapter_out_has_no_next_yet():
    base = site()
    _, _, ch = engine.read_page(f"{base}/chapter-3")
    assert ch["next"] == f"{base}/chapter-4" and ch["guessed"]       # worked out from the address
    assert engine.novel_next(ch) is None                              # (404: not out yet)


def test_titles_written_the_other_way_round():
    page = chapter_page(1).replace("The Long Road - Chapter 1: Departure | Free Web Novel",
                                   "Chapter 1 - The Long Road - Free Web Novel")
    page = page.replace('<span class="chapter">Chapter 1: Departure</span>', "")
    ch = engine.novel_chapter(page, "https://example.org/novel/the-long-road/chapter-1")
    assert ch["novel"] == "The Long Road" and ch["label"] == "Chapter 1"


def test_an_ordinary_page_is_not_a_chapter():
    page = "<html><head><title>How to bake bread</title></head><body><article>" + \
        "<p>Mix the flour and water, then knead it for ten minutes until smooth.</p>" * 8 + "</article></body></html>"
    assert engine.novel_chapter(page, "https://example.org/recipes/bread") is None


def lines(path):
    with open(path) as f:
        return f.read().splitlines()


@needs("audio")
def test_listening_goes_on_from_chapter_to_chapter():
    base = site()
    jobdir = None
    try:
        jobdir, _ = run("", url=f"{base}/chapter-1", words="0", files={"at": "999"})
        assert lines(os.path.join(jobdir, "title")) == ["The Long Road"]
        serial = [ln.split("\t") for ln in lines(os.path.join(jobdir, "serial"))]
        assert [(n, u) for _, n, u in serial] == [("1", f"{base}/chapter-1"), ("2", f"{base}/chapter-2"),
                                                  ("3", f"{base}/chapter-3")]
        chapters = [ln.split("\t")[1] for ln in lines(os.path.join(jobdir, "chapters"))]
        assert chapters == ["Chapter 1: Departure", "Chapter 2: The Ridge", "Chapter 3: The River"]
        assert dict(ln.split("=", 1) for ln in lines(os.path.join(jobdir, "novel"))) == \
            {"title": "The Long Road", "url": base, "prev": ""}
        first2 = int(serial[1][0])
        with open(os.path.join(jobdir, "stream", f"{first2:04d}.txt")) as f:
            assert f.read() == "Chapter 2: The Ridge."                    # each chapter starts with its name
        total = int(lines(os.path.join(jobdir, "progress"))[0].split()[1])
        assert os.path.exists(os.path.join(jobdir, "stream", f"{total:04d}.wav"))
        assert os.path.exists(os.path.join(jobdir, "stream", "end"))
    finally:
        if jobdir:
            shutil.rmtree(jobdir, ignore_errors=True)


@needs("audio")
def test_the_next_chapter_waits_for_the_listener():
    """Only one chapter is made ahead: chapter 3 is fetched once the player is in chapter 2."""
    base = site()
    Site.hits.clear()
    d = f"/tmp/kokoro.{os.urandom(6).hex()}"
    t = threading.Thread(target=run, args=("",), kwargs={"url": f"{base}/chapter-2", "words": "0", "jobdir": d})
    try:
        t.start()
        for _ in range(100):
            if os.path.exists(os.path.join(d, "stream", "0001.wav")):
                break
            time.sleep(0.1)
        time.sleep(1.0)
        assert not [p for p in Site.hits if p.endswith("chapter-3")]       # not yet: no player in chapter 2
        with open(os.path.join(d, "at"), "w") as f:
            f.write("1")                                                     # now the player is
        for _ in range(100):
            if [p for p in Site.hits if p.endswith("chapter-3")]:
                break
            time.sleep(0.1)
        assert [p for p in Site.hits if p.endswith("chapter-3")]
        time.sleep(1.0)
        assert len(lines(os.path.join(d, "serial"))) == 2
        assert not [p for p in Site.hits if p.endswith("chapter-4")]       # and waits again, in chapter 3
        with open(os.path.join(d, "at"), "w") as f:
            f.write("999")
        t.join(30)
        assert not t.is_alive()
    finally:
        shutil.rmtree(d, ignore_errors=True)


@needs("audio")
def test_picking_up_mid_listen_keeps_the_numbering():
    """A jump or a new speed starts a new job at a later chapter (base=N), with the
    chapters before it copied in: parts keep their numbers in the whole listen."""
    base = site()
    jobdir = d2 = None
    try:
        jobdir, _ = run("", url=f"{base}/chapter-1", words="0", files={"at": "999"})
        serial = lines(os.path.join(jobdir, "serial"))
        chapters = lines(os.path.join(jobdir, "chapters"))
        first2 = int(serial[1].split("\t")[0])
        d2, _ = run("", url=f"{base}/chapter-2", words="0", base=str(first2), start=str(first2 + 1),
                    files={"serial": "\n".join(serial[:1]) + "\n", "chapters": "\n".join(chapters[:1]) + "\n",
                           "at": "999"})
        assert lines(os.path.join(d2, "serial")) == serial                   # the same numbers
        assert lines(os.path.join(d2, "stream", "first")) == [str(first2 + 1)]
        assert not os.path.exists(os.path.join(d2, "stream", f"{first2:04d}.wav"))
    finally:
        for d in (jobdir, d2):
            if d:
                shutil.rmtree(d, ignore_errors=True)


@needs("audio")
def test_carrying_on_after_the_last_chapter_heard():
    base = site()
    jobdir = None
    try:
        jobdir, _ = run("", url=f"{base}/chapter-1", words="0", skip="1", files={"at": "999"})
        assert lines(os.path.join(jobdir, "serial"))[0].split("\t")[1:] == ["2", f"{base}/chapter-2"]
    finally:
        if jobdir:
            shutil.rmtree(jobdir, ignore_errors=True)
    try:
        run("", url=f"{base}/chapter-3", words="0", skip="1")
        raise AssertionError("expected: no new chapter")
    except engine.JobError as e:
        assert "No new chapter after Chapter 3: The River yet" in str(e)


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
