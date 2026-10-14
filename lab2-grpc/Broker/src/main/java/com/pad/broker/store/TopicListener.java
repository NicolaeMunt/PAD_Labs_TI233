package com.pad.broker.store;

/** Receives messages published to a topic after it attached. Called under the topic's lock: must not block. */
public interface TopicListener {

    void onMessage(StoredMessage message);
}
