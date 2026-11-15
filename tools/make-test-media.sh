#!/bin/zsh
# The clips the instrumentation tests play, written to app/src/androidTest/assets/media/ (macOS only:
# swift, python3 and afconvert come with the system and Xcode's command-line tools).
set -eu
ROOT=${0:A:h:h}
OUT=${1:-$ROOT/app/src/androidTest/assets/media}
mkdir -p $OUT
TMP=$(mktemp -d)
# tone.m4a — 3 s of AAC: a 440 Hz tone that steps up a fifth each second; the clip's soundtrack, 8 s of
# a quieter 220 Hz one.
python3 - $TMP <<'PY'
import math, struct, sys, wave
rate = 22050
def tone(path, seconds, base, level):
    with wave.open(path, "wb") as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(rate)
        frames = bytearray()
        for i in range(rate * seconds):
            freq = base * 1.5 ** (i // rate)
            frames += struct.pack("<h", int(level * math.sin(2 * math.pi * freq * i / rate)))
        f.writeframes(bytes(frames))
tone(sys.argv[1] + "/tone.wav", 3, 440, 12000)
tone(sys.argv[1] + "/soundtrack.wav", 8, 220 / 1.5 ** 3, 4000)
PY
afconvert -f m4af -d aac -b 64000 $TMP/tone.wav $OUT/tone.m4a
afconvert -f m4af -d aac -b 24000 $TMP/soundtrack.wav $TMP/soundtrack.m4a
swift $ROOT/tools/make-test-media.swift $OUT $TMP/soundtrack.m4a
rm -r $TMP
ls -la $OUT
