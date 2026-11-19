using System.Runtime.InteropServices;
using MessageBroker.Receiver.Configuration;
using MessageBroker.Receiver.Handling;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Networking;
using MessageBroker.Receiver.Pipeline;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver;

/// <summary>
/// Entry point and composition root.
/// </summary>
public static class Program
{
    /// <summary>Exit code for a clean shutdown.</summary>
    public const int ExitOk = 0;

    /// <summary>Exit code for an unexpected fatal error.</summary>
    public const int ExitFatal = 1;

    /// <summary>Exit code for invalid command-line arguments.</summary>
    public const int ExitUsage = 2;

    /// <summary>Parses arguments, builds the object graph and runs until Ctrl+C.</summary>
    public static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;

        var parsed = CommandLineParser.Parse(args);
        if (parsed.ShowHelp)
        {
            Console.WriteLine(CommandLineParser.Usage);
            return ExitOk;
        }

        if (parsed.Options is null)
        {
            Console.Error.WriteLine($"Error: {parsed.Error}");
            Console.Error.WriteLine();
            Console.Error.WriteLine(CommandLineParser.Usage);
            return ExitUsage;
        }

        var options = parsed.Options;
        var logger = new ConsoleLogger(options.Verbose ? LogLevel.Debug : LogLevel.Info);

        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            logger.Error("Unobserved task exception", e.Exception);
            e.SetObserved();
        };

        using var shutdown = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => OnCancelKeyPress(e, shutdown, logger);
        using var sigterm = RegisterSigterm(shutdown, logger);

        FileMessageAppender? fileAppender = null;
        try
        {
            logger.Info($"Receiver starting: broker={options.Host}:{options.Port} clientId='{options.ClientId}' " +
                        $"topics=[{string.Join(", ", options.Topics)}] workers={options.WorkerCount}. Press Ctrl+C to stop.");

            var handlers = new List<IMessageHandler> { new ConsoleMessagePrinter() };
            if (options.LogFilePath is not null)
            {
                fileAppender = new FileMessageAppender(options.LogFilePath);
                handlers.Add(fileAppender);
                logger.Info($"Appending messages to {fileAppender.FullPath}");
            }

            var host = BuildHost(options, handlers, logger);
            await host.RunAsync(shutdown.Token).ConfigureAwait(false);
            return ExitOk;
        }
        catch (Exception ex)
        {
            logger.Error("Fatal error", ex);
            return ExitFatal;
        }
        finally
        {
            if (fileAppender is not null)
            {
                await fileAppender.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    /// <summary>
    /// Builds the object graph. This is the only place that knows concrete types;
    /// every other class depends on interfaces passed in here.
    /// </summary>
    private static ReceiverHost BuildHost(ReceiverOptions options, IReadOnlyList<IMessageHandler> handlers, ILogger logger)
    {
        IEnvelopeCodec codec = new JsonEnvelopeCodec();

        var connectionFactory = new TcpBrokerConnectionFactory(
            options.Host, options.Port, options.ConnectTimeout, options.MaxFrameBytes, codec, logger);

        var client = new SubscriptionClient(
            connectionFactory,
            new ExponentialBackoffPolicy(options.InitialReconnectDelay, options.MaxReconnectDelay),
            options.Topics,
            options.ClientId,
            logger);

        var processor = new FrameProcessor(
            codec,
            new EnvelopeValidator(),
            new BoundedMessageDeduplicator(options.DedupCapacity),
            handlers,
            sender: client,
            options.ClientId,
            logger);

        var workers = new WorkerPool(processor, options.WorkerCount, logger);
        return new ReceiverHost(client, workers, options.QueueCapacity, options.DrainTimeout, logger);
    }

    private static void OnCancelKeyPress(ConsoleCancelEventArgs e, CancellationTokenSource shutdown, ILogger logger)
    {
        if (shutdown.IsCancellationRequested)
        {
            // Second Ctrl+C: let the runtime terminate the process immediately.
            logger.Warn("Second Ctrl+C received; forcing exit.");
            return;
        }

        e.Cancel = true; // keep the process alive so we can shut down gracefully
        logger.Info("Ctrl+C received; shutting down gracefully (press again to force)...");
        TryCancel(shutdown);
    }

    private static PosixSignalRegistration? RegisterSigterm(CancellationTokenSource shutdown, ILogger logger)
    {
        try
        {
            return PosixSignalRegistration.Create(PosixSignal.SIGTERM, context =>
            {
                context.Cancel = true;
                logger.Info("SIGTERM received; shutting down gracefully...");
                TryCancel(shutdown);
            });
        }
        catch (PlatformNotSupportedException)
        {
            return null;
        }
    }

    private static void TryCancel(CancellationTokenSource source)
    {
        try
        {
            source.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // Signal arrived after Main already finished.
        }
    }
}
