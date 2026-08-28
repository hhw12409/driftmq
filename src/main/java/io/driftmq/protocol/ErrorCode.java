package io.driftmq.protocol;

/** wire ERROR 프레임의 errorCode (u16 BE). 스펙 §6. */
public enum ErrorCode {
    UNKNOWN_TOPIC(1),
    TOPIC_ALREADY_EXISTS(2),
    MALFORMED_FRAME(3),
    STORAGE_ERROR(4),
    UNKNOWN_REQUEST_TYPE(5),
    INTERNAL(6);

    public final int code;

    ErrorCode(int code) { this.code = code; }

    public static ErrorCode fromCode(int code) {
        for (ErrorCode e : values()) if (e.code == code) return e;
        return INTERNAL;
    }
}
