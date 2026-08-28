package io.driftmq.broker;

import io.driftmq.common.Header;

import java.util.List;

/** 로그에서 읽어낸 메시지 한 건. offset 은 파일 내 순서(0-based). */
public record StoredMessage(long offset, long timestamp, List<Header> headers, byte[] payload) {
    public StoredMessage {
        headers = List.copyOf(headers);
    }
}
