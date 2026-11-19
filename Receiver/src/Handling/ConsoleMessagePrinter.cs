using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Handling;

/// <summary>
/// Prints each message to standard output as one line.
/// </summary>
public sealed class ConsoleMessagePrinter : IMessageHandler
{
    /// <inheritdoc />
    public Task HandleAsync(Envelope message, CancellationToken cancellationToken)
    {
        var line = MessageFormatter.Format(message);

        lock (ConsoleLock.Sync)
        {
            var previous = Console.ForegroundColor;
            Console.ForegroundColor = ConsoleColor.Green;
            Console.Out.WriteLine(line);
            Console.ForegroundColor = previous;
        }

        return Task.CompletedTask;
    }
}
