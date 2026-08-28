---
name: mq-architecture
description: "메시지 브로커(DriftMQ) 아키텍처 설계 절차. 스택 선정 ADR, wire 프로토콜 스펙, append-only log 저장 구조, delivery semantics 정의, 로드맵 버전의 구현 태스크 분해와 인터페이스 계약 작성. architect 에이전트가 설계/스펙/ADR/트레이드오프 작업 시 사용."
---

# MQ Architecture — DriftMQ 설계 절차

DriftMQ의 내부 모델을 안정화하는 설계 산출물을 만드는 절차. 핵심 원칙은 `docs/driftmq.md` §16: **Simple first. Distributed later.** 각 단계에서 "왜 다음 기능이 필요한지" 근거를 남긴다.

## 산출물과 순서

1. 스택 선정 ADR → `_workspace/01_architect_stack-adr.md`
2. wire 프로토콜 스펙 → `_workspace/01_architect_protocol-spec.md`
3. 저장 구조 설계 → `_workspace/01_architect_storage-design.md`
4. delivery semantics 정의 → `_workspace/01_architect_delivery-semantics.md` (대상 범위가 v0.2 이상일 때)
5. 구현 태스크 분해 + 인터페이스 계약 → `_workspace/01_architect_task-breakdown.md`

## 1. 스택 선정 ADR

제약 (`docs/driftmq.md` §3, §4):
- 단일 실행 명령(`driftmq start`), 별도 DB 없음. 네이티브 단일 바이너리가 아닌 언어면 패키징 방안(fat JAR + 래퍼 스크립트 / jlink / native-image)을 ADR에 명시
- Kafka 프로토콜 호환·exactly-once·multi-region은 non-goal
- 학습 목적 — 이해하기 쉬운 구현

평가 축: 배포 패키징 용이성(단일 바이너리 또는 fat JAR + 실행 스크립트), 파일 I/O·fsync 제어, 동시성 모델, 생태계(테스트·벤치 도구), 팀 친숙도.

ADR 형식:
```markdown
# ADR-001: 구현 스택

## 컨텍스트
{제약과 목표}

## 결정
언어: {선택}  / 빌드·패키징: {Gradle fat JAR | jlink | native-image | 단일 바이너리}  / 전송: {TCP 길이-prefix 바이너리 | HTTP 우선}  / 직렬화: {선택}

## 근거
{왜 이것이 제약에 맞는가}

## 대안과 기각 사유
| 대안 | 장점 | 기각 사유 |
|------|------|-----------|

## 결과
{빌드·배포·테스트에 미치는 영향}
```

기본 추천(정보 부족 시): **Java 21(LTS) + `java.nio` 논블로킹 소켓 또는 가상 스레드 + 길이-prefix 바이너리 프레이밍 + 커스텀 인코딩**. 빌드: Gradle. 배포: fat JAR + `driftmq` 래퍼 스크립트(후속: jlink/native-image). 근거: 팀이 사용 가능한 유일 언어, Kafka·ActiveMQ·Pulsar 레퍼런스 다수, `FileChannel.force()`·`CRC32` 등 표준 API, Virtual Thread로 연결당 스레드 모델을 단순하게 유지. HTTP 우선 검증 후 TCP 전환도 유효한 대안이며 ADR에 명시.

## 2. wire 프로토콜 스펙

`docs/driftmq.md` §12의 방향(`PUBLISH`, `FETCH`, `ACK`)을 정확한 스펙으로 만든다.

반드시 정의할 것:
- **프레이밍**: 메시지 경계를 어떻게 아는가 (길이 prefix 폭, 엔디안). 부분 read 처리.
- **요청 타입별 레이아웃**: 필드 순서, 각 정수 필드의 바이트 폭과 엔디안, 가변 길이 필드(topic 이름, payload, headers)의 길이 인코딩. → **모든 정수 필드의 바이트 폭/엔디안을 표로** 명시 (경계면 버그 예방).
- **응답 타입별 레이아웃**: 성공 응답 shape (PUBLISH → 할당된 offset/id, FETCH → 메시지 배열, ACK → 확인).
- **에러 코드**: 열거값과 의미 (UNKNOWN_TOPIC, STORAGE_FULL, MALFORMED_FRAME 등). 에러 응답 레이아웃.
- **버전 협상**: 프로토콜 버전 필드. 향후 확장을 위한 예약 바이트.
- **연결 수명**: FETCH가 롱폴링인가 즉시 응답인가, keepalive.

Message 필드는 `docs/driftmq.md` §5: id, topic, offset, timestamp, headers, payload. 각각의 타입·크기 상한을 정한다.

## 3. 저장 구조 설계

`docs/driftmq.md` §7. append-only log.

정의할 것:
- **로그 프레임 포맷**: 디스크에 쓰이는 레코드 레이아웃 (length, CRC, timestamp, headers, payload). wire 포맷과 별개 — 디스크는 CRC로 무결성 검증.
- **디렉토리 구조**: `data/{topic}/{segment}.log`. 초기엔 단일 세그먼트, v0.5에서 세그먼트 롤링.
- **오프셋 → 파일 위치 매핑**: 순차 스캔인가 인덱스인가. 초기엔 인메모리 인덱스(재시작 시 로그 스캔으로 재구축) 권장.
- **fsync 정책**: publish마다 fsync (안전, 느림) vs 배치 fsync (빠름, 창 손실). 기본값과 설정 옵션 명시. "저장 완료 후 성공 응답"(§6) 원칙과의 관계 명시.
- **크래시 복구 시맨틱**: 재시작 시 마지막 레코드가 부분 쓰기일 수 있음 → CRC 불일치 감지 → 해당 지점에서 truncate. consumer 오프셋·ACK 상태의 영속화 위치와 복구 순서.
- **오프셋 영속화**: consumer 소비 위치를 어디에 저장하는가 (별도 파일, 로그 내 마커). 초기엔 `data/{topic}/offsets` 파일 권장.

## 4. delivery semantics 정의 (v0.2+)

`docs/driftmq.md` §8. at-least-once.

숫자로 답할 것:
- ACK timeout 기본값 (초). 설정 가능.
- timeout 후 재전송 정책: 즉시 / 백오프. 최대 재시도 횟수. 초과 시 동작 (v0.2에선 로그만, DLQ는 장기 목표).
- in-flight 상한: 한 consumer에게 동시에 몇 개까지 미ACK 상태로 전달하는가.
- 중복 전달 경계: 정확히 어떤 상황에서 같은 메시지가 두 번 가는가 (timeout 후 재전송 중 원래 ACK 도착 등). consumer idempotency 가이드.
- consumer 재연결 시: 미ACK 메시지를 재전달하는가, 새 메시지부터인가.

## 5. 구현 태스크 분해 + 인터페이스 계약

대상 버전을 broker-engineer와 protocol-client-engineer가 **병렬로** 구현할 수 있는 태스크로 나눈다. 핵심은 **경계 인터페이스를 먼저 확정**하는 것.

각 태스크에 명시:
- 담당 에이전트
- 입력/출력 (함수 시그니처 또는 의사코드)
- 의존 태스크
- 완료 기준 (어떤 테스트가 통과해야 하는가)

**공유 계약** (두 에이전트가 합의해야 하는 지점):
- wire 코덱 인터페이스: `encode(request) -> bytes`, `decode(bytes) -> request`. broker-engineer와 protocol-client-engineer가 **같은 코덱 모듈**을 쓴다 — 계약에 "코덱은 공유 모듈, 중복 구현 금지" 명시.
- Server가 노출하는 핸들러 시그니처: `handle_publish(req) -> PublishResponse` 등.
- Message Store 공개 API: `append(topic, msg) -> offset`, `read(topic, offset, max) -> [msg]`.
- Offset Manager 공개 API: `commit(consumer, topic, offset)`, `position(consumer, topic) -> offset`.

## 스펙 질문 대응

구현 중 엔지니어가 모호성을 질문하면:
1. 즉시 결정한다 (미루지 않는다).
2. "Simple first" 원칙으로 판정하고 근거를 회신에 포함.
3. 해당 스펙 문서(`_workspace/01_architect_*.md`)를 갱신한다 — 구두 답변만 남기지 않는다.
4. 결정이 이미 구현된 코드를 깨면 어떤 태스크가 영향받는지 명시하고 관련 에이전트에게 알린다.

## 이전 산출물 개정 시

`_workspace/01_architect_*.md`가 있으면 읽고, 피드백이 가리키는 문서만 개정한다. 프로토콜/저장 포맷 변경은 파급이 크므로: 변경 요약 + 깨지는 하위 태스크 목록 + 마이그레이션 필요 여부를 문서 상단에 기록하고 두 엔지니어·qa-verifier에게 통지.
