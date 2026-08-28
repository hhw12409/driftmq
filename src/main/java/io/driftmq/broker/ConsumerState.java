package io.driftmq.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 한 {@code (topic, consumerId)} 의 소비 위치. 저장 설계 §7.
 *
 * <ul>
 *   <li>{@code committedPosition} — 연속 ACK 구간의 다음 offset. offsets 파일에 영속. 재시작 후 FETCH 재개점.
 *   <li>{@code deliveredCursor} — 다음 FETCH 가 내보낼 offset. 인메모리. 시작값 = committedPosition.
 *   <li>{@code inFlight} — 전달했으나 미ACK 인 offset → 전달시각(ms).
 *   <li>{@code ackedAbove} — committedPosition 위쪽에서 이미 ACK 된 offset (비연속 ACK 대비).
 * </ul>
 *
 * 모든 메서드는 {@code synchronized} — 상태 객체 단위 직렬화.
 */
public final class ConsumerState {

    /** consumer/topic 당 동시 미ACK 상한 (백프레셔). delivery semantics 기본값. */
    public static final int IN_FLIGHT_CAP = 1000;

    private final String consumerId;
    private final Runnable onCommitAdvance;

    private long committedPosition;
    private long deliveredCursor;
    private final TreeMap<Long, Long> inFlight = new TreeMap<>();
    private final TreeSet<Long> ackedAbove = new TreeSet<>();

    ConsumerState(String consumerId, long committedPosition, Runnable onCommitAdvance) {
        this.consumerId = consumerId;
        this.committedPosition = committedPosition;
        this.deliveredCursor = committedPosition;
        this.onCommitAdvance = onCommitAdvance;
    }

    public String consumerId() { return consumerId; }

    public synchronized long committedPosition() { return committedPosition; }

    public synchronized long deliveredCursor() { return deliveredCursor; }

    public synchronized int inFlightCount() { return inFlight.size(); }

    /**
     * {@code deliveredCursor} 부터 최대 {@code max} 개의 offset 을 전달 대상으로 예약한다.
     * 이미 ACK 된 offset(ackedAbove)은 건너뛴다. in-flight 상한을 넘기지 않는다.
     *
     * @return 전달할 offset 목록 (오름차순, 갭 있을 수 있음)
     */
    public synchronized List<Long> reserveForDelivery(long endOffset, int max, long nowMillis) {
        List<Long> out = new ArrayList<>();
        int room = IN_FLIGHT_CAP - inFlight.size();
        if (room <= 0 || max <= 0) return out;

        long o = deliveredCursor;
        while (out.size() < max && out.size() < room && o < endOffset) {
            if (ackedAbove.contains(o) || o < committedPosition) {
                o++;
                continue;
            }
            inFlight.put(o, nowMillis);
            out.add(o);
            o++;
        }
        deliveredCursor = o;
        return out;
    }

    /** offset(포함)까지 처리 완료. inFlight 에서 제거하고 연속 구간만큼 committedPosition 전진. */
    public synchronized void ack(long offset) {
        inFlight.remove(offset);
        if (offset < committedPosition) return; // 중복/철 지난 ACK — 무시

        boolean advanced = false;
        if (offset == committedPosition) {
            committedPosition++;
            while (ackedAbove.remove(committedPosition)) committedPosition++;
            advanced = true;
        } else {
            ackedAbove.add(offset);
        }
        if (deliveredCursor < committedPosition) deliveredCursor = committedPosition;
        if (advanced && onCommitAdvance != null) onCommitAdvance.run();
    }

    /**
     * inFlight 중 {@code nowMillis - deliveredAt > timeoutMillis} 인 항목이 있으면
     * deliveredCursor 를 committedPosition 으로 되감아 미ACK 전량을 재전달 대상으로 만든다 (bulk rewind).
     *
     * @return 되감았으면 true
     */
    public synchronized boolean redeliverIfTimedOut(long nowMillis, long timeoutMillis) {
        if (inFlight.isEmpty()) return false;
        long oldest = Long.MAX_VALUE;
        for (long ts : inFlight.values()) oldest = Math.min(oldest, ts);
        if (nowMillis - oldest <= timeoutMillis) return false;

        rewindForRedelivery();
        return true;
    }

    /**
     * 미ACK 전량을 즉시 재전달 대상으로 만든다 (deliveredCursor 를 committedPosition 으로 되감기).
     * consumer 연결이 끊겼을 때 호출 — ack-timeout 을 기다리지 않고 재연결 즉시 재개하도록.
     */
    public synchronized void rewindForRedelivery() {
        inFlight.clear();
        deliveredCursor = committedPosition;
    }

    /** 로드 시 committedPosition 이 로그 끝을 넘어가면 클램프 (손상 방어). */
    synchronized void clampTo(long endOffset) {
        if (committedPosition > endOffset) {
            committedPosition = endOffset;
        }
        if (deliveredCursor < committedPosition) deliveredCursor = committedPosition;
    }

    /** 영속화용 스냅샷. */
    synchronized long committedForPersist() { return committedPosition; }

    /** 테스트/describe 용 inFlight 스냅샷. */
    public synchronized Map<Long, Long> inFlightSnapshot() {
        return new TreeMap<>(inFlight);
    }
}
