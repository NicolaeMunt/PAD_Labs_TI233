using System.Threading.Channels;
using Grpc.Core;
using Grpc.Net.Client;
using MessageBroker.Receiver.Grpc;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Pipeline;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// The reader pipeline. Runs on its own task and does nothing but keep a <c>Subscribe</c> call
/// open, pull messages off the response stream and hand them to the inbound queue.
/// </summary>
/// <remarks>
/// One <see cref="GrpcChannel"/> (one HTTP/2 connection, re-established by gRPC itself) is kept
/// for the whole run. A broken stream is replaced by a new <c>Subscribe</c> call on that channel,
/// after the back-off delay. The broker answers each new call with the topics' full history.
/// </remarks>
public sealed class SubscriptionClient : ISubscriptionClient, IMessageSender
{
    private readonly GrpcChannel _channel;
    private readonly Broker.BrokerClient _broker;
    private readonly IBackoffPolicy _backoffPolicy;
    private readonly IReadOnlyList<string> _topics;
    private readonly string _clientId;
    private readonly ILogger _logger;
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    private AsyncDuplexStreamingCall<SubscriberFrame, SubscriberEvent>? _current;
    private long _streamCount;

    /// <summary>Creates the client.</summary>
    /// <param name="address">Broker address, e.g. <c>http://127.0.0.1:5000</c>.</param>
    /// <param name="connectTimeout">Timeout for opening the TCP connection.</param>
    /// <param name="backoffPolicy">Delay strategy between reconnect attempts.</param>
    /// <param name="topics">Topics to subscribe to on every (re)connect.</param>
    /// <param name="clientId">Stable subscriber id sent in the Subscribe frame.</param>
    /// <param name="logger">Logger.</param>
    public SubscriptionClient(
        Uri address,
        TimeSpan connectTimeout,
        IBackoffPolicy backoffPolicy,
        IReadOnlyList<string> topics,
        string clientId,
        ILogger logger)
    {
        if (topics is null || topics.Count == 0) throw new ArgumentException("At least one topic is required.", nameof(topics));
        _backoffPolicy = backoffPolicy ?? throw new ArgumentNullException(nameof(backoffPolicy));
        _topics = topics;
        _clientId = clientId ?? throw new ArgumentNullException(nameof(clientId));
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));

        _channel = GrpcChannel.ForAddress(address, new GrpcChannelOptions
        {
            HttpHandler = new SocketsHttpHandler
            {
                ConnectTimeout = connectTimeout,
                // HTTP/2 pings detect a dead connection even when no messages are flowing.
                KeepAlivePingDelay = TimeSpan.FromSeconds(20),
                KeepAlivePingTimeout = TimeSpan.FromSeconds(10),
                KeepAlivePingPolicy = HttpKeepAlivePingPolicy.Always,
            },
        });
        _broker = new Broker.BrokerClient(_channel);
        Address = address.ToString();
    }

    /// <summary>Broker address, for logs.</summary>
    public string Address { get; }

    /// <inheritdoc />
    public async Task RunAsync(ChannelWriter<InboundFrame> sink, CancellationToken cancellationToken)
    {
        var consecutiveFailures = 0;
        var attempt = 0;

        while (!cancellationToken.IsCancellationRequested)
        {
            attempt++;
            var streamId = Interlocked.Increment(ref _streamCount);
            AsyncDuplexStreamingCall<SubscriberFrame, SubscriberEvent>? call = null;
            var subscribed = false;

            try
            {
                _logger.Info($"Connecting to broker at {Address} (attempt {attempt})...");
                call = _broker.Subscribe();
                await call.RequestStream.WriteAsync(new SubscriberFrame
                {
                    Subscribe = new Subscribe { SubscriberId = _clientId, Topics = { _topics } },
                }).ConfigureAwait(false);
                Volatile.Write(ref _current, call);

                var received = await ReadLoopAsync(call, streamId, sink, () =>
                {
                    subscribed = true;
                    consecutiveFailures = 0;
                    attempt = 0;
                }, cancellationToken).ConfigureAwait(false);

                if (cancellationToken.IsCancellationRequested)
                {
                    _logger.Info("Reader loop stopping (shutdown requested).");
                    break;
                }

                _logger.Warn($"Broker ended stream #{streamId} after {received} message(s).");
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                _logger.Info("Reader loop stopping (shutdown requested).");
                break;
            }
            catch (ChannelClosedException)
            {
                _logger.Warn("Inbound queue was closed; reader loop stopping.");
                break;
            }
            catch (RpcException ex) when (ex.StatusCode == StatusCode.InvalidArgument)
            {
                // Retrying would be rejected the same way.
                _logger.Error($"Broker rejected the subscription: {ex.Status.Detail}");
                break;
            }
            catch (RpcException ex) when (ex.StatusCode == StatusCode.Aborted)
            {
                // Another receiver connected with the same id. Reconnecting would take the stream
                // back from it, and the two would keep replacing each other.
                _logger.Error($"Stopping: {ex.Status.Detail}. Is another receiver running with -c {_clientId}?");
                break;
            }
            catch (Exception ex)
            {
                // Broker unreachable, connection reset, keepalive timeout, … all end up here and
                // are handled by opening a new stream.
                var where = subscribed ? $"Stream #{streamId} failed" : "Connect attempt failed";
                _logger.Warn(where, ex is RpcException rpc ? new Exception($"{rpc.StatusCode}: {rpc.Status.Detail}") : ex);
            }
            finally
            {
                // On shutdown the live stream stays open for draining ACKs; see DisposeAsync.
                if (call is not null && !cancellationToken.IsCancellationRequested)
                {
                    Interlocked.CompareExchange(ref _current, null, call);
                    call.Dispose();
                }
            }

            if (cancellationToken.IsCancellationRequested)
            {
                break;
            }

            consecutiveFailures++;
            var delay = _backoffPolicy.GetDelay(consecutiveFailures);
            _logger.Info($"Reconnecting in {delay.TotalSeconds:0.#}s (retry #{consecutiveFailures}).");

            try
            {
                await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    /// <inheritdoc />
    public async Task<bool> TrySendAckAsync(string messageId, CancellationToken cancellationToken)
    {
        var call = Volatile.Read(ref _current);
        if (call is null)
        {
            return false;
        }

        // A gRPC request stream accepts only one write at a time; workers ACK concurrently.
        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await call.RequestStream.WriteAsync(new SubscriberFrame { Ack = new Ack { MessageId = messageId } }).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.Debug($"Could not send ACK for '{messageId}': {ex.Message}");
            return false;
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>Ends the stream that is still open after <see cref="RunAsync"/> returned, and the channel.</summary>
    public async ValueTask DisposeAsync()
    {
        var call = Interlocked.Exchange(ref _current, null);
        if (call is not null)
        {
            await _writeLock.WaitAsync().ConfigureAwait(false);
            try
            {
                await call.RequestStream.CompleteAsync().ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.Debug($"Could not complete the request stream: {ex.Message}");
            }
            finally
            {
                _writeLock.Release();
            }

            call.Dispose();
            _logger.Info("Disconnected from broker.");
        }

        _channel.Dispose();
    }

    /// <summary>
    /// Reads events until the broker ends the stream or shutdown is requested. Deliberately does
    /// no handling: every message goes straight into the queue so the stream is drained at full
    /// speed, which matters most during the history replay right after subscribing.
    /// </summary>
    /// <returns>The number of messages read.</returns>
    private async Task<long> ReadLoopAsync(
        AsyncDuplexStreamingCall<SubscriberFrame, SubscriberEvent> call,
        long streamId,
        ChannelWriter<InboundFrame> sink,
        Action onSubscribed,
        CancellationToken cancellationToken)
    {
        var count = 0L;
        var stopped = Task.Delay(Timeout.Infinite, cancellationToken);

        while (true)
        {
            // Cancelling MoveNext would cancel the whole call, and the workers still need it to
            // send ACKs while draining. So on shutdown the loop just stops waiting for it.
            var next = call.ResponseStream.MoveNext(CancellationToken.None);
            if (await Task.WhenAny(next, stopped).ConfigureAwait(false) == stopped)
            {
                _ = next.ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted);
                return count;
            }

            if (!await next.ConfigureAwait(false))
            {
                return count;
            }

            var evt = call.ResponseStream.Current;
            switch (evt.KindCase)
            {
                case SubscriberEvent.KindOneofCase.Subscribed:
                    onSubscribed();
                    var history = evt.Subscribed.HistoryCount;
                    _logger.Info(
                        $"Subscribed on stream #{streamId} as clientId='{_clientId}' to [{string.Join(", ", evt.Subscribed.Topics)}]; " +
                        (history == 0 ? "no stored messages." : $"{history} stored message(s) follow.") + " Listening...");
                    break;

                case SubscriberEvent.KindOneofCase.Message:
                    count++;
                    var inbound = new InboundFrame(evt.Message, streamId, DateTimeOffset.UtcNow);
                    // With an unbounded channel TryWrite always succeeds immediately.
                    if (!sink.TryWrite(inbound))
                    {
                        await sink.WriteAsync(inbound, cancellationToken).ConfigureAwait(false);
                    }

                    break;

                default:
                    _logger.Warn($"Ignoring unknown event on stream #{streamId}.");
                    break;
            }
        }
    }
}
