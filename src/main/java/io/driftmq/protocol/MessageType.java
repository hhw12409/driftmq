package io.driftmq.protocol;

/** wire 프레임 type 바이트 값. 요청 1..6, 응답 128..133, 에러 255. */
public final class MessageType {
    private MessageType() {}

    // 요청
    public static final int PUBLISH = 1;
    public static final int FETCH = 2;
    public static final int ACK = 3;
    public static final int TOPIC_CREATE = 4;
    public static final int TOPIC_LIST = 5;
    public static final int TOPIC_DESCRIBE = 6;

    // 응답
    public static final int PUBLISH_OK = 128;
    public static final int FETCH_OK = 129;
    public static final int ACK_OK = 130;
    public static final int TOPIC_CREATE_OK = 131;
    public static final int TOPIC_LIST_OK = 132;
    public static final int TOPIC_DESCRIBE_OK = 133;

    public static final int ERROR = 255;

    public static String name(int type) {
        return switch (type) {
            case PUBLISH -> "PUBLISH";
            case FETCH -> "FETCH";
            case ACK -> "ACK";
            case TOPIC_CREATE -> "TOPIC_CREATE";
            case TOPIC_LIST -> "TOPIC_LIST";
            case TOPIC_DESCRIBE -> "TOPIC_DESCRIBE";
            case PUBLISH_OK -> "PUBLISH_OK";
            case FETCH_OK -> "FETCH_OK";
            case ACK_OK -> "ACK_OK";
            case TOPIC_CREATE_OK -> "TOPIC_CREATE_OK";
            case TOPIC_LIST_OK -> "TOPIC_LIST_OK";
            case TOPIC_DESCRIBE_OK -> "TOPIC_DESCRIBE_OK";
            case ERROR -> "ERROR";
            default -> "UNKNOWN(" + type + ")";
        };
    }
}
