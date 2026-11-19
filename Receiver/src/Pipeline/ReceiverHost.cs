using System.Threading.Channels;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Networking;

namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// Wires the reader pipeline and the handler pipeline together through a channel and
/// orchestrates graceful shutdown.
/// </summary>
public sealed class ReceiverHost
{
    private readonly ISubscriptionClient _client;
    private readonly WorkerPool _workers;
    private readonly int _queueCapacity;
    private readonly TimeSpan _drainTimeout;
    private readonly ILogger _logger;

    /// <summary>Creates the host.</summary>
    /// <param name="client">Reader pipeline (connection owner).</param>
    /// <param name="workers">Handler pipeline.</param>
    /// <param name="queueCapacity">Queue bound; 0 for unbounded.</param>
    /// <param name="drainTimeout">Maximum time to drain the queue on shutdown.</param>
    /// <param name="logger">Logger.</param>
    public ReceiverHost(
        ISubscriptionClient client,
        WorkerPool workers,
        int queueCapacity,
        TimeSpan drainTimeout,
        ILogger logger)
    {
        _client = client;
        _workers = workers;
        _queueCapacity = queueCapacity;
        _drainTimeout = drainTimeout;
        _logger = logger;
    }

    /// <summary>
    /// Runs until <paramref name="shutdownToken"/> fires, then: stops the reader, lets the workers
    /// drain everything already queued, and finally closes the socket.
    /// </summary>
    public async Task RunAsync(CancellationToken shutdownToken)
    {
        var queue = CreateQueue();
        using var abortWorkers = new CancellationTokenSource();
        var workers = _workers.RunAsync(queue.Reader, abortWorkers.Token);

        try
        {
            await _client.RunAsync(queue.Writer, shutdownToken).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.Error("Reader pipeline terminated unexpectedly", ex);
        }
        finally
        {
            // 1. No more frames will be produced.
            queue.Writer.TryComplete();
            _logger.Info($"Draining {queue.Reader.Count} queued frame(s) (timeout {_drainTimeout.TotalSeconds:0}s)...");

            // 2. Workers finish what is queued (ACKs still go out on the open socket).
            abortWorkers.CancelAfter(_drainTimeout);
            await workers.ConfigureAwait(false);

            // 3. Only now close the connection.
            await _client.DisposeAsync().ConfigureAwait(false);
            _logger.Info("Shutdown complete.");
        }
    }

    private Channel<InboundFrame> CreateQueue()
    {
        if (_queueCapacity <= 0)
        {
            return Channel.CreateUnbounded<InboundFrame>(new UnboundedChannelOptions
            {
                SingleWriter = true,
                SingleReader = false,
                AllowSynchronousContinuations = false,
            });
        }

        // Bounded: when full, the reader waits, which pushes back on the broker through TCP flow
        // control instead of growing memory without limit. Nothing is ever dropped.
        return Channel.CreateBounded<InboundFrame>(new BoundedChannelOptions(_queueCapacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleWriter = true,
            SingleReader = false,
            AllowSynchronousContinuations = false,
        });
    }
}
