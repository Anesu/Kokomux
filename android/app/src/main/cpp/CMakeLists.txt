# espeak-ng 1.52.0's library for the phone: misaki hands it the words it
# doesn't know, as in the container (espeakng-loader 0.2.4 ships the same
# release, and its data goes into the app's assets; see build.gradle.kts).
# Only the library is built: no audio output, mbrola, sonic or async, which
# turning text into sounds doesn't use.
cmake_minimum_required(VERSION 3.22)
project(kokomux_espeak VERSION 1.52.0 LANGUAGES C)   # espeak-ng's version, which its files use

include(FetchContent)
FetchContent_Declare(espeak_ng
  GIT_REPOSITORY https://github.com/espeak-ng/espeak-ng.git
  GIT_TAG 1.52.0                                       # 4870adfa25b1a32b4361592f1be8a40337c58d6c
  GIT_SHALLOW TRUE
  SOURCE_SUBDIR not-its-own-build)                     # fetch it, but build only what's below
FetchContent_MakeAvailable(espeak_ng)
set(ES ${espeak_ng_SOURCE_DIR})

set(HAVE_MKSTEMP ON)
foreach(opt USE_ASYNC USE_KLATT USE_LIBPCAUDIO USE_LIBSONIC USE_MBROLA USE_SPEECHPLAYER)
  set(${opt} OFF)
endforeach()
set(BUILD_SHARED_LIBS ON)

add_library(espeak-include INTERFACE)
target_include_directories(espeak-include INTERFACE ${ES}/src/include ${ES}/src/include/compat)
add_subdirectory(${ES}/src/ucd-tools ucd-tools)
add_subdirectory(${ES}/src/libespeak-ng libespeak-ng)
