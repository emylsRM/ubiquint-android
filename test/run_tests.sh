#!/usr/bin/env bash
# Prove sulla JVM delle regole dell'app (Policy). Richiede ./build.sh già eseguito (usa build/gen/.../Config.java).
set -euo pipefail
cd "$(dirname "$0")/.."
CFG=build/gen/it/ubiquint/app/Config.java
[ -f "$CFG" ] || { echo "Esegui prima ./build.sh"; exit 1; }
OUT=build/test-classes
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" app/src/it/ubiquint/app/Policy.java "$CFG" test/PolicyTest.java test/Probe.java
java -cp "$OUT" it.ubiquint.app.PolicyTest
