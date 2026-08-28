package io.driftmq.broker;

import io.driftmq.common.DriftException;

/** 이미 존재하는 topic 을 생성하려 할 때. wire: TOPIC_ALREADY_EXISTS. */
public class TopicExistsException extends DriftException {
    private static final long serialVersionUID = 1L;

    public TopicExistsException(String topic) {
        super("topic already exists: " + topic);
    }
}
