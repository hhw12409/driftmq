package io.driftmq.protocol;

/**
 * 프레임을 파싱할 수 없을 때 (버전 불일치, 알 수 없는 type, 잘린 필드, 크기 초과).
 * 데이터가 아직 덜 온 "부분 프레임"과는 구분한다 — 그 경우 Codec.decodeFrame 은 null 을 반환한다.
 */
public class ProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient ErrorCode errorCode;

    public ProtocolException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
