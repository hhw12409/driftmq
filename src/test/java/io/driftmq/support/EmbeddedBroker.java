package io.driftmq.support;

import io.driftmq.broker.Broker;
import io.driftmq.broker.BrokerConfig;
import io.driftmq.broker.Server;
import io.driftmq.broker.StorageException;
import io.driftmq.client.DriftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 테스트용 인프로세스 브로커+서버. 임의(0) 포트에 바인드한다.
 * {@link #restart()} 는 실제 프로세스 재시작과 동일하게 디스크 상태만 남기고 새로 연다.
 */
public final class EmbeddedBroker implements AutoCloseable {

    private final Path dataDir;
    private final int ackTimeoutSeconds;
    private Broker broker;
    private Server server;

    private EmbeddedBroker(Path dataDir, int ackTimeoutSeconds) throws IOException, StorageException {
        this.dataDir = dataDir;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        open();
    }

    public static EmbeddedBroker start() throws IOException, StorageException {
        return new EmbeddedBroker(Files.createTempDirectory("driftmq-it-"), 30);
    }

    public static EmbeddedBroker start(int ackTimeoutSeconds) throws IOException, StorageException {
        return new EmbeddedBroker(Files.createTempDirectory("driftmq-it-"), ackTimeoutSeconds);
    }

    private void open() throws IOException, StorageException {
        BrokerConfig config = new BrokerConfig(dataDir, 0, ackTimeoutSeconds, "always");
        broker = new Broker(config);
        broker.start();
        server = new Server(broker, 0);
        server.start();
    }

    /** 프로세스 재시작 흉내: 서버·브로커를 닫고 같은 data-dir 로 다시 연다. 포트는 바뀔 수 있다. */
    public void restart() throws IOException, StorageException {
        close();
        open();
    }

    /** kill -9 흉내: 정상 close 없이 로그 파일 tail 에 쓰레기 바이트를 붙이고 재시작. */
    public void crashWithPartialWrite(String topic) throws IOException, StorageException {
        server.close();
        broker.close();
        Path log = dataDir.resolve(topic).resolve("00000000000000000000.log");
        try (var ch = Files.newByteChannel(log,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{0, 0, 2, 0, 1, 2, 3, 4, 5})); // recordLen 큰데 뒤 5바이트뿐
        }
        open();
    }

    public int port() {
        return server.port();
    }

    public Path dataDir() {
        return dataDir;
    }

    public Broker broker() {
        return broker;
    }

    public DriftClient connect() throws IOException {
        return DriftClient.connect("localhost", port());
    }

    @Override
    public void close() {
        if (server != null) server.close();
        if (broker != null) broker.close();
    }

    public void deleteData() {
        try (Stream<Path> walk = Files.walk(dataDir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
        } catch (IOException ignore) {
        }
    }
}
