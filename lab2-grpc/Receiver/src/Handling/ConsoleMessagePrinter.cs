using MessageBroker.Receiver.Logging;

namespace MessageBroker.Receiver.Handling;

/// <summary>
/// Prints each message to standard output as one line: live messages in green,
/// messages replayed from history in cyan.
/// </summary>
public sealed class ConsoleMessagePrinter : IMessageHandler
{
    /// <inheritdoc />
    public Task HandleAsync(ReceivedMessage message, CancellationToken cancellationToken)
    {
        var line = MessageFormatter.Format(message);

        lock (ConsoleLock.Sync)
        {
            var previous = Console.ForegroundColor;
            Console.ForegroundColor = message.Replayed ? ConsoleColor.Cyan : ConsoleColor.Green;
            Console.Out.WriteLine(line);
            Console.ForegroundColor = previous;
        }

        return Task.CompletedTask;
    }
}
