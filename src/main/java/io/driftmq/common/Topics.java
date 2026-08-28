package io.driftmq.common;

import java.util.regex.Pattern;

/** Topic 이름 규칙. 파일시스템 안전 문자만 허용 (저장 설계 §1). */
public final class Topics {
    private Topics() {}

    public static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,255}");

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches()
                && !name.equals(".") && !name.equals("..");
    }

    public static void requireValid(String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException(
                "invalid topic name: '" + name + "' (allowed: [A-Za-z0-9._-], 1..255 chars)");
        }
    }
}
