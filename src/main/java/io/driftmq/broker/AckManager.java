package io.driftmq.broker;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACK timeout 스캐너. 1초 주기로 모든 consumer 상태를 순회하며,
 * ack-timeout 을 넘긴 미ACK 메시지가 있으면 해당 consumer 의 deliveredCursor 를 되감아 재전달시킨다.
 * delivery semantics §숫자: ack-timeout 기본 30초, v0.1 은 bulk 되감기.
 */
public final class AckManager {

    private final OffsetManager offsets;
    private final long ackTimeoutMillis;
    private final long scanIntervalMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    // 관측용 카운터
    private volatile long redeliveryRewinds;

    public AckManager(OffsetManager offsets, long ackTimeoutMillis) {
        this(offsets, ackTimeoutMillis, 1000);
    }

    public AckManager(OffsetManager offsets, long ackTimeoutMillis, long scanIntervalMillis) {
        this.offsets = offsets;
        this.ackTimeoutMillis = ackTimeoutMillis;
        this.scanIntervalMillis = scanIntervalMillis;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread t = new Thread(this::loop, "driftmq-ack-scanner");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** 한 번 스캔 (테스트에서 직접 호출 가능). */
    public int scanOnce() {
        long now = System.currentTimeMillis();
        int rewound = 0;
        for (ConsumerState st : offsets.allStates()) {
            if (st.redeliverIfTimedOut(now, ackTimeoutMillis)) {
                rewound++;
            }
        }
        if (rewound > 0) redeliveryRewinds += rewound;
        return rewound;
    }

    public long redeliveryRewinds() { return redeliveryRewinds; }

    public long ackTimeoutMillis() { return ackTimeoutMillis; }

    private void loop() {
        while (running.get()) {
            try {
                scanOnce();
                TimeUnit.MILLISECONDS.sleep(scanIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 스캐너는 죽지 않는다 — 다음 주기에 재시도
                System.err.println("[ack-scanner] " + e);
            }
        }
    }
}
