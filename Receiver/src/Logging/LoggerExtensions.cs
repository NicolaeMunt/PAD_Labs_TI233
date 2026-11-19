namespace MessageBroker.Receiver.Logging;

/// <summary>Convenience overloads for <see cref="ILogger"/>.</summary>
public static class LoggerExtensions
{
    /// <summary>Logs at <see cref="LogLevel.Debug"/>.</summary>
    public static void Debug(this ILogger logger, string message) => logger.Log(LogLevel.Debug, message);

    /// <summary>Logs at <see cref="LogLevel.Info"/>.</summary>
    public static void Info(this ILogger logger, string message) => logger.Log(LogLevel.Info, message);

    /// <summary>Logs at <see cref="LogLevel.Warning"/>.</summary>
    public static void Warn(this ILogger logger, string message, Exception? exception = null) =>
        logger.Log(LogLevel.Warning, message, exception);

    /// <summary>Logs at <see cref="LogLevel.Error"/>.</summary>
    public static void Error(this ILogger logger, string message, Exception? exception = null) =>
        logger.Log(LogLevel.Error, message, exception);
}
