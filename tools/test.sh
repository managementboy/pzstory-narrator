#!/usr/bin/env bash
# Tests that need no Project Zomboid jars, so CI can run them.
#
# Deliberately not JUnit: the project ships no runtime dependencies and
# downloads nothing during a build, and a test framework would break both.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -z "${JDK:-}" ]; then
    command -v javac >/dev/null 2>&1 || { echo "No javac on PATH" >&2; exit 1; }
    JDK="$(cd "$(dirname "$(command -v javac)")" && pwd)"
fi

# Pure-Java units only. Anything touching zombie.* belongs in the in-game
# checks listed in dev/README.md, not here.
UNITS="src/de/fricke/pzstory/AtomicFiles.java \
src/de/fricke/pzstory/BoundedFiles.java \
src/de/fricke/pzstory/Endpoint.java \
src/de/fricke/pzstory/Json.java \
src/de/fricke/pzstory/JsonParse.java \
src/de/fricke/pzstory/Version.java"

rm -rf build/test && mkdir -p build/test
"$JDK/javac" -encoding UTF-8 -Xlint:all -d build/test $UNITS test/de/fricke/pzstory/*.java
"$JDK/java" -cp build/test de.fricke.pzstory.AllTests
