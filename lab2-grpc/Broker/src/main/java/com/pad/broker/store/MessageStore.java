package com.pad.broker.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory persistence: one {@link TopicLog} per topic, created on first use (by a publish or a
 * subscribe, so a subscriber may arrive before its topic's publisher). Each log keeps the last
 * {@code historyLimit} messages. Everything is lost when the broker stops.
 */
public class MessageStore {

    private final int historyLimit;
    private final Map<String, TopicLog> logs = new ConcurrentHashMap<>();

    public MessageStore(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public TopicLog topic(String topic) {
        return logs.computeIfAbsent(topic, name -> new TopicLog(name, historyLimit));
    }

    public int historyLimit() {
        return historyLimit;
    }
}
