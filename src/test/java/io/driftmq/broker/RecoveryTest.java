package io.driftmq.broker;

import io.driftmq.test.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** 크래시 복구: 부분 프레임 truncate, CRC 손상 감지, 복구 후 불변식. (docs §14 7~9) */
public class RecoveryTest {

    private Path dir;
    private Path log;

    public void beforeEach() throws IOException {
        dir = Files.createTempDirectory("driftmq-recovery-");
        log = dir.resolve(MessageStore.SEGMENT_NAME);
    }

    public void afterEach() throws IOException {
        MessageStoreTest.deleteRecursively(dir);
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private void appendN(int n) throws Exception {
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            for (int i = 0; i < n; i++) store.append(List.of(), utf8("msg-" + i));
        }
    }

    public void testTruncatedTailBytesAreDropped() throws Exception {
        appendN(10);
        long goodSize = Files.size(log);
        // 마지막 레코드가 반쯤 쓰이다 만 상황을 흉내: 쓰레기 바이트를 파일 끝에 덧붙임
        try (var ch = Files.newByteChannel(log, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{0, 0, 0, 40, 1, 2, 3})); // recordLen=40 이라 주장, 뒤 3바이트뿐
        }
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(10, store.endOffset(), "부분 tail 무시 후 endOffset");
            Assert.assertEquals(10, store.read(0, 100).size(), "유효 레코드 전부 존재");
        }
        Assert.assertEquals(goodSize, Files.size(log), "파일이 마지막 유효 레코드 끝으로 truncate");
    }

    public void testCorruptedLastRecordCrcIsTruncated() throws Exception {
        appendN(5);
        long sizeBefore4 = -1;
        // 5번째(마지막) 레코드의 payload 바이트 하나를 뒤집어 CRC 를 깨뜨림
        byte[] all = Files.readAllBytes(log);
        all[all.length - 1] ^= 0x7F;
        Files.write(log, all);

        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(4, store.endOffset(), "손상된 마지막 레코드는 버려짐");
            List<StoredMessage> msgs = store.read(0, 100);
            Assert.assertEquals(4, msgs.size(), "유효 4개만");
            Assert.assertArrayEquals(utf8("msg-3"), msgs.get(3).payload(), "offset 3 온전");
            // 복구 후 append 가 이어서 정상 동작 (offset 4 재사용)
            Assert.assertEquals(4, store.append(List.of(), utf8("msg-4-again")), "복구 후 append offset");
        }
    }

    public void testCorruptedMiddleStopsAtFirstBadRecord() throws Exception {
        appendN(6);
        // 3번째 레코드 근처의 recordLen 필드를 거대한 값으로 훼손 → 그 지점에서 멈춰야 함
        // (레코드 위치를 알기 위해 정상 로그를 다시 스캔)
        long[] starts = new long[6];
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            var msgs = store.read(0, 6);
            Assert.assertEquals(6, msgs.size(), "사전조건");
        }
        // 첫 레코드 크기를 재서 3번째 시작 위치를 계산
        byte[] all = Files.readAllBytes(log);
        long pos = 0;
        for (int i = 0; i < 3; i++) {
            int recLen = ((all[(int) pos] & 0xFF) << 24) | ((all[(int) pos + 1] & 0xFF) << 16)
                    | ((all[(int) pos + 2] & 0xFF) << 8) | (all[(int) pos + 3] & 0xFF);
            pos += 4 + recLen;
        }
        // pos = 4번째 레코드(offset 3) 시작. recordLen 을 손상.
        all[(int) pos] = 0x7F; all[(int) pos + 1] = (byte) 0xFF;
        Files.write(log, all);

        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(3, store.endOffset(), "손상 지점 이전까지만 복구");
        }
        Assert.assertEquals(pos, Files.size(log), "손상 레코드 시작점에서 truncate");
    }

    public void testEmptyAndZeroByteLogRecoverCleanly() throws Exception {
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(0, store.endOffset(), "새 로그");
        }
        // 3바이트만 있는 로그 (recordLen 필드 미완성)
        Files.write(log, new byte[]{1, 2, 3});
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(0, store.endOffset(), "쓰레기 3바이트 무시");
        }
        Assert.assertEquals(0, Files.size(log), "truncate 되어 0바이트");
    }

    public void testOffsetMonotonicAcrossRestart() throws Exception {
        appendN(30);
        appendN(20); // 재오픈 후 이어서
        try (MessageStore store = MessageStore.openOrRecover(dir)) {
            Assert.assertEquals(50, store.endOffset(), "재시작 across offset 이어짐, 역행/스킵 없음");
            var msgs = store.read(0, 100);
            for (int i = 0; i < 50; i++) Assert.assertEquals(i, msgs.get(i).offset(), "offset " + i);
        }
    }
}
