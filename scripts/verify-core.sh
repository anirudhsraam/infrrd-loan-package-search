#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/target/core-check"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
  "$ROOT/src/main/java/ai/infrrd/loansearch/domain/PageRecord.java" \
  "$ROOT/src/main/java/ai/infrrd/loansearch/search/QuerySyntaxException.java" \
  "$ROOT/src/main/java/ai/infrrd/loansearch/search/QueryNode.java" \
  "$ROOT/src/main/java/ai/infrrd/loansearch/search/TextTokenizer.java" \
  "$ROOT/src/main/java/ai/infrrd/loansearch/search/QueryParser.java" \
  "$ROOT/src/main/java/ai/infrrd/loansearch/search/SearchEngine.java" \
  "$ROOT/scripts/SearchEngineSelfTest.java" \
  "$ROOT/scripts/SearchBenchmark.java"
java -cp "$OUT" SearchEngineSelfTest
java -cp "$OUT" SearchBenchmark "$ROOT/data/layout.txt"
