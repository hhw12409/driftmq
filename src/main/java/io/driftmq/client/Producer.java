package io.driftmq.client;

import io.driftmq.common.Header;
import io.driftmq.protocol.WireMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 메시지 발행. {@code publish} 는 브로커가 <b>fsync 후</b> 보낸 응답을 받은 뒤에만 성공으로 간주한다
 * (응답 전에는 저장 여부 불명 — at-least-once 이므로 재전송 시 중복 가능).
 */
public final class Producer {

    private final DriftClient client;

    Producer(DriftClient client) {
        this.client = client;
    }

    public PublishResult publish(String topic, byte[] payload, List<Header> headers) {
        WireMessage resp = client.callOrThrow(
                new WireMessage.Publish(client.nextCorrelationId(), topic, headers, payload));
        if (resp instanceof WireMessage.PublishOk ok) {
            return new PublishResult(ok.offset());
        }
        throw new ClientException("unexpected response to PUBLISH: " + resp);
    }

    public PublishResult publish(String topic, byte[] payload) {
        return publish(topic, payload, List.of());
    }

    public PublishResult publish(String topic, String payload) {
        return publish(topic, payload.getBytes(StandardCharsets.UTF_8), List.of());
    }
}
