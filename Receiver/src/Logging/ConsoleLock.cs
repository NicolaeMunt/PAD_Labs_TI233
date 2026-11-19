namespace MessageBroker.Receiver.Logging;

/// <summary>
/// Shared lock that keeps log lines and message lines written by concurrent workers
/// from interleaving (including their colours).
/// </summary>
public static class ConsoleLock
{
    /// <summary>The synchronisation object guarding all console output.</summary>
    public static readonly object Sync = new();
}
