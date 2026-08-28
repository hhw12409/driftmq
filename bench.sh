#!/usr/bin/env bash
# DriftMQ 벤치마크. 환경 정보 + 결과를 bench/results/ 에 저장한다.
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
[ -f driftmq.jar ] || ./build.sh

mkdir -p bench/results
STAMP="$(date +%Y%m%d_%H%M%S)"
OUT="bench/results/bench_${STAMP}.txt"

{
  echo "=== 환경 ==="
  uname -a
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ "$(uname)" = "Darwin" ]; then
    sysctl -n machdep.cpu.brand_string 2>/dev/null || true
    echo "mem: $(( $(sysctl -n hw.memsize) / 1024 / 1024 )) MiB"
  fi
  echo
  echo "=== fsync=none (배치 fsync 상한 참고, 빠름) ==="
  "$JAVA" -Ddriftmq.fsync=none -cp driftmq.jar io.driftmq.bench.Bench "$@"
  echo
  # always 는 fsync 왕복에 바운드되어 느리다 (~250 msgs/s). 기본은 작은 N.
  echo "=== always fsync (기본 정책, fsync 왕복에 바운드 — 수 분 소요 가능) ==="
  "$JAVA" -cp driftmq.jar io.driftmq.bench.Bench --messages "${ALWAYS_MESSAGES:-2000}" --runs "${ALWAYS_RUNS:-3}"
} | tee "$OUT"

echo
echo "saved -> $OUT"
