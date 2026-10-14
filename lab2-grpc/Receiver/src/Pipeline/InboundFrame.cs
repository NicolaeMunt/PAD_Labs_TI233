using MessageBroker.Receiver.Grpc;

namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// A message as read off the gRPC stream, queued for the workers.
/// </summary>
/// <param name="Delivery">The protobuf message sent by the broker.</param>
/// <param name="StreamId">Which Subscribe call it arrived on (for logs).</param>
/// <param name="ReceivedAt">When the reader loop took it off the stream.</param>
public sealed record InboundFrame(Delivery Delivery, long StreamId, DateTimeOffset ReceivedAt);
