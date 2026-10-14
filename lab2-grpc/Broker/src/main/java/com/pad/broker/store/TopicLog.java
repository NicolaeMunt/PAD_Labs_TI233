package com.pad.broker.store;

import com.pad.broker.util.BrokerLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The in-memory history of one topic plus its live listeners.
 *
 * <p>One lock guards both, which is what makes replay exact: {@link #attach} copies the history
 * and adds the listener in one step, and {@link #append} stores a message and hands it to the
 * listeners in one step. A message is therefore either in the snapshot a subscriber receives or
 * delivered to it live, never both and never neither.
 */
public class TopicLog {

    public record AppendResult(StoredMessage message, boolean duplicate) {
    }

    private final String topic;
    private final int historyLimit;
    private final Deque<StoredMessage> history = new ArrayDeque<>();
    private final Map<String, StoredMessage> byId = new HashMap<>();
    private final Set<TopicListener> listeners = new LinkedHashSet<>();
    private long nextSequence = 1;

    public TopicLog(String topic, int historyLimit) {
        this.topic = topic;
        this.historyLimit = historyLimit;
    }

    /**
     * Stores a message and fans it out. A message id that is still in the history is not stored
     * again; the publisher is resending after a reconnect.
     */
    public synchronized AppendResult append(String messageId, String publisherId, String payload, String timestamp) {
        StoredMessage existing = byId.get(messageId);
        if (existing != null) {
            return new AppendResult(existing, true);
        }

        StoredMessage message = new StoredMessage(messageId, publisherId, topic, payload, timestamp, nextSequence++);
        history.addLast(message);
        byId.put(messageId, message);
        while (history.size() > historyLimit) {
            byId.remove(history.removeFirst().messageId());
        }

        // Logged before the fan-out, so it never appears after the subscribers' ACKs.
        BrokerLogger.log("Message stored", "topic=" + topic + " seq=" + message.sequence()
                + " publisherId=" + publisherId + " messageId=" + messageId + " subscribers=" + listeners.size());
        for (TopicListener listener : listeners) {
            listener.onMessage(message);
        }
        return new AppendResult(message, false);
    }

    /** Registers a live listener and returns the topic's stored history, oldest first. */
    public synchronized List<StoredMessage> attach(TopicListener listener) {
        listeners.add(listener);
        return new ArrayList<>(history);
    }

    public synchronized void detach(TopicListener listener) {
        listeners.remove(listener);
    }
}
