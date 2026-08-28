package io.driftmq.broker;

import io.driftmq.protocol.Codec;
import io.driftmq.protocol.ErrorCode;
import io.driftmq.protocol.Protocol;
import io.driftmq.protocol.ProtocolException;
import io.driftmq.protocol.WireMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 한 클라이언트 연결을 순차 처리한다 (연결당 가상 스레드 1개).
 * 프레이밍: {@code [frameLen u32 BE][body]}. body 는 {@link Codec#decodeBody(byte[])} 로 파싱.
 * 요청/응답은 파이프라이닝 없이 1:1.
 */
final class ConnectionHandler implements Runnable {

    private final Socket socket;
    private final Broker broker;
    /** 이 연결에서 FETCH 한 (topic, consumerId) — 연결 종료 시 미ACK 를 재전달 대상으로 되돌린다. */
    private final Set<String> fetchedConsumers = new LinkedHashSet<>();

    ConnectionHandler(Socket socket, Broker broker) {
        this.socket = socket;
        this.broker = broker;
    }

    @Override
    public void run() {
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            while (true) {
                int frameLen;
                try {
                    frameLen = in.readInt();
                } catch (EOFException eof) {
                    return; // 클라이언트가 정상적으로 연결 종료
                }
                if (frameLen < 0 || frameLen > Protocol.MAX_FRAME_BYTES) {
                    writeFrame(out, WireMessage.Error.of(0, ErrorCode.MALFORMED_FRAME,
                            "frame length out of range: " + frameLen));
                    return; // 스트림 동기화 불가 — 연결 종료
                }
                byte[] body = new byte[frameLen];
                in.readFully(body);

                WireMessage response;
                try {
                    WireMessage request = Codec.decodeBody(body);
                    if (request instanceof WireMessage.Fetch f) {
                        fetchedConsumers.add(f.topic() + '\0' + f.consumerId());
                    }
                    response = broker.handle(request);
                } catch (ProtocolException e) {
                    int cid = Codec.peekCorrelationId(body);
                    response = WireMessage.Error.of(cid, e.errorCode(), e.getMessage());
                    if (e.errorCode() == ErrorCode.MALFORMED_FRAME) {
                        writeFrame(out, response);
                        return; // 프레임 자체가 깨졌으면 이후 스트림 신뢰 불가
                    }
                }
                writeFrame(out, response);
            }
        } catch (IOException e) {
            // 연결 끊김 등 — 조용히 종료.
        } finally {
            // 이 연결이 담당하던 consumer 의 미ACK 메시지를 즉시 재전달 대상으로 되돌린다.
            for (String key : fetchedConsumers) {
                int sep = key.indexOf('\0');
                broker.onConsumerDisconnect(key.substring(0, sep), key.substring(sep + 1));
            }
        }
    }

    private static void writeFrame(OutputStream out, WireMessage msg) throws IOException {
        out.write(Codec.encodeFrame(msg));
        out.flush();
    }
}
