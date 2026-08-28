package io.driftmq.client;

/** publish 성공 결과. offset 은 topic 내 유일·단조 — 메시지 id 로도 쓴다. */
public record PublishResult(long offset) {}
