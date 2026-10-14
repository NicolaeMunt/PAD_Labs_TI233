namespace MessageBroker.Receiver.Logging;

/// <summary>Severity of a log entry.</summary>
public enum LogLevel
{
    /// <summary>Diagnostic detail, hidden unless <c>--verbose</c> is set.</summary>
    Debug,

    /// <summary>Normal operational events.</summary>
    Info,

    /// <summary>Recoverable problems such as bad frames or dropped connections.</summary>
    Warning,

    /// <summary>Unexpected failures.</summary>
    Error,
}
