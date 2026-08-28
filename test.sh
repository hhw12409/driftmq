#!/usr/bin/env bash
# DriftMQ 테스트 — 단위 + MVP 시나리오. 외부 프레임워크 없음.
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

MAIN_OUT=out/main
TEST_OUT=out/test

if [ ! -d "$MAIN_OUT" ]; then
  echo "[test] main not built; running build.sh"
  ./build.sh
fi

rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"

echo "[test] compiling test sources..."
find src/test/java -name '*.java' > "$TEST_OUT/.sources"
"$JAVAC" --release 21 -cp "$MAIN_OUT" -d "$TEST_OUT" @"$TEST_OUT/.sources"

# 명시적 테스트 클래스 목록 (classpath 스캔 대신 — Simple first)
TESTS=(
  io.driftmq.protocol.CodecTest
  io.driftmq.broker.MessageStoreTest
  io.driftmq.broker.RecoveryTest
  io.driftmq.broker.OffsetManagerTest
  io.driftmq.broker.AckManagerTest
  io.driftmq.client.ClientIntegrationTest
  io.driftmq.cli.CliTest
  io.driftmq.scenario.MvpScenarioTest
  io.driftmq.scenario.CorrectnessTest
)

echo "[test] running..."
"$JAVA" --enable-native-access=ALL-UNNAMED -cp "$MAIN_OUT:$TEST_OUT" io.driftmq.test.MiniTest "${TESTS[@]}"
