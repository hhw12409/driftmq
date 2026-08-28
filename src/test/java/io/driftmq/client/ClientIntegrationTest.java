package io.driftmq.client;

import io.driftmq.common.Header;
import io.driftmq.protocol.ErrorCode;
import io.driftmq.support.EmbeddedBroker;
import io.driftmq.test.Assert;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 실제 브로커(인프로세스 Server)를 상대로 코덱·클라이언트 경계면을 검증한다. */
public class ClientIntegrationTest {

    private EmbeddedBroker mq;

    public void beforeEach() throws Exception {
        mq = EmbeddedBroker.start();
    }

    public void afterEach() {
        if (mq != null) { mq.close(); mq.deleteData(); }
    }

    public void testPublishFetchAckRoundtrip() throws Exception {
        try (DriftClient client = mq.connect()) {
            client.createTopic("orders");
            Producer p = client.newProducer();
            for (int i = 0; i < 20; i++) {
                PublishResult r = p.publish("orders", "msg-" + i);
                Assert.assertEquals(i, r.offset(), "publish offset " + i);
            }
            Consumer c = client.newConsumer("orders", "consumer-A");
            List<ConsumerRecord> batch = c.poll(100);
            Assert.assertEquals(20, batch.size(), "전량 fetch");
            for (int i = 0; i < 20; i++) {
                Assert.assertEquals(i, batch.get(i).offset(), "fetch 순서 " + i);
                Assert.assertEquals("msg-" + i, batch.get(i).payloadAsString(), "내용 " + i);
                c.ack(batch.get(i).offset());
            }
            List<ConsumerRecord> after = c.poll(100);
            Assert.assertEquals(0, after.size(), "전부 ACK 후 더 없음");
        }
    }

    public void testHeadersSurviveWire() throws Exception {
        List<Header> headers = List.of(
                new Header("content-type", "application/json".getBytes(StandardCharsets.UTF_8)),
                new Header("bin", new byte[]{0, -1, 42}));
        try (DriftClient client = mq.connect()) {
            client.createTopic("t");
            client.newProducer().publish("t", "body".getBytes(StandardCharsets.UTF_8), headers);
            ConsumerRecord rec = client.newConsumer("t", "c").poll(1).get(0);
            Assert.assertEquals(headers, rec.headers(), "헤더 wire 라운드트립");
        }
    }

    public void testUnknownTopicError() throws Exception {
        try (DriftClient client = mq.connect()) {
            ClientException e = Assert.assertThrows(ClientException.class,
                    () -> client.newProducer().publish("ghost", "x"), "없는 topic");
            Assert.assertEquals(ErrorCode.UNKNOWN_TOPIC, e.errorCode(), "에러 코드");
        }
    }

    public void testDuplicateTopicError() throws Exception {
        try (DriftClient client = mq.connect()) {
            client.createTopic("dup");
            ClientException e = Assert.assertThrows(ClientException.class,
                    () -> client.createTopic("dup"), "중복 topic");
            Assert.assertEquals(ErrorCode.TOPIC_ALREADY_EXISTS, e.errorCode(), "에러 코드");
        }
    }

    public void testReconnectResumesFromUnacked() throws Exception {
        try (DriftClient c1 = mq.connect()) {
            c1.createTopic("orders");
            Producer p = c1.newProducer();
            for (int i = 0; i < 10; i++) p.publish("orders", "m" + i);
            Consumer con = c1.newConsumer("orders", "worker");
            List<ConsumerRecord> first = con.poll(5);
            Assert.assertEquals(5, first.size(), "첫 5개");
            con.ack(first.get(0).offset());
            con.ack(first.get(1).offset()); // 0,1 만 ACK — 2..9 는 미ACK 상태로 연결 끊음
        }
        try (DriftClient c2 = mq.connect()) {
            Consumer con = c2.newConsumer("orders", "worker");
            // 서버 측 연결-종료 rewind 는 비동기다. race 로 rewind 이전 배치(5..9)를 먼저 볼 수 있으므로
            // (at-least-once — 중복 수신은 허용) 정확한 개수가 아니라 커버리지를 검증한다:
            // 미ACK 2..9 는 전부 재전달되고, ACK 한 0·1 은 다시 오지 않는다.
            java.util.Set<Long> seen = new java.util.HashSet<>();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord r : con.poll(100)) seen.add(r.offset());
                if (java.util.stream.LongStream.rangeClosed(2, 9).allMatch(seen::contains)) break;
                Thread.sleep(20);
            }
            for (long o = 2; o <= 9; o++) {
                Assert.assertTrue(seen.contains(o), "재연결 후 미ACK offset " + o + " 재전달");
            }
            Assert.assertFalse(seen.contains(0L), "ACK 한 0 은 다시 오지 않음");
            Assert.assertFalse(seen.contains(1L), "ACK 한 1 은 다시 오지 않음");
        }
    }

    public void testTwoConsumersIndependentPositions() throws Exception {
        try (DriftClient client = mq.connect()) {
            client.createTopic("orders");
            Producer p = client.newProducer();
            for (int i = 0; i < 6; i++) p.publish("orders", "m" + i);
            Consumer a = client.newConsumer("orders", "A");
            Consumer b = client.newConsumer("orders", "B");
            for (ConsumerRecord r : a.poll(6)) a.ack(r.offset());
            List<ConsumerRecord> bAll = b.poll(6);
            Assert.assertEquals(6, bAll.size(), "B 는 독립적으로 전량 수신");
            Assert.assertEquals(0, bAll.get(0).offset(), "B 는 0 부터");
        }
    }

    public void testDescribeReflectsState() throws Exception {
        try (DriftClient client = mq.connect()) {
            client.createTopic("orders");
            Producer p = client.newProducer();
            for (int i = 0; i < 100; i++) p.publish("orders", "m" + i);
            Consumer c = client.newConsumer("orders", "consumer-A");
            for (ConsumerRecord r : c.poll(40)) c.ack(r.offset());

            var d = client.describeTopic("orders");
            Assert.assertEquals(100, d.messageCount(), "메시지 수");
            Assert.assertEquals(100, d.endOffset(), "end offset");
            Assert.assertEquals(1, d.consumers().size(), "consumer 1");
            Assert.assertEquals(40, d.consumers().get(0).position(), "consumer-A position");
        }
    }
}
