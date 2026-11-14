# kokomux for Android

kokomux as an Android app, for the same phone (a Samsung S26 Ultra), with
the same rules: always the fp32 model and misaki, golden-ratio design, short
plain wording. The Termux version in `src/` stays as it is.

## What it does now (v0.4)

- **Home**: the voice new things are read in (tap it to choose another) and
  settings; what's playing; **File**, **Web page**, **Clipboard** and
  **Type**; the bookshelf, each book with its cover and how far through.
- **The reader**: the whole book in a reading column, the part being read on
  the golden line with the spoken word lit, the text fading with its distance
  from it (the focus effect); tap any part to hear it. Below: the bar (tap it,
  or drag along it to see the part and chapter you'd land on; it shows how far
  ahead parts are made, and where chapters start),
  the part and the time left, ⏮ ▶ ⏭ (⏮ goes back to the part before in its
  first three seconds), then the speed, the sleep timer, the chapters and the
  voice, each showing its value. ⌄ folds the reader away, still playing; ⋮ has
  settings and stop.
- **A new speed is heard at once**: the part playing carries on at it, pitch
  kept, while the rest is made again at that speed. **A new voice** starts
  again at the part you're on.
- **The sleep timer** (15, 30 or 60 minutes, or the end of the chapter) fades
  the voice over its last 13 seconds, counts only time spent playing, and keeps
  your place. Carrying on after a minute away goes back 5 seconds first (10
  after an hour).
- **In the background**, with the notification's and the lock screen's
  controls: one gapless playlist, made about ten minutes ahead.
- **Settings**, as Termux's: voice, speed, colours (auto, dark, light), the
  focus effect, word highlighting and its timing, shown on a few lines of the
  reader that follow every change; then how Kokoro runs (`kokoro --benchmark`'s
  choices), how fast it makes speech here, the pronunciation check, and the
  kept audio.
- **The voice picker**: a chip for each language, the voices grouped by
  language, ▶ to hear one say a line in its own language.

## Design

The Termux screens' rules (src/theme.sh, src/tui.sh), on a touch screen
(`app/src/main/kotlin/kokomux/app/ui/Theme.kt`):

- **The form says what it does, so no words explain it.** A control shows
  its value (1.1×, 12 min, Emma, 4 of 12, 2 h 14 min left); settings show
  their effect on a sample of the reader; play spins while the first part is
  made, and the mark's bars move while something loads, opens or speaks; the
  download button fills as it downloads.
- **The golden ratio throughout.** Spacing in Fibonacci dp (3, 5, 8, 13, 21,
  34, 55, 89); type in √φ steps (10, 13, 16, 20, 26, 33 sp), the reading face
  20 sp on 32 sp lines (φ); the part being read φ⁻² of the way down the reading
  area; a screen's free space split 1 : φ above and below (the optical centre);
  the play button φ times ⏮ and ⏭ (89 : 55 dp); covers as golden rectangles;
  motion in Fibonacci milliseconds (144, 233, 377, 1597).
- **Termux's palette.** Its dark and light colours, with greys in golden steps
  (φ⁻¹ secondary text, ½ what's been heard, φ⁻² rules, φ⁻⁴ surfaces), and the
  focus effect's fade a quarter golden step at a time, never below φ⁻⁴.
  Secondary text keeps 4.5 : 1 (WCAG AA): φ⁻¹ of the way to the text on the
  page, 1 − φ⁻³ on a raised surface (a card, a sheet, the chosen row).
- **Within reach.** The controls you use while listening are at the bottom,
  where the thumb is; every target is at least 48 dp, the transport 55 and 89.
- **The icons every phone uses stand alone** (play, pause, ⏮ ⏭, back, ⌄,
  settings, ⋮); the rest stand over their word or value.
- **One thing at a time.** One main action on each screen; how Kokoro runs and
  the checks are low in settings, stopping is behind ⋮.

`./gradlew :app:testDebugUnitTest` draws every screen (`app/build/screens/`),
at the S26 Ultra's width and on DeX, dark and light, and taps through the
flows; CI uploads the drawings (**screens**).

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
| `app/src/main/kotlin/kokomux/app/ListenService.kt` | listening in the background: the playlist, the notification, the bookmark, speed, voice, the sleep timer |
| `app/…/AppModel.kt` | the screens' state, from the service's and the settings; what each control does |
| `app/…/Engine.kt` | what the app loads once: the models, Python, Kokoro, the bookmarks and kept parts |
| `app/…/Listening.kt` | what's playing, shared by the service and the screens |
| `app/…/Store.kt` | the settings, under Termux's names |
| `app/…/ui/Theme.kt`, `Glyphs.kt`, `Parts.kt` | the design system (golden ratio, Termux's palette), the icons, the shared pieces |
| `app/…/ui/Home.kt`, `Reader.kt`, `Settings.kt`, `VoicesScreen.kt`, `Sheets.kt`, `FirstRun.kt` | the screens and the sheets |
| `app/…/ui/App.kt`, `State.kt` | the screens together (where back goes), and what they show and can ask for |
| `app/…/Text.kt`, `Models.kt` | the Python side, and downloading the model |
| `app/src/main/python/kokomux_android.py` | what the app calls in Python (engine.py underneath) |
| `app/src/main/cpp/CMakeLists.txt` | espeak-ng 1.52.0's library, built for arm64 |
| `app/requirements.txt` | the phone's Python packages |
| `app/build.gradle.kts` | the app's build, with the espeak data and wheels tasks |
| `app/src/test/…/ui/` | the screens drawn and tapped through on this machine (Robolectric), and word timings |
| `app/src/androidTest/…` | on the phone: pronunciation, reading, and a short text heard through the service (speed, sleep, the end) |
| `tools/pronunciation.py`, `tools/golden.py` | engine.py's sounds and kokoro-onnx's speech, to check the app against |
| `tools/test_text_side.py` | the app's Python side on this machine |
| `tools/wheels.py` | wheels Chaquopy can't get from PyPI as they are |
