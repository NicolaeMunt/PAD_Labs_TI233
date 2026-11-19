using System.Threading.Channels;
using MessageBroker.Receiver.Logging;

namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// The handler pipeline: a fixed number of worker tasks draining the inbound queue.
/// </summary>
/// <remarks>
/// Workers stop when the queue is completed <em>and</em> empty, which is how graceful shutdown
/// drains pending frames. The abort token is only a last resort if draining takes too long.
/// With more than one worker, messages may be handled out of order.
/// </remarks>
public sealed class WorkerPool
{
    private readonly IFrameProcessor _processor;
    private readonly int _workerCount;
    private readonly ILogger _logger;

    /// <summary>Creates the pool.</summary>
    public WorkerPool(IFrameProcessor processor, int workerCount, ILogger logger)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(workerCount, 1);
        _processor = processor ?? throw new ArgumentNullException(nameof(processor));
        _workerCount = workerCount;
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));
    }

    /// <summary>
    /// Starts all workers and returns a task that completes when every worker has finished.
    /// The returned task never faults.
    /// </summary>
    /// <param name="queue">The inbound queue.</param>
    /// <param name="abortToken">Cancels in-flight work when the drain deadline passes.</param>
    public Task RunAsync(ChannelReader<InboundFrame> queue, CancellationToken abortToken)
    {
        var workers = Enumerable.Range(1, _workerCount)
            .Select(n => Task.Run(() => RunWorkerAsync(n, queue, abortToken), CancellationToken.None))
            .ToArray();

        _logger.Info($"Started {_workerCount} worker(s).");
        return Task.WhenAll(workers);
    }

    private async Task RunWorkerAsync(int workerNumber, ChannelReader<InboundFrame> queue, CancellationToken abortToken)
    {
        var handled = 0L;
        try
        {
            while (await queue.WaitToReadAsync(abortToken).ConfigureAwait(false))
            {
                while (queue.TryRead(out var frame))
                {
                    try
                    {
                        await _processor.ProcessAsync(frame, abortToken).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (abortToken.IsCancellationRequested)
                    {
                        throw;
                    }
                    catch (Exception ex)
                    {
                        // A single poisonous frame must not kill the worker.
                        _logger.Error($"Worker {workerNumber}: unexpected error while processing a frame", ex);
                    }

                    handled++;
                }
            }

            _logger.Debug($"Worker {workerNumber} finished after {handled} frame(s).");
        }
        catch (OperationCanceledException) when (abortToken.IsCancellationRequested)
        {
            _logger.Warn($"Worker {workerNumber} aborted: drain deadline exceeded.");
        }
        catch (Exception ex)
        {
            _logger.Error($"Worker {workerNumber} crashed", ex);
        }
    }
}
