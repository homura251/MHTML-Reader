#!/bin/sh
set -eu
if [ "$#" -ne 1 ]; then
  echo "usage: $0 <archive-file>" >&2
  exit 2
fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/mhtml-lens-smoke
rm -rf "$TMP"
mkdir -p "$TMP"
kotlinc "$ROOT/app/src/main/java/com/example/mhtmllens/mhtml/MhtmlParser.kt" -d "$TMP/parser.jar"
kotlinc -cp "$TMP/parser.jar" "$ROOT/tools/ParserSmokeTest.kt" -include-runtime -d "$TMP/test.jar"
java -cp "$TMP/test.jar:$TMP/parser.jar" ParserSmokeTestKt "$1"
