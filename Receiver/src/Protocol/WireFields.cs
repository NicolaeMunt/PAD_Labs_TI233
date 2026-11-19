namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// JSON property names of the envelope. Names are case-sensitive on the wire.
/// </summary>
public static class WireFields
{
    /// <summary>Message type: SUBSCRIBE, ACK, MESSAGE or ERROR.</summary>
    public const string Type = "type";

    /// <summary>Topic name.</summary>
    public const string Topic = "topic";

    /// <summary>Client id of the frame's author.</summary>
    public const string ClientId = "clientId";

    /// <summary>String payload.</summary>
    public const string Payload = "payload";

    /// <summary>ISO-8601 timestamp.</summary>
    public const string Timestamp = "timestamp";

    /// <summary>Optional unique message id (preferred name).</summary>
    public const string MessageId = "messageId";

    /// <summary>Optional unique message id (accepted fallback name on inbound frames).</summary>
    public const string Id = "id";
}
