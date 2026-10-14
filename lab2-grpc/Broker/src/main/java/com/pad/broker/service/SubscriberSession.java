package com.pad.broker.service;

import com.pad.broker.grpc.SubscriberEvent;
import com.pad.broker.grpc.Subscribed;
import com.pad.broker.store.StoredMessage;
import com.pad.broker.store.TopicListener;
import com.pad.broker.store.TopicLog;
import com.pad.broker.util.BrokerLogger;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One connected subscriber: the server side of one {@code Subscribe} call.
 *
 * <p>A gRPC response stream must not be written from two threads at once, while messages arrive
 * from any publisher's thread. Every write therefore goes through a single-thread executor, which
 * also keeps the order: Subscribed, then the history, then live messages.
 *
 * <p>Live messages that arrive while the history is still being collected are held back in
 * {@code early} and sent right after the history.
 */
public class SubscriberSession implements TopicListener {

    private final String subscriberId;
    private final List<String> topics;
    private final ServerCallStreamObserver<SubscriberEvent> stream;
    private final ExecutorService writer;
    private final Set<String> unacked = ConcurrentHashMap.newKeySet();
    private final List<TopicLog> attached = new CopyOnWriteArrayList<>();
    private final List<StoredMessage> early = new ArrayList<>();
    private boolean started;
    private boolean closed;
    private volatile boolean cancelled;

    public SubscriberSession(String subscriberId, List<String> topics, ServerCallStreamObserver<SubscriberEvent> stream) {
        this.subscriberId = subscriberId;
        this.topics = List.copyOf(topics);
        this.stream = stream;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subscriber-" + subscriberId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public String subscriberId() {
        return subscriberId;
    }

    public List<String> topics() {
        return topics;
    }

    /**
     * Attaches to a topic and returns its history; live messages are held until {@link #start}.
     * Not synchronized: the topic's lock is taken here, and {@link TopicLog#append} takes the topic
     * lock before this session's lock, so holding both in the other order could deadlock.
     */
    public List<StoredMessage> attach(TopicLog log) {
        attached.add(log);
        List<StoredMessage> history = log.attach(this);
        if (isClosed()) {
            // Closed (e.g. replaced) while attaching: close() may have missed this log.
            log.detach(this);
        }
        return history;
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    /** Sends Subscribed, then {@code history} (marked replayed), then live messages that arrived meanwhile. */
    public synchronized void start(List<StoredMessage> history) {
        if (closed) {
            return;
        }
        write(SubscriberEvent.newBuilder()
                .setSubscribed(Subscribed.newBuilder().addAllTopics(topics).setHistoryCount(history.size()))
                .build());
        for (StoredMessage message : history) {
            deliver(message, true);
        }
        for (StoredMessage message : early) {
            deliver(message, false);
        }
        early.clear();
        started = true;
    }

    @Override
    public synchronized void onMessage(StoredMessage message) {
        if (closed) {
            return;
        }
        if (!started) {
            early.add(message);
            return;
        }
        deliver(message, false);
    }

    /** Returns false when the message was not delivered on this stream or was already acked. */
    public boolean ack(String messageId) {
        return unacked.remove(messageId);
    }

    public int unackedCount() {
        return unacked.size();
    }

    /** The client went away: stop writing and detach from the topics. */
    public void cancel() {
        cancelled = true;
        close(null);
    }

    /**
     * Detaches from the topics and ends the stream after everything already queued is written:
     * with {@code status} as an error, or normally when it is null.
     */
    public void close(Status status) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        // Outside this session's lock, for the same reason as in attach().
        for (TopicLog log : attached) {
            log.detach(this);
        }
        writer.execute(() -> {
            if (cancelled) {
                return;
            }
            try {
                if (status == null) {
                    stream.onCompleted();
                } else {
                    stream.onError(status.asRuntimeException());
                }
            } catch (RuntimeException ignored) {
                // The call already ended.
            }
        });
        writer.shutdown();
    }

    private void deliver(StoredMessage message, boolean replayed) {
        unacked.add(message.messageId());
        write(SubscriberEvent.newBuilder().setMessage(message.toDelivery(replayed)).build());
    }

    private void write(SubscriberEvent event) {
        writer.execute(() -> {
            if (cancelled) {
                return;
            }
            try {
                stream.onNext(event);
            } catch (RuntimeException e) {
                BrokerLogger.log("Write to subscriber failed", "subscriberId=" + subscriberId + " error=" + e.getMessage());
            }
        });
    }
}
