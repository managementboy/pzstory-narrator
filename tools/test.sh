#!/usr/bin/env bash
# Tests that need no Project Zomboid jars, so CI can run them.
#
# Deliberately not JUnit: the project ships no runtime dependencies and
# downloads nothing during a build, and a test framework would break both.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_MODULES=""
if command -v java >/dev/null 2>&1; then
    JAVA_MODULES="$(java --list-modules 2>/dev/null || true)"
fi

if [ -n "${JDK:-}" ]; then
    JAVAC=("$JDK/javac")
    JAVA_BIN="$JDK/java"
elif command -v javac >/dev/null 2>&1; then
    JAVAC=("$(command -v javac)")
    JAVA_BIN="$(command -v java)"
elif command -v java >/dev/null 2>&1 \
        && [[ "$JAVA_MODULES" == *"jdk.compiler@"* ]]; then
    # Some minimal JDK images ship the compiler module but omit the javac
    # launcher. The launcher is only a thin wrapper around this main class.
    JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
    JAVA_BIN="$(command -v java)"
else
    echo "No Java compiler available" >&2
    exit 1
fi

# Pure-Java units only. Anything touching zombie.* belongs in the in-game
# checks listed in dev/README.md, not here.
UNITS="src/de/fricke/pzstory/AtomicFiles.java \
src/de/fricke/pzstory/BoundedFiles.java \
src/de/fricke/pzstory/Campaign.java \
src/de/fricke/pzstory/Config.java \
src/de/fricke/pzstory/Delta.java \
src/de/fricke/pzstory/EventDetector.java \
src/de/fricke/pzstory/EventJournal.java \
src/de/fricke/pzstory/Endpoint.java \
src/de/fricke/pzstory/Json.java \
src/de/fricke/pzstory/JsonParse.java \
src/de/fricke/pzstory/Llm.java \
src/de/fricke/pzstory/NarrativeState.java \
src/de/fricke/pzstory/PageResult.java \
src/de/fricke/pzstory/PlaceRef.java \
src/de/fricke/pzstory/Prompt.java \
src/de/fricke/pzstory/Scenario.java \
src/de/fricke/pzstory/StoryEvent.java \
src/de/fricke/pzstory/Settings.java \
src/de/fricke/pzstory/Version.java \
src/de/fricke/pzstory/WorldMemory.java"

rm -rf build/test && mkdir -p build/test
"${JAVAC[@]}" -encoding UTF-8 -Xlint:all -d build/test $UNITS \
    test/zombie/ZomboidFileSystem.java test/de/fricke/pzstory/*.java
"$JAVA_BIN" -cp build/test de.fricke.pzstory.AllTests
