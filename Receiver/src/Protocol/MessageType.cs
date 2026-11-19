namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// The <c>type</c> field of an envelope. On the wire these are upper-case strings.
/// </summary>
public enum MessageType
{
    /// <summary>A value that is not part of the protocol. Such frames are logged and ignored.</summary>
    Unknown,

    /// <summary>Receiver → broker: register interest in a topic (handshake).</summary>
    Subscribe,

    /// <summary>Both directions: acknowledgement of a SUBSCRIBE or of a delivered MESSAGE.</summary>
    Ack,

    /// <summary>Broker → receiver: a published message.</summary>
    Message,

    /// <summary>Broker → receiver: the broker rejected something; details in <c>payload</c>.</summary>
    Error,
}
