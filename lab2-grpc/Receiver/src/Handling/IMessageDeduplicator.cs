namespace MessageBroker.Receiver.Handling;

/// <summary>
/// Remembers message ids that were already processed. Must be thread-safe.
/// </summary>
public interface IMessageDeduplicator
{
    /// <summary>
    /// Atomically records <paramref name="messageId"/> as seen.
    /// </summary>
    /// <returns><c>true</c> if the id is new; <c>false</c> if it is a duplicate.</returns>
    bool TryRegister(string messageId);

    /// <summary>
    /// Removes <paramref name="messageId"/> so a later redelivery is processed again
    /// (used when handling failed).
    /// </summary>
    void Forget(string messageId);
}
