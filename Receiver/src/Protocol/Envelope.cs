namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// The common envelope shared by every frame on the wire.
/// Fields that were absent in an inbound frame are <c>null</c>.
/// </summary>
public sealed record Envelope
{
    /// <summary>Parsed message type.</summary>
    public required MessageType Type { get; init; }

    /// <summary>The <c>type</c> string exactly as received (useful when logging unknown types).</summary>
    public string? RawType { get; init; }

    /// <summary>Topic the frame refers to.</summary>
    public string? Topic { get; init; }

    /// <summary>
    /// Client id. On outbound frames this is our own id; on MESSAGE frames it identifies the sender.
    /// </summary>
    public string? ClientId { get; init; }

    /// <summary>Application payload (always a string on the wire).</summary>
    public string? Payload { get; init; }

    /// <summary>ISO-8601 timestamp string as produced by the sender.</summary>
    public string? Timestamp { get; init; }

    /// <summary>
    /// Optional unique message id (wire field <c>messageId</c>, or <c>id</c> as a fallback).
    /// Used for de-duplication and echoed back in ACK frames.
    /// </summary>
    public string? MessageId { get; init; }

    /// <summary>Builds the SUBSCRIBE handshake frame for one topic.</summary>
    public static Envelope Subscribe(string topic, string clientId) => new()
    {
        Type = MessageType.Subscribe,
        Topic = topic,
        ClientId = clientId,
        Payload = string.Empty,
        Timestamp = NowIso(),
    };

    /// <summary>Builds an ACK frame confirming that <paramref name="message"/> was handled.</summary>
    public static Envelope AckFor(Envelope message, string clientId) => new()
    {
        Type = MessageType.Ack,
        Topic = message.Topic,
        ClientId = clientId,
        Payload = message.MessageId ?? string.Empty,
        Timestamp = NowIso(),
        MessageId = message.MessageId,
    };

    /// <summary>Current UTC time in round-trip ISO-8601 format, e.g. <c>2026-09-16T14:03:07.1234567Z</c>.</summary>
    public static string NowIso() => DateTimeOffset.UtcNow.ToString("O");

    /// <summary>A compact one-line description for logs.</summary>
    public string Describe() =>
        $"{RawType ?? Type.ToString()} topic='{Topic}' from='{ClientId}'" +
        (MessageId is null ? string.Empty : $" id='{MessageId}'");
}
