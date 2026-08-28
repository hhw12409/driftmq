package io.driftmq.test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 소형 테스트 러너. 인자로 받은 각 클래스에서 no-arg {@code test*} 메서드를 이름순으로 실행한다.
 * 클래스에 {@code beforeEach()} / {@code afterEach()} no-arg 메서드가 있으면 각 테스트 전후 호출.
 * 실패가 하나라도 있으면 exit code 1.
 */
public final class MiniTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: MiniTest <test.Class> ...");
            System.exit(2);
        }
        int total = 0, passed = 0;
        List<String> failures = new ArrayList<>();
        long start = System.nanoTime();

        for (String className : args) {
            Class<?> cls = Class.forName(className);
            Method before = optional(cls, "beforeEach");
            Method after = optional(cls, "afterEach");
            List<Method> tests = new ArrayList<>();
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().startsWith("test") && m.getParameterCount() == 0) tests.add(m);
            }
            tests.sort(Comparator.comparing(Method::getName));
            System.out.println("── " + className + " (" + tests.size() + ")");

            for (Method m : tests) {
                total++;
                Object instance = cls.getDeclaredConstructor().newInstance();
                m.setAccessible(true);
                String label = "  " + m.getName();
                try {
                    if (before != null) { before.setAccessible(true); before.invoke(instance); }
                    m.invoke(instance);
                    if (after != null) { after.setAccessible(true); after.invoke(instance); }
                    passed++;
                    System.out.println("  ✓ " + m.getName());
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    failures.add(className + "." + m.getName() + " :: " + cause);
                    System.out.println("  ✗ " + m.getName() + " — " + cause);
                    for (StackTraceElement el : firstFrames(cause, className)) {
                        System.out.println("      at " + el);
                    }
                    try { if (after != null) after.invoke(instance); } catch (Exception ignore) {}
                } catch (Throwable t) {
                    failures.add(className + "." + m.getName() + " :: " + t);
                    System.out.println("  ✗ " + m.getName() + " — " + t);
                }
            }
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println();
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  %d/%d passed  (%d ms)%n", passed, total, ms);
        if (!failures.isEmpty()) {
            System.out.println("  FAILURES:");
            for (String f : failures) System.out.println("   - " + f);
        }
        System.out.println("═══════════════════════════════════════");
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static Method optional(Class<?> cls, String name) {
        try { return cls.getDeclaredMethod(name); } catch (NoSuchMethodException e) { return null; }
    }

    private static List<StackTraceElement> firstFrames(Throwable t, String hint) {
        List<StackTraceElement> out = new ArrayList<>();
        for (StackTraceElement el : t.getStackTrace()) {
            if (el.getClassName().startsWith("io.driftmq")) {
                out.add(el);
                if (out.size() >= 4) break;
            }
        }
        if (out.isEmpty()) out.addAll(Arrays.asList(t.getStackTrace()).subList(0, Math.min(3, t.getStackTrace().length)));
        return out;
    }
}
