namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Sends ACKs to the broker over whatever stream is currently open.
/// Used by workers without knowing about reconnects.
/// </summary>
public interface IMessageSender
{
    /// <summary>
    /// Tries to ACK <paramref name="messageId"/>.
    /// </summary>
    /// <returns><c>false</c> if there is no open stream or the write failed.</returns>
    Task<bool> TrySendAckAsync(string messageId, CancellationToken cancellationToken);
}
