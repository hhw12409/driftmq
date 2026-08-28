package io.driftmq.broker;

import io.driftmq.test.Assert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AckManagerTest {

    private Path dataDir;

    public void beforeEach() throws IOException {
        dataDir = Files.createTempDirectory("driftmq-ack-");
    }

    public void afterEach() throws IOException {
        MessageStoreTest.deleteRecursively(dataDir);
    }

    public void testTimedOutInFlightIsRewoundForRedelivery() {
        OffsetManager om = new OffsetManager(dataDir);
        AckManager ack = new AckManager(om, 30_000, 60_000); // timeout 30s, 스캔은 수동
        ConsumerState st = om.state("orders", "c1");

        // t=0 에 5개 전달, 3개만 ACK
        st.reserveForDelivery(5, 5, 0L);
        st.ack(0); st.ack(1); st.ack(2);
        Assert.assertEquals(2, st.inFlightCount(), "3,4 미ACK");

        // timeout 이전 스캔 → 되감기 없음 (redeliverIfTimedOut 을 직접, now 를 제어)
        Assert.assertFalse(st.redeliverIfTimedOut(10_000L, 30_000L), "timeout 전");
        Assert.assertEquals(2, st.inFlightCount(), "그대로");

        // timeout 이후 → 되감기
        Assert.assertTrue(st.redeliverIfTimedOut(40_001L, 30_000L), "timeout 후 되감김");
        Assert.assertEquals(0, st.inFlightCount(), "inFlight 클리어");
        Assert.assertEquals(3, st.deliveredCursor(), "deliveredCursor = committedPosition(3)");

        List<Long> again = st.reserveForDelivery(5, 10, 50_000L);
        Assert.assertEquals(List.of(3L, 4L), again, "미ACK 3,4 재전달");
    }

    public void testScannerThreadRewindsRealTime() throws Exception {
        OffsetManager om = new OffsetManager(dataDir);
        AckManager ack = new AckManager(om, 150, 20); // 150ms timeout, 20ms 스캔
        ConsumerState st = om.state("orders", "c1");
        st.reserveForDelivery(10, 10, System.currentTimeMillis());
        st.ack(0);
        Assert.assertEquals(9, st.inFlightCount(), "1..9 미ACK");

        ack.start();
        try {
            long deadline = System.currentTimeMillis() + 2000;
            while (st.inFlightCount() != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        } finally {
            ack.stop();
        }
        Assert.assertEquals(0, st.inFlightCount(), "스캐너가 timeout 된 in-flight 를 되감음");
        Assert.assertEquals(1, st.deliveredCursor(), "committedPosition(1) 으로 되감김");
        Assert.assertTrue(ack.redeliveryRewinds() >= 1, "되감기 카운터 증가");
    }

    public void testNoRewindWhenEverythingAcked() {
        OffsetManager om = new OffsetManager(dataDir);
        ConsumerState st = om.state("orders", "c1");
        st.reserveForDelivery(5, 5, 0L);
        for (long i = 0; i < 5; i++) st.ack(i);
        Assert.assertFalse(st.redeliverIfTimedOut(Long.MAX_VALUE / 2, 1L), "in-flight 없으면 되감기 없음");
    }
}
