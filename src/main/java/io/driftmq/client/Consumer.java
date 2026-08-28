package io.driftmq.client;

import io.driftmq.protocol.WireMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 메시지 소비. FETCH 루프 → 콜백 → 성공 시 ACK → 다음 FETCH.
 *
 * <ul>
 *   <li><b>순서</b>: 받은 메시지를 offset 오름차순으로 순차 콜백한다. 병렬 처리 시 순서가 깨진다.
 *   <li><b>at-least-once</b>: 같은 offset 이 두 번 올 수 있다. 콜백은 멱등해야 한다.
 *   <li>ACK 실패/연결 끊김 시 브로커가 ack-timeout 후 재전달한다.
 * </ul>
 */
public final class Consumer {

    /** 소비 콜백. 예외를 던지면 해당 메시지는 ACK 되지 않고 이후 재전달된다. */
    public interface Handler {
        void handle(ConsumerRecord record) throws Exception;
    }

    private final DriftClient client;
    private final String topic;
    private final String consumerId;

    private int fetchMax = 100;
    private long pollIntervalMillis = 200;
    private volatile boolean running;

    Consumer(DriftClient client, String topic, String consumerId) {
        this.client = client;
        this.topic = topic;
        this.consumerId = consumerId;
    }

    public Consumer fetchMax(int n) { this.fetchMax = n; return this; }

    public Consumer pollInterval(long millis) { this.pollIntervalMillis = millis; return this; }

    public String consumerId() { return consumerId; }

    /** 한 번 FETCH 한다. 소비할 메시지가 없으면 빈 리스트. */
    public List<ConsumerRecord> poll(int max) {
        WireMessage resp = client.callOrThrow(
                new WireMessage.Fetch(client.nextCorrelationId(), topic, consumerId, max));
        if (!(resp instanceof WireMessage.FetchOk ok)) {
            throw new ClientException("unexpected response to FETCH: " + resp);
        }
        List<ConsumerRecord> records = new ArrayList<>(ok.messages().size());
        for (WireMessage.FetchedMessage m : ok.messages()) {
            records.add(new ConsumerRecord(topic, m.offset(), m.timestamp(), m.headers(), m.payload()));
        }
        return records;
    }

    public List<ConsumerRecord> poll() {
        return poll(fetchMax);
    }

    /** offset(포함)까지 처리 완료를 브로커에 알린다. */
    public void ack(long offset) {
        client.callOrThrow(new WireMessage.Ack(client.nextCorrelationId(), topic, consumerId, offset));
    }

    /**
     * {@link #stop()} 이 불릴 때까지 FETCH→콜백→ACK 루프를 돈다.
     * 콜백이 예외를 던지면 그 메시지는 ACK 하지 않고 루프를 계속한다 (재전달 대상).
     *
     * @return 콜백을 성공적으로 통과시킨 메시지 수
     */
    public long run(Handler handler) throws InterruptedException {
        running = true;
        long delivered = 0;
        while (running) {
            List<ConsumerRecord> batch = poll(fetchMax);
            if (batch.isEmpty()) {
                Thread.sleep(pollIntervalMillis);
                continue;
            }
            boolean failed = false;
            for (ConsumerRecord rec : batch) {
                if (!running) return delivered;
                try {
                    handler.handle(rec);
                    ack(rec.offset());
                    delivered++;
                } catch (Exception e) {
                    // ACK 안 함 — ack-timeout 후 재전달됨. 이번 배치 중단, 다음 루프에서 재개.
                    failed = true;
                    break;
                }
            }
            if (failed) Thread.sleep(pollIntervalMillis);
        }
        return delivered;
    }

    public void stop() {
        running = false;
    }
}
