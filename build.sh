#!/usr/bin/env bash
# PZStory build. Plain javac + jar - no Gradle, no daemon, no downloads.
#
# Project Zomboid 42.20.3 ships a Java 25 runtime and its own classes are
# compiled to class-file major 69 (Java 25), so the compiler must be JDK 25 or
# newer. ZombieBuddy itself is major 61 (Java 17).
set -euo pipefail

# Where your JDK 25+ lives. Override: JDK=/path/to/jdk/bin ./build.sh
JDK="${JDK:-$(dirname "$(readlink -f "$(command -v javac)")")}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
# Your Project Zomboid install - the folder holding projectzomboid.jar and
# ZombieBuddy.jar. Override: PZ=/path/to/ProjectZomboid ./build.sh
# Typical: ~/.steam/steam/steamapps/common/ProjectZomboid  (Linux)
#          C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid
LIB="${PZ:-$HOME/.steam/steam/steamapps/common/ProjectZomboid}"

if [ ! -f "$LIB/projectzomboid.jar" ]; then
    echo "Cannot find projectzomboid.jar under: $LIB" >&2
    echo "Set PZ to your game folder, e.g.  PZ=/path/to/ProjectZomboid ./build.sh" >&2
    exit 1
fi
if [ ! -f "$LIB/ZombieBuddy.jar" ]; then
    echo "Cannot find ZombieBuddy.jar under: $LIB" >&2
    echo "Install ZombieBuddy first - it is what lets a mod ship Java." >&2
    exit 1
fi
OUT="$ROOT/build/classes"
JAR="$ROOT/mod/42/media/java/PZStory.jar"

rm -rf "$OUT"
mkdir -p "$OUT" "$(dirname "$JAR")"

echo "== compiling =="
find "$ROOT/src" -name '*.java' > "$ROOT/build/sources.txt"
"$JDK/javac" \
    -encoding UTF-8 \
    -Xlint:all,-serial \
    -cp "$LIB/ZombieBuddy.jar:$LIB/projectzomboid.jar" \
    -d "$OUT" \
    @"$ROOT/build/sources.txt"

echo "== packaging =="
"$JDK/jar" --create --file "$JAR" -C "$OUT" .

echo "== done =="
ls -la "$JAR"
"$JDK/jar" --list --file "$JAR"
