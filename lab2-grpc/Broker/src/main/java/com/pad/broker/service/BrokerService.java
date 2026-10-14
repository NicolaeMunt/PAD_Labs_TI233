package com.pad.broker.service;

import com.pad.broker.dlq.DeadLetterQueue;
import com.pad.broker.grpc.Ack;
import com.pad.broker.grpc.BrokerGrpc;
import com.pad.broker.grpc.PublishAck;
import com.pad.broker.grpc.PublishMessage;
import com.pad.broker.grpc.PublishRejected;
import com.pad.broker.grpc.PublisherFrame;
import com.pad.broker.grpc.PublisherReply;
import com.pad.broker.grpc.Register;
import com.pad.broker.grpc.Registered;
import com.pad.broker.grpc.Subscribe;
import com.pad.broker.grpc.SubscriberEvent;
import com.pad.broker.grpc.SubscriberFrame;
import com.pad.broker.store.MessageStore;
import com.pad.broker.store.StoredMessage;
import com.pad.broker.store.TopicLog;
import com.pad.broker.topic.PublisherRegistry;
import com.pad.broker.util.BrokerLogger;
import com.pad.broker.validation.MessageValidator;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the two streaming RPCs from pubsub.proto. gRPC calls the methods of one call's
 * request observer one at a time, so the per-call fields below need no locking.
 */
public class BrokerService extends BrokerGrpc.BrokerImplBase {

    private final MessageStore store;
    private final PublisherRegistry publishers;
    private final SubscriberRegistry subscribers;
    private final MessageValidator validator;
    private final DeadLetterQueue deadLetterQueue;

    public BrokerService(MessageStore store,
                         PublisherRegistry publishers,
                         SubscriberRegistry subscribers,
                         MessageValidator validator,
                         DeadLetterQueue deadLetterQueue) {
        this.store = store;
        this.publishers = publishers;
        this.subscribers = subscribers;
        this.validator = validator;
        this.deadLetterQueue = deadLetterQueue;
    }

    // ---------------------------------------------------------------- Publish

    @Override
    public StreamObserver<PublisherFrame> publish(StreamObserver<PublisherReply> responseObserver) {
        ServerCallStreamObserver<PublisherReply> replies = (ServerCallStreamObserver<PublisherReply>) responseObserver;
        PublisherCall call = new PublisherCall(replies);
        replies.setOnCancelHandler(call::onCancelled);
        return call;
    }

    private final class PublisherCall implements StreamObserver<PublisherFrame> {

        private final ServerCallStreamObserver<PublisherReply> replies;
        private String publisherId;
        private String topic;
        private boolean ended;
        private boolean disconnectLogged;

        PublisherCall(ServerCallStreamObserver<PublisherReply> replies) {
            this.replies = replies;
        }

        @Override
        public void onNext(PublisherFrame frame) {
            if (ended) {
                return;
            }
            switch (frame.getKindCase()) {
                case REGISTER -> register(frame, frame.getRegister());
                case MESSAGE -> publish(frame, frame.getMessage());
                default -> reject(frame, "", "frame has neither 'register' nor 'message' set");
            }
        }

        private void register(PublisherFrame frame, Register request) {
            String error = validator.validateRegister(request);
            if (error == null && publisherId != null
                    && !(publisherId.equals(request.getPublisherId()) && topic.equals(request.getTopic()))) {
                error = "this stream is already registered as '" + publisherId + "' for topic '" + topic + "'";
            }
            if (error != null) {
                deadLetterQueue.add(frame, error);
                end(Status.INVALID_ARGUMENT.withDescription(error));
                return;
            }

            error = publishers.register(request.getPublisherId(), request.getTopic());
            if (error != null) {
                deadLetterQueue.add(frame, error);
                BrokerLogger.log("Publisher registration rejected", "publisherId=" + request.getPublisherId() + " reason=" + error);
                end(Status.ALREADY_EXISTS.withDescription(error));
                return;
            }

            publisherId = request.getPublisherId();
            topic = request.getTopic();
            store.topic(topic);
            BrokerLogger.log("Publisher registered", "publisherId=" + publisherId + " topic=" + topic);
            send(PublisherReply.newBuilder()
                    .setRegistered(Registered.newBuilder()
                            .setDetail("Publisher '" + publisherId + "' registered for topic '" + topic + "'"))
                    .build());
        }

        private void publish(PublisherFrame frame, PublishMessage message) {
            String error = validator.validatePublish(message, topic);
            if (error != null) {
                reject(frame, message.getMessageId(), error);
                return;
            }

            TopicLog.AppendResult result = store.topic(topic)
                    .append(message.getMessageId(), publisherId, message.getPayload(), message.getTimestamp());
            StoredMessage stored = result.message();
            if (result.duplicate()) {
                BrokerLogger.log("Duplicate publish ignored", "publisherId=" + publisherId + " messageId=" + stored.messageId());
            }
            send(PublisherReply.newBuilder()
                    .setAck(PublishAck.newBuilder()
                            .setMessageId(stored.messageId())
                            .setSequence(stored.sequence())
                            .setDuplicate(result.duplicate()))
                    .build());
        }

        /** Invalid frames are answered (so the publisher's FIFO of pending replies stays aligned) and kept in the DLQ. */
        private void reject(PublisherFrame frame, String messageId, String reason) {
            deadLetterQueue.add(frame, reason);
            send(PublisherReply.newBuilder()
                    .setRejected(PublishRejected.newBuilder().setMessageId(messageId).setReason(reason))
                    .build());
        }

        private void send(PublisherReply reply) {
            try {
                replies.onNext(reply);
            } catch (RuntimeException e) {
                ended = true;
            }
        }

        private void end(Status status) {
            ended = true;
            try {
                replies.onError(status.asRuntimeException());
            } catch (RuntimeException ignored) {
                // The call already ended.
            }
        }

        void onCancelled() {
            ended = true;
            logDisconnect("connection lost");
        }

        @Override
        public void onError(Throwable t) {
            ended = true;
            logDisconnect(Status.fromThrowable(t).getCode().toString());
        }

        @Override
        public void onCompleted() {
            logDisconnect("closed by publisher");
            if (!ended) {
                ended = true;
                replies.onCompleted();
            }
        }

        /** A cancelled call reports both onCancelled and onError; log the disconnect once. */
        private void logDisconnect(String reason) {
            if (publisherId != null && !disconnectLogged) {
                disconnectLogged = true;
                BrokerLogger.log("Publisher disconnected", "publisherId=" + publisherId + " reason=" + reason);
            }
        }
    }

    // ---------------------------------------------------------------- Subscribe

    @Override
    public StreamObserver<SubscriberFrame> subscribe(StreamObserver<SubscriberEvent> responseObserver) {
        ServerCallStreamObserver<SubscriberEvent> events = (ServerCallStreamObserver<SubscriberEvent>) responseObserver;
        SubscriberCall call = new SubscriberCall(events);
        events.setOnCancelHandler(call::onCancelled);
        return call;
    }

    private final class SubscriberCall implements StreamObserver<SubscriberFrame> {

        private final ServerCallStreamObserver<SubscriberEvent> events;
        private SubscriberSession session;
        private boolean ended;
        private boolean disconnectLogged;

        SubscriberCall(ServerCallStreamObserver<SubscriberEvent> events) {
            this.events = events;
        }

        @Override
        public void onNext(SubscriberFrame frame) {
            if (ended) {
                return;
            }
            switch (frame.getKindCase()) {
                case SUBSCRIBE -> subscribe(frame, frame.getSubscribe());
                case ACK -> ack(frame, frame.getAck());
                default -> deadLetterQueue.add(frame, "frame has neither 'subscribe' nor 'ack' set");
            }
        }

        private void subscribe(SubscriberFrame frame, Subscribe request) {
            String error = session != null
                    ? "this stream is already subscribed; open a new stream to change topics"
                    : validator.validateSubscribe(request);
            if (error != null) {
                deadLetterQueue.add(frame, error);
                if (session == null) {
                    ended = true;
                    try {
                        events.onError(Status.INVALID_ARGUMENT.withDescription(error).asRuntimeException());
                    } catch (RuntimeException ignored) {
                        // The call already ended.
                    }
                }
                return;
            }

            List<String> topics = request.getTopicsList().stream().distinct().toList();
            session = new SubscriberSession(request.getSubscriberId(), topics, events);
            boolean replaced = subscribers.register(session);

            List<StoredMessage> history = new ArrayList<>();
            for (String topic : topics) {
                history.addAll(session.attach(store.topic(topic)));
            }
            BrokerLogger.log(replaced ? "Subscriber reconnected (old stream replaced)" : "Subscriber subscribed",
                    "subscriberId=" + session.subscriberId() + " topics=" + topics);
            if (!history.isEmpty()) {
                BrokerLogger.log("Replaying history", "subscriberId=" + session.subscriberId() + " count=" + history.size());
            }
            session.start(history);
        }

        private void ack(SubscriberFrame frame, Ack ack) {
            if (session == null) {
                deadLetterQueue.add(frame, "ack before subscribe");
                return;
            }
            if (session.ack(ack.getMessageId())) {
                BrokerLogger.log("Ack received", "subscriberId=" + session.subscriberId() + " messageId=" + ack.getMessageId());
            } else {
                BrokerLogger.log("Ack ignored (not pending on this stream)",
                        "subscriberId=" + session.subscriberId() + " messageId=" + ack.getMessageId());
            }
        }

        void onCancelled() {
            ended = true;
            if (session != null) {
                session.cancel();
                disconnected("connection lost");
            }
        }

        @Override
        public void onError(Throwable t) {
            ended = true;
            if (session != null) {
                session.cancel();
                disconnected(Status.fromThrowable(t).getCode().toString());
            }
        }

        @Override
        public void onCompleted() {
            ended = true;
            if (session != null) {
                session.close(null);
                disconnected("closed by subscriber");
            } else {
                events.onCompleted();
            }
        }

        /** A cancelled call reports both onCancelled and onError; handle the disconnect once. */
        private void disconnected(String reason) {
            if (disconnectLogged) {
                return;
            }
            disconnectLogged = true;
            subscribers.remove(session);
            BrokerLogger.log("Subscriber disconnected", "subscriberId=" + session.subscriberId()
                    + " reason=" + reason + " unacked=" + session.unackedCount());
        }
    }
}
