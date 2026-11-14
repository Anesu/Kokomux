# kokomux for Android

kokomux as an Android app, for the same phone (a Samsung S26 Ultra), with
the same rules: Kokoro always in fp32 with misaki, golden-ratio design, short
plain wording. The Termux version in `src/` stays as it is.

## What it does (1.0)

- **Home**: the voice new things are read in (tap it to choose another) and
  settings; what's playing; **File**, **Web page**, **Clipboard** and
  **Type**; today's listening and the streak; saved passages; the bookshelf,
  each book with its cover and how far through, a web novel with its chapter
  and how many more are out (⋮ shows a book's listening, or removes it, with
  5 seconds to undo).
- **The reader**: the whole book in a reading column, the part being read on
  the golden line with the spoken word lit, the text fading with its distance
  from it (the focus effect); tap any part to hear it. Below: the bar (tap it,
  or drag along it to see the part and chapter you'd land on; it shows how far
  ahead parts are made, and where chapters start), the part and the time left,
  ⏮ ▶ ⏭, then the speed, the sleep timer, the chapters, **Save** (keeps the
  sentence just heard) and the voice, each showing its value. ⌄ folds the
  reader away, still playing; ⋮ has the book's saved passages and listening,
  **Save as audiobook**, settings and stop. With a keyboard (DeX), Termux's
  keys: Space, ← →, [ ], − +, t, c, s, v and q.
- **Three engines, one voice picker.** A tab each for **Kokoro** (fp32, on
  the CPU), **Ming 3.0** (on the NPU, eight built-in voices, 44.1 kHz) and
  **Qwen3-TTS** (your own voices). Ming and Qwen download when first chosen,
  the button filling as they come (2.8 and 1.7 GB); Ming gets ready for the
  phone on its first listen, about a minute, once.
- **Your own voice**: **New voice** shows a passage to read aloud (or pick a
  clip from the phone), plays it back, and makes a voice of it named as you
  like; Qwen carries on from the clip itself, which sounds the most like it.
- **Web novels** (freewebnovel.com and the like) are heard chapter after
  chapter: the next is fetched as this one is made, and the three after it
  are kept so they can be heard offline. **Web page** takes an address or a
  novel's name: **Find** lists the novels, then their chapters to start
  from. **Chapters** lists all of a novel's chapters, those heard quieter.
  New chapters are checked for daily, fetched, and a notification says so.
  A site that wants a browser check first is read through the app's own
  browser.
- **Listening**: today's time, the days in a row, this week and all time; the
  last 7 days as bars and the month as a calendar; the pace in time, words and
  chapters a day; each book's time and when it'll be finished at its pace (a
  novel: when it'll be up to date); and every listen, from where to where.
- **Share to kokomux** from any app (a page, some text, an EPUB or PDF), or
  open a book with it. **A home-screen widget** shows what's playing with
  play and pause. **Save as audiobook**: this chapter, from here, or the whole
  book, as one .m4a where you choose.
- **A new speed is heard at once**: Kokoro's part playing carries on at it,
  pitch kept, while the rest is made again at it; Ming and Qwen are made at 1×
  and just played faster. **A new voice** starts again at the part you're on.
- **The sleep timer** (15, 30 or 60 minutes, or the end of the chapter) fades
  the voice over its last 13 seconds, counts only time spent playing, and keeps
  your place. Carrying on after a minute away goes back 5 seconds first (10
  after an hour).
- **In the background**, with the notification's and the lock screen's
  controls: one gapless playlist, made about ten minutes ahead.
- **Settings**, as Termux's: voice, speed, colours (auto, dark, light), the
  focus effect, word highlighting and its timing, shown on a few lines of the
  reader that follow every change; then how Kokoro runs (`kokoro --benchmark`'s
  choices), how fast the voice in use makes speech here, the pronunciation
  check, the kept audio and each engine's files (with Remove).

## Design

The Termux screens' rules (src/theme.sh, src/tui.sh), on a touch screen
(`app/src/main/kotlin/kokomux/app/ui/Theme.kt`), one set of parts for every
screen (`Parts.kt`):

- **The form says what it does, so no words explain it.** A control shows
  its value (1.1×, 12 min, Emma, 4 of 12, 2 h 14 min left, Chapter 8 of 11 · 3
  new); settings show their effect on a sample of the reader; play spins while
  the first part is made (and the line under the bar says why, while an engine
  gets ready); the mark's bars move while something loads, opens or speaks;
  every download or long task is a bar that fills as it goes (`ProgressFill`);
  **Read** becomes **Find** when what's typed isn't an address.
- **One way to do each thing.** Choosing one of a few (a language, an engine, a
  book) is a row of chips (`Chip`); a list to pick from is rows with a mark on
  the one in effect (`Choice`, `ChapterList`); a value you set is a pill
  (`Pill`); typing is one field (`Field`); a word for a moment is a bar at the
  bottom (`Bar`: "Saved: …", "Removed: … Undo"); more is behind ⋮.
- **The golden ratio throughout.** Spacing in Fibonacci dp (3, 5, 8, 13, 21,
  34, 55, 89); type in √φ steps (10, 13, 16, 20, 26, 33, 53 sp), the reading
  face 20 sp on 32 sp lines (φ); the part being read φ⁻² of the way down the
  reading area; a screen's free space split 1 : φ above and below (the optical
  centre); the play button φ times ⏮ and ⏭ (89 : 55 dp); covers as golden
  rectangles; motion in Fibonacci milliseconds (144, 233, 377, 1597); the
  calendar's five shades φ apart.
- **Termux's palette.** Its dark and light colours, with greys in golden steps
  (φ⁻¹ secondary text, ½ what's been heard, φ⁻² rules, φ⁻⁴ surfaces), and the
  focus effect's fade a quarter golden step at a time, never below φ⁻⁴.
  Secondary text keeps 4.5 : 1 (WCAG AA): φ⁻¹ of the way to the text on the
  page, 1 − φ⁻³ on a raised surface (a card, a sheet, the chosen row).
- **Charts in one colour.** The week's bars and the month's calendar are the
  accent, deeper for more; numbers stay in the text's colours; one big number
  a screen (today's time); tap a bar or a day for its value, and TalkBack
  reads them all.
- **Within reach.** The controls you use while listening are at the bottom,
  where the thumb is; every target is at least 48 dp, the transport 55 and 89.
- **The icons every phone uses stand alone** (play, pause, ⏮ ⏭, back, ⌄,
  settings, ⋮); the rest stand over their word or value.

`./gradlew :app:testDebugUnitTest` draws every screen (`app/build/screens/`),
at the S26 Ultra's width, at its narrowest (320 dp, the largest display size),
with large text and on DeX, dark and light, and taps through the flows; CI
uploads the drawings (**screens**).

## How it works

```
Screen ──OPEN──► ListenService (MediaSession, ExoPlayer: one gapless playlist)
                   │ a thread runs Maker (:engine): engine.py's run_job, in Kotlin
                   ├─► Python (Chaquopy 3.10): engine.py's read_source, normalise, split_text,
                   │     novel_chapter; misaki + spaCy (espeak-ng 1.52.0), words.py's timings
                   └─► the voice's engine (:engine):
                         Kokoro on ONNX Runtime 1.32 (CPU, fp32), a port of kokoro-onnx 0.6.1
                         Ming-3.0-tts 0.5B on LiteRT 2.3.2 (the NPU, fp16, QNN 2.47)
                         Qwen3-TTS 0.6B Base on ONNX Runtime (CPU, 4-bit)
```

- **The text side is engine.py itself.** `src/engine.py` and `src/words.py`
  are packaged into the app as they are, so the phone reads books and pages
  (and web novels' chapters), tidies, splits and pronounces text with the code
  Termux runs (`app/src/main/python/kokomux_android.py` calls it, and adds a
  novel's chapter list and search). Only `fetch` is the app's own: Android has
  no curl, so pages come through urllib, with the same user agent, limits and
  errors, or the app's browser for a site that asks for its check.
- **The job runner is Kotlin** (`engine/…/Maker.kt`): engine.py's run_job for
  the player, with the same part cache (the same folder names, made from the
  voice, speed and language), staying ten minutes ahead, and the same
  bookmarks (`<book>.mark`, the same keys, a novel's too) and book ids as
  Termux. A voice is a `Speaker`: it says a part and gives its word timings
  (Kokoro's from its durations, Ming's and Qwen's spread over each piece said).
- **Kokoro runs in Kotlin**: `engine/` is a plain-Kotlin port of what
  kokoro-onnx does with misaki's sounds, tested against the Python original.
- **Ming 3.0** is litert-community's conversion for the phone's NPU (the
  "faster placement": every graph on the Hexagon, fp16), with its Android
  sample's host code (MIT) in `engine/…/ming/`: the Qwen tokenizer, the prompt,
  the frame loop, the decoder and the ISTFT. LiteRT and Qualcomm's QNN runtime
  come from Maven; only the Hexagon v81 libraries go in the APK.
- **Qwen3-TTS** is onnx-community's 4-bit export with wavekat's 4-bit code
  predictor, which keeps a cache (five times faster), and its full-precision
  code tables (`engine/…/qwen/`). A voice is a clip's speaker vector and, with
  its words, the clip's own sound codes, which Qwen carries on from. Its
  tokenizer is Ming's (the same Qwen tokenizer).
- **misaki and spaCy run in Chaquopy.** Chaquopy has spaCy 3.8.7 and its
  native packages built for Python 3.10 on arm64. The pins are in
  `app/requirements.txt`. **Web pages** are read with the container's
  trafilatura 2.3.1, given Chaquopy's lxml and regex builds by
  `tools/wheels.py`. **espeak-ng** 1.52.0 is built with the NDK
  (`app/src/main/cpp`), with the data from the espeakng-loader 0.2.4 wheel.
- **Each engine's files download** into the app's storage from addresses
  pinned to a commit, each kept only if its SHA-256 is the one written in
  `Models.kt`: Kokoro on first run (354 MB), Ming and
  Qwen when one of their voices is first wanted.

## Building

You need JDK 21, the Android SDK (platform 37, NDK 30.0.16248370, CMake
3.31.6) and Python 3.10 on the build machine (`python3.10`, or set
`KOKOMUX_BUILD_PYTHON`).

```
cd android
./gradlew :app:assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew :engine:test                     # the engines and the job runner (the files they need: below)
./gradlew :app:testDebugUnitTest           # the screens drawn and tapped through, and the app's own logic
./gradlew :app:connectedDebugAndroidTest   # on the phone: pronunciation, reading, listening, saving an audiobook
```

`:engine:test` finds Kokoro's model and voices in `../.dev/app/models`
(dev/rig.sh) or `KOKOMUX_MODELS`; Ming's host files (its tokenizer's tables
and small tensors, 15 MB) in `../.dev/app/ming` or `KOKOMUX_MING`; and Qwen's
files, as the app downloads them, in `../.dev/app/qwen/app` or `KOKOMUX_QWEN`
(the Android workflow shows where each comes from). A test whose files aren't
there is skipped.

GitHub Actions builds the APK on every push that touches the app (the
**Android** workflow); download **kokomux-debug-apk** from the run and open
it on the phone to install it. Its emulator job runs the instrumented tests
with the model, and saves the screen before and after loading
(**emulator-screen**).

## Checking it

- **Pronunciation:** `tools/pronunciation.py --write`, run with the rig's
  Python (`.dev/app/venv/bin/python`), records engine.py's sounds into
  `app/src/main/assets/pronunciation.json`; without `--write`, any Python with
  `app/requirements.txt` compares its sounds with them (CI does this). On the
  phone, **Check pronunciation** does the same.
- **Reading:** `tools/test_text_side.py` runs `kokomux_android.py` on this
  machine (a PDF, an EPUB, text, pages, Termux's web-novel tests through the
  app's fetch); `tools/test_novels.py` a novel's chapters, its chapter list and
  a search, against a made-up site laid out as freewebnovel.com is.
- **Kokoro:** `tools/golden.py` records kokoro-onnx's output into
  `tools/golden.json`; `:engine:test` checks the Kotlin engine gives the same
  length, the same moment for every sound, and the same loudness.
- **Ming:** its prompts are checked against the official tokenizer
  (`engine/src/test/resources/ming_prompts.json`, made with `tokenizers` on
  Ming's tokenizer.json), and its loop over stand-in graphs; the graphs run
  only on the phone's NPU.
- **Qwen:** `tools/qwen_ref.py` runs a greedy voice clone in Python with the
  same files; `:engine:test` checks the Kotlin one makes the same frames
  (`engine/src/test/resources/qwen_clone.json`).

## Files

| File | What it is |
|---|---|
| `engine/src/main/kotlin/kokomux/engine/Kokoro.kt` | the model: a part's sounds to speech, with each sound's timing |
| `engine/…/Chunker.kt`, `Pauses.kt`, `Audio.kt` | kokoro-onnx's batches, pauses at marks and silence trimming; engine.py's soft edges; WAV files |
| `engine/…/Voices.kt`, `Vocab.kt`, `Npy.kt` | Kokoro's voices file and sound-to-token table; numpy arrays |
| `engine/…/Book.kt` | a book's parts and chapters (a novel's as they arrive), the text side's interface, book ids, voices by engine |
| `engine/…/Speaker.kt`, `Estimate.kt` | a voice saying a part (Kokoro's through misaki); word timings spread over what was said |
| `engine/…/Maker.kt` | engine.py's run_job: each part made and kept, ten minutes ahead of the player |
| `engine/…/Bookmarks.kt` | where each book was left, as Termux's `.mark` files |
| `engine/…/Stats.kt` | the listens kept, and what they add up to |
| `engine/…/Plain.kt` | what went wrong, in a few plain words to show |
| `engine/…/ming/` | Ming-3.0-tts's host side (from its Android sample, MIT: see `LICENSE` there) and its speaker |
| `engine/…/qwen/` | Qwen3-TTS: cloning a voice, the talker and code predictor's loop, decoding; its speaker |
| `app/src/main/kotlin/kokomux/app/ListenService.kt` | listening in the background: the playlist, the notification, the bookmark, speed, voice, a novel's next chapter; reading and making as jobs that cancel |
| `app/…/SleepTimer.kt`, `ListenLog.kt` | the sleep timer and its fade; the listens kept for the stats |
| `app/…/AppModel.kt` | the screens' state, from the service's and the settings; what each control does |
| `app/…/VoiceStudio.kt`, `NovelLists.kt` | the model's voices (samples, engines' files, a new voice from a clip) and novels' lists (chapters, search) |
| `app/…/Engine.kt` | what the app loads once: the engines and their files, Python, cloned voices, the bookmarks and kept parts |
| `app/…/MingNpu.kt` | Ming's graphs on the NPU through LiteRT |
| `app/…/Novels.kt`, `NewChapters.kt`, `Browser.kt` | web novels' chapters kept as they're fetched; the daily check for new ones; the browser for sites that ask |
| `app/…/Clips.kt` | a clip for a new voice: recorded or decoded, at 24 kHz |
| `app/…/Passages.kt`, `Export.kt`, `ListeningWidget.kt` | saved passages; saving an audiobook; the home-screen widget |
| `app/…/Listening.kt` | what's playing, shared by the service and the screens |
| `app/…/Store.kt` | the settings, under Termux's names |
| `app/…/ui/Theme.kt`, `Glyphs.kt`, `Parts.kt` | the design system (golden ratio, Termux's palette), the icons, the shared pieces |
| `app/…/ui/Home.kt`, `Reader.kt`, `Settings.kt`, `VoicesScreen.kt`, `NewVoice.kt`, `StatsScreen.kt`, `Passages.kt`, `Sheets.kt`, `FirstRun.kt` | the screens and the sheets |
| `app/…/ui/App.kt`, `State.kt` | the screens together (where back goes), and what they show and can ask for |
| `app/…/Text.kt`, `Models.kt` | the Python side, and downloading each engine's files |
| `app/src/main/python/kokomux_android.py` | what the app calls in Python (engine.py underneath) |
| `app/src/main/cpp/CMakeLists.txt` | espeak-ng 1.52.0's library, built for arm64 |
| `app/requirements.txt` | the phone's Python packages |
| `app/build.gradle.kts` | the app's build, with the espeak data and wheels tasks |
| `app/src/test/…` | the screens drawn and tapped through on this machine (Robolectric), word timings, saved passages |
| `app/src/androidTest/…` | on the phone: pronunciation, reading, a short text heard through the service, saving an audiobook |
| `tools/pronunciation.py`, `tools/golden.py`, `tools/qwen_ref.py` | engine.py's sounds, kokoro-onnx's speech and a Qwen clone in Python, to check the app against |
| `tools/test_text_side.py`, `tools/test_novels.py` | the app's Python side on this machine |
| `tools/wheels.py` | wheels Chaquopy can't get from PyPI as they are |
