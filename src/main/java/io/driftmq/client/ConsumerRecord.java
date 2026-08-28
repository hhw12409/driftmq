package io.driftmq.client;

import io.driftmq.common.Header;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * consume 한 메시지 한 건. {@code offset} 은 topic 내 유일·불변 —
 * at-least-once 하에서 <b>중복 감지 키</b>로 사용하라 (같은 메시지가 두 번 올 수 있음).
 */
public record ConsumerRecord(String topic, long offset, long timestamp,
                             List<Header> headers, byte[] payload) {
    public ConsumerRecord {
        headers = List.copyOf(headers);
    }

    public String payloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
