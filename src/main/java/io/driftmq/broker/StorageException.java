package io.driftmq.broker;

import io.driftmq.common.DriftException;

/** append / fsync / read / 복구 중 I/O 실패. wire 로는 STORAGE_ERROR 로 매핑된다. */
public class StorageException extends DriftException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) { super(message, cause); }
    public StorageException(String message) { super(message); }
}
