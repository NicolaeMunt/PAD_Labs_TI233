package com.pad.broker.store;

import com.pad.broker.grpc.Delivery;

/** A message as kept in a topic's history. */
public record StoredMessage(
        String messageId,
        String publisherId,
        String topic,
        String payload,
        String timestamp,
        long sequence
) {

    public Delivery toDelivery(boolean replayed) {
        return Delivery.newBuilder()
                .setMessageId(messageId)
                .setPublisherId(publisherId)
                .setTopic(topic)
                .setPayload(payload)
                .setTimestamp(timestamp)
                .setSequence(sequence)
                .setReplayed(replayed)
                .build();
    }
}
