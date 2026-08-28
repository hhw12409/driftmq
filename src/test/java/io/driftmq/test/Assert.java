package io.driftmq.test;

import java.util.Arrays;
import java.util.Objects;

/** 최소 assertion 헬퍼. 외부 테스트 프레임워크 대체. */
public final class Assert {
    private Assert() {}

    public static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("expected true: " + msg);
    }

    public static void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError("expected false: " + msg);
    }

    public static void assertEquals(long expected, long actual, String msg) {
        if (expected != actual)
            throw new AssertionError(msg + " — expected <" + expected + "> but was <" + actual + ">");
    }

    public static void assertEquals(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(msg + " — expected <" + expected + "> but was <" + actual + ">");
    }

    public static void assertArrayEquals(byte[] expected, byte[] actual, String msg) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionError(msg + " — byte arrays differ; expected len "
                    + (expected == null ? "null" : expected.length)
                    + " actual len " + (actual == null ? "null" : actual.length));
    }

    public static void assertNull(Object o, String msg) {
        if (o != null) throw new AssertionError(msg + " — expected null but was <" + o + ">");
    }

    public static void assertNotNull(Object o, String msg) {
        if (o == null) throw new AssertionError(msg + " — expected non-null");
    }

    public static void fail(String msg) {
        throw new AssertionError(msg);
    }

    public interface ThrowingRunnable { void run() throws Exception; }

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable body, String msg) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return (T) t;
            throw new AssertionError(msg + " — expected " + type.getSimpleName()
                    + " but got " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        throw new AssertionError(msg + " — expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
