using MessageBroker.Receiver.Grpc;

namespace MessageBroker.Receiver.Handling;

/// <summary>
/// A message delivered by the broker, as passed to the handlers.
/// </summary>
/// <param name="MessageId">Publisher-assigned unique id; used for de-duplication and ACKs.</param>
/// <param name="PublisherId">Who published it.</param>
/// <param name="Topic">Topic it was published to.</param>
/// <param name="Payload">Message text.</param>
/// <param name="Timestamp">ISO-8601 time set by the publisher.</param>
/// <param name="Sequence">Position in the topic's history (1, 2, 3, …).</param>
/// <param name="Replayed">Whether it came from the topic's stored history rather than live.</param>
public sealed record ReceivedMessage(
    string MessageId,
    string PublisherId,
    string Topic,
    string Payload,
    string Timestamp,
    long Sequence,
    bool Replayed)
{
    /// <summary>Converts the generated protobuf type.</summary>
    public static ReceivedMessage From(Delivery delivery) => new(
        delivery.MessageId,
        delivery.PublisherId,
        delivery.Topic,
        delivery.Payload,
        delivery.Timestamp,
        delivery.Sequence,
        delivery.Replayed);

    /// <summary>A compact one-line description for logs.</summary>
    public string Describe() => $"message topic='{Topic}' from='{PublisherId}' seq={Sequence} id='{MessageId}'";
}
