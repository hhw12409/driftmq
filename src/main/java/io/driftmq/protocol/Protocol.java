package io.driftmq.protocol;

/** DriftMQ wire protocol v1 상수. */
public final class Protocol {
    private Protocol() {}

    /** 공통 헤더의 version 바이트. */
    public static final int VERSION = 1;

    /** 프레임 길이 prefix 폭 (bytes), unsigned u32 BE. */
    public static final int LENGTH_PREFIX_BYTES = 4;

    /** 프레임 body 최대 크기. 초과 시 MALFORMED_FRAME + 연결 종료. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** FETCH maxMessages 필드가 0일 때 적용하는 서버 기본값. */
    public static final int DEFAULT_FETCH_MAX = 100;

    /** 공통 헤더 크기: version(1) + type(1) + correlationId(4). */
    public static final int COMMON_HEADER_BYTES = 6;
}
