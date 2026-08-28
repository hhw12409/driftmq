#!/usr/bin/env bash
# DriftMQ 빌드 — 순수 JDK (Gradle/Maven 불필요, 외부 의존성 0)
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

OUT=out/main
rm -rf "$OUT"
mkdir -p "$OUT"

echo "[build] compiling main sources..."
find src/main/java -name '*.java' > "$OUT/.sources"
"$JAVAC" --release 21 -Xlint:all -d "$OUT" @"$OUT/.sources"

echo "[build] packaging driftmq.jar..."
"$JAR" --create --file driftmq.jar --main-class io.driftmq.cli.Main -C "$OUT" .

echo "[build] OK -> driftmq.jar"
