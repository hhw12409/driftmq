package io.driftmq.broker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCP 수용 루프. 연결마다 가상 스레드로 {@link ConnectionHandler} 를 돌린다
 * ("연결 1개 = 스레드 1개" — 가장 단순한 모델, Virtual Thread 로 수천 연결까지).
 */
public final class Server implements AutoCloseable {

    private final Broker broker;
    private final int requestedPort;
    private final Set<Socket> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public Server(Broker broker, int port) {
        this.broker = broker;
        this.requestedPort = port;
    }

    /** 바인드하고 수용 루프를 시작한다. 반환 시 {@link #port()} 가 유효하다. */
    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(requestedPort));
        running = true;
        acceptThread = Thread.ofPlatform().name("driftmq-accept").daemon(false).start(this::acceptLoop);
    }

    public int port() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) System.err.println("[server] accept failed: " + e);
                return;
            }
            connections.add(socket);
            Thread.ofVirtual().name("driftmq-conn-", socket.getPort()).start(() -> {
                try {
                    new ConnectionHandler(socket, broker).run();
                } finally {
                    connections.remove(socket);
                }
            });
        }
    }

    /** 수용 중단 + 열린 연결 강제 종료. 진행 중이던 append 의 fsync 는 이미 끝난 상태다. */
    @Override
    public synchronized void close() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignore) {
        }
        for (Socket s : connections) {
            try { s.close(); } catch (IOException ignore) {}
        }
        if (acceptThread != null) {
            try { acceptThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
