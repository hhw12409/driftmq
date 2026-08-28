package io.driftmq.common;

/** DriftMQ 공통 체크 예외의 뿌리. */
public class DriftException extends Exception {
    private static final long serialVersionUID = 1L;

    public DriftException(String message) { super(message); }
    public DriftException(String message, Throwable cause) { super(message, cause); }
}
