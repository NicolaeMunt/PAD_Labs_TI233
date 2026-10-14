package com.pad.broker.validation;

import com.pad.broker.grpc.PublishMessage;
import com.pad.broker.grpc.Register;
import com.pad.broker.grpc.Subscribe;

import java.util.regex.Pattern;

/**
 * Side-effect-free checks of incoming frames. Returns {@code null} when a frame is valid,
 * otherwise the reason it is rejected. Business rules (topic ownership) live in the registries.
 */
public class MessageValidator {

    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    public String validateRegister(Register register) {
        if (!NAME.matcher(register.getPublisherId()).matches()) {
            return "publisher_id must be 1-64 letters, digits, '.', '_' or '-'";
        }
        if (!NAME.matcher(register.getTopic()).matches()) {
            return "topic must be 1-64 letters, digits, '.', '_' or '-'";
        }
        return null;
    }

    /** @param registeredTopic the topic the publisher registered for on this stream, or null */
    public String validatePublish(PublishMessage message, String registeredTopic) {
        if (registeredTopic == null) {
            return "publisher is not registered; send Register first";
        }
        if (message.getMessageId().isBlank()) {
            return "message_id is required";
        }
        if (!registeredTopic.equals(message.getTopic())) {
            return "publisher is registered for topic '" + registeredTopic + "', not '" + message.getTopic() + "'";
        }
        if (message.getPayload().isEmpty()) {
            return "payload is required";
        }
        if (message.getPayload().length() > MAX_PAYLOAD_CHARS) {
            return "payload is longer than " + MAX_PAYLOAD_CHARS + " characters";
        }
        if (message.getTimestamp().isBlank()) {
            return "timestamp is required";
        }
        return null;
    }

    public String validateSubscribe(Subscribe subscribe) {
        if (!NAME.matcher(subscribe.getSubscriberId()).matches()) {
            return "subscriber_id must be 1-64 letters, digits, '.', '_' or '-'";
        }
        if (subscribe.getTopicsCount() == 0) {
            return "at least one topic is required";
        }
        for (String topic : subscribe.getTopicsList()) {
            if (!NAME.matcher(topic).matches()) {
                return "invalid topic '" + topic + "'";
            }
        }
        return null;
    }
}
