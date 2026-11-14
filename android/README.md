# kokomux for Android

kokomux as an Android app, for the same phone (a Samsung S26 Ultra), with
the same rules: always the fp32 model and misaki, golden-ratio design, short
plain wording. The Termux version in `src/` stays as it is.

## What it does now (v0.2)

One screen, for now; the screens matching Termux's come next.

- **Listen to a book, a file, a web page or a typed text.** **Open a book or
  file** takes an EPUB, a PDF or a text file; **Web page** reads a page's
  article, or one chapter of a web novel; the text box reads what's typed, in the
  chosen voice. All 54 voices speak, in Kokoro's nine languages.
- **In the background**, with the notification's and the lock screen's
  controls: one gapless playlist for the whole book, made about ten minutes
  ahead of what's playing, as in Termux. On the screen: the book, the chapter,
  the part being read, and **‹ Again**, **Pause**, **Next ›** and **Stop**.
- **Books in progress** are on the shelf with their progress: tap one to carry
  on where you stopped, in the voice it was in. A book heard to the end leaves
  the shelf, with its kept audio.
- **Made parts are kept** per book, voice, speed and language, so listening
  again reuses them; the kept audio never grows past 50 GB, letting go of the
  books heard least recently.
- **The time each part took** (misaki and Kokoro separately), the speech it
  made, and the real-time factor, to compare with `kokoro --benchmark`; the
  text box starts with the benchmark's own text. The chips are the
  benchmark's ways of running Kokoro (its own choice of threads, 2 to 8,
  block 4): pick the one it kept in Termux.
- **Check pronunciation** compares misaki here with engine.py's sounds.

## How it works

```
Screen ──OPEN──► ListenService (MediaSession, ExoPlayer: one gapless playlist)
                   │ a thread runs Maker (:engine): engine.py's run_job, in Kotlin
                   ├─► Python (Chaquopy 3.10): engine.py's read_source, normalise, split_text,
                   │     misaki + spaCy (espeak-ng 1.52.0 for unknown words and other languages),
                   │     words.py's timings
                   └─► Kokoro on ONNX Runtime 1.32 (:engine), a port of kokoro-onnx 0.6.1
```

- **The text side is engine.py itself.** `src/engine.py` and `src/words.py`
  are packaged into the app as they are, so the phone reads books and pages,
  tidies, splits and pronounces text with the code Termux runs
  (`app/src/main/python/kokomux_android.py` calls it). Only `fetch` is the
  app's own: Android has no curl, so pages come through urllib, with the same
  user agent, limits and errors.
- **The job runner is Kotlin** (`engine/…/Maker.kt`): engine.py's run_job for
  the player, with the same part cache (the same folder names, made from the
  voice, speed and language), staying ten minutes ahead, and the same
  bookmarks (`<book>.mark`, the same keys) and book ids as Termux.
- **misaki and spaCy run in Chaquopy.** Chaquopy has spaCy 3.8.7 and its
  native packages built for Python 3.10 on arm64. The pins are in
  `app/requirements.txt`.
- **Web pages are read with the container's trafilatura 2.3.1.** It asks for
  newer lxml and regex builds than Chaquopy has, but works with Chaquopy's
  (Termux's own web, novel and book tests pass with them): `tools/wheels.py`
  gives those packages to the build as `+chaquopy` versions asking for
  Chaquopy's builds.
- **espeak-ng**, for the words misaki doesn't know ("Igdrasil",
  "traveller") and the other languages: espeak-ng 1.52.0's library is built
  with the NDK (`app/src/main/cpp`), with the data from the espeakng-loader
  0.2.4 wheel the container installs. The real phonemizer drives it.
- **Kokoro runs in Kotlin**: Chaquopy has no ONNX Runtime. `engine/` is a
  plain-Kotlin port of what kokoro-onnx does with misaki's sounds, tested
  against the Python original.
- **The model downloads on first run** (354 MB) into the app's storage, from
  the addresses engine.py's `download` uses.

## Building

You need JDK 21, the Android SDK (platform 37, NDK 30.0.16248370, CMake
3.31.6) and Python 3.10 on the build machine (`python3.10`, or set
`KOKOMUX_BUILD_PYTHON`).

```
cd android
./gradlew :app:assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew :engine:test                     # Kokoro against kokoro-onnx (needs dev/rig.sh's models), the job runner
./gradlew :app:connectedDebugAndroidTest   # on the phone: pronunciation, reading, and listening to a short text
```

GitHub Actions builds the APK on every push that touches the app (the
**Android** workflow); download **kokomux-debug-apk** from the run and open
it on the phone to install it. Its emulator job runs the instrumented tests
with the model, and saves the screen before and after loading
(**emulator-screen**).

## Checking it matches Termux

- **Pronunciation:** `tools/pronunciation.py --write`, run with the rig's
  Python (`.dev/app/venv/bin/python`), records engine.py's sounds for the
  fixtures' English, the README's misaki examples and test_text.py's cases, in
  both accents, into `app/src/main/assets/pronunciation.json`. Without
  `--write`, any Python with `app/requirements.txt` installed compares its
  sounds with them (CI does this). On the phone, **Check pronunciation** does
  the same, and so does the instrumented test.
- **Reading:** `tools/test_text_side.py`, with the app's packages installed
  (CI makes them with `tools/wheels.py`), runs `kokomux_android.py` on this
  machine: a PDF, an EPUB, text and pages served locally, and Termux's own
  web-page and web-novel tests again through the app's fetch.
- **Speech:** `tools/golden.py` (rig's Python) records kokoro-onnx's output
  for a few sentences into `tools/golden.json`; `./gradlew :engine:test`
  checks the Kotlin engine gives the same length, the same moment for every
  sound, and the same loudness.

## Files

| File | What it is |
|---|---|
| `engine/src/main/kotlin/kokomux/engine/Kokoro.kt` | the model: a part's sounds to speech, with each sound's timing |
| `engine/…/Chunker.kt`, `Pauses.kt`, `Audio.kt` | kokoro-onnx's batches, pauses at marks and silence trimming; engine.py's soft edges; WAV files |
| `engine/…/Voices.kt`, `Vocab.kt` | the voices file (a numpy .npz) and the model's sound-to-token table |
| `engine/…/Book.kt` | a book's parts and chapters, the text side's interface, book ids and languages |
| `engine/…/Maker.kt` | engine.py's run_job: each part made and kept, ten minutes ahead of the player |
| `engine/…/Bookmarks.kt` | where each book was left, as Termux's `.mark` files |
| `app/src/main/kotlin/kokomux/app/ListenService.kt` | listening in the background: the playlist, the notification, the bookmark |
| `app/…/Engine.kt` | what the app loads once: the models, Python, Kokoro, the bookmarks and kept parts |
| `app/…/Listening.kt` | what's playing, shared by the service and the screen |
| `app/…/Spike.kt` | the screen's state: download, load, open, the controls, the checks |
| `app/…/Screen.kt`, `Theme.kt` | the screen, and the Termux palette (src/theme.sh) in Compose |
| `app/…/Text.kt`, `Models.kt` | the Python side, and downloading the model |
| `app/src/main/python/kokomux_android.py` | what the app calls in Python (engine.py underneath) |
| `app/src/main/cpp/CMakeLists.txt` | espeak-ng 1.52.0's library, built for arm64 |
| `app/requirements.txt` | the phone's Python packages |
| `app/build.gradle.kts` | the app's build, with the espeak data and wheels tasks |
| `app/src/androidTest/…` | on the phone: pronunciation, reading, and a short text heard through the service |
| `tools/pronunciation.py`, `tools/golden.py` | engine.py's sounds and kokoro-onnx's speech, to check the app against |
| `tools/test_text_side.py` | the app's Python side on this machine |
| `tools/wheels.py` | wheels Chaquopy can't get from PyPI as they are |
