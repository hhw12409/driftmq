package io.driftmq.bench;

import io.driftmq.broker.Broker;
import io.driftmq.broker.BrokerConfig;
import io.driftmq.broker.Server;
import io.driftmq.client.Consumer;
import io.driftmq.client.ConsumerRecord;
import io.driftmq.client.DriftClient;
import io.driftmq.client.Producer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 재현 가능한 벤치마크. 인프로세스 브로커(임의 포트)를 띄우고 측정한다.
 * 결과는 stdout 에 표로 — {@code bench.sh} 가 환경 정보와 함께 파일로 저장한다.
 *
 * <pre>
 *   java -cp driftmq.jar io.driftmq.bench.Bench [--messages N] [--runs R]
 *   java -Ddriftmq.fsync=none -cp driftmq.jar io.driftmq.bench.Bench   # fsync 생략 상한
 * </pre>
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        int messages = intArg(args, "--messages", 5_000);
        int runs = intArg(args, "--runs", 3);
        int[] sizes = {100, 1024, 10240};

        System.out.println("# DriftMQ bench");
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("os = " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("cpus = " + Runtime.getRuntime().availableProcessors());
        System.out.println("fsync = " + System.getProperty("driftmq.fsync", "always"));
        System.out.println("messages/run = " + messages + ", runs = " + runs);
        System.out.println();

        Path dir = Files.createTempDirectory("driftmq-bench-");
        BrokerConfig config = new BrokerConfig(dir, 0, 30, "always");
        try (Broker broker = new Broker(config)) {
            broker.start();
            Server server = new Server(broker, 0);
            server.start();
            try (DriftClient client = DriftClient.connect("localhost", server.port())) {
                throughput(client, sizes, messages, runs);
                latency(client, messages);
            }
            server.close();
        }
        recoveryTime(dir);
        deleteRecursively(dir);
    }

    private static void throughput(DriftClient client, int[] sizes, int messages, int runs) {
        System.out.println("## publish 처리량");
        System.out.printf("%-12s %-14s %-16s %-14s%n", "msg size", "msgs/sec(med)", "MB/sec(med)", "runs(msgs/s)");
        for (int size : sizes) {
            String topic = "thr-" + size;
            client.createTopic(topic);
            Producer p = client.newProducer();
            byte[] payload = filled(size);

            warmup(p, topic, payload, 2_000);
            double[] rates = new double[runs];
            for (int r = 0; r < runs; r++) {
                long start = System.nanoTime();
                for (int i = 0; i < messages; i++) p.publish(topic, payload);
                double sec = (System.nanoTime() - start) / 1e9;
                rates[r] = messages / sec;
            }
            Arrays.sort(rates);
            double med = rates[runs / 2];
            System.out.printf("%-12s %-14.0f %-16.2f %s%n",
                    size + "B", med, med * size / (1024 * 1024), fmt(rates));
        }
        System.out.println();
    }

    private static void latency(DriftClient client, int messages) throws Exception {
        System.out.println("## 지연 (ms)");
        int n = Math.min(messages, 5_000);

        // publish 지연 (fsync 포함)
        client.createTopic("lat-pub");
        Producer p = client.newProducer();
        byte[] payload = filled(256);
        warmup(p, "lat-pub", payload, 1_000);
        long[] pub = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            p.publish("lat-pub", payload);
            pub[i] = System.nanoTime() - t0;
        }
        printPercentiles("publish", pub);

        // end-to-end (publish 시각 → consume 시각)
        client.createTopic("lat-e2e");
        Producer pe = client.newProducer();
        Consumer ce = client.newConsumer("lat-e2e", "bench");
        List<Long> e2e = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] stamp = Long.toString(System.nanoTime()).getBytes(StandardCharsets.UTF_8);
            pe.publish("lat-e2e", stamp);
            List<ConsumerRecord> batch = ce.poll(1);
            while (batch.isEmpty()) batch = ce.poll(1);
            long sent = Long.parseLong(batch.get(0).payloadAsString());
            e2e.add(System.nanoTime() - sent);
            ce.ack(batch.get(0).offset());
        }
        printPercentiles("end-to-end", e2e.stream().mapToLong(Long::longValue).toArray());
        System.out.println();
    }

    private static void recoveryTime(Path dir) throws Exception {
        System.out.println("## 크래시 복구 시간");
        BrokerConfig config = new BrokerConfig(dir, 0, 30, "always");
        for (int count : new int[]{3_000, 10_000}) {
            deleteRecursively(dir);
            try (Broker broker = new Broker(config)) {
                broker.start();
                Server server = new Server(broker, 0);
                server.start();
                try (DriftClient client = DriftClient.connect("localhost", server.port())) {
                    client.createTopic("rec");
                    Producer p = client.newProducer();
                    byte[] payload = filled(512);
                    for (int i = 0; i < count; i++) p.publish("rec", payload);
                }
                server.close();
            }
            // 정상 close 없이 재오픈 (복구 경로 강제하려 로그 tail 훼손)
            Path log = dir.resolve("rec").resolve("00000000000000000000.log");
            try (var ch = Files.newByteChannel(log,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND)) {
                ch.write(java.nio.ByteBuffer.wrap(new byte[]{0, 0, 1, 0, 9, 9}));
            }
            long start = System.nanoTime();
            try (Broker broker = new Broker(config)) {
                broker.start();
                long end = broker.topics().get("rec").store().endOffset();
                double ms = (System.nanoTime() - start) / 1e6;
                System.out.printf("  %,d 레코드 → 복구 %.1f ms (endOffset=%d)%n", count, ms, end);
            }
        }
        System.out.println();
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static void warmup(Producer p, String topic, byte[] payload, int n) {
        for (int i = 0; i < n; i++) p.publish(topic, payload);
    }

    private static void printPercentiles(String label, long[] nanos) {
        long[] ms = Arrays.stream(nanos).sorted().toArray();
        System.out.printf("  %-12s p50=%.2f  p95=%.2f  p99=%.2f  max=%.2f%n",
                label, pct(ms, 50) / 1e6, pct(ms, 95) / 1e6, pct(ms, 99) / 1e6,
                ms[ms.length - 1] / 1e6);
    }

    private static double pct(long[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static byte[] filled(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    private static String fmt(double[] rates) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rates.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.0f", rates[i]));
        }
        return sb.append(']').toString();
    }

    private static int intArg(String[] args, String key, int def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return Integer.parseInt(args[i + 1]);
        }
        return def;
    }

    private static void deleteRecursively(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(x -> { try { Files.deleteIfExists(x); } catch (Exception ignore) {} });
        } catch (Exception ignore) {
        }
    }
}
