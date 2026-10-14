package com.pad.broker.service;

import io.grpc.Status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most one live session per subscriber id. A receiver that reconnects before the broker noticed
 * its old connection died (e.g. after a network drop) replaces the stale session.
 */
public class SubscriberRegistry {

    private final Map<String, SubscriberSession> sessions = new ConcurrentHashMap<>();

    /** Returns true when an older session with the same id was replaced. */
    public boolean register(SubscriberSession session) {
        SubscriberSession previous = sessions.put(session.subscriberId(), session);
        if (previous == null) {
            return false;
        }
        previous.close(Status.ABORTED.withDescription("Replaced by a newer connection with the same subscriber id"));
        return true;
    }

    /** Removes the session only if it is still the current one for its id. */
    public void remove(SubscriberSession session) {
        sessions.remove(session.subscriberId(), session);
    }
}
