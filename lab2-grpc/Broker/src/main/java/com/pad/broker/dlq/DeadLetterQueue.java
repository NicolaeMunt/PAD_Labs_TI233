package com.pad.broker.dlq;

import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.pad.broker.util.BrokerLogger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Keeps rejected frames instead of dropping them silently. Bounded: only the most recent
 * {@code capacity} entries are kept, so a misbehaving client cannot exhaust memory.
 */
public class DeadLetterQueue {

    public record Entry(String frame, String reason, Instant rejectedAt) {
    }

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public DeadLetterQueue(int capacity) {
        this.capacity = capacity;
    }

    public void add(Message frame, String reason) {
        String text = TextFormat.shortDebugString(frame);
        synchronized (entries) {
            entries.addLast(new Entry(text, reason, Instant.now()));
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
        BrokerLogger.log("[DLQ] Message rejected", "reason=" + reason + " frame={" + text + "}");
    }

    public List<Entry> getAll() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }
}
