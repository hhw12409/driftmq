---
name: mq-benchmarking
description: "DriftMQ 벤치마크·사용자 문서 작성 절차. 처리량/지연(p50/p95/p99)/복구시간 측정 하니스, 워크로드별 결과 해석, README 빠른 시작·CLI 레퍼런스·wire 프로토콜 문서·아키텍처 개요 작성. bench-doc-writer 에이전트가 bench/measure/docs/readme 작업 시 사용."
---

# MQ Benchmarking & Docs — DriftMQ 측정과 문서화

재현 가능한 벤치마크와, 처음 보는 사람이 5분 안에 브로커를 띄우는 문서를 만드는 절차. 정확성이 확인되지 않은(qa-verifier PASS 전) 빌드는 벤치하지 않는다.

## 파트 1: 벤치마크

### 측정 지표
- **publish 처리량**: msgs/sec, MB/sec. 메시지 크기별(100B, 1KB, 10KB).
- **end-to-end 지연**: publish 시각 → consume 시각. p50/p95/p99/max.
- **크래시 복구 시간**: kill -9 → 재기동 → 첫 정상 응답까지. 로그 크기별.
- **저장 증가율**: 메시지 payload 대비 디스크 사용량 (오버헤드 비율).
- **동시성 확장**: producer/consumer 수를 늘리며 처리량 변화.

### 하니스 원칙
- **환경 기록**: CPU 모델·코어 수, RAM, OS·커널, 디스크 종류(SSD/NVMe/가상), 파일시스템. 이것 없는 수치는 무의미.
- **설정 기록**: fsync 정책, ack-timeout, in-flight 상한, 메시지 크기, 배치 여부.
- **워밍업**: 첫 N초/N개는 버린다 (JIT/캐시/파일 확장 안정화).
- **다중 실행**: 최소 3회, 중앙값과 분산 보고. 단일 최대치를 대표값으로 쓰지 않는다.
- **격리**: 벤치 중 다른 부하 없는 상태. CI/가상화면 그 사실을 명시하고 절대 수치보다 상대 비교(설정 A vs B)에 집중.
- 하니스는 프로젝트 소스 트리에 (`bench/`), 재실행 가능하게. 한 커맨드로 전체 스위트 실행.

### fsync 정책 비교 (핵심 실험)
`docs/driftmq.md` §7의 트레이드오프를 수치로:
| 정책 | publish 처리량 | 지연 p99 | 크래시 시 손실 창 |
|------|---------------|----------|-------------------|
| publish마다 fsync | | | 0 |
| 배치 fsync (Nms) | | | 최대 Nms |

### 결과 문서 (`_workspace/04_bench_results.md`)
```markdown
# Bench Results — {대상 범위} — {날짜}

## 환경
{CPU/RAM/OS/디스크/FS}

## 결과
### publish 처리량
| 메시지 크기 | fsync 정책 | msgs/sec (중앙값) | MB/sec | 분산 |

### end-to-end 지연
| 조건 | p50 | p95 | p99 | max |

## 해석
{병목 지점. 예: "1KB에서 fsync-per-publish가 디스크 IOPS에 바운드, 배치 fsync로 4배". }

## 회귀/개선 (이전 결과 대비)
{이전 벤치가 있으면 델타}
```

성능 이상(지연 스파이크, 처리량 급락)은 데이터와 함께 broker-engineer에게 `SendMessage`.

## 파트 2: 사용자 문서

`docs/driftmq.md`는 원본 비전 문서 — **수정하지 않는다.** 구현 현황과의 차이는 별도 문서에.

### README.md (빠른 시작)
- 한 문단 소개 + non-goals 명시 (Kafka급 처리량/exactly-once 아님 — 기대 설정).
- 빌드 방법 (한 커맨드).
- 5분 튜토리얼: `driftmq start` → topic 생성 → 예제 producer/consumer 코드 → 메시지 흐르는 것 확인.
- 실제로 복붙해서 동작하는 명령만. protocol-client-engineer의 CLI 실제 동작을 확인하고 쓴다.

### CLI 레퍼런스 (`docs/cli.md`)
- 각 명령: 구문, 플래그, 예시 입출력, exit code.
- CLI 소스와 대조하여 실제와 일치 확인.

### wire 프로토콜 문서 (`docs/protocol.md`)
- architect의 `_workspace/01_architect_protocol-spec.md`를 사용자용으로 재작성.
- 프레임 레이아웃 다이어그램, 요청/응답 타입, 에러 코드 표, 버전.
- 코덱 구현과 대조 — 문서와 코드가 다르면 architect·qa-verifier에게 알림.

### 아키텍처 개요 (`docs/architecture.md`)
- 컴포넌트 다이어그램 (`docs/driftmq.md` §11 기반, 실제 구현된 것만).
- 데이터 흐름: publish 경로, consume 경로, 복구 경로.
- 저장 레이아웃: `data/` 디렉토리 구조, 로그 레코드 포맷.
- delivery semantics 요약 (at-least-once가 실무에서 의미하는 것).

### 문서 동기화
코드/프로토콜/CLI가 바뀌면 해당 문서 갱신. `_workspace/04_doc_summary.md`에 작성/갱신 문서 목록과 커버리지(어떤 기능이 문서화됐고 안 됐는지) 기록.

## 원칙
- 명령형·예시 중심. 장황한 설명 대신 동작하는 예시.
- 문서의 모든 명령·코드는 실행 검증. 안 되는 것 싣지 않는다.
- non-goal을 눈에 띄게. 사용자 기대를 정확히.

## 이전 산출물 수정 시
기존 벤치 스크립트·문서와 `_workspace/04_*`를 읽는다. 변경분에 해당하는 벤치만 재실행, 이전 결과와 비교. 문서는 변경된 섹션만 갱신.
