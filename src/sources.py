"""kokomux: what a job reads. Runs inside the container.

Web pages (the article, found by trafilatura), web novels' chapters (the
chapter's text, its number, and the next and previous chapters), EPUBs
(chapters in reading order, author and cover) and PDFs (lines joined back
into sentences). Pages are fetched with curl, as the phone's browser would.
trafilatura and pypdf are imported where they're used, so this imports bare.
"""
import os
import re
import sys
import urllib.parse
import zipfile
from html.parser import HTMLParser

# A phone browser's: some sites (behind Cloudflare, say) turn other clients away.
USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"


class JobError(Exception):
    """A problem with the request itself (bad voice, empty text...)."""


def log(msg):
    """A line for the server's log (stderr)."""
    print(msg, file=sys.stderr, flush=True)


# ------------------------------------------------------------------ web pages

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
