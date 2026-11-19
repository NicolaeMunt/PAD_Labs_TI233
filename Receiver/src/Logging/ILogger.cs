namespace MessageBroker.Receiver.Logging;

/// <summary>
/// Minimal logging abstraction so components do not depend on a concrete sink.
/// </summary>
public interface ILogger
{
    /// <summary>Writes a log entry.</summary>
    /// <param name="level">Severity.</param>
    /// <param name="message">Human-readable message.</param>
    /// <param name="exception">Optional exception whose type and message are appended.</param>
    void Log(LogLevel level, string message, Exception? exception = null);
}
