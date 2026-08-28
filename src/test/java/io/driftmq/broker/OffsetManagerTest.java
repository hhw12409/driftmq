package io.driftmq.broker;

import io.driftmq.test.Assert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class OffsetManagerTest {

    private Path dataDir;

    public void beforeEach() throws IOException {
        dataDir = Files.createTempDirectory("driftmq-offset-");
        Files.createDirectories(dataDir.resolve("orders"));
    }

    public void afterEach() throws IOException {
        MessageStoreTest.deleteRecursively(dataDir);
    }

    public void testReserveAdvancesDeliveredCursor() {
        OffsetManager om = new OffsetManager(dataDir);
        ConsumerState st = om.state("orders", "c1");
        List<Long> first = st.reserveForDelivery(10, 4, 1000);
        Assert.assertEquals(List.of(0L, 1L, 2L, 3L), first, "첫 예약");
        Assert.assertEquals(4, st.deliveredCursor(), "deliveredCursor 전진");
        List<Long> second = st.reserveForDelivery(10, 100, 1000);
        Assert.assertEquals(List.of(4L, 5L, 6L, 7L, 8L, 9L), second, "endOffset 까지만");
        Assert.assertEquals(10, st.inFlightCount(), "inFlight 등록");
    }

    public void testContiguousAckAdvancesCommittedPosition() throws Exception {
        OffsetManager om = new OffsetManager(dataDir);
        ConsumerState st = om.state("orders", "c1");
        st.reserveForDelivery(5, 5, 1000);
        for (long i = 0; i < 5; i++) st.ack(i);
        Assert.assertEquals(5, st.committedPosition(), "연속 ACK 후 committedPosition");
        Assert.assertEquals(0, st.inFlightCount(), "in-flight 0");
        Map<String, Long> persisted = om.persistedPositions("orders");
        Assert.assertEquals(5L, (long) persisted.get("c1"), "파일에 영속화된 위치");
    }

    public void testNonContiguousAckHoldsUntilGapFilled() {
        OffsetManager om = new OffsetManager(dataDir);
        ConsumerState st = om.state("orders", "c1");
        st.reserveForDelivery(5, 5, 1000);
        st.ack(0);
        st.ack(1);
        st.ack(3); // 2 가 빔
        st.ack(4);
        Assert.assertEquals(2, st.committedPosition(), "갭(2) 앞에서 멈춤");
        st.ack(2); // 갭 채움
        Assert.assertEquals(5, st.committedPosition(), "갭 채우면 3,4 까지 연속 전진");
    }

    public void testDuplicateAckIsIgnored() {
        OffsetManager om = new OffsetManager(dataDir);
        ConsumerState st = om.state("orders", "c1");
        st.reserveForDelivery(3, 3, 1000);
        st.ack(0); st.ack(1); st.ack(2);
        Assert.assertEquals(3, st.committedPosition(), "정상 전진");
        st.ack(1); // 철 지난 중복 ACK
        st.ack(0);
        Assert.assertEquals(3, st.committedPosition(), "중복 ACK 는 committedPosition 을 되돌리지 않음");
    }

    public void testReloadFromFileRestoresPosition() throws Exception {
        OffsetManager om1 = new OffsetManager(dataDir);
        ConsumerState a = om1.state("orders", "consumer-A");
        ConsumerState b = om1.state("orders", "consumer-B");
        a.reserveForDelivery(100, 60, 1000);
        for (long i = 0; i < 50; i++) a.ack(i);
        b.reserveForDelivery(100, 10, 1000);
        for (long i = 0; i < 10; i++) b.ack(i);

        OffsetManager om2 = new OffsetManager(dataDir);
        om2.attachTopic("orders", 100);
        Assert.assertEquals(50, om2.state("orders", "consumer-A").committedPosition(), "A 복원");
        Assert.assertEquals(10, om2.state("orders", "consumer-B").committedPosition(), "B 복원");
        // 재시작 후 deliveredCursor = committedPosition → 미ACK(50..99) 재전달
        List<Long> redelivered = om2.state("orders", "consumer-A").reserveForDelivery(100, 5, 2000);
        Assert.assertEquals(List.of(50L, 51L, 52L, 53L, 54L), redelivered, "재시작 후 미ACK 부터 재개");
    }

    public void testClampToEndOffsetOnLoad() throws Exception {
        Files.writeString(dataDir.resolve("orders").resolve("offsets"), "c1\t9999\n");
        OffsetManager om = new OffsetManager(dataDir);
        om.attachTopic("orders", 10);
        Assert.assertEquals(10, om.state("orders", "c1").committedPosition(), "endOffset 로 클램프");
    }

    public void testCorruptedOffsetLineStopsParsing() throws Exception {
        Files.writeString(dataDir.resolve("orders").resolve("offsets"),
                "c1\t5\nc2\tGARBAGE\nc3\t8\n");
        OffsetManager om = new OffsetManager(dataDir);
        om.attachTopic("orders", 100);
        Assert.assertEquals(5, om.state("orders", "c1").committedPosition(), "손상 줄 앞은 정상");
        Assert.assertEquals(0, om.state("orders", "c3").committedPosition(), "손상 줄 이후는 무시(0)");
    }
}
