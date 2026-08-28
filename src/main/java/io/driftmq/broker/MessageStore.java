package io.driftmq.broker;

import io.driftmq.common.Header;
import io.driftmq.protocol.Codec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * topic 하나의 append-only 로그. 저장 레이아웃 요약은 README 참조.
 *
 * <p>디스크 레코드: {@code [recordLen u32][crc32c u32][timestamp i64][headerBytesLen u32][headerBytes][payloadLen u32][payload]}
 * — {@code recordLen} 은 recordLen 필드 다음부터 끝까지의 바이트 수, {@code crc32c} 는 {@code [timestamp..payload]} 구간.
 *
 * <p>동시성: append/read/close 는 모두 {@code synchronized} — 한 파티션 로그는 단일 writer 로 직렬화된다
 * (락 대신 직렬화가 추론하기 쉽다). v0.1 은 fsync 정책이 {@code always} 뿐이라 별도 writer 스레드/큐가 불필요하다;
 * batch fsync(v0.2)에서 큐를 도입한다.
 */
public final class MessageStore implements AutoCloseable {

    /** v0.1: topic 당 단일 세그먼트. 파일명 = 시작 offset 20자리 0-padding. */
    static final String SEGMENT_NAME = "00000000000000000000.log";

    /** 레코드 최대 크기 방어 한계 (복구 시 손상 감지). */
    static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;

    /** recordLen 다음 최소 바이트: crc(4)+ts(8)+hlen(4)+빈헤더(2)+plen(4)+빈페이로드(0). */
    static final int MIN_RECORD_LEN = 4 + 8 + 4 + 2 + 4;

    /**
     * 벤치 전용 knob: {@code -Ddriftmq.fsync=none} 이면 append 마다의 fsync 를 생략한다
     * (배치 fsync 정책의 상한을 재보기 위함). 기본은 {@code always} — 크래시 손실 창 0.
     * 운영에서 사용 금지.
     */
    private static final boolean FSYNC_ON_APPEND =
            !"none".equalsIgnoreCase(System.getProperty("driftmq.fsync", "always"));

    private final Path logFile;
    private final FileChannel channel;
    private final ArrayList<Long> positions; // offset -> 파일 내 레코드 시작 위치
    private long fileSize;

    private MessageStore(Path logFile, FileChannel channel, ArrayList<Long> positions, long fileSize) {
        this.logFile = logFile;
        this.channel = channel;
        this.positions = positions;
        this.fileSize = fileSize;
    }

    /**
     * topic 디렉토리의 로그를 열고, 필요하면 크래시 복구를 수행한다.
     * 마지막 레코드가 부분 쓰기(길이 부족 또는 CRC 불일치)면 그 지점에서 truncate 한다.
     */
    public static MessageStore openOrRecover(Path topicDir) throws StorageException {
        try {
            Files.createDirectories(topicDir);
            Path logFile = topicDir.resolve(SEGMENT_NAME);
            FileChannel channel = FileChannel.open(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

            long size = channel.size();
            ArrayList<Long> positions = new ArrayList<>();
            long pos = 0;

            while (true) {
                if (size - pos < 4) {
                    break; // recordLen 필드조차 온전하지 않음 → 정상 끝 또는 부분 쓰기
                }
                int recordLen = readIntAt(channel, pos);
                if (recordLen < MIN_RECORD_LEN || recordLen > MAX_RECORD_BYTES
                        || size - pos - 4 < recordLen) {
                    break; // 부분 쓰기 또는 손상
                }
                ByteBuffer rec = readFully(channel, pos + 4, recordLen);
                int storedCrc = rec.getInt(); // rec[0..4)
                if ((int) crc32c(rec, 4, recordLen - 4) != storedCrc) {
                    break; // CRC 불일치 → 부분 쓰기/손상
                }
                positions.add(pos);
                pos += 4L + recordLen;
            }

            if (pos != size) {
                channel.truncate(pos);
                channel.force(true);
            }
            return new MessageStore(logFile, channel, positions, pos);
        } catch (IOException e) {
            throw new StorageException("failed to open/recover log for " + topicDir, e);
        }
    }

    /** 다음에 할당될 offset = 현재 저장된 메시지 수. */
    public synchronized long endOffset() {
        return positions.size();
    }

    /**
     * 메시지를 로그 끝에 append 하고 fsync 가 완료된 뒤에야 할당된 offset 을 반환한다.
     * (저장 완료 전에는 절대 성공으로 간주하지 않는다.)
     */
    public synchronized long append(List<Header> headers, byte[] payload) throws StorageException {
        byte[] headerBytes = Codec.encodeHeaders(headers);
        int afterCrcLen = 8 + 4 + headerBytes.length + 4 + payload.length;
        int recordLen = 4 + afterCrcLen; // + crc 필드

        ByteBuffer afterCrc = ByteBuffer.allocate(afterCrcLen).order(ByteOrder.BIG_ENDIAN);
        afterCrc.putLong(System.currentTimeMillis());
        afterCrc.putInt(headerBytes.length);
        afterCrc.put(headerBytes);
        afterCrc.putInt(payload.length);
        afterCrc.put(payload);
        afterCrc.flip();

        CRC32C crc = new CRC32C();
        crc.update(afterCrc.duplicate());
        int crcValue = (int) crc.getValue();

        ByteBuffer record = ByteBuffer.allocate(4 + recordLen).order(ByteOrder.BIG_ENDIAN);
        record.putInt(recordLen);
        record.putInt(crcValue);
        record.put(afterCrc);
        record.flip();

        long startPos = fileSize;
        try {
            channel.position(startPos);
            while (record.hasRemaining()) {
                channel.write(record);
            }
            if (FSYNC_ON_APPEND) {
                channel.force(false); // fsync — 데이터 확정 (저장 완료 후 성공 응답, §6)
            }
        } catch (IOException e) {
            // 인덱스/크기 갱신 전에 실패 → 부분 바이트는 다음 복구에서 truncate 됨
            throw new StorageException("append failed for " + logFile, e);
        }

        positions.add(startPos);
        fileSize = startPos + 4L + recordLen;
        return positions.size() - 1L;
    }

    /** {@code fromOffset} 부터 최대 {@code maxMessages} 개를 offset 오름차순으로 읽는다. */
    public synchronized List<StoredMessage> read(long fromOffset, int maxMessages) throws StorageException {
        List<StoredMessage> out = new ArrayList<>();
        if (fromOffset < 0 || fromOffset >= positions.size() || maxMessages <= 0) {
            return out;
        }
        long end = Math.min(positions.size(), fromOffset + maxMessages);
        try {
            for (long off = fromOffset; off < end; off++) {
                long pos = positions.get((int) off);
                int recordLen = readIntAt(channel, pos);
                ByteBuffer rec = readFully(channel, pos + 4, recordLen);
                int storedCrc = rec.getInt();
                if ((int) crc32c(rec, 4, recordLen - 4) != storedCrc) {
                    throw new StorageException("CRC mismatch reading offset " + off + " of " + logFile);
                }
                rec.position(4);
                long timestamp = rec.getLong();
                int headerBytesLen = rec.getInt();
                ByteBuffer headerSlice = rec.slice(rec.position(), headerBytesLen).order(ByteOrder.BIG_ENDIAN);
                rec.position(rec.position() + headerBytesLen);
                List<Header> headers = Codec.readHeaders(headerSlice);
                int payloadLen = rec.getInt();
                byte[] payload = new byte[payloadLen];
                rec.get(payload);
                out.add(new StoredMessage(off, timestamp, headers, payload));
            }
        } catch (IOException e) {
            throw new StorageException("read failed for " + logFile, e);
        }
        return out;
    }

    /** 로그 파일 크기 (테스트/복구 검증용). */
    public synchronized long fileSizeBytes() {
        return fileSize;
    }

    @Override
    public synchronized void close() throws StorageException {
        try {
            channel.force(true);
            channel.close();
        } catch (IOException e) {
            throw new StorageException("close failed for " + logFile, e);
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static int readIntAt(FileChannel ch, long pos) throws IOException {
        return readFully(ch, pos, 4).getInt(0);
    }

    private static ByteBuffer readFully(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.BIG_ENDIAN);
        long p = pos;
        while (b.hasRemaining()) {
            int n = ch.read(b, p);
            if (n < 0) throw new IOException("unexpected EOF at " + p + " (wanted " + len + " from " + pos + ")");
            p += n;
        }
        b.flip();
        return b;
    }

    /** buffer 의 [offset, offset+len) 구간에 대한 CRC32C. buffer position 불변. */
    private static long crc32c(ByteBuffer buf, int offset, int len) {
        ByteBuffer slice = buf.duplicate();
        slice.position(offset).limit(offset + len);
        CRC32C c = new CRC32C();
        c.update(slice);
        return c.getValue();
    }
}
