package io.driftmq.example;

import io.driftmq.client.Consumer;
import io.driftmq.client.DriftClient;
import io.driftmq.client.Producer;

/**
 * README 5분 튜토리얼용 실행 예제.
 * <pre>java -cp driftmq.jar io.driftmq.example.Demo [host] [port]</pre>
 * 브로커가 떠 있어야 한다 ({@code driftmq start}).
 */
public final class Demo {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7644;
        String topic = "demo";

        try (DriftClient client = DriftClient.connect(host, port)) {
            try {
                client.createTopic(topic);
                System.out.println("created topic '" + topic + "'");
            } catch (RuntimeException e) {
                System.out.println("topic '" + topic + "' already exists — continuing");
            }

            Producer producer = client.newProducer();
            for (int i = 0; i < 10; i++) {
                long offset = producer.publish(topic, "hello-" + i).offset();
                System.out.println("published offset=" + offset);
            }

            Consumer consumer = client.newConsumer(topic, "demo-consumer");
            int got = 0;
            while (got < 10) {
                var batch = consumer.poll(10);
                for (var rec : batch) {
                    System.out.println("consumed #" + rec.offset() + ": " + rec.payloadAsString());
                    consumer.ack(rec.offset());
                    got++;
                }
                if (batch.isEmpty()) Thread.sleep(100);
            }
            System.out.println("done — consumed " + got + " messages");
        }
    }
}
