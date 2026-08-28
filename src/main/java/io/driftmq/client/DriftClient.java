package io.driftmq.client;

import io.driftmq.protocol.Codec;
import io.driftmq.protocol.Protocol;
import io.driftmq.protocol.WireMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 브로커로의 단일 TCP 연결. 요청/응답은 순차(한 번에 하나). 스레드 세이프 —
 * {@link #call(WireMessage)} 이 {@code synchronized}.
 *
 * <p>공유 {@link Codec} 를 그대로 사용한다 — 서버와 동일한 인코딩/디코딩.
 */
public final class DriftClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final AtomicInteger correlation = new AtomicInteger(1);

    private DriftClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** {@code host:port} 로 연결. 실패 시 {@link IOException} (CLI 는 exit code 2 로 매핑). */
    public static DriftClient connect(String host, int port, int timeoutMillis) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMillis);
        s.setTcpNoDelay(true);
        return new DriftClient(s);
    }

    public static DriftClient connect(String host, int port) throws IOException {
        return connect(host, port, 5000);
    }

    int nextCorrelationId() {
        return correlation.getAndIncrement();
    }

    /** 요청을 보내고 응답 프레임을 받는다. ERROR 응답도 그대로 반환한다 (호출측이 판단). */
    public synchronized WireMessage call(WireMessage request) {
        try {
            out.write(Codec.encodeFrame(request));
            out.flush();
            int len = in.readInt();
            if (len < 0 || len > Protocol.MAX_FRAME_BYTES) {
                throw new ClientException("bad response frame length: " + len);
            }
            byte[] body = new byte[len];
            in.readFully(body);
            WireMessage resp = Codec.decodeBody(body);
            if (resp.correlationId() != request.correlationId()) {
                throw new ClientException("correlationId mismatch: sent " + request.correlationId()
                        + " got " + resp.correlationId());
            }
            return resp;
        } catch (IOException e) {
            throw new ClientException("connection failed: " + e.getMessage());
        }
    }

    /** ERROR 응답이면 {@link ClientException} 을 던지고, 아니면 그대로 반환한다. */
    public WireMessage callOrThrow(WireMessage request) {
        WireMessage resp = call(request);
        if (resp instanceof WireMessage.Error err) {
            throw new ClientException(err.code(), err.message());
        }
        return resp;
    }

    // ─────────────────────────── admin ───────────────────────────

    public void createTopic(String topic) {
        callOrThrow(new WireMessage.TopicCreate(nextCorrelationId(), topic));
    }

    public List<WireMessage.TopicInfo> listTopics() {
        WireMessage.TopicListOk ok = (WireMessage.TopicListOk)
                callOrThrow(new WireMessage.TopicList(nextCorrelationId()));
        return ok.topics();
    }

    public WireMessage.TopicDescribeOk describeTopic(String topic) {
        return (WireMessage.TopicDescribeOk)
                callOrThrow(new WireMessage.TopicDescribe(nextCorrelationId(), topic));
    }

    public Producer newProducer() {
        return new Producer(this);
    }

    public Consumer newConsumer(String topic, String consumerId) {
        return new Consumer(this, topic, consumerId);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignore) {
        }
    }
}
