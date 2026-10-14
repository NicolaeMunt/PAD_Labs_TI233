package com.pad.broker.topic;

import java.util.HashMap;
import java.util.Map;

/**
 * Enforces one publisher per topic. A topic belongs to the first publisher that registers for it
 * and stays with it while the broker runs, so the same publisher can reconnect and register again.
 */
public class PublisherRegistry {

    private final Map<String, String> topicByPublisher = new HashMap<>();
    private final Map<String, String> publisherByTopic = new HashMap<>();

    /** Returns null on success, otherwise why the registration is refused. */
    public synchronized String register(String publisherId, String topic) {
        String ownTopic = topicByPublisher.get(publisherId);
        if (topic.equals(ownTopic)) {
            return null;
        }
        if (ownTopic != null) {
            return "Publisher '" + publisherId + "' is already registered for topic '" + ownTopic + "'";
        }
        if (publisherByTopic.containsKey(topic)) {
            return "Topic '" + topic + "' already has a registered publisher";
        }
        topicByPublisher.put(publisherId, topic);
        publisherByTopic.put(topic, publisherId);
        return null;
    }
}
