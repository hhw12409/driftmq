# DriftMQ

경량 메시지 브로커. 비전·로드맵·MVP 기준은 `docs/driftmq.md` 참조.

## 하네스: DriftMQ 브로커 개발

**목표:** DriftMQ의 설계·구현·통합검증·문서화를 에이전트 팀으로 일관되게 수행하고, "Simple first, distributed later" 원칙을 로드맵 전 단계에 유지한다.

**트리거:** DriftMQ 관련 개발 작업 요청 시 `driftmq-orchestrator` 스킬을 사용하라. 브로커/토픽/publish/consume/ACK/재전송/오프셋/파티션/컨슈머그룹/저장/복구/프로토콜/CLI 구현, 로드맵 버전 개발, 아키텍처 설계, 벤치마크, 문서, 그리고 후속 수정·부분 재실행·버그 수정·QA 재검증·벤치 재측정이 모두 포함된다. 단순 개념 질문은 직접 응답 가능.

**팀:** architect · broker-engineer · protocol-client-engineer · qa-verifier · bench-doc-writer (단일 팀, 세션 내 상시 활성, 파이프라인 + incremental QA). 상세는 `.claude/agents/`, `.claude/skills/`.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-08-28 | 초기 구성 (에이전트 5, 스킬 6) | 전체 | - |
| 2026-08-28 | 기본 스택 추천 Go → Java 21 | architect, mq-architecture, broker-implementation, driftmq-orchestrator, mq-qa-verification | 팀이 사용 가능한 언어가 Java뿐 |
