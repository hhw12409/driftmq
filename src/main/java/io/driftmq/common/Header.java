package io.driftmq.common;

import java.util.Arrays;
import java.util.Objects;

/** 메시지 헤더 한 항목. key는 UTF-8 문자열, value는 임의 바이트. */
public record Header(String key, byte[] value) {
    public Header {
        Objects.requireNonNull(key, "header key");
        Objects.requireNonNull(value, "header value");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Header h)) return false;
        return key.equals(h.key) && Arrays.equals(value, h.value);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Header[" + key + "=(" + value.length + "B)]";
    }
}
