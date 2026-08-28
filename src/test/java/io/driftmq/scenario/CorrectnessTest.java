package io.driftmq.scenario;

import io.driftmq.client.Consumer;
import io.driftmq.client.ConsumerRecord;
import io.driftmq.client.DriftClient;
import io.driftmq.client.Producer;
import io.driftmq.support.EmbeddedBroker;
import io.driftmq.test.Assert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 정확성 스펙: 순서 보장 · at-least-once · 크래시 복구 · 오프셋 단조성. (mq-qa-verification) */
public class CorrectnessTest {

    private EmbeddedBroker mq;

    public void afterEach() {
        if (mq != null) { mq.close(); mq.deleteData(); }
    }

    public void testOrderingUnderLoad() throws Exception {
        mq = EmbeddedBroker.start();
        int n = 5_000;
        try (DriftClient client = mq.connect()) {
            client.createTopic("load");
            Producer p = client.newProducer();
            for (int i = 0; i < n; i++) p.publish("load", Integer.toString(i));

            Consumer c = client.newConsumer("load", "c");
            int seen = 0;
            while (seen < n) {
                List<ConsumerRecord> batch = c.poll(500);
                Assert.assertFalse(batch.isEmpty(), "남은 메시지 (" + seen + "/" + n + ")");
                for (ConsumerRecord r : batch) {
                    Assert.assertEquals(seen, r.offset(), "offset 순서");
                    Assert.assertEquals(Integer.toString(seen), r.payloadAsString(), "payload 순서");
                    c.ack(r.offset());
                    seen++;
                }
            }
        }
    }

    public void testAtLeastOnceConsumerDiesWithoutAck() throws Exception {
        mq = EmbeddedBroker.start();
        Set<Long> delivered = new HashSet<>();
        try (DriftClient client = mq.connect()) {
            client.createTopic("orders");
            Producer p = client.newProducer();
            for (int i = 0; i < 100; i++) p.publish("orders", "m" + i);

            // consumer 가 50개 consume + ACK 후 "죽음" (연결만 끊음)
            Consumer c = client.newConsumer("orders", "grp");
            int acked = 0;
            while (acked < 50) {
                for (ConsumerRecord r : c.poll(100)) {
                    delivered.add(r.offset());
                    if (acked < 50) { c.ack(r.offset()); acked++; }
                }
            }
        }
        // 재연결 → 나머지 재수신, 유실 0. (연결-종료 rewind 는 비동기라 짧게 재시도)
        try (DriftClient client = mq.connect()) {
            Consumer c = client.newConsumer("orders", "grp");
            List<ConsumerRecord> rest = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 5000;
            while (rest.size() < 50 && System.currentTimeMillis() < deadline) {
                List<ConsumerRecord> batch = c.poll(100);
                if (batch.isEmpty()) Thread.sleep(25);
                else rest.addAll(batch);
            }
            Assert.assertEquals(50, rest.size(), "나머지 50개 재수신");
            for (ConsumerRecord r : rest) delivered.add(r.offset());
        }
        for (long o = 0; o < 100; o++) {
            Assert.assertTrue(delivered.contains(o), "offset " + o + " 유실되면 안 됨");
        }
    }

    public void testCrashRecoveryWithPartialWrite() throws Exception {
        mq = EmbeddedBroker.start();
        try (DriftClient client = mq.connect()) {
            client.createTopic("orders");
            Producer p = client.newProducer();
            for (int i = 0; i < 50; i++) p.publish("orders", "m" + i);
        }
        // kill -9 흉내: 정상 종료 없이 로그 tail 에 부분 바이트 주입 후 재시작
        mq.crashWithPartialWrite("orders");

        try (DriftClient client = mq.connect()) {
            Assert.assertEquals(50, client.describeTopic("orders").messageCount(),
                    "커밋된 50개는 전부 존재, 부분 프레임은 truncate");
            Consumer c = client.newConsumer("orders", "c");
            List<ConsumerRecord> all = new ArrayList<>();
            while (all.size() < 50) all.addAll(c.poll(100));
            for (int i = 0; i < 50; i++) {
                Assert.assertEquals(i, all.get(i).offset(), "복구 후 offset " + i);
                Assert.assertEquals("m" + i, all.get(i).payloadAsString(), "복구 후 내용 " + i);
            }
            // 복구 후 append 도 정상
            Assert.assertEquals(50, client.newProducer().publish("orders", "m50").offset(),
                    "복구 후 append 는 offset 50");
        }
    }

    public void testOffsetMonotonicAcrossRestarts() throws Exception {
        mq = EmbeddedBroker.start();
        long lastEnd = 0;
        for (int round = 0; round < 4; round++) {
            try (DriftClient client = mq.connect()) {
                if (round == 0) client.createTopic("m");
                Producer p = client.newProducer();
                for (int i = 0; i < 25; i++) {
                    long off = p.publish("m", "r" + round + "-" + i).offset();
                    Assert.assertEquals(lastEnd, off, "offset 은 역행/스킵 없이 이어짐");
                    lastEnd++;
                }
                long end = client.describeTopic("m").endOffset();
                Assert.assertEquals(lastEnd, end, "endOffset 일관");
            }
            mq.restart();
        }
        try (DriftClient client = mq.connect()) {
            Assert.assertEquals(100, client.describeTopic("m").messageCount(), "총 100개");
            Consumer c = client.newConsumer("m", "c");
            List<ConsumerRecord> all = new ArrayList<>();
            while (all.size() < 100) all.addAll(c.poll(200));
            for (int i = 0; i < 100; i++) Assert.assertEquals(i, all.get(i).offset(), "최종 순서 " + i);
        }
    }

    public void testSlowConsumerBackpressureCap() throws Exception {
        mq = EmbeddedBroker.start();
        try (DriftClient client = mq.connect()) {
            client.createTopic("big");
            Producer p = client.newProducer();
            for (int i = 0; i < 2500; i++) p.publish("big", "x" + i);
            // ACK 를 전혀 안 하는 consumer → in-flight 상한(1000)에서 멈춰야 함
            Consumer c = client.newConsumer("big", "slow");
            int total = 0;
            for (int i = 0; i < 5; i++) total += c.poll(1000).size();
            Assert.assertEquals(1000, total,
                    "미ACK 상태에서 in-flight 상한(" + 1000 + ")을 넘겨 전달하지 않음");
        }
    }
}
