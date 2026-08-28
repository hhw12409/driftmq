---
name: broker-implementation
description: "DriftMQ 브로커 코어 구현 절차. append-only log Message Store, Offset Manager, Server 요청 디스패치, ACK/Retry Manager, kill -9 크래시 복구를 구현하는 패턴과 함정. broker-engineer 에이전트가 store/offset/server/ack/retry/recovery 구현 작업 시 사용."
---

# Broker Implementation — DriftMQ 브로커 코어 구현

architect의 `_workspace/01_architect_*.md` 계약을 구현으로 옮기는 절차. 절대 원칙: **fsync로 저장이 확정되기 전에는 producer에게 성공 응답을 보내지 않는다** (`docs/driftmq.md` §6).

## 구현 순서 (의존성)

```
Message Store ──┬──► 크래시 복구
Offset Manager ─┘
Server ──► ACK/Retry Manager
```

Store와 Offset Manager를 먼저 완성하고 단위 테스트를 통과시킨 뒤 Server를 올린다.

## Message Store (append-only log)

- **단일 writer**: 한 파티션 로그는 단일 writer 스레드가 직렬로 append. `BlockingQueue`로 쓰기 요청을 받는다. 락 대신 직렬화가 추론하기 쉽다.
- **레코드 포맷**: architect의 저장 설계를 따른다. 최소 `[length][crc32][timestamp][headers_len][headers][payload]`. length는 자기 자신을 제외한 나머지 바이트 수.
- **append 절차**: 레코드 직렬화 → `write()` → **fsync** (Java: `FileChannel.force(false)` 또는 `FileDescriptor.sync()`) → 인메모리 인덱스 갱신 → offset 반환. fsync 실패 시 offset을 반환하지 않고 에러.
- **read 절차**: offset → 인메모리 인덱스로 파일 위치 조회 → 해당 위치부터 N개 레코드 읽기 → CRC 검증 → 디코딩. CRC 불일치는 손상 — 로그하고 그 지점에서 멈춘다.
- **인메모리 인덱스**: `offset -> file_position` 맵. append마다 갱신. 재시작 시 로그 전체 스캔으로 재구축.
- **fsync 정책**: architect가 정한 기본값 구현. 배치 fsync 옵션이면, fsync 완료 전까지 대기 중인 요청들의 응답을 보류하는 큐가 필요.

## Offset Manager

- **메시지 offset 할당**: Store append와 원자적으로. offset은 파티션 내 0부터 단조 증가, 절대 재사용/역행 없음.
- **consumer 소비 위치**: `commit(consumer_id, topic, offset)` → 영속화. `position(consumer_id, topic) -> offset`. architect가 정한 위치(`data/{topic}/offsets` 등)에 저장, commit마다 fsync 또는 주기적 fsync (설계 따름).
- 재시작 시 offset 파일을 읽어 각 consumer 위치 복원. 파일이 손상/부재면 0(처음부터) 또는 로그 끝(신규만) — architect의 delivery semantics를 따른다.

## Server + 요청 디스패치

- architect의 프로토콜 스펙을 서버 측에서 구현. **wire 코덱은 protocol-client-engineer와 공유하는 단일 모듈** — 여기서 재구현하지 않는다. 코덱 모듈을 import한다.
- 연결당 read 루프: 프레임 경계까지 버퍼링 → 코덱으로 decode → 핸들러 디스패치 → 응답 encode → write.
- 부분 read: TCP는 프레임 중간에서 끊길 수 있다. 길이 prefix를 먼저 완전히 읽고, 명시된 바이트 수만큼 채워질 때까지 read 반복.
- 핸들러: `handle_publish`, `handle_fetch`, `handle_ack`. 시그니처는 `_workspace/01_architect_task-breakdown.md` 계약을 따른다.
- 백프레셔: 느린 consumer가 서버 메모리를 무한정 먹지 않도록 연결별 in-flight 상한 (ACK Manager와 연동).
- 확정된 응답 shape을 `_workspace/02_broker_progress.md`에 정확한 바이트 레이아웃으로 기록 → protocol-client-engineer가 대조.

## ACK / Retry Manager (v0.2+)

- **in-flight 테이블**: `(consumer_id, message_id) -> {delivered_at, retry_count}`. FETCH로 전달할 때 등록.
- **ACK 수신**: 테이블에서 제거. 이미 없으면(중복 ACK 또는 timeout 후 도착) 무시하고 로그.
- **timeout 스캐너**: 주기적으로 `now - delivered_at > ack_timeout`인 항목을 재전송 큐로. `retry_count++`. architect가 정한 최대 재시도 초과 시 처리(v0.2는 로그, 장기적으로 DLQ).
- **재전송 시 순서**: 같은 consumer에게 재전송 메시지를 신규보다 먼저 줄지 정책 확인 (architect delivery semantics).
- consumer 연결 종료 시: 해당 consumer의 in-flight 항목을 즉시 재전송 대상으로 (다른 consumer 또는 재연결 대기).

## 크래시 복구 (핵심 학습 포인트)

**가정: `kill -9`가 임의 시점에 발생.** 정상 종료를 가정하지 않는다.

복구 절차 (재시작 시):
1. 각 topic 로그 파일을 처음부터 스캔.
2. 레코드마다: length 읽기 → 그만큼 읽기 → CRC 검증.
3. **부분 쓰기 감지**: 파일 끝에서 length가 남은 바이트보다 크거나, CRC 불일치 → 마지막 레코드가 부분 쓰기. 그 레코드 시작 위치로 파일을 `truncate`.
4. 유효한 마지막 레코드의 offset = 복구된 로그 끝. 인메모리 인덱스 재구축.
5. offset 파일 읽어 consumer 위치 복원. offset 파일 자체도 부분 쓰기 가능 → 파싱 실패 시 마지막 유효 항목까지만.
6. ACK 상태: in-flight 테이블은 영속화하지 않는 것이 단순 (재시작 후 모든 미커밋 메시지를 재전달 대상으로). architect 설계에 따름.

**복구 후 불변식** (qa-verifier가 검증):
- offset이 재시작 across 역행/스킵 없음.
- 커밋된 메시지는 전부 존재, 부분 프레임은 제거됨.
- 미ACK 메시지는 재시작 후 재전달됨, 유실 0.

## 동시성 함정

- Store append와 offset 할당 사이에 다른 append가 끼면 offset이 뒤섞인다 → 단일 writer로 직렬화.
- 인메모리 인덱스 읽기(read 핸들러)와 쓰기(append) 동시 접근 → `ReentrantReadWriteLock`, 또는 인덱스를 `ConcurrentHashMap`으로, 또는 인덱스 갱신도 writer 스레드 경유.
- timeout 스캐너와 ACK 핸들러가 in-flight 테이블 동시 수정 → 락(`synchronized` / `ReentrantLock`), 락 범위 최소.

## 테스트

각 컴포넌트에 단위 테스트. 추가로:
- Store: 1만 개 append 후 순차 read가 동일 순서·내용.
- 복구: append 도중 프로세스 중단 시뮬레이션(부분 바이트를 파일에 직접 write) → 복구 후 불변식 확인.
- ACK: timeout 후 재전송, 재전송 중 ACK 도착 시 중복 처리.
- 플래키 테스트는 타이밍 의존을 제거하여 결정적으로. skip 금지.

## 진행 기록

`_workspace/02_broker_progress.md`에 유지:
- 완료 컴포넌트 + 공개 인터페이스 시그니처
- 확정된 서버 응답 바이트 레이아웃
- 미결 이슈 / architect에게 질문 중인 항목

## 이전 산출물 수정 시

기존 소스와 progress 파일을 읽고 피드백이 가리키는 컴포넌트만 수정. 저장 포맷 변경 시 버전 바이트 추가 또는 마이그레이션 로직을 함께 넣고 qa-verifier에게 복구 재검증 요청.
