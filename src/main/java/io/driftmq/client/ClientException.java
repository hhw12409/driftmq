package io.driftmq.client;

import io.driftmq.protocol.ErrorCode;

/** 브로커가 ERROR 를 반환했거나 프로토콜 수준 문제가 생겼을 때. */
public class ClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient ErrorCode errorCode; // null 이면 클라이언트 측 문제

    public ClientException(String message) {
        super(message);
        this.errorCode = null;
    }

    public ClientException(ErrorCode errorCode, String message) {
        super("[" + errorCode + "] " + message);
        this.errorCode = errorCode;
    }

    /** 브로커 에러 코드. 클라이언트 측 문제면 null. */
    public ErrorCode errorCode() { return errorCode; }
}
