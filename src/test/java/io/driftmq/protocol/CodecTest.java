package io.driftmq.protocol;

import io.driftmq.common.Header;
import io.driftmq.test.Assert;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 라운드트립 불변식 {@code decode(encode(m)).equals(m)} + 부분 데이터 처리 검증. */
public class CodecTest {

    private static WireMessage roundtrip(WireMessage in) {
        byte[] frame = Codec.encodeFrame(in);
        ByteBuffer buf = ByteBuffer.wrap(frame);
        WireMessage out = Codec.decodeFrame(buf);
        Assert.assertEquals(0, buf.remaining(), "decode 후 버퍼가 정확히 소진되어야 함");
        Assert.assertEquals(in, out, "라운드트립: " + in);
        return out;
    }

    private static List<Header> headers() {
        return List.of(
                new Header("content-type", "application/json".getBytes(StandardCharsets.UTF_8)),
                new Header("trace-id", new byte[]{0, 1, 2, 3, (byte) 0xFF}),
                new Header("empty", new byte[0]));
    }

    public void testPublishRoundtrip() {
        roundtrip(new WireMessage.Publish(42, "orders", headers(),
                "{\"orderId\":123}".getBytes(StandardCharsets.UTF_8)));
    }

    public void testPublishEmptyPayloadNoHeaders() {
        roundtrip(new WireMessage.Publish(0, "t", List.of(), new byte[0]));
    }

    public void testFetchRoundtrip() {
        roundtrip(new WireMessage.Fetch(7, "orders", "consumer-A", 100));
        roundtrip(new WireMessage.Fetch(Integer.MAX_VALUE, "orders", "c", 0));
    }

    public void testAckRoundtrip() {
        roundtrip(new WireMessage.Ack(9, "orders", "consumer-A", 1024L));
        roundtrip(new WireMessage.Ack(9, "orders", "consumer-A", 9_000_000_000L)); // > int
    }

    public void testTopicRequestsRoundtrip() {
        roundtrip(new WireMessage.TopicCreate(1, "orders"));
        roundtrip(new WireMessage.TopicList(2));
        roundtrip(new WireMessage.TopicDescribe(3, "orders"));
    }

    public void testPublishOkRoundtrip() {
        roundtrip(new WireMessage.PublishOk(5, 0L));
        roundtrip(new WireMessage.PublishOk(5, 12_345_678_901L));
    }

    public void testFetchOkRoundtrip() {
        var msgs = List.of(
                new WireMessage.FetchedMessage(0, 1_700_000_000_000L, headers(),
                        "m0".getBytes(StandardCharsets.UTF_8)),
                new WireMessage.FetchedMessage(1, 1_700_000_000_001L, List.of(),
                        new byte[]{9, 8, 7}));
        roundtrip(new WireMessage.FetchOk(11, msgs));
    }

    public void testFetchOkEmpty() {
        roundtrip(new WireMessage.FetchOk(11, List.of()));
    }

    public void testAckOkAndTopicCreateOk() {
        roundtrip(new WireMessage.AckOk(3));
        roundtrip(new WireMessage.TopicCreateOk(4));
    }

    public void testTopicListOkRoundtrip() {
        roundtrip(new WireMessage.TopicListOk(6, List.of(
                new WireMessage.TopicInfo("orders", 100),
                new WireMessage.TopicInfo("payments", 0))));
        roundtrip(new WireMessage.TopicListOk(6, List.of()));
    }

    public void testTopicDescribeOkRoundtrip() {
        roundtrip(new WireMessage.TopicDescribeOk(8, "orders", 100, 100, List.of(
                new WireMessage.ConsumerPosition("consumer-A", 50),
                new WireMessage.ConsumerPosition("consumer-B", 0))));
    }

    public void testErrorRoundtrip() {
        roundtrip(WireMessage.Error.of(2, ErrorCode.UNKNOWN_TOPIC, "no such topic: ghost"));
    }

    public void testPartialFrameReturnsNull() {
        byte[] frame = Codec.encodeFrame(new WireMessage.Publish(1, "orders", headers(),
                "hello world".getBytes(StandardCharsets.UTF_8)));
        for (int cut = 1; cut < frame.length; cut++) {
            ByteBuffer partial = ByteBuffer.allocate(cut);
            partial.put(frame, 0, cut).flip();
            WireMessage out = Codec.decodeFrame(partial);
            Assert.assertNull(out, "불완전 프레임(" + cut + "/" + frame.length + "B)은 null 이어야 함");
            Assert.assertEquals(0, partial.position(), "null 반환 시 position 원복되어야 함");
        }
        // 완전한 프레임 + 다음 프레임 일부가 뒤에 붙은 경우
        byte[] two = new byte[frame.length + 3];
        System.arraycopy(frame, 0, two, 0, frame.length);
        ByteBuffer buf = ByteBuffer.wrap(two);
        Assert.assertNotNull(Codec.decodeFrame(buf), "첫 프레임은 파싱되어야 함");
        Assert.assertEquals(3, buf.remaining(), "두 번째 프레임 조각은 버퍼에 남아야 함");
        Assert.assertNull(Codec.decodeFrame(buf), "남은 조각은 아직 null");
    }

    public void testTwoFramesBackToBack() {
        byte[] a = Codec.encodeFrame(new WireMessage.Ack(1, "t", "c", 10));
        byte[] b = Codec.encodeFrame(new WireMessage.PublishOk(2, 11));
        ByteBuffer buf = ByteBuffer.allocate(a.length + b.length);
        buf.put(a).put(b).flip();
        Assert.assertEquals(new WireMessage.Ack(1, "t", "c", 10), Codec.decodeFrame(buf), "프레임 1");
        Assert.assertEquals(new WireMessage.PublishOk(2, 11), Codec.decodeFrame(buf), "프레임 2");
        Assert.assertEquals(0, buf.remaining(), "정확히 소진");
    }

    public void testUnknownTypeThrows() {
        // version=1, type=99, cid=0, frameLen=6
        byte[] bad = ByteBuffer.allocate(10)
                .putInt(6).put((byte) 1).put((byte) 99).putInt(0).array();
        ProtocolException e = Assert.assertThrows(ProtocolException.class,
                () -> Codec.decodeFrame(ByteBuffer.wrap(bad)), "알 수 없는 type");
        Assert.assertEquals(ErrorCode.UNKNOWN_REQUEST_TYPE, e.errorCode(), "에러 코드");
    }

    public void testBadVersionThrows() {
        byte[] bad = ByteBuffer.allocate(10)
                .putInt(6).put((byte) 2).put((byte) 1).putInt(0).array();
        ProtocolException e = Assert.assertThrows(ProtocolException.class,
                () -> Codec.decodeFrame(ByteBuffer.wrap(bad)), "잘못된 버전");
        Assert.assertEquals(ErrorCode.MALFORMED_FRAME, e.errorCode(), "에러 코드");
    }

    public void testOversizedFrameThrows() {
        byte[] bad = ByteBuffer.allocate(8).putInt(Protocol.MAX_FRAME_BYTES + 1).putInt(0).array();
        Assert.assertThrows(ProtocolException.class,
                () -> Codec.decodeFrame(ByteBuffer.wrap(bad)), "프레임 크기 초과");
    }

    public void testTruncatedBodyThrows() {
        // frameLen=100 이라고 주장하지만 실제로는 body 10바이트만
        byte[] bad = new byte[4 + 10];
        ByteBuffer.wrap(bad).putInt(100);
        ByteBuffer buf = ByteBuffer.wrap(bad);
        Assert.assertNull(Codec.decodeFrame(buf), "frameLen 만큼 안 왔으면 null (부분 프레임)");
    }
}
