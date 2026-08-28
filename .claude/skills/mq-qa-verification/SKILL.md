---
name: mq-qa-verification
description: "DriftMQ 통합 정합성·정확성 검증 절차. 프로토콜 스펙↔서버↔클라이언트 코덱 경계면 교차 비교, MVP 인수 시나리오 실행, 순서 보장·at-least-once·크래시 복구·오프셋 단조성 테스트, 폴트 인젝션. qa-verifier 에이전트가 verify/qa/integration-check/mvp-scenario 작업 시 사용."
---

# MQ QA Verification — DriftMQ 통합 검증 절차

"각각은 맞는데 붙이면 깨진다"는 경계면 버그를 잡는 절차. 빌드/타입체크 통과는 정상 동작을 보장하지 않는다 — **반드시 브로커를 실제로 띄워 트래픽을 흘린다.**

## 검증 우선순위

1. **통합 정합성** (최우선) — 경계면 계약 불일치
2. **정확성 스펙 준수** — 순서, at-least-once, 오프셋 단조성, 크래시 복구
3. **MVP 인수 시나리오** — `docs/driftmq.md` §14
4. **코드 품질** — skip된 테스트, 플래키 테스트, 미사용 코드

## 원칙: "양쪽 동시 읽기"

경계면은 생산자·소비자 코드를 **함께 열어** 대조한다. 한쪽만 읽으면 못 잡는다.

| 검증 대상 | 왼쪽 (생산자) | 오른쪽 (소비자) | 검사 방법 |
|-----------|--------------|----------------|-----------|
| wire 메시지 레이아웃 | 프로토콜 스펙의 정수 필드 표 + 서버 인코딩 | 클라이언트 코덱 디코딩 | 필드별 바이트 폭/엔디안이 3곳 모두 동일한가 |
| 코덱 공유 여부 | broker-engineer가 import하는 코덱 모듈 경로 | protocol-client-engineer의 코덱 모듈 경로 | 같은 파일인가. 중복 구현이면 FAIL |
| 서버 응답 shape | 서버 응답 생성부 | 클라이언트 응답 파싱부 | 래핑/필드명/필드 순서 일치 |
| 오프셋 시맨틱 | Offset Manager 할당·commit | Consumer 오프셋 추적 | 0-기반? 단조 증가? commit 의미(마지막 처리 vs 다음 읽을 위치) 일치 |
| 저장 포맷 | Store append 인코딩 | 크래시 복구 read 디코딩 | length/CRC 위치·폭 일치 |
| ACK 계약 | ACK Manager timeout·재전송 규칙 | Consumer ACK 전송 타이밍·형식 | timeout 값, 재전송 트리거, ACK 메시지 레이아웃 일치 |

**방법**: Grep으로 양쪽의 인코딩/디코딩 코드를 추출 → 필드별 표로 나란히 정렬 → 불일치 표시. 필요하면 `scripts/`의 대조 스크립트 사용 또는 작성.

## incremental QA

전체 완성을 기다리지 않는다. 엔지니어가 "컴포넌트 완성" 통지하면 즉시:
- 코덱 완성 → 코덱 라운드트립 + 스펙 대조
- Server 완성 → 서버 응답 shape ↔ 클라이언트 파싱 대조
- Store + 복구 완성 → 크래시 복구 테스트
- 클라이언트 완성 → publish→fetch→ack 흐름
- ACK/Retry 완성 → at-least-once 테스트

버그를 조기에 잡아야 후속 모듈로 전파되지 않는다.

## 핵심 정확성 테스트 (실제 실행)

### 순서 보장
```
brew 브로커 기동 → topic 생성 → producer로 N개 메시지(payload=순번) publish
→ consumer로 전량 consume → 수신 순서가 0,1,2,...,N-1 인가
```
FAIL 조건: 순서 뒤바뀜, 누락, 중복(ACK 정상 흐름에서).

### at-least-once
```
100개 publish → consumer가 50개 consume+ACK 후 kill(ACK 없이 죽음)
→ consumer 재시작 → 나머지 50개(+ 미ACK분) 수신되는가. 유실 0인가.
```
중복은 허용(at-least-once), 유실은 FAIL.

### 크래시 복구 (`docs/driftmq.md` §14 시나리오 7~9)
```
N개 publish (fsync 확인) → publish 도중 broker에 kill -9
→ broker 재시작 → 커밋된 메시지 전부 존재하는가
→ 부분 쓰인 마지막 프레임이 truncate 되었는가 (로그 파일 크기/CRC 확인)
→ consumer 오프셋 복원되는가 → 미ACK 메시지 재전달되는가
```

### 오프셋 단조성
```
publish/consume/재시작을 섞어 반복 → 어느 시점에도 offset이 역행하거나 건너뛰지 않는가
consumer position이 재시작 후 되돌아가지 않는가
```

### 폴트 인젝션
- fsync 직전 크래시 (가능하면 fsync를 가로채는 테스트 훅)
- consumer 연결을 처리 중 강제 종료 → in-flight 메시지 재전달 확인
- 매우 느린 consumer → 서버 메모리 무한 증가하지 않는가 (백프레셔)
- 손상된 로그 파일(임의 바이트 뒤집기) → 복구가 안전하게 중단/truncate 하는가

## MVP 인수 시나리오 (`docs/driftmq.md` §14)

9단계를 순서대로 실행하고 단계별 PASS/FAIL 표로 기록:

| # | 단계 | 검증 | 결과 |
|---|------|------|------|
| 1 | DriftMQ 실행 | `driftmq start` 정상 기동 | |
| 2 | Topic 생성 | `driftmq topic create orders` 성공 | |
| 3 | 100개 publish | 전량 성공 응답, offset 0~99 | |
| 4 | 디스크 저장 | `data/orders/` 로그 파일에 100개 레코드 | |
| 5 | 순서대로 consume | 수신 순서 = publish 순서 | |
| 6 | ACK | 100개 ACK 후 in-flight 0 | |
| 7 | Broker 재시작 | 정상 종료 후 재기동 | |
| 8 | 메시지·상태 복구 | 100개 메시지 존재, consumer position 복원 | |
| 9 | 미ACK 재전달 | (일부 미ACK 상태에서 재시작) 미ACK분 재전달 | |

## 리포트 형식

`_workspace/03_qa_report.md`:
```markdown
# QA Report — {대상 범위} — {날짜}

## 통합 정합성
| 경계면 | 결과 | 상세 |
| ... | PASS/FAIL | 불일치 시: 파일:라인 (양쪽) + 수정 방법 |

## 정확성 테스트
| 테스트 | 결과 | 정량 결과 |
| 순서 보장 | PASS | 10000개, 순서 일치 |
| at-least-once | FAIL | 3개 유실. broker/AckManager.java:88 timeout 스캐너가 ... |

## MVP 인수 시나리오
{9단계 표}

## 미검증 항목
{이유와 함께}
```

3분류(PASS/FAIL/미검증)를 명확히. FAIL은 재현 절차(입력→기대→실제)와 수정 대상 파일:라인. 정량 결과는 숫자로.

## 팀 통신

- 경계면 FAIL은 관련 **양쪽** 에이전트 모두에게 `SendMessage` (파일:라인 + 수정 방법).
- 같은 경계면이 3회+ FAIL이면 리더에게 알림 → architect의 스펙 명확화 필요 신호.
- 리더에게 검증 리포트 요약 (통과 수 / 실패 수 / 미검증 수).

## 회귀 방지 (이전 리포트가 있을 때)

`_workspace/03_qa_report.md`를 읽고 이전 FAIL 항목을 **먼저** 재검증. 수정됐다면 PASS로, 여전히면 우선순위 상향. 새 변경이 이전 PASS를 깨지 않았는지 관련 테스트 재실행.
