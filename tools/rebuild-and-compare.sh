#!/usr/bin/env bash
# Prove that the checked-in release JAR is exactly the artifact produced by
# this checkout. The build needs Project Zomboid's proprietary compile-time
# libraries, so this is a maintainer/reviewer gate rather than a hosted-CI job.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMITTED="$ROOT/mod/42/media/java/PZStory.jar"

if [ ! -f "$COMMITTED" ]; then
    echo "Committed release jar is missing: $COMMITTED" >&2
    exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pzstory-rebuild.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
REBUILT="$WORK/PZStory.jar"

echo "== isolated rebuild =="
JAR_OUT="$REBUILT" "$ROOT/build.sh"

echo "== byte comparison =="
if cmp -s "$COMMITTED" "$REBUILT"; then
    HASH="$(sha256sum "$REBUILT" 2>/dev/null | awk '{print $1}' \
            || shasum -a 256 "$REBUILT" | awk '{print $1}')"
    echo "REPRODUCIBLE: committed and rebuilt jars are identical"
    echo "sha256: $HASH"
    exit 0
fi

echo "REPRODUCIBILITY FAILURE: committed jar differs from this checkout" >&2
echo >&2
echo "committed:" >&2
(sha256sum "$COMMITTED" 2>/dev/null || shasum -a 256 "$COMMITTED") >&2
echo "rebuilt:" >&2
(sha256sum "$REBUILT" 2>/dev/null || shasum -a 256 "$REBUILT") >&2
echo >&2
echo "Archive entry comparison:" >&2
diff -u <(unzip -Z1 "$COMMITTED") <(unzip -Z1 "$REBUILT") >&2 || true
echo >&2
echo "Rebuild and commit mod/42/media/java/PZStory.jar with the documented" >&2
echo "JDK/game-library toolchain before publishing." >&2
exit 1
