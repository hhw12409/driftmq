package io.driftmq.cli;

import io.driftmq.support.EmbeddedBroker;
import io.driftmq.test.Assert;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** CLI 명령의 정상/실패 경로와 exit code. 브로커는 인프로세스. */
public class CliTest {

    private EmbeddedBroker mq;
    private String addr;

    public void beforeEach() throws Exception {
        mq = EmbeddedBroker.start();
        addr = "localhost:" + mq.port();
    }

    public void afterEach() {
        if (mq != null) { mq.close(); mq.deleteData(); }
    }

    private record Run(int code, String out, String err) {}

    private Run cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out, oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int code = Main.run(args);
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    public void testTopicCreateListDescribe() {
        Run create = cli("topic", "create", "orders", "--addr", addr);
        Assert.assertEquals(Main.EXIT_OK, create.code(), "create exit");
        Assert.assertTrue(create.out().contains("created topic 'orders'"), "create 출력");

        Run list = cli("topic", "list", "--addr", addr);
        Assert.assertEquals(Main.EXIT_OK, list.code(), "list exit");
        Assert.assertTrue(list.out().contains("orders"), "list 에 orders");

        Run json = cli("topic", "list", "--addr", addr, "--json");
        Assert.assertTrue(json.out().trim().startsWith("[{\"topic\":\"orders\""), "json 출력: " + json.out());

        Run describe = cli("topic", "describe", "orders", "--addr", addr);
        Assert.assertEquals(Main.EXIT_OK, describe.code(), "describe exit");
        Assert.assertTrue(describe.out().contains("messages:     0"), "describe 출력");
    }

    public void testDuplicateTopicExit1() {
        cli("topic", "create", "dup", "--addr", addr);
        Run dup = cli("topic", "create", "dup", "--addr", addr);
        Assert.assertEquals(Main.EXIT_USER_ERROR, dup.code(), "중복 생성은 exit 1");
        Assert.assertTrue(dup.err().contains("TOPIC_ALREADY_EXISTS"), "에러 메시지");
    }

    public void testConnectionFailureExit2() {
        Run r = cli("topic", "list", "--addr", "localhost:1");
        Assert.assertEquals(Main.EXIT_CONN_FAILURE, r.code(), "연결 실패는 exit 2");
        Assert.assertTrue(r.err().contains("is it running?"), "실행 가능한 에러 메시지");
    }

    public void testMissingTopicNameExit1() {
        Run r = cli("topic", "create", "--addr", addr);
        Assert.assertEquals(Main.EXIT_USER_ERROR, r.code(), "이름 누락은 exit 1");
        Assert.assertTrue(r.err().contains("missing topic name"), "에러 메시지");
    }

    public void testUnknownCommandExit1() {
        Run r = cli("frobnicate");
        Assert.assertEquals(Main.EXIT_USER_ERROR, r.code(), "알 수 없는 명령");
        Assert.assertTrue(r.err().contains("unknown command"), "에러 메시지");
    }

    public void testHelpExit0() {
        Run r = cli("help");
        Assert.assertEquals(Main.EXIT_OK, r.code(), "help exit 0");
        Assert.assertTrue(r.out().contains("driftmq start"), "usage 출력");
    }

    public void testBadAddrExit1() {
        Run r = cli("topic", "list", "--addr", "not-an-addr");
        Assert.assertEquals(Main.EXIT_USER_ERROR, r.code(), "잘못된 addr");
    }
}
