package io.driftmq.scenario;

import io.driftmq.client.Consumer;
import io.driftmq.client.ConsumerRecord;
import io.driftmq.client.DriftClient;
import io.driftmq.client.Producer;
import io.driftmq.support.EmbeddedBroker;
import io.driftmq.test.Assert;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MVP 인수 시나리오 9단계 (실행 → 토픽 생성 → 100 publish → 디스크 저장 → 순서 consume →
 * ACK → 재시작 → 상태 복구 → 미ACK 재전달).
 * 재시작은 프로세스 재시작과 동일하게 디스크 상태만 유지하고 broker/server 를 새로 연다.
 */
public class MvpScenarioTest {

    private EmbeddedBroker mq;

    public void afterEach() {
        if (mq != null) { mq.close(); mq.deleteData(); }
    }

    public void testMvpAcceptanceScenario() throws Exception {
        // ── 1. DriftMQ 실행
        mq = EmbeddedBroker.start();
        Path log = mq.dataDir().resolve("orders").resolve("00000000000000000000.log");

        try (DriftClient client = mq.connect()) {
            // ── 2. Topic 생성
            client.createTopic("orders");

            // ── 3. Producer 가 100개 Publish
            Producer producer = client.newProducer();
            for (int i = 0; i < 100; i++) {
                long offset = producer.publish("orders", ("order-" + i)).offset();
                Assert.assertEquals(i, offset, "publish offset " + i);
            }

            // ── 4. 메시지가 디스크에 저장
            Assert.assertTrue(Files.exists(log), "로그 파일 존재");
            Assert.assertEquals(100, countRecords(log), "로그 파일에 100개 레코드");
            Assert.assertEquals(100, client.describeTopic("orders").messageCount(), "describe 메시지 수");

            // ── 5. Consumer 가 메시지를 순서대로 Consume
            Consumer consumer = client.newConsumer("orders", "consumer-A");
            List<ConsumerRecord> received = new ArrayList<>();
            while (received.size() < 100) {
                List<ConsumerRecord> batch = consumer.poll(100);
                Assert.assertFalse(batch.isEmpty(), "메시지가 남아있어야 함 (" + received.size() + "/100)");
                received.addAll(batch);
            }
            for (int i = 0; i < 100; i++) {
                Assert.assertEquals(i, received.get(i).offset(), "consume 순서 " + i);
                Assert.assertEquals("order-" + i, received.get(i).payloadAsString(), "내용 " + i);
            }

            // ── 6. Consumer 가 ACK
            for (ConsumerRecord r : received) consumer.ack(r.offset());
            Assert.assertEquals(0, mq.broker().offsets().state("orders", "consumer-A").inFlightCount(),
                    "100개 ACK 후 in-flight 0");
            Assert.assertEquals(100, mq.broker().offsets().state("orders", "consumer-A").committedPosition(),
                    "committedPosition = 100");
        }

        // ── 7. Broker 재시작
        mq.restart();

        try (DriftClient client = mq.connect()) {
            // ── 8. 기존 메시지와 상태 복구
            Assert.assertEquals(100, client.describeTopic("orders").messageCount(),
                    "재시작 후 메시지 100개 존재");
            Assert.assertEquals(100, countRecords(log), "재시작 후 로그 레코드 100개");
            Consumer consumer = client.newConsumer("orders", "consumer-A");
            Assert.assertEquals(0, consumer.poll(100).size(),
                    "consumer position 복원 — 이미 ACK 한 메시지는 다시 안 옴");
            Assert.assertEquals(100, mq.broker().offsets().state("orders", "consumer-A").committedPosition(),
                    "재시작 후 committedPosition 복원");
        }
    }

    public void testStep9UnackedRedeliveryAcrossRestart() throws Exception {
        mq = EmbeddedBroker.start();
        Set<Long> everDelivered = new HashSet<>();

        try (DriftClient client = mq.connect()) {
            client.createTopic("orders");
            Producer producer = client.newProducer();
            for (int i = 0; i < 100; i++) producer.publish("orders", "m" + i);

            Consumer worker = client.newConsumer("orders", "worker");
            // 앞 50개만 처리 + ACK, 나머지는 미ACK 상태
            int acked = 0;
            while (acked < 50) {
                for (ConsumerRecord r : worker.poll(100)) {
                    everDelivered.add(r.offset());
                    if (acked < 50) { worker.ack(r.offset()); acked++; }
                }
            }
            // 51..? 를 받되 ACK 하지 않음 (미ACK 상태 유지)
            for (ConsumerRecord r : worker.poll(20)) everDelivered.add(r.offset());
        }

        // ── 9. Broker 재시작 → ACK 되지 않은 메시지 재전달
        mq.restart();

        try (DriftClient client = mq.connect()) {
            Consumer worker = client.newConsumer("orders", "worker");
            List<ConsumerRecord> redelivered = new ArrayList<>();
            while (redelivered.size() < 50) {
                List<ConsumerRecord> batch = worker.poll(100);
                Assert.assertFalse(batch.isEmpty(), "미ACK 50개가 재전달되어야 함 (" + redelivered.size() + ")");
                redelivered.addAll(batch);
            }
            for (ConsumerRecord r : redelivered) everDelivered.add(r.offset());

            Assert.assertEquals(50, redelivered.get(0).offset(), "재시작 후 committedPosition(50) 부터");
            for (int i = 0; i < 50; i++) {
                Assert.assertEquals(50 + i, redelivered.get(i).offset(), "재전달 순서 " + i);
            }
            // 유실 0: 100개 전부 한 번 이상 전달됨
            for (long o = 0; o < 100; o++) {
                Assert.assertTrue(everDelivered.contains(o), "offset " + o + " 은 전달된 적 있어야 함 (유실 0)");
            }
        }
    }

    /** 로그 파일의 recordLen 필드를 따라가며 유효 레코드 수를 센다. */
    static int countRecords(Path log) throws IOException {
        byte[] all = Files.readAllBytes(log);
        ByteBuffer b = ByteBuffer.wrap(all);
        int count = 0;
        while (b.remaining() >= 4) {
            int recordLen = b.getInt();
            if (recordLen <= 0 || b.remaining() < recordLen) break;
            b.position(b.position() + recordLen);
            count++;
        }
        return count;
    }
}
