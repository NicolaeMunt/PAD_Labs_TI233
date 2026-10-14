namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Decides how long to wait before a reconnect attempt.
/// </summary>
public interface IBackoffPolicy
{
    /// <summary>
    /// Returns the delay to wait after <paramref name="consecutiveFailures"/> failed attempts in a row.
    /// </summary>
    /// <param name="consecutiveFailures">Number of consecutive failures; at least 1.</param>
    TimeSpan GetDelay(int consecutiveFailures);
}
