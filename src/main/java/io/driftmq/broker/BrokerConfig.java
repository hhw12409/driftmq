package io.driftmq.broker;

import java.nio.file.Path;

/** 브로커 실행 설정. CLI {@code driftmq start} 플래그로 채워진다. */
public record BrokerConfig(Path dataDir, int port, int ackTimeoutSeconds, String fsyncPolicy) {

    public static final int DEFAULT_PORT = 7644;
    public static final int DEFAULT_ACK_TIMEOUT_SECONDS = 30;
    public static final String DEFAULT_FSYNC_POLICY = "always";

    public BrokerConfig {
        // 0 = OS 가 임의 포트 배정 (테스트/임베디드).
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        if (ackTimeoutSeconds < 1) throw new IllegalArgumentException("ack-timeout must be >= 1s");
        if (!fsyncPolicy.equals("always") && !fsyncPolicy.startsWith("batch:")) {
            throw new IllegalArgumentException("fsync policy must be 'always' or 'batch:<ms>'");
        }
    }

    public static BrokerConfig defaults(Path dataDir) {
        return new BrokerConfig(dataDir, DEFAULT_PORT, DEFAULT_ACK_TIMEOUT_SECONDS, DEFAULT_FSYNC_POLICY);
    }

    public long ackTimeoutMillis() {
        return ackTimeoutSeconds * 1000L;
    }

    /** v0.1 은 {@code always} 만 구현. {@code batch} 지정 시 경고 후 always 로 폴백. */
    public String effectiveFsyncPolicy() {
        return "always";
    }
}
