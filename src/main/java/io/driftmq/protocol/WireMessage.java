package io.driftmq.protocol;

import io.driftmq.common.Header;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * wire 프레임의 타입 세이프 표현. broker·client 가 공유한다.
 * 각 record 의 필드 = 스펙 §4/§5 의 body 레이아웃. byte[]/List 필드는 값 동등성을 구현한다
 * (라운드트립 불변식 {@code decode(encode(m)).equals(m)} 검증용).
 */
public sealed interface WireMessage {

    int type();

    int correlationId();

    // ───────────────────────── 요청 ─────────────────────────

    record Publish(int correlationId, String topic, List<Header> headers, byte[] payload)
            implements WireMessage {
        public Publish {
            Objects.requireNonNull(topic);
            headers = List.copyOf(headers);
            Objects.requireNonNull(payload);
        }
        public int type() { return MessageType.PUBLISH; }
        @Override public boolean equals(Object o) {
            return o instanceof Publish p && correlationId == p.correlationId
                    && topic.equals(p.topic) && headers.equals(p.headers)
                    && Arrays.equals(payload, p.payload);
        }
        @Override public int hashCode() {
            return Objects.hash(correlationId, topic, headers) * 31 + Arrays.hashCode(payload);
        }
        @Override public String toString() {
            return "Publish[cid=" + correlationId + ", topic=" + topic
                    + ", headers=" + headers.size() + ", payload=" + payload.length + "B]";
        }
    }

    record Fetch(int correlationId, String topic, String consumerId, int maxMessages)
            implements WireMessage {
        public int type() { return MessageType.FETCH; }
    }

    record Ack(int correlationId, String topic, String consumerId, long offset)
            implements WireMessage {
        public int type() { return MessageType.ACK; }
    }

    record TopicCreate(int correlationId, String topic) implements WireMessage {
        public int type() { return MessageType.TOPIC_CREATE; }
    }

    record TopicList(int correlationId) implements WireMessage {
        public int type() { return MessageType.TOPIC_LIST; }
    }

    record TopicDescribe(int correlationId, String topic) implements WireMessage {
        public int type() { return MessageType.TOPIC_DESCRIBE; }
    }

    // ───────────────────────── 응답 ─────────────────────────

    record PublishOk(int correlationId, long offset) implements WireMessage {
        public int type() { return MessageType.PUBLISH_OK; }
    }

    /** FETCH_OK 안의 메시지 한 건. */
    record FetchedMessage(long offset, long timestamp, List<Header> headers, byte[] payload) {
        public FetchedMessage {
            headers = List.copyOf(headers);
            Objects.requireNonNull(payload);
        }
        @Override public boolean equals(Object o) {
            return o instanceof FetchedMessage m && offset == m.offset && timestamp == m.timestamp
                    && headers.equals(m.headers) && Arrays.equals(payload, m.payload);
        }
        @Override public int hashCode() {
            return Objects.hash(offset, timestamp, headers) * 31 + Arrays.hashCode(payload);
        }
        @Override public String toString() {
            return "Msg[offset=" + offset + ", ts=" + timestamp
                    + ", headers=" + headers.size() + ", payload=" + payload.length + "B]";
        }
    }

    record FetchOk(int correlationId, List<FetchedMessage> messages) implements WireMessage {
        public FetchOk { messages = List.copyOf(messages); }
        public int type() { return MessageType.FETCH_OK; }
    }

    record AckOk(int correlationId) implements WireMessage {
        public int type() { return MessageType.ACK_OK; }
    }

    record TopicCreateOk(int correlationId) implements WireMessage {
        public int type() { return MessageType.TOPIC_CREATE_OK; }
    }

    record TopicInfo(String topic, long messageCount) {}

    record TopicListOk(int correlationId, List<TopicInfo> topics) implements WireMessage {
        public TopicListOk { topics = List.copyOf(topics); }
        public int type() { return MessageType.TOPIC_LIST_OK; }
    }

    record ConsumerPosition(String consumerId, long position) {}

    record TopicDescribeOk(int correlationId, String topic, long messageCount, long endOffset,
                           List<ConsumerPosition> consumers) implements WireMessage {
        public TopicDescribeOk { consumers = List.copyOf(consumers); }
        public int type() { return MessageType.TOPIC_DESCRIBE_OK; }
    }

    record Error(int correlationId, int errorCode, String message) implements WireMessage {
        public int type() { return MessageType.ERROR; }
        public ErrorCode code() { return ErrorCode.fromCode(errorCode); }
        public static Error of(int correlationId, ErrorCode code, String message) {
            return new Error(correlationId, code.code, message);
        }
    }
}
