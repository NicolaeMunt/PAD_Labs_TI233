namespace MessageBroker.Receiver.Logging;

/// <summary>
/// Thread-safe logger that writes coloured diagnostic lines to standard error,
/// keeping standard output free for the received messages.
/// </summary>
public sealed class ConsoleLogger : ILogger
{
    private readonly LogLevel _minimumLevel;

    /// <summary>Creates a logger that ignores entries below <paramref name="minimumLevel"/>.</summary>
    public ConsoleLogger(LogLevel minimumLevel)
    {
        _minimumLevel = minimumLevel;
    }

    /// <inheritdoc />
    public void Log(LogLevel level, string message, Exception? exception = null)
    {
        if (level < _minimumLevel)
        {
            return;
        }

        var line = $"{DateTimeOffset.Now:HH:mm:ss.fff} {Label(level)} [T{Environment.CurrentManagedThreadId:D2}] {message}";
        if (exception is not null)
        {
            line += $" ({exception.GetType().Name}: {exception.Message})";
        }

        lock (ConsoleLock.Sync)
        {
            var previous = Console.ForegroundColor;
            Console.ForegroundColor = Colour(level);
            Console.Error.WriteLine(line);
            Console.ForegroundColor = previous;
        }
    }

    private static string Label(LogLevel level) => level switch
    {
        LogLevel.Debug => "DBG",
        LogLevel.Info => "INF",
        LogLevel.Warning => "WRN",
        _ => "ERR",
    };

    private static ConsoleColor Colour(LogLevel level) => level switch
    {
        LogLevel.Debug => ConsoleColor.DarkGray,
        LogLevel.Info => ConsoleColor.Gray,
        LogLevel.Warning => ConsoleColor.Yellow,
        _ => ConsoleColor.Red,
    };
}
