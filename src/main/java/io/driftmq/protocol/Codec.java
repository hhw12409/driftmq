package io.driftmq.protocol;

import io.driftmq.common.Header;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * wire 프레임의 유일한 encode/decode 구현. broker 와 client 가 <b>이 클래스만</b> 사용한다
 * (인코딩/디코딩 로직이 두 곳에 있으면 반드시 드리프트한다).
 *
 * <p>모든 정수는 big-endian ({@link DataOutputStream} 기본), 문자열은 UTF-8, 길이 prefix 포함.
 * 모든 정수 필드의 바이트 폭/엔디안을 명시적으로 고정한다 (경계면 버그 예방).
 *
 * <p>부분 데이터: {@link #decodeFrame(ByteBuffer)} 는 완전한 프레임이 아직 없으면 예외가 아니라
 * {@code null} 을 반환하고 buffer position 을 원복한다. 호출측이 더 읽어서 재시도한다.
 */
public final class Codec {
    private Codec() {}

    private static final int U16_MAX = 0xFFFF;

    // ─────────────────────────────── encode ───────────────────────────────

    /** WireMessage → 완성된 프레임 바이트 (frameLen u32 prefix 포함). */
    public static byte[] encodeFrame(WireMessage msg) {
        byte[] body = encodeBody(msg);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + Protocol.LENGTH_PREFIX_BYTES);
        DataOutputStream out = new DataOutputStream(frame);
        try {
            out.writeInt(body.length);
            out.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream 은 던지지 않음
        }
        return frame.toByteArray();
    }

    private static byte[] encodeBody(WireMessage msg) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        DataOutputStream o = new DataOutputStream(buf);
        try {
            o.writeByte(Protocol.VERSION);
            o.writeByte(msg.type());
            o.writeInt(msg.correlationId());
            switch (msg) {
                case WireMessage.Publish m -> {
                    writeString(o, m.topic());
                    writeHeaders(o, m.headers());
                    writeBytes(o, m.payload());
                }
                case WireMessage.Fetch m -> {
                    writeString(o, m.topic());
                    writeString(o, m.consumerId());
                    o.writeInt(m.maxMessages());
                }
                case WireMessage.Ack m -> {
                    writeString(o, m.topic());
                    writeString(o, m.consumerId());
                    o.writeLong(m.offset());
                }
                case WireMessage.TopicCreate m -> writeString(o, m.topic());
                case WireMessage.TopicList ignored -> { /* body 없음 */ }
                case WireMessage.TopicDescribe m -> writeString(o, m.topic());
                case WireMessage.PublishOk m -> o.writeLong(m.offset());
                case WireMessage.FetchOk m -> {
                    o.writeInt(m.messages().size());
                    for (WireMessage.FetchedMessage fm : m.messages()) {
                        o.writeLong(fm.offset());
                        o.writeLong(fm.timestamp());
                        writeHeaders(o, fm.headers());
                        writeBytes(o, fm.payload());
                    }
                }
                case WireMessage.AckOk ignored -> { }
                case WireMessage.TopicCreateOk ignored -> { }
                case WireMessage.TopicListOk m -> {
                    o.writeInt(m.topics().size());
                    for (WireMessage.TopicInfo t : m.topics()) {
                        writeString(o, t.topic());
                        o.writeLong(t.messageCount());
                    }
                }
                case WireMessage.TopicDescribeOk m -> {
                    writeString(o, m.topic());
                    o.writeLong(m.messageCount());
                    o.writeLong(m.endOffset());
                    o.writeInt(m.consumers().size());
                    for (WireMessage.ConsumerPosition c : m.consumers()) {
                        writeString(o, c.consumerId());
                        o.writeLong(c.position());
                    }
                }
                case WireMessage.Error m -> {
                    o.writeShort(m.errorCode());
                    writeString(o, m.message());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    /** 헤더 목록을 독립 바이트 배열로 인코딩. 저장 레이어(MessageStore)가 재사용 — 단일 소스. */
    public static byte[] encodeHeaders(List<Header> headers) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(32);
        try {
            writeHeaders(new DataOutputStream(b), headers);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.toByteArray();
    }

    /** 헤더 목록 인코딩. 저장 레이어(MessageStore)도 이 메서드를 재사용한다 — 단일 소스. */
    public static void writeHeaders(DataOutputStream o, List<Header> headers) throws IOException {
        if (headers.size() > U16_MAX) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "too many headers: " + headers.size());
        }
        o.writeShort(headers.size());
        for (Header h : headers) {
            writeString(o, h.key());
            writeBytes(o, h.value());
        }
    }

    private static void writeString(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > U16_MAX) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "string too long: " + b.length + " bytes");
        }
        o.writeShort(b.length);
        o.write(b);
    }

    private static void writeBytes(DataOutputStream o, byte[] b) throws IOException {
        o.writeInt(b.length);
        o.write(b);
    }

    // ─────────────────────────────── decode ───────────────────────────────

    /**
     * 버퍼 앞에서 프레임 하나를 파싱한다. 성공 시 buffer position 은 프레임 끝으로 전진한다.
     * 완전한 프레임이 아직 없으면 {@code null} 을 반환하고 position 을 원복한다.
     *
     * @throws ProtocolException 구조적으로 깨진 프레임, 버전 불일치, 알 수 없는 type, 크기 초과
     */
    public static WireMessage decodeFrame(ByteBuffer buf) {
        buf.order(ByteOrder.BIG_ENDIAN);
        int start = buf.position();
        if (buf.remaining() < Protocol.LENGTH_PREFIX_BYTES) {
            return null;
        }
        long frameLen = buf.getInt() & 0xFFFF_FFFFL;
        if (frameLen > Protocol.MAX_FRAME_BYTES) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME,
                    "frame too large: " + frameLen + " > " + Protocol.MAX_FRAME_BYTES);
        }
        if (buf.remaining() < frameLen) {
            buf.position(start); // 부분 프레임 — 더 읽어야 함
            return null;
        }
        // body 를 독립 슬라이스로 잘라 파싱 (원본 position 은 프레임 끝으로 전진)
        int bodyLen = (int) frameLen;
        ByteBuffer body = buf.slice(buf.position(), bodyLen).order(ByteOrder.BIG_ENDIAN);
        buf.position(buf.position() + bodyLen);
        return decodeBody(body);
    }

    /**
     * 길이 prefix 없이 프레임 body 바이트만 파싱한다 (서버/클라이언트가 프레이밍을 직접 처리할 때).
     * @throws ProtocolException 구조적으로 깨진 body, 버전 불일치, 알 수 없는 type
     */
    public static WireMessage decodeBody(byte[] body) {
        return decodeBody(ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN));
    }

    /** body 앞 6바이트에서 correlationId 만 뽑아본다 (파싱 실패 시 에러 응답용). 실패하면 0. */
    public static int peekCorrelationId(byte[] body) {
        if (body.length < Protocol.COMMON_HEADER_BYTES) return 0;
        return ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).getInt(2);
    }

    private static WireMessage decodeBody(ByteBuffer b) {
        if (b.remaining() < Protocol.COMMON_HEADER_BYTES) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "frame body shorter than common header");
        }
        int version = b.get() & 0xFF;
        int type = b.get() & 0xFF;
        int correlationId = b.getInt();
        if (version != Protocol.VERSION) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, "unsupported protocol version: " + version);
        }
        try {
            return switch (type) {
                case MessageType.PUBLISH -> new WireMessage.Publish(
                        correlationId, readString(b), readHeaders(b), readBytes(b));
                case MessageType.FETCH -> new WireMessage.Fetch(
                        correlationId, readString(b), readString(b), b.getInt());
                case MessageType.ACK -> new WireMessage.Ack(
                        correlationId, readString(b), readString(b), b.getLong());
                case MessageType.TOPIC_CREATE -> new WireMessage.TopicCreate(correlationId, readString(b));
                case MessageType.TOPIC_LIST -> new WireMessage.TopicList(correlationId);
                case MessageType.TOPIC_DESCRIBE -> new WireMessage.TopicDescribe(correlationId, readString(b));
                case MessageType.PUBLISH_OK -> new WireMessage.PublishOk(correlationId, b.getLong());
                case MessageType.FETCH_OK -> decodeFetchOk(correlationId, b);
                case MessageType.ACK_OK -> new WireMessage.AckOk(correlationId);
                case MessageType.TOPIC_CREATE_OK -> new WireMessage.TopicCreateOk(correlationId);
                case MessageType.TOPIC_LIST_OK -> decodeTopicListOk(correlationId, b);
                case MessageType.TOPIC_DESCRIBE_OK -> decodeTopicDescribeOk(correlationId, b);
                case MessageType.ERROR -> new WireMessage.Error(
                        correlationId, b.getShort() & 0xFFFF, readString(b));
                default -> throw new ProtocolException(ErrorCode.UNKNOWN_REQUEST_TYPE,
                        "unknown message type: " + type);
            };
        } catch (java.nio.BufferUnderflowException e) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME,
                    "truncated body for " + MessageType.name(type));
        }
    }

    private static WireMessage decodeFetchOk(int cid, ByteBuffer b) {
        int count = b.getInt();
        requireCount(count, b, "FETCH_OK messageCount");
        List<WireMessage.FetchedMessage> msgs = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            long offset = b.getLong();
            long ts = b.getLong();
            List<Header> headers = readHeaders(b);
            byte[] payload = readBytes(b);
            msgs.add(new WireMessage.FetchedMessage(offset, ts, headers, payload));
        }
        return new WireMessage.FetchOk(cid, msgs);
    }

    private static WireMessage decodeTopicListOk(int cid, ByteBuffer b) {
        int count = b.getInt();
        requireCount(count, b, "TOPIC_LIST_OK count");
        List<WireMessage.TopicInfo> topics = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            topics.add(new WireMessage.TopicInfo(readString(b), b.getLong()));
        }
        return new WireMessage.TopicListOk(cid, topics);
    }

    private static WireMessage decodeTopicDescribeOk(int cid, ByteBuffer b) {
        String topic = readString(b);
        long messageCount = b.getLong();
        long endOffset = b.getLong();
        int count = b.getInt();
        requireCount(count, b, "TOPIC_DESCRIBE_OK consumerCount");
        List<WireMessage.ConsumerPosition> consumers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            consumers.add(new WireMessage.ConsumerPosition(readString(b), b.getLong()));
        }
        return new WireMessage.TopicDescribeOk(cid, topic, messageCount, endOffset, consumers);
    }

    /** 헤더 목록 디코딩. MessageStore 도 재사용. */
    public static List<Header> readHeaders(ByteBuffer b) {
        int count = b.getShort() & 0xFFFF;
        requireCount(count, b, "headerCount");
        List<Header> headers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            headers.add(new Header(readString(b), readBytes(b)));
        }
        return headers;
    }

    private static String readString(ByteBuffer b) {
        int len = b.getShort() & 0xFFFF;
        if (b.remaining() < len) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME,
                    "string length " + len + " exceeds remaining " + b.remaining());
        }
        byte[] s = new byte[len];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuffer b) {
        int len = b.getInt();
        if (len < 0 || b.remaining() < len) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME,
                    "byte field length " + len + " invalid (remaining " + b.remaining() + ")");
        }
        byte[] v = new byte[len];
        b.get(v);
        return v;
    }

    /** count 필드가 남은 바이트보다 터무니없이 크면 손상으로 간주 (각 항목 최소 1바이트). */
    private static void requireCount(int count, ByteBuffer b, String field) {
        if (count < 0 || count > b.remaining() + 1) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME,
                    field + " = " + count + " but only " + b.remaining() + " bytes remain");
        }
    }
}
