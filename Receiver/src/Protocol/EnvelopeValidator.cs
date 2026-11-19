namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// Default validation rules for inbound frames.
/// </summary>
/// <remarks>
/// <list type="bullet">
/// <item><c>MESSAGE</c> needs <c>topic</c> and <c>payload</c> (payload may be an empty string).</item>
/// <item><c>ACK</c> and <c>ERROR</c> need nothing beyond <c>type</c>.</item>
/// <item>Any other type is rejected, because a receiver never expects to get it.</item>
/// </list>
/// Missing <c>clientId</c> or <c>timestamp</c> on a MESSAGE is tolerated and shown as "?".
/// </remarks>
public sealed class EnvelopeValidator : IEnvelopeValidator
{
    /// <inheritdoc />
    public bool IsValid(Envelope envelope, out string? error)
    {
        error = envelope.Type switch
        {
            MessageType.Message when string.IsNullOrWhiteSpace(envelope.Topic) => "MESSAGE without 'topic'",
            MessageType.Message when envelope.Payload is null => "MESSAGE without 'payload'",
            MessageType.Message or MessageType.Ack or MessageType.Error => null,
            MessageType.Subscribe => "unexpected SUBSCRIBE frame sent to a receiver",
            _ => $"unknown message type '{envelope.RawType}'",
        };

        return error is null;
    }
}
