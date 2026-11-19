using System.Diagnostics.CodeAnalysis;

namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// Result of decoding a frame: either an <see cref="Protocol.Envelope"/> or an error description.
/// </summary>
public readonly record struct DecodeResult(Envelope? Envelope, string? Error)
{
    /// <summary>Whether decoding succeeded.</summary>
    [MemberNotNullWhen(true, nameof(Envelope))]
    [MemberNotNullWhen(false, nameof(Error))]
    public bool IsSuccess => Envelope is not null;

    /// <summary>Creates a successful result.</summary>
    public static DecodeResult Ok(Envelope envelope) => new(envelope, null);

    /// <summary>Creates a failed result.</summary>
    public static DecodeResult Fail(string error) => new(null, error);
}
