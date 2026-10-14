package com.pad.broker;

import com.pad.broker.dlq.DeadLetterQueue;
import com.pad.broker.service.BrokerService;
import com.pad.broker.service.SubscriberRegistry;
import com.pad.broker.store.MessageStore;
import com.pad.broker.topic.PublisherRegistry;
import com.pad.broker.util.BrokerLogger;
import com.pad.broker.validation.MessageValidator;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Composition root: wires the collaborators together and starts the gRPC server.
 * Usage: {@code java -jar broker.jar [port] [historyLimit]} (defaults 5000 and 1000; the
 * {@code HISTORY_LIMIT} environment variable also sets the limit).
 */
public class Main {

    private static final int DEFAULT_PORT = 5000;
    private static final int DEFAULT_HISTORY_LIMIT = 1000;
    private static final int DEAD_LETTER_CAPACITY = 1000;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String limitSetting = args.length > 1 ? args[1] : System.getenv("HISTORY_LIMIT");
        int historyLimit = limitSetting == null || limitSetting.isBlank() ? DEFAULT_HISTORY_LIMIT : Integer.parseInt(limitSetting.trim());
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be at least 1");
        }

        MessageStore store = new MessageStore(historyLimit);
        BrokerService service = new BrokerService(
                store,
                new PublisherRegistry(),
                new SubscriberRegistry(),
                new MessageValidator(),
                new DeadLetterQueue(DEAD_LETTER_CAPACITY));

        Server server = NettyServerBuilder.forPort(port)
                .addService(service)
                // Ping idle connections so a receiver that vanished (sleep, cable pulled) is
                // noticed and its session closed; and allow the clients' own keepalive pings.
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .permitKeepAliveTime(10, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .build()
                .start();

        BrokerLogger.log("Broker started", "gRPC port=" + port + " historyLimit=" + historyLimit + " per topic");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            BrokerLogger.log("Broker stopping", "");
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.shutdownNow();
        }));

        server.awaitTermination();
    }
}
