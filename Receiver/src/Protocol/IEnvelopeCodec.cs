namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// Converts envelopes to and from their on-the-wire byte representation.
/// </summary>
public interface IEnvelopeCodec
{
    /// <summary>
    /// Encodes <paramref name="envelope"/> as one UTF-8 JSON line, including the trailing <c>'\n'</c>.
    /// </summary>
    byte[] Encode(Envelope envelope);

    /// <summary>
    /// Decodes one frame (without its newline). Never throws for malformed input.
    /// </summary>
    DecodeResult Decode(ReadOnlyMemory<byte> frame);
}
