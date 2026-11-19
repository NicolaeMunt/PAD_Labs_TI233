namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// Checks that a decoded envelope carries the fields its type requires.
/// </summary>
public interface IEnvelopeValidator
{
    /// <summary>
    /// Validates <paramref name="envelope"/>.
    /// </summary>
    /// <param name="envelope">The decoded envelope.</param>
    /// <param name="error">Why validation failed, or <c>null</c> when it succeeded.</param>
    /// <returns><c>true</c> when the envelope can be processed.</returns>
    bool IsValid(Envelope envelope, out string? error);
}
