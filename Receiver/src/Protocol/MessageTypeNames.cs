namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// Maps <see cref="MessageType"/> values to and from their wire representation.
/// </summary>
public static class MessageTypeNames
{
    /// <summary>Wire name of <see cref="MessageType.Subscribe"/>.</summary>
    public const string Subscribe = "SUBSCRIBE";

    /// <summary>Wire name of <see cref="MessageType.Ack"/>.</summary>
    public const string Ack = "ACK";

    /// <summary>Wire name of <see cref="MessageType.Message"/>.</summary>
    public const string Message = "MESSAGE";

    /// <summary>Wire name of <see cref="MessageType.Error"/>.</summary>
    public const string Error = "ERROR";

    /// <summary>Returns the wire name of <paramref name="type"/>.</summary>
    /// <exception cref="ArgumentOutOfRangeException"><paramref name="type"/> is <see cref="MessageType.Unknown"/>.</exception>
    public static string ToWire(MessageType type) => type switch
    {
        MessageType.Subscribe => Subscribe,
        MessageType.Ack => Ack,
        MessageType.Message => Message,
        MessageType.Error => Error,
        _ => throw new ArgumentOutOfRangeException(nameof(type), type, "Unknown messages cannot be sent."),
    };

    /// <summary>
    /// Parses a wire name. Matching is case-insensitive to be lenient with peers;
    /// unrecognised values map to <see cref="MessageType.Unknown"/>.
    /// </summary>
    public static MessageType FromWire(string? value) => value?.Trim().ToUpperInvariant() switch
    {
        Subscribe => MessageType.Subscribe,
        Ack => MessageType.Ack,
        Message => MessageType.Message,
        Error => MessageType.Error,
        _ => MessageType.Unknown,
    };
}
