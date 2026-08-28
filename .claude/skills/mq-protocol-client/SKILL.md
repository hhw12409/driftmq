---
name: mq-protocol-client
description: "DriftMQ 프로토콜 코덱·클라이언트·CLI 구현 절차. 공유 wire 코덱(encode/decode), Producer/Consumer 클라이언트 라이브러리, `driftmq` CLI(start, topic create/list/describe) 구현 패턴과 경계면 함정. protocol-client-engineer 에이전트가 codec/producer/consumer/cli 작업 시 사용."
---

# MQ Protocol & Client — DriftMQ 클라이언트 측 구현

architect의 `_workspace/01_architect_protocol-spec.md`를 코덱·클라이언트·CLI로 구현하는 절차. 핵심: **코덱은 브로커와 공유하는 단일 모듈**, 서버 응답 shape과 디코더 기대가 바이트 단위로 일치.

## 구현 순서

```
공유 wire 코덱 ──┬──► Producer/Consumer 클라이언트 ──► CLI
                └──► (broker-engineer가 서버 측에서 같은 코덱 사용)
```

## 공유 wire 코덱

- 프로젝트에 **하나의 코덱 모듈**을 만든다. broker-engineer가 이 모듈을 import한다. 인코딩/디코딩 로직이 두 곳에 있으면 반드시 드리프트한다.
- 스펙의 "모든 정수 필드 바이트 폭/엔디안 표"를 그대로 구현. 표에 없는 필드는 architect에게 질문.
- **encode**: 요청/응답 구조체 → 바이트. 길이 prefix 포함.
- **decode**: 바이트 스트림 → 구조체. **부분 데이터 처리**: 아직 완전한 프레임이 없으면 "더 필요"를 반환 (예외 아님). 호출측이 더 읽어서 재시도.
- **라운드트립 불변식**: `decode(encode(x)) == x` 모든 메시지 타입에 대해. 이걸 property 테스트로.
- 에러 응답 디코딩: 에러 코드 enum → 클라이언트 예외/에러 타입 매핑.

## Producer 클라이언트

- 연결: 브로커 주소로 TCP 연결, 프로토콜 버전 협상.
- `publish(topic, payload, headers?) -> PublishResult`: 코덱으로 PUBLISH encode → 전송 → 응답 대기 → 할당된 offset/id 반환. **응답을 받기 전까지 성공으로 간주하지 않는다** (브로커가 fsync 후에만 응답).
- 재연결: 연결 끊기면 재시도(백오프). 진행 중이던 publish는 결과 불명 → 호출측에 알림 (at-least-once이므로 재전송 시 중복 가능).
- 배치(선택, v0.1 범위면 생략 가능): 여러 메시지를 한 프레임에.

## Consumer 클라이언트

- `subscribe(topic, consumer_id)` → FETCH 루프 시작.
- FETCH 루프: FETCH 요청 → 메시지 배열 수신 → 사용자 콜백 호출 → 콜백 성공 시 `ack(topic, offset)` 전송 → 다음 FETCH.
- **순서 보장**: 받은 메시지를 offset 순서대로 콜백에 넘긴다. 병렬 처리하면 순서가 깨지므로 기본은 순차.
- **at-least-once idempotency 훅**: 콜백에 message id를 전달하여 사용자가 중복 감지 가능하게. 문서에 "같은 메시지가 두 번 올 수 있음" 명시.
- ACK 실패/연결 끊김: 재연결 후 브로커가 미ACK 메시지를 재전달 (브로커 ACK Manager 담당). 클라이언트는 중복 수신에 대비.
- 롱폴링 vs 폴링: architect 스펙 따름. 폴링이면 빈 응답 시 짧은 sleep.

## driftmq CLI (`docs/driftmq.md` §13)

명령:
- `driftmq start [--data-dir] [--port] [--ack-timeout]` — 브로커 프로세스 기동. broker-engineer의 Server를 띄운다. foreground 실행, SIGINT/SIGTERM에서 graceful shutdown(진행 중 fsync 완료 후 종료).
- `driftmq topic create <name>` — 관리 요청 전송. 이미 존재하면 명확한 에러.
- `driftmq topic list` — 토픽 목록. 사람용 테이블 + `--json` 옵션.
- `driftmq topic describe <name>` — 메시지 수, 최신 offset, consumer 위치 등.

원칙:
- 브로커 미기동 시: "cannot connect to driftmq at localhost:PORT — is it running? (driftmq start)" 같은 실행 가능한 에러.
- 모든 명령에 `--addr` 로 브로커 주소 지정 가능, 기본 `localhost:기본포트`.
- exit code: 성공 0, 사용자 오류 1, 연결 실패 2.
- 출력은 사람이 읽기 쉽게 + `--json`으로 스크립트 파싱 가능.

## 경계면 함정 (qa-verifier가 집중 검사하는 지점)

| 함정 | 예방 |
|------|------|
| 서버는 offset을 8바이트 LE, 클라이언트는 4바이트로 디코딩 | 스펙의 정수 필드 표를 코덱 단일 소스로. broker-engineer와 `_workspace/02_broker_progress.md`의 레이아웃 대조 |
| 코덱이 브로커/클라이언트에 중복 구현되어 드리프트 | 단일 공유 모듈, import만 |
| FETCH 응답이 `{messages: [...]}` 래핑인데 클라이언트가 배열로 기대 | 스펙의 응답 shape을 정확히. decode 결과 타입 명시 |
| 즉시 응답(ACK 확인)과 비동기(재전송)를 클라이언트가 혼동 | 응답 타입 필드로 구분, 스펙 준수 |
| CLI가 브로커 없이도 성공한 척 | 연결 실패를 exit code 2 + 명확한 메시지로 |
| headers 길이 인코딩 불일치 (개수 vs 바이트 수) | 스펙 확인, 코덱 라운드트립 테스트에 headers 포함 |

## 테스트

- 코덱: 모든 메시지 타입 라운드트립 property 테스트, 부분 데이터 decode 테스트.
- 클라이언트: 실제 브로커(broker-engineer 빌드) 대상 통합 테스트 — publish→fetch→ack 흐름, 재연결.
- CLI: 각 명령의 정상/실패 경로, 브로커 미기동 시 에러.
- 라운드트립 실패 = 코덱 버그, 반드시 근본 원인 수정.

## 진행 기록

`_workspace/02_client_progress.md`에 유지: 완료 항목, CLI 명령 목록, 코덱이 다루는 메시지 타입, 코덱이 기대하는 바이트 레이아웃, 미결 이슈.

## 이전 산출물 수정 시

기존 코덱/클라이언트/CLI와 progress 파일을 읽고 피드백 대상만 수정. 코덱 변경 시 broker-engineer의 공유 모듈 사용처도 갱신되는지 확인, qa-verifier에게 라운드트립 + 경계면 재검증 요청.
