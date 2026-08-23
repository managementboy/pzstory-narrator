#!/usr/bin/env bash
# Release integrity gate.
#
# Exists because of a real failure: at commit c097a5f the repository shipped
# Main.VERSION="1.23.0-notnow" while mod.info said 1.23.1, the Lua required
# 1.23.1 and the committed JAR reported 1.23.1-public. A clean build from the
# committed source therefore produced firmware the committed Lua refused to
# run, silently breaking the README's "build it yourself" promise.
#
# Nothing here needs the game jars, so CI can run it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SRC=src/de/fricke/pzstory
LUA=mod/42/media/lua/client/PZStory/PZStoryBook.lua
JAR=mod/42/media/java/PZStory.jar
fail=0
say()  { printf '  %-46s %s\n' "$1" "$2"; }
bad()  { say "$1" "FAIL - $2"; fail=1; }
good() { say "$1" "ok ($2)"; }

# file, sed-substitution -> first capture. The caller supplies the whole
# pattern including any anchors, so anchored patterns work too.
extract() {
    sed -n "s/$2/\1/p" "$1" | head -1
}

echo "== versions =="
REL_JAVA=$(extract "$SRC/Version.java" '.*RELEASE = "\([^"]*\)";.*')
API_JAVA=$(extract "$SRC/Version.java" '.*String API = "\([^"]*\)";.*')
REL_MOD=$(extract mod/42/mod.info '^modversion=\(.*\)$')
API_LUA=$(extract "$LUA" '^local NEEDS_API = "\([^"]*\)"')

[ -n "$REL_JAVA" ] || bad "Version.RELEASE parsed" "not found"
[ -n "$API_JAVA" ] || bad "Version.API parsed"     "not found"

if [ "$REL_JAVA" = "$REL_MOD" ]; then good "release: Version.java == mod.info" "$REL_JAVA"
else bad "release: Version.java == mod.info" "java=$REL_JAVA mod.info=$REL_MOD"; fi

if [ "$API_JAVA" = "$API_LUA" ]; then good "api: Version.java == PZStoryBook.lua" "$API_JAVA"
else bad "api: Version.java == PZStoryBook.lua" "java=$API_JAVA lua=$API_LUA"; fi

# The Lua must compare exactly. A prefix match lets 1.23.10 satisfy 1.23.1.
if grep -q 'v:sub(1, #NEEDS' "$LUA"; then
    bad "lua uses exact comparison" "still prefix-matching with sub()"
else
    good "lua uses exact comparison" "api ~= NEEDS_API"
fi

echo
echo "== committed jar =="
if [ ! -f "$JAR" ]; then
    bad "jar present" "missing"
else
    JAR_REL=$(unzip -p "$JAR" de/fricke/pzstory/Version.class 2>/dev/null \
              | strings | grep -xE '[0-9]+\.[0-9]+\.[0-9]+[-A-Za-z0-9.]*' | head -1 || true)
    if [ "$JAR_REL" = "$REL_JAVA" ]; then good "jar release matches source" "$JAR_REL"
    else bad "jar release matches source" "jar=$JAR_REL source=$REL_JAVA"; fi

    # Only our own classes and a manifest. No game classes, no dev files, no
    # stray resources - a jar is a binary users are asked to trust.
    UNEXPECTED=$(unzip -Z1 "$JAR" \
        | grep -vE '^(META-INF/|META-INF/MANIFEST\.MF$|de/(fricke/(pzstory/)?)?$|de/$)' \
        | grep -vE '^de/fricke/pzstory/[A-Za-z0-9$]+\.class$' || true)
    if [ -z "$UNEXPECTED" ]; then good "jar contains only project classes" "$(unzip -Z1 "$JAR" | grep -c '\.class$') classes"
    else bad "jar contains only project classes" "unexpected: $(echo "$UNEXPECTED" | tr '\n' ' ')"; fi

    if unzip -t "$JAR" >/dev/null 2>&1; then good "jar integrity" "zip ok"
    else bad "jar integrity" "corrupt archive"; fi
fi

echo
echo "== no secrets or private data in tracked files =="
# Placeholders are fine; real credentials are not.
LEAK=$(git grep -nIE 'sk-ant-api03-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{30,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY' \
       -- . 2>/dev/null | grep -v REPLACE-ME || true)
if [ -z "$LEAK" ]; then good "no key-shaped strings" "clean"
else bad "no key-shaped strings" "$LEAK"; fi

FORBIDDEN=$(git ls-files | grep -iE '(^|/)(profiles\.json|settings\.json|campaign\.json|console\.txt)$|\.(key|pem|log)$' || true)
if [ -z "$FORBIDDEN" ]; then good "no config/log/save files tracked" "clean"
else bad "no config/log/save files tracked" "$FORBIDDEN"; fi

echo
if [ "$fail" -ne 0 ]; then
    echo "VERIFY FAILED"
    exit 1
fi
echo "VERIFY OK   release $REL_JAVA   bridge api $API_JAVA"
