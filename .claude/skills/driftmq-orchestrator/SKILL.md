---
name: driftmq-orchestrator
description: "DriftMQ 메시지 브로커 개발 에이전트 팀을 조율하는 오케스트레이터. 브로커/토픽/publish/consume/ACK/재전송/오프셋/파티션/컨슈머그룹/저장/복구/프로토콜/CLI 구현, 로드맵 버전(v0.1~v1.0) 개발, 아키텍처 설계, 벤치마크, 문서 작업 시 반드시 이 스킬을 사용. 후속 작업 — DriftMQ 기능 수정, 특정 컴포넌트(store/offset/codec/cli 등)만 다시, 버그 수정, 재실행, 업데이트, 보완, 이전 결과 개선, QA 재검증, 벤치 재측정 요청 시에도 반드시 이 스킬을 사용. 단순 개념 질문은 직접 응답 가능."
---

# DriftMQ Orchestrator

DriftMQ(경량 메시지 브로커)의 에이전트 팀을 조율하여 설계·구현·검증·문서화를 하나의 워크플로우로 수행한다.

## 실행 모드: 에이전트 팀

단일 팀 5명이 세션 내내 활성 상태로 협업한다. 파이프라인(설계→구현→검증→문서)이지만 팀을 유지하여 qa-verifier가 각 모듈 완성 직후 incremental QA를 하고, architect가 구현 중 스펙 질문에 실시간 답한다.

## 에이전트 구성

| 팀원 | 타입 | 역할 | 스킬 | 주요 출력 |
|------|------|------|------|-----------|
| architect | general-purpose | 스택 ADR, 프로토콜 스펙, 저장 설계, delivery semantics, 태스크 분해 | mq-architecture | `_workspace/01_architect_*.md` |
| broker-engineer | general-purpose | Server/Store/Offset/ACK/Retry/복구 구현 | broker-implementation | 소스 + `_workspace/02_broker_progress.md` |
| protocol-client-engineer | general-purpose | 코덱, Producer/Consumer 클라이언트, CLI | mq-protocol-client | 소스 + `_workspace/02_client_progress.md` |
| qa-verifier | general-purpose | 경계면 교차검증, MVP 시나리오, 크래시/재전송/순서 테스트 | mq-qa-verification | `_workspace/03_qa_report.md` |
| bench-doc-writer | general-purpose | 벤치마크 하니스·측정, 사용자 문서 | mq-benchmarking | 소스 + `_workspace/04_*.md` |

모든 `TeamCreate` 멤버와 모든 `Agent` 호출에 `model: "opus"`를 명시한다.

## 워크플로우

### Phase 0: 컨텍스트 확인 (초기/후속/부분 재실행 판별)

1. `_workspace/` 디렉토리 존재 여부 확인.
2. 실행 모드 결정:
   - **`_workspace/` 미존재** → **초기 실행**. Phase 1로.
   - **`_workspace/` 존재 + 사용자가 부분 수정/특정 컴포넌트 요청** (예: "코덱만 다시", "복구 버그 수정", "벤치 재측정") → **부분 재실행**. 관련 에이전트만 팀에 포함하고, 그 에이전트에게 이전 산출물 경로 + 피드백을 전달한다. 나머지 산출물은 건드리지 않는다.
   - **`_workspace/` 존재 + 새 대상 버전/새 범위 지정** (예: "이제 v0.3 컨슈머 그룹") → **새 실행**. 기존 `_workspace/`를 `_workspace_{YYYYMMDD_HHMMSS}/`로 이동한 뒤 새로 생성. 단, 이전 소스 코드는 유지하고 그 위에 증분 개발.
3. 사용자에게 판별 결과와 대상 범위를 1~2줄로 확인받는다.

### Phase 1: 준비

1. `docs/driftmq.md`를 읽어 대상 범위를 확정한다. 사용자가 버전을 지정하지 않았으면 로드맵상 다음 미완성 버전을 제안한다 (초기 실행이면 v0.1).
2. `_workspace/` 생성 (또는 새 실행 시 기존 것 이동 후 재생성).
3. `_workspace/00_input/`에 대상 범위·사용자 요구사항·제약을 기록.
4. 기존 소스 트리가 있으면 현재 구현 상태를 파악하여 `_workspace/00_input/current_state.md`에 요약.

### Phase 2: 팀 구성

1. 팀 생성:
   ```
   TeamCreate(
     team_name: "driftmq-team",
     members: [
       { name: "architect", agent_type: "general-purpose", model: "opus",
         prompt: "너는 DriftMQ architect다. .claude/agents/architect.md와 mq-architecture 스킬을 따른다. 대상 범위: {범위}. docs/driftmq.md와 _workspace/00_input/를 읽고 시작하라." },
       { name: "broker-engineer", agent_type: "general-purpose", model: "opus",
         prompt: "너는 DriftMQ broker-engineer다. .claude/agents/broker-engineer.md와 broker-implementation 스킬을 따른다. architect의 _workspace/01_architect_task-breakdown.md가 나오면 담당 태스크를 구현하라." },
       { name: "protocol-client-engineer", agent_type: "general-purpose", model: "opus",
         prompt: "너는 DriftMQ protocol-client-engineer다. .claude/agents/protocol-client-engineer.md와 mq-protocol-client 스킬을 따른다. architect의 프로토콜 스펙이 나오면 코덱/클라이언트/CLI를 구현하라." },
       { name: "qa-verifier", agent_type: "general-purpose", model: "opus",
         prompt: "너는 DriftMQ qa-verifier다. .claude/agents/qa-verifier.md와 mq-qa-verification 스킬을 따른다. 엔지니어가 컴포넌트 완성을 통지하면 즉시 해당 경계면을 검증하라(incremental QA)." },
       { name: "bench-doc-writer", agent_type: "general-purpose", model: "opus",
         prompt: "너는 DriftMQ bench-doc-writer다. .claude/agents/bench-doc-writer.md와 mq-benchmarking 스킬을 따른다. qa-verifier가 정확성을 확인한 뒤 벤치와 문서를 작업하라." }
     ]
   )
   ```
   > 부분 재실행 시에는 관련 에이전트만 members에 포함한다.

2. 작업 등록 (`TaskCreate`) — 의존성으로 파이프라인을 표현하되, 구현·QA는 컴포넌트 단위로 잘게 쪼갠다:
   ```
   TaskCreate(tasks: [
     { title: "스택 ADR + 프로토콜 스펙 + 저장 설계", assignee: "architect" },
     { title: "delivery semantics 정의", assignee: "architect", depends_on: ["스택 ADR + 프로토콜 스펙 + 저장 설계"] },
     { title: "구현 태스크 분해 + 인터페이스 계약", assignee: "architect", depends_on: ["스택 ADR + 프로토콜 스펙 + 저장 설계"] },
     { title: "Message Store (append-only log) 구현", assignee: "broker-engineer", depends_on: ["구현 태스크 분해 + 인터페이스 계약"] },
     { title: "Offset Manager 구현", assignee: "broker-engineer", depends_on: ["구현 태스크 분해 + 인터페이스 계약"] },
     { title: "Server + 요청 디스패치 구현", assignee: "broker-engineer", depends_on: ["구현 태스크 분해 + 인터페이스 계약"] },
     { title: "ACK/Retry Manager 구현", assignee: "broker-engineer", depends_on: ["Server + 요청 디스패치 구현"] },
     { title: "크래시 복구 구현", assignee: "broker-engineer", depends_on: ["Message Store (append-only log) 구현", "Offset Manager 구현"] },
     { title: "공유 wire 코덱 구현", assignee: "protocol-client-engineer", depends_on: ["구현 태스크 분해 + 인터페이스 계약"] },
     { title: "Producer/Consumer 클라이언트 구현", assignee: "protocol-client-engineer", depends_on: ["공유 wire 코덱 구현", "Server + 요청 디스패치 구현"] },
     { title: "driftmq CLI 구현", assignee: "protocol-client-engineer", depends_on: ["공유 wire 코덱 구현"] },
     { title: "코덱 경계면 교차검증", assignee: "qa-verifier", depends_on: ["공유 wire 코덱 구현", "Server + 요청 디스패치 구현"] },
     { title: "순서 보장 + at-least-once 테스트", assignee: "qa-verifier", depends_on: ["Producer/Consumer 클라이언트 구현", "ACK/Retry Manager 구현"] },
     { title: "크래시 복구 테스트", assignee: "qa-verifier", depends_on: ["크래시 복구 구현"] },
     { title: "MVP 인수 시나리오 실행", assignee: "qa-verifier", depends_on: ["순서 보장 + at-least-once 테스트", "크래시 복구 테스트", "driftmq CLI 구현"] },
     { title: "벤치마크 하니스 + 측정", assignee: "bench-doc-writer", depends_on: ["MVP 인수 시나리오 실행"] },
     { title: "README + CLI 레퍼런스 + 프로토콜 문서", assignee: "bench-doc-writer", depends_on: ["MVP 인수 시나리오 실행"] }
   ])
   ```
   > 팀원당 3~6개 태스크. 대상 범위가 v0.1보다 크면 컴포넌트 태스크를 범위에 맞게 조정한다.

### Phase 3: 설계 → 구현 → 검증 (팀 자체 조율)

**팀원 간 통신 규칙:**
- architect가 프로토콜 스펙·인터페이스 계약을 완성하면 두 엔지니어에게 `SendMessage`로 담당 태스크와 계약을 전달한다.
- 두 엔지니어는 스펙 모호성을 architect에게 즉시 질문한다. architect는 결정하고 해당 스펙 문서를 갱신한다.
- broker-engineer는 서버가 노출하는 응답 shape이 확정되면 protocol-client-engineer에게 알린다. 두 엔지니어는 wire 바이트 레이아웃을 서로 대조한다.
- **각 컴포넌트 완성 즉시** 담당 엔지니어가 qa-verifier에게 "검증 가능" 통지 → qa-verifier가 해당 경계면을 즉시 검증(incremental QA).
- qa-verifier의 경계면 이슈는 관련 **양쪽** 에이전트 모두에게 파일:라인 + 수정 방법과 함께 전달된다.
- bench-doc-writer는 qa-verifier가 MVP 시나리오 PASS를 낼 때까지 벤치를 시작하지 않는다. 문서 초안은 그 전에 준비 가능.

**리더 모니터링:**
- 팀원이 유휴가 되면 `TaskGet`으로 전체 진행률 확인, 막힌 팀원에게 `SendMessage`로 개입 또는 재할당.
- qa-verifier의 FAIL이 3회 이상 같은 경계면에서 반복되면, architect에게 해당 스펙을 더 명확히 하도록 지시한다 (하네스 진화 신호로 기록).

### Phase 4: 통합 및 최종 확인

1. 모든 태스크 `done` 확인 (`TaskGet`).
2. `_workspace/03_qa_report.md`의 MVP 시나리오 표에서 모든 단계 PASS 확인. FAIL이 있으면 담당 에이전트에게 재수정 요청 (1회), 재실패 시 리포트에 명시하고 진행.
3. `_workspace/04_bench_results.md`, `_workspace/04_doc_summary.md` 수집.
4. 최종 요약 작성: 대상 범위 대비 완료 항목, QA 통과/실패/미검증, 벤치 핵심 수치, 생성/갱신 문서 목록, 알려진 이슈.

### Phase 5: 정리

1. 팀원들에게 종료 통지 (`SendMessage`), `TeamDelete`.
2. `_workspace/`는 보존 (감사 추적).
3. 사용자에게 최종 요약 보고 + **피드백 요청**: "결과에서 개선할 부분이 있나요? 팀 구성이나 워크플로우를 바꾸고 싶은 점은?"
4. 피드백이 하네스 변경을 함의하면 `harness` 스킬의 Phase 7(진화)로 넘기고, CLAUDE.md 변경 이력에 기록한다.

## 데이터 흐름

```
docs/driftmq.md + _workspace/00_input/
        │
        ▼
   [architect] ──01_architect_*.md──┬──► [broker-engineer] ──소스 + 02_broker_progress.md──┐
        ▲                            │                                                      │
        │ 스펙 질문/갱신              └──► [protocol-client-engineer] ──소스 + 02_client_progress.md──┤
        │                                              │  (wire 레이아웃 상호 대조)          │
        │                                              ▼                                     ▼
        └───────────────────────────────────── [qa-verifier] ◄── incremental QA 통지 ────────┘
                                                       │  03_qa_report.md (경계면 이슈 → 양쪽 에이전트)
                                                       ▼  MVP 시나리오 PASS
                                              [bench-doc-writer]
                                                       │
                                                       ▼
                                          04_bench_results.md + README/docs + 04_doc_summary.md
                                                       │
                                                       ▼
                                              [리더: 최종 요약]
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| architect 스택 결정 불가 (정보 부족) | 사용자에게 질문. 무응답 시 합리적 기본값(Java 21 + 길이-prefix 바이너리, Gradle fat JAR 배포) 제안하고 진행, ADR에 명시 |
| 엔지니어 1명 실패/중지 | 리더가 유휴 알림 수신 → `SendMessage`로 상태 확인 → 1회 재시작. 재실패 시 남은 태스크를 다른 엔지니어에게 재할당하고 리포트에 명시 |
| 같은 경계면 FAIL 3회+ 반복 | architect에게 스펙 명확화 지시. 반복 패턴은 하네스 진화 신호로 CLAUDE.md 이력에 기록 |
| qa-verifier가 브로커 기동 실패 | 빌드/설정 문제 → 담당 엔지니어에게 이관, QA는 다른 경계면 검증 계속 |
| 팀원 간 데이터 충돌 (서버 shape vs 클라이언트 기대) | 삭제하지 않고 양쪽 출처 병기. architect가 스펙 기준으로 판정 |
| 타임아웃 | 현재까지 완료된 컴포넌트로 MVP 시나리오 부분 실행, 미완료 항목 리포트에 명시 |
| MVP 시나리오 일부 FAIL, 1회 재수정도 실패 | 해당 단계 FAIL로 최종 요약에 명시하고 종료 (은폐 금지) |

## 테스트 시나리오

### 정상 흐름
1. 사용자: "DriftMQ v0.1 코어 브로커 구현해줘" (초기 실행).
2. Phase 0: `_workspace/` 없음 → 초기 실행. Phase 1에서 범위 = v0.1 (Broker/Topic/Publish/Consume/Persistence).
3. Phase 2: 5명 팀 + ~17개 태스크 등록.
4. Phase 3: architect가 Java 21 채택 ADR + 길이-prefix 바이너리 프로토콜 스펙 + append-only log 설계 → 두 엔지니어 병렬 구현 → qa-verifier가 코덱 경계면부터 incremental 검증 → 순서/복구 테스트.
5. Phase 4: MVP 9단계 시나리오 전부 PASS 확인. bench-doc-writer가 처리량/지연 측정 + README 작성.
6. Phase 5: `TeamDelete`, `_workspace/` 보존, 요약 + 피드백 요청.
7. 예상 결과: 동작하는 `driftmq` 바이너리, 통과하는 MVP 시나리오, 벤치 결과, README/CLI 레퍼런스/프로토콜 문서.

### 에러 흐름
1. Phase 3에서 qa-verifier가 "서버가 offset을 8바이트 LE로 인코딩, 클라이언트 코덱은 4바이트로 디코딩" 불일치를 코덱 경계면 검증에서 발견.
2. broker-engineer와 protocol-client-engineer **양쪽**에 파일:라인과 함께 리포트.
3. architect가 프로토콜 스펙 확인 → "offset은 8바이트 unsigned LE" 확정, 스펙 문서 갱신.
4. protocol-client-engineer가 코덱 수정, broker-engineer 변경 없음.
5. qa-verifier가 라운드트립 재검증 → PASS.
6. 이 경계면 이슈가 처음이면 정상 처리. 유사 이슈 3회째면 architect에게 "프로토콜 스펙에 모든 정수 필드의 바이트 폭/엔디안 표 추가" 지시하고 CLAUDE.md 이력에 기록.

## 부분 재실행 예시

- "코덱만 다시 만들어줘" → 팀 = {architect(스펙 참조용), protocol-client-engineer, qa-verifier}. `_workspace/01_architect_protocol-spec.md` 기준으로 코덱 재구현 → qa-verifier 코덱 경계면 재검증.
- "크래시 복구가 부분 프레임에서 깨져" → 팀 = {broker-engineer, qa-verifier}. `_workspace/03_qa_report.md`의 해당 FAIL 항목 → broker-engineer 복구 로직 수정 → qa-verifier 크래시 테스트 재실행.
- "벤치 다시 돌려줘" → 팀 = {bench-doc-writer}. 최신 소스로 벤치 재측정, 이전 결과와 비교.
