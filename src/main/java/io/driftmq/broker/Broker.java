package io.driftmq.broker;

import io.driftmq.protocol.ErrorCode;
import io.driftmq.protocol.MessageType;
import io.driftmq.protocol.Protocol;
import io.driftmq.protocol.WireMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 브로커 코어 — TopicManager / OffsetManager / AckManager 를 조립하고, wire 요청을 처리한다.
 * 네트워크(Server)와 분리되어 있어 테스트에서 소켓 없이 {@link #handle(WireMessage)} 로 직접 호출 가능.
 */
public final class Broker implements AutoCloseable {

    private final BrokerConfig config;
    private final OffsetManager offsetManager;
    private final TopicManager topicManager;
    private final AckManager ackManager;

    public Broker(BrokerConfig config) throws StorageException {
        this.config = config;
        this.offsetManager = new OffsetManager(config.dataDir());
        this.topicManager = new TopicManager(config.dataDir(), offsetManager);
        this.topicManager.init();
        this.ackManager = new AckManager(offsetManager, config.ackTimeoutMillis());
    }

    public void start() {
        ackManager.start();
    }

    public BrokerConfig config() { return config; }
    public TopicManager topics() { return topicManager; }
    public OffsetManager offsets() { return offsetManager; }
    public AckManager ackManager() { return ackManager; }

    /** wire 요청 하나를 처리하고 응답 wire 메시지를 만든다. 절대 예외를 던지지 않는다. */
    public WireMessage handle(WireMessage req) {
        int cid = req.correlationId();
        try {
            return switch (req) {
                case WireMessage.Publish r -> handlePublish(r);
                case WireMessage.Fetch r -> handleFetch(r);
                case WireMessage.Ack r -> handleAck(r);
                case WireMessage.TopicCreate r -> handleTopicCreate(r);
                case WireMessage.TopicList r -> handleTopicList(r);
                case WireMessage.TopicDescribe r -> handleTopicDescribe(r);
                default -> WireMessage.Error.of(cid, ErrorCode.UNKNOWN_REQUEST_TYPE,
                        "not a request type: " + MessageType.name(req.type()));
            };
        } catch (StorageException | java.io.UncheckedIOException e) {
            return WireMessage.Error.of(cid, ErrorCode.STORAGE_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            return WireMessage.Error.of(cid, ErrorCode.INTERNAL,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private WireMessage handlePublish(WireMessage.Publish r) throws StorageException {
        TopicManager.TopicHandle h = topicManager.get(r.topic());
        if (h == null) {
            return WireMessage.Error.of(r.correlationId(), ErrorCode.UNKNOWN_TOPIC,
                    "unknown topic: " + r.topic());
        }
        long offset = h.store().append(r.headers(), r.payload());
        return new WireMessage.PublishOk(r.correlationId(), offset);
    }

    private WireMessage handleFetch(WireMessage.Fetch r) throws StorageException {
        TopicManager.TopicHandle h = topicManager.get(r.topic());
        if (h == null) {
            return WireMessage.Error.of(r.correlationId(), ErrorCode.UNKNOWN_TOPIC,
                    "unknown topic: " + r.topic());
        }
        int max = r.maxMessages() <= 0 ? Protocol.DEFAULT_FETCH_MAX
                : Math.min(r.maxMessages(), ConsumerState.IN_FLIGHT_CAP);
        ConsumerState state = offsetManager.state(r.topic(), r.consumerId());
        long endOffset = h.store().endOffset();
        List<Long> offs = state.reserveForDelivery(endOffset, max, System.currentTimeMillis());
        if (offs.isEmpty()) {
            return new WireMessage.FetchOk(r.correlationId(), List.of());
        }
        long first = offs.get(0);
        long last = offs.get(offs.size() - 1);
        List<StoredMessage> range = h.store().read(first, (int) (last - first + 1));
        Map<Long, StoredMessage> byOffset = new HashMap<>();
        for (StoredMessage m : range) byOffset.put(m.offset(), m);

        List<WireMessage.FetchedMessage> out = new ArrayList<>(offs.size());
        for (long off : offs) {
            StoredMessage m = byOffset.get(off);
            if (m == null) continue; // 이론상 없음 (예약 범위는 endOffset 이하)
            out.add(new WireMessage.FetchedMessage(m.offset(), m.timestamp(), m.headers(), m.payload()));
        }
        return new WireMessage.FetchOk(r.correlationId(), out);
    }

    /**
     * consumer 연결이 끊겼을 때 ConnectionHandler 가 호출한다. 해당 consumer 의 미ACK 메시지를
     * 즉시 재전달 대상으로 되돌린다 (재연결 시 ack-timeout 을 기다리지 않고 이어받도록).
     */
    void onConsumerDisconnect(String topic, String consumerId) {
        if (topicManager.exists(topic)) {
            offsetManager.state(topic, consumerId).rewindForRedelivery();
        }
    }

    private WireMessage handleAck(WireMessage.Ack r) {
        TopicManager.TopicHandle h = topicManager.get(r.topic());
        if (h == null) {
            return WireMessage.Error.of(r.correlationId(), ErrorCode.UNKNOWN_TOPIC,
                    "unknown topic: " + r.topic());
        }
        offsetManager.state(r.topic(), r.consumerId()).ack(r.offset());
        return new WireMessage.AckOk(r.correlationId());
    }

    private WireMessage handleTopicCreate(WireMessage.TopicCreate r) throws StorageException {
        try {
            topicManager.create(r.topic());
            return new WireMessage.TopicCreateOk(r.correlationId());
        } catch (TopicExistsException e) {
            return WireMessage.Error.of(r.correlationId(), ErrorCode.TOPIC_ALREADY_EXISTS, e.getMessage());
        } catch (IllegalArgumentException e) {
            return WireMessage.Error.of(r.correlationId(), ErrorCode.MALFORMED_FRAME, e.getMessage());
        }
    }

    private WireMessage handleTopicList(WireMessage.TopicList r) {
        List<WireMessage.TopicInfo> infos = new ArrayList<>();
        for (TopicManager.TopicHandle h : topicManager.list()) {
            infos.add(new WireMessage.TopicInfo(h.name(), h.store().endOffset()));
        }
        return new WireMessage.TopicListOk(r.correlationId(), infos);
    }

    private WireMessage handleTopicDescribe(WireMessage.TopicDescribe r) {
        TopicManager.TopicHandle h = topicManager.get(r.topic());
        if (h == null) {
            return WireMessage.Error.of(r.correlationId(), ErrorCode.UNKNOWN_TOPIC,
                    "unknown topic: " + r.topic());
        }
        long end = h.store().endOffset();
        List<WireMessage.ConsumerPosition> consumers = new ArrayList<>();
        for (ConsumerState st : offsetManager.statesOf(r.topic())) {
            consumers.add(new WireMessage.ConsumerPosition(st.consumerId(), st.committedPosition()));
        }
        consumers.sort((a, b) -> a.consumerId().compareTo(b.consumerId()));
        return new WireMessage.TopicDescribeOk(r.correlationId(), r.topic(), end, end, consumers);
    }

    @Override
    public void close() {
        ackManager.stop();
        topicManager.close();
    }
}
