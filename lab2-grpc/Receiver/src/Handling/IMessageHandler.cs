namespace MessageBroker.Receiver.Handling;

/// <summary>
/// Application-level consumer of a delivered message. Implementations may be called
/// concurrently from several workers and must be thread-safe.
/// </summary>
public interface IMessageHandler
{
    /// <summary>Handles one validated, de-duplicated message.</summary>
    Task HandleAsync(ReceivedMessage message, CancellationToken cancellationToken);
}
