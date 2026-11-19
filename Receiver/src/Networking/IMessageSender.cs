using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Sends frames to the broker over whatever connection is currently active.
/// Used by workers to send ACKs without knowing about reconnects.
/// </summary>
public interface IMessageSender
{
    /// <summary>
    /// Tries to send <paramref name="envelope"/>.
    /// </summary>
    /// <returns><c>false</c> if there is no live connection or the write failed.</returns>
    Task<bool> TrySendAsync(Envelope envelope, CancellationToken cancellationToken);
}
