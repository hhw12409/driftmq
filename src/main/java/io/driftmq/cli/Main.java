package io.driftmq.cli;

import io.driftmq.broker.Broker;
import io.driftmq.broker.BrokerConfig;
import io.driftmq.broker.Server;
import io.driftmq.broker.StorageException;
import io.driftmq.client.ClientException;
import io.driftmq.client.DriftClient;
import io.driftmq.protocol.WireMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * DriftMQ CLI.
 * <pre>
 *   driftmq start [--data-dir DIR] [--port N] [--ack-timeout SEC] [--fsync always|batch:MS]
 *   driftmq topic create &lt;name&gt;  [--addr host:port]
 *   driftmq topic list           [--addr host:port] [--json]
 *   driftmq topic describe &lt;name&gt; [--addr host:port] [--json]
 * </pre>
 * exit code: 0 성공 · 1 사용자 오류 · 2 연결 실패.
 */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_USER_ERROR = 1;
    static final int EXIT_CONN_FAILURE = 2;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** 테스트에서 process 를 안 띄우고 호출할 수 있도록 exit code 를 반환한다 (start 제외). */
    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage(System.out);
            return args.length == 0 ? EXIT_USER_ERROR : EXIT_OK;
        }
        try {
            return switch (args[0]) {
                case "start" -> cmdStart(rest(args));
                case "topic" -> cmdTopic(rest(args));
                default -> {
                    System.err.println("unknown command: " + args[0]);
                    printUsage(System.err);
                    yield EXIT_USER_ERROR;
                }
            };
        } catch (UserError e) {
            System.err.println("error: " + e.getMessage());
            return EXIT_USER_ERROR;
        }
    }

    // ─────────────────────────── start ───────────────────────────

    private static int cmdStart(String[] args) {
        Args a = Args.parse(args);
        Path dataDir = Path.of(a.opt("data-dir", "./data"));
        int port = a.optInt("port", BrokerConfig.DEFAULT_PORT);
        int ackTimeout = a.optInt("ack-timeout", BrokerConfig.DEFAULT_ACK_TIMEOUT_SECONDS);
        String fsync = a.opt("fsync", BrokerConfig.DEFAULT_FSYNC_POLICY);

        BrokerConfig config;
        try {
            config = new BrokerConfig(dataDir, port, ackTimeout, fsync);
        } catch (IllegalArgumentException e) {
            throw new UserError(e.getMessage());
        }
        if (!config.effectiveFsyncPolicy().equals(fsync)) {
            System.err.println("warning: fsync policy '" + fsync
                    + "' not implemented in v0.1 — falling back to 'always'");
        }

        Broker broker;
        Server server;
        try {
            broker = new Broker(config);
            broker.start();
            server = new Server(broker, port);
            server.start();
        } catch (IOException | StorageException | RuntimeException e) {
            System.err.println("failed to start broker: " + e.getMessage());
            return EXIT_USER_ERROR;
        }

        System.out.printf("DriftMQ v0.1 listening on :%d  (data-dir=%s, ack-timeout=%ds, fsync=%s)%n",
                server.port(), dataDir.toAbsolutePath(), ackTimeout, config.effectiveFsyncPolicy());
        System.out.println("press Ctrl-C to stop");

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nshutting down...");
            server.close();
            broker.close();
            stop.countDown();
        }, "driftmq-shutdown"));
        try {
            stop.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return EXIT_OK;
    }

    // ─────────────────────────── topic ───────────────────────────

    private static int cmdTopic(String[] args) {
        if (args.length == 0) {
            throw new UserError("usage: driftmq topic <create|list|describe> ...");
        }
        String sub = args[0];
        Args a = Args.parse(rest(args));
        String[] addr = splitAddr(a.opt("addr", "localhost:" + BrokerConfig.DEFAULT_PORT));
        boolean json = a.flag("json");

        try (DriftClient client = DriftClient.connect(addr[0], Integer.parseInt(addr[1]))) {
            switch (sub) {
                case "create" -> {
                    String name = a.positionalOrThrow(0, "topic name");
                    client.createTopic(name);
                    System.out.println("created topic '" + name + "'");
                }
                case "list" -> {
                    List<WireMessage.TopicInfo> topics = client.listTopics();
                    if (json) {
                        System.out.println(topicsJson(topics));
                    } else if (topics.isEmpty()) {
                        System.out.println("(no topics)");
                    } else {
                        System.out.printf("%-32s %12s%n", "TOPIC", "MESSAGES");
                        for (WireMessage.TopicInfo t : topics) {
                            System.out.printf("%-32s %12d%n", t.topic(), t.messageCount());
                        }
                    }
                }
                case "describe" -> {
                    String name = a.positionalOrThrow(0, "topic name");
                    WireMessage.TopicDescribeOk d = client.describeTopic(name);
                    if (json) {
                        System.out.println(describeJson(d));
                    } else {
                        System.out.println("topic:        " + d.topic());
                        System.out.println("messages:     " + d.messageCount());
                        System.out.println("end offset:   " + d.endOffset());
                        System.out.println("consumers:    " + d.consumers().size());
                        for (WireMessage.ConsumerPosition c : d.consumers()) {
                            System.out.printf("  - %-24s position=%d%n", c.consumerId(), c.position());
                        }
                    }
                }
                default -> throw new UserError("unknown topic subcommand: " + sub);
            }
            return EXIT_OK;
        } catch (IOException e) {
            System.err.println("cannot connect to driftmq at " + addr[0] + ":" + addr[1]
                    + " — is it running? (driftmq start)");
            return EXIT_CONN_FAILURE;
        } catch (ClientException e) {
            System.err.println("error: " + e.getMessage());
            return EXIT_USER_ERROR;
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static String[] splitAddr(String addr) {
        int colon = addr.lastIndexOf(':');
        if (colon <= 0 || colon == addr.length() - 1) {
            throw new UserError("--addr must be host:port (got '" + addr + "')");
        }
        try {
            Integer.parseInt(addr.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new UserError("--addr port is not a number: " + addr);
        }
        return new String[]{addr.substring(0, colon), addr.substring(colon + 1)};
    }

    private static String topicsJson(List<WireMessage.TopicInfo> topics) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < topics.size(); i++) {
            WireMessage.TopicInfo t = topics.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"topic\":\"").append(esc(t.topic()))
              .append("\",\"messageCount\":").append(t.messageCount()).append('}');
        }
        return sb.append(']').toString();
    }

    private static String describeJson(WireMessage.TopicDescribeOk d) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"topic\":\"").append(esc(d.topic()))
          .append("\",\"messageCount\":").append(d.messageCount())
          .append(",\"endOffset\":").append(d.endOffset())
          .append(",\"consumers\":[");
        for (int i = 0; i < d.consumers().size(); i++) {
            WireMessage.ConsumerPosition c = d.consumers().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"consumerId\":\"").append(esc(c.consumerId()))
              .append("\",\"position\":").append(c.position()).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String[] rest(String[] args) {
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    private static void printUsage(java.io.PrintStream o) {
        o.println("""
            driftmq — lightweight message broker (v0.1)

            usage:
              driftmq start [--data-dir DIR] [--port N] [--ack-timeout SEC] [--fsync always|batch:MS]
              driftmq topic create <name>   [--addr host:port]
              driftmq topic list            [--addr host:port] [--json]
              driftmq topic describe <name> [--addr host:port] [--json]

            exit codes: 0 ok · 1 user error · 2 connection failure""");
    }

    /** 사용자 입력 오류 → exit 1. */
    static final class UserError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        UserError(String message) { super(message); }
    }

    /** 아주 작은 flag/option/positional 파서. {@code --key value}, {@code --flag}, 나머지는 positional. */
    static final class Args {
        private final Map<String, String> opts = new LinkedHashMap<>();
        private final List<String> positionals = new ArrayList<>();

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String s = args[i];
                if (s.startsWith("--")) {
                    String key = s.substring(2);
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        a.opts.put(key, args[++i]);
                    } else {
                        a.opts.put(key, "true"); // flag
                    }
                } else {
                    a.positionals.add(s);
                }
            }
            return a;
        }

        String opt(String key, String def) { return opts.getOrDefault(key, def); }

        int optInt(String key, int def) {
            String v = opts.get(key);
            if (v == null) return def;
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new UserError("--" + key + " must be an integer (got '" + v + "')");
            }
        }

        boolean flag(String key) { return "true".equals(opts.get(key)); }

        String positionalOrThrow(int idx, String what) {
            if (idx >= positionals.size()) throw new UserError("missing " + what);
            return positionals.get(idx);
        }
    }
}
