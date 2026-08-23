#!/usr/bin/env bash
# PZStory build. Plain javac + jar - no Gradle, no daemon, no downloads.
#
# Project Zomboid 42.20.3 ships a Java 25 runtime and its own classes are
# compiled to class-file major 69 (Java 25), so the compiler must be JDK 25 or
# newer. ZombieBuddy itself is major 61 (Java 17).
#
# Deterministic: sources are sorted and every entry is stamped with
# SOURCE_DATE_EPOCH, so the same source produces a byte-identical JAR and a
# reviewer can confirm the committed binary matches the committed code.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

# ---------------------------------------------------------------- toolchain
# Resolve the JDK without readlink -f, which stock macOS does not have.
# Override with: JDK=/path/to/jdk/bin ./build.sh
if [ -z "${JDK:-}" ]; then
    if ! command -v javac >/dev/null 2>&1; then
        echo "No javac on PATH. Install a JDK 25+ or set JDK=/path/to/jdk/bin" >&2
        exit 1
    fi
    JDK="$(cd "$(dirname "$(command -v javac)")" && pwd)"
fi
for t in javac jar; do
    [ -x "$JDK/$t" ] || { echo "Not executable: $JDK/$t" >&2; exit 1; }
done

JAVAC_MAJOR="$("$JDK/javac" -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p')"
if [ -z "$JAVAC_MAJOR" ] || [ "$JAVAC_MAJOR" -lt 25 ]; then
    echo "Need JDK 25 or newer; found: $("$JDK/javac" -version 2>&1)" >&2
    echo "Project Zomboid 42 classes are bytecode major 69 and an older" >&2
    echo "compiler cannot read them." >&2
    exit 1
fi

# ------------------------------------------------------------- game classes
# The folder holding projectzomboid.jar and ZombieBuddy.jar.
# Override: PZ=/path/to/ProjectZomboid ./build.sh
LIB="${PZ:-$HOME/.steam/steam/steamapps/common/ProjectZomboid}"
for j in projectzomboid.jar ZombieBuddy.jar; do
    if [ ! -f "$LIB/$j" ]; then
        echo "Cannot find $j under: $LIB" >&2
        echo >&2
        echo "Set PZ to your game folder:" >&2
        echo "  Linux   PZ=~/.steam/steam/steamapps/common/ProjectZomboid ./build.sh" >&2
        echo "  macOS   PZ=~/Library/Application\\ Support/Steam/steamapps/common/ProjectZomboid ./build.sh" >&2
        echo "  Windows run under Git Bash or WSL, e.g." >&2
        echo "          PZ='/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid' ./build.sh" >&2
        exit 1
    fi
done

# javac wants ';' between classpath entries on Windows, ':' everywhere else.
# Git Bash and MSYS report MINGW*/MSYS*; Cygwin reports CYGWIN*.
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
    *)                    CPSEP=':' ;;
esac

OUT="$ROOT/build/classes"
JAR="$ROOT/mod/42/media/java/PZStory.jar"

rm -rf "$OUT"
mkdir -p "$OUT" "$(dirname "$JAR")"

echo "== compiling =="
# Sorted, so the class list and therefore the archive order is stable.
find "$ROOT/src" -name '*.java' | LC_ALL=C sort > "$ROOT/build/sources.txt"
"$JDK/javac" \
    -encoding UTF-8 \
    -Xlint:all,-serial \
    -cp "$LIB/ZombieBuddy.jar${CPSEP}$LIB/projectzomboid.jar" \
    -d "$OUT" \
    @"$ROOT/build/sources.txt"

echo "== packaging =="
# A fixed timestamp makes the archive reproducible. Honour SOURCE_DATE_EPOCH
# when the caller sets it (the reproducible-builds convention); otherwise pin
# to a constant so two builds of the same source are byte-identical.
if [ -n "${SOURCE_DATE_EPOCH:-}" ]; then
    STAMP="$(date -u -d "@$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
             || date -u -r "$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ)"
else
    STAMP="2020-01-01T00:00:00Z"
fi
"$JDK/jar" --create --file "$JAR" --date "$STAMP" -C "$OUT" .

echo "== verifying =="
"$ROOT/tools/verify.sh"

echo "== done =="
ls -la "$JAR"
command -v sha256sum >/dev/null 2>&1 && sha256sum "$JAR" || shasum -a 256 "$JAR"
