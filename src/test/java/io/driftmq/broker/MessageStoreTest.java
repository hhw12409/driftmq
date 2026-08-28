package io.driftmq.broker;

import io.driftmq.common.Header;
import io.driftmq.test.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class MessageStoreTest {

    private Path dir;

    public void beforeEach() throws IOException {
        dir = Files.createTempDirectory("driftmq-store-");
    }

    public void afterEach() throws IOException {
        if (dir != null) deleteRecursively(dir);
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public void testAppendAssignsMonotonicOffsets() throws Exception {
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(0, store.endOffset(), "빈 로그 endOffset");
            for (int i = 0; i < 100; i++) {
                Assert.assertEquals(i, store.append(List.of(), utf8("m" + i)), "offset " + i);
            }
            Assert.assertEquals(100, store.endOffset(), "append 후 endOffset");
        }
    }

    public void testReadReturnsInOrderWithSameContent() throws Exception {
        int n = 3_000; // fsync-per-append 이므로 규모는 제한. 대량은 bench 에서 측정.
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            for (int i = 0; i < n; i++) store.append(List.of(), utf8("payload-" + i));
            List<StoredMessage> all = store.read(0, n);
            Assert.assertEquals(n, all.size(), "전량 read");
            for (int i = 0; i < n; i++) {
                Assert.assertEquals(i, all.get(i).offset(), "순서 " + i);
                Assert.assertArrayEquals(utf8("payload-" + i), all.get(i).payload(), "내용 " + i);
            }
        }
    }

    public void testReadRespectsMaxAndOffset() throws Exception {
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            for (int i = 0; i < 50; i++) store.append(List.of(), utf8("x" + i));
            List<StoredMessage> page = store.read(20, 10);
            Assert.assertEquals(10, page.size(), "페이지 크기");
            Assert.assertEquals(20, page.get(0).offset(), "시작 offset");
            Assert.assertEquals(29, page.get(9).offset(), "끝 offset");
            Assert.assertEquals(0, store.read(50, 10).size(), "끝 이후는 빈 결과");
            Assert.assertEquals(0, store.read(999, 10).size(), "범위 밖은 빈 결과");
        }
    }

    public void testHeadersRoundtripThroughDisk() throws Exception {
        List<Header> headers = List.of(
                new Header("k1", utf8("v1")),
                new Header("bin", new byte[]{0, -1, 5}),
                new Header("empty", new byte[0]));
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            store.append(headers, utf8("hello"));
        }
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            StoredMessage m = store.read(0, 1).get(0);
            Assert.assertEquals(headers, m.headers(), "헤더 디스크 라운드트립");
            Assert.assertArrayEquals(utf8("hello"), m.payload(), "payload");
        }
    }

    public void testPersistsAcrossReopen() throws Exception {
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            for (int i = 0; i < 100; i++) store.append(List.of(), utf8("v" + i));
        }
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(100, store.endOffset(), "재오픈 후 endOffset");
            Assert.assertEquals(100, store.read(0, 1000).size(), "재오픈 후 전량 read");
            Assert.assertEquals(100, store.append(List.of(), utf8("v100")), "재오픈 후 append 는 100");
        }
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignore) {} });
        }
    }
}
