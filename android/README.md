# kokomux for Android

kokomux as an Android app, for the same phone (a Samsung S26 Ultra), with
the same rules: always the fp32 model and misaki, golden-ratio design, short
plain wording. The Termux version in `src/` stays as it is.

**Phase 1** is a feasibility spike: one screen with a text box, a voice
picker and Play. It shows how long each part took (misaki and Kokoro
separately), how much speech it made, and the real-time factor, so it can be
compared with `kokoro --benchmark` in Termux. The text box starts with the
benchmark's own text, and the chips under it are the benchmark's ways of
running Kokoro (its own choice of threads, 2 to 8, block 4): pick the one
it kept in Termux.

## How it works

```
Compose screen ─► Python (Chaquopy, 3.10)                ─► Kotlin (:engine)                ─► ExoPlayer
                  engine.py's normalise and split_text       Kokoro on ONNX Runtime 1.32,      one playlist,
                  misaki 0.7.4 + spaCy en_core_web_sm        a port of kokoro-onnx 0.6.1:      a WAV per part
                  phonemizer ─► espeak-ng 1.52.0 (NDK)       batches, trim, pauses at marks
```

- **The text side is engine.py itself.** `src/engine.py` and `src/words.py`
  are packaged into the app as they are, so the phone tidies, splits and
  pronounces text with the code Termux runs (`app/src/main/python/kokomux_android.py`
  calls it).
- **misaki and spaCy run in Chaquopy.** Chaquopy has spaCy 3.8.7 and its
  native packages (thinc, blis, cymem, preshed, murmurhash, srsly, numpy) built
  for Python 3.10 on arm64, so the container's way works on the phone. The
  pins are in `app/requirements.txt`.
- **espeak-ng for the words misaki doesn't know**, as in the container
  ("Igdrasil", "traveller"): espeak-ng 1.52.0's library is built with the NDK
  (`app/src/main/cpp`), and its English data comes from the espeakng-loader
  0.2.4 wheel the container installs. The real phonemizer drives it.
- **Kokoro runs in Kotlin**, not Python: ONNX Runtime's Android build has no
  Python bindings in Chaquopy. `engine/` is a plain-Kotlin port of what
  kokoro-onnx does with misaki's sounds (`create_timed(..., is_phonemes=True)`),
  tested against the Python original.
- **The model downloads on first run** (354 MB) into the app's storage, from
  the addresses engine.py's `download` uses.

## Building

You need JDK 21, the Android SDK (platform 37, NDK 30.0.16248370, CMake
3.31.6) and Python 3.10 on the build machine (`python3.10`, or set
`KOKOMUX_BUILD_PYTHON`).

```
cd android
./gradlew :app:assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew :engine:test                 # the engine against kokoro-onnx (needs dev/rig.sh's models)
./gradlew :app:connectedDebugAndroidTest   # with the phone attached: the pronunciation check
```

GitHub Actions builds the APK on every push that touches the app (the
**Android** workflow); download **kokomux-debug-apk** from the run and open
it on the phone to install it.

## Checking it matches Termux

- **Pronunciation:** `tools/pronunciation.py --write`, run with the rig's
  Python (`.dev/app/venv/bin/python`), records engine.py's sounds for the
  fixtures' English, the README's misaki examples and test_text.py's cases, in
  both accents, into `app/src/main/assets/pronunciation.json`. Without
  `--write`, any Python with `app/requirements.txt` installed compares its
  sounds with them (CI does this). On the phone, **Check pronunciation** does
  the same, and so does the instrumented test.
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
| `app/src/main/kotlin/kokomux/app/Spike.kt` | the screen's state: download, load, make each part, play, check |
| `app/…/Screen.kt`, `Theme.kt` | the screen, and the Termux palette (src/theme.sh) in Compose |
| `app/…/Text.kt`, `Models.kt` | the Python side, and downloading the model |
| `app/src/main/python/kokomux_android.py` | what the app calls in Python (engine.py underneath) |
| `app/src/main/cpp/CMakeLists.txt` | espeak-ng 1.52.0's library, built for arm64 |
| `app/requirements.txt` | the phone's Python packages |
| `app/build.gradle.kts` | the app's build, with the espeak data and docopt wheel tasks |
| `tools/pronunciation.py`, `tools/golden.py` | engine.py's sounds and kokoro-onnx's speech, to check the app against |
