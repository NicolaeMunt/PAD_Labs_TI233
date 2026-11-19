using System.Threading.Channels;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Pipeline;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// The reader pipeline. Runs on its own task and does nothing but keep a connection alive,
/// pull frames off the socket and hand them to the inbound queue.
/// </summary>
public sealed class SubscriptionClient : ISubscriptionClient, IMessageSender
{
    private readonly IBrokerConnectionFactory _connectionFactory;
    private readonly IBackoffPolicy _backoffPolicy;
    private readonly IReadOnlyList<string> _topics;
    private readonly string _clientId;
    private readonly ILogger _logger;

    private IBrokerConnection? _current;

    /// <summary>Creates the client.</summary>
    /// <param name="connectionFactory">Opens broker connections.</param>
    /// <param name="backoffPolicy">Delay strategy between reconnect attempts.</param>
    /// <param name="topics">Topics to subscribe to on every (re)connect.</param>
    /// <param name="clientId">Stable subscriber id sent in the handshake.</param>
    /// <param name="logger">Logger.</param>
    public SubscriptionClient(
        IBrokerConnectionFactory connectionFactory,
        IBackoffPolicy backoffPolicy,
        IReadOnlyList<string> topics,
        string clientId,
        ILogger logger)
    {
        if (topics is null || topics.Count == 0) throw new ArgumentException("At least one topic is required.", nameof(topics));
        _connectionFactory = connectionFactory ?? throw new ArgumentNullException(nameof(connectionFactory));
        _backoffPolicy = backoffPolicy ?? throw new ArgumentNullException(nameof(backoffPolicy));
        _topics = topics;
        _clientId = clientId ?? throw new ArgumentNullException(nameof(clientId));
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));
    }

    /// <inheritdoc />
    public async Task RunAsync(ChannelWriter<InboundFrame> sink, CancellationToken cancellationToken)
    {
        var consecutiveFailures = 0;
        var attempt = 0;

        while (!cancellationToken.IsCancellationRequested)
        {
            attempt++;
            IBrokerConnection? connection = null;
            var framesReceived = 0L;

            try
            {
                _logger.Info($"Connecting to broker at {_connectionFactory.Address} (attempt {attempt})...");
                connection = await _connectionFactory.ConnectAsync(cancellationToken).ConfigureAwait(false);

                await HandshakeAsync(connection, cancellationToken).ConfigureAwait(false);
                Volatile.Write(ref _current, connection);
                consecutiveFailures = 0;
                attempt = 0;

                framesReceived = await ReadLoopAsync(connection, sink, cancellationToken).ConfigureAwait(false);
                _logger.Warn($"Broker closed connection #{connection.Id} after {framesReceived} frame(s).");
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
            catch (Exception ex)
            {
                // Any failure — refused connection, reset, keep-alive timeout, bug in a lower
                // layer — is logged and handled by reconnecting. Nothing escapes this loop.
                var where = connection is null ? "Connect attempt failed" : $"Connection #{connection.Id} failed";
                _logger.Warn(where, ex);
            }
            finally
            {
                // On shutdown the live connection stays open for draining ACKs; see DisposeAsync.
                if (connection is not null && !cancellationToken.IsCancellationRequested)
                {
                    Interlocked.CompareExchange(ref _current, null, connection);
                    await connection.DisposeAsync().ConfigureAwait(false);
                }
            }

            if (cancellationToken.IsCancellationRequested)
            {
                break;
            }

            // A connection that was up and then dropped restarts the back-off at 1s.
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
    public async Task<bool> TrySendAsync(Envelope envelope, CancellationToken cancellationToken)
    {
        var connection = Volatile.Read(ref _current);
        if (connection is null)
        {
            return false;
        }

        try
        {
            await connection.SendAsync(envelope, cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.Debug($"Could not send {envelope.Type} on connection #{connection.Id}: {ex.Message}");
            return false;
        }
    }

    /// <summary>Closes the connection that is still open after <see cref="RunAsync"/> returned.</summary>
    public async ValueTask DisposeAsync()
    {
        var connection = Interlocked.Exchange(ref _current, null);
        if (connection is not null)
        {
            await connection.DisposeAsync().ConfigureAwait(false);
            _logger.Info($"Disconnected from broker (connection #{connection.Id}).");
        }
    }

    private async Task HandshakeAsync(IBrokerConnection connection, CancellationToken cancellationToken)
    {
        foreach (var topic in _topics)
        {
            await connection.SendAsync(Envelope.Subscribe(topic, _clientId), cancellationToken).ConfigureAwait(false);
        }

        _logger.Info(
            $"Connected as #{connection.Id} to {connection.RemoteEndPoint}; " +
            $"subscribed clientId='{_clientId}' to [{string.Join(", ", _topics)}]. Listening...");
    }

    /// <summary>
    /// Reads frames until the broker closes the connection. Deliberately does no parsing or
    /// handling: every frame goes straight into the queue so the socket is drained at full speed,
    /// which matters most during the replay burst right after a reconnect.
    /// </summary>
    /// <returns>The number of frames read before the connection was closed.</returns>
    private static async Task<long> ReadLoopAsync(
        IBrokerConnection connection,
        ChannelWriter<InboundFrame> sink,
        CancellationToken cancellationToken)
    {
        var count = 0L;
        while (true)
        {
            var frame = await connection.ReadFrameAsync(cancellationToken).ConfigureAwait(false);
            if (frame is null)
            {
                return count;
            }

            count++;
            var inbound = new InboundFrame(frame, connection.Id, DateTimeOffset.UtcNow);

            // With an unbounded channel TryWrite always succeeds immediately.
            if (!sink.TryWrite(inbound))
            {
                await sink.WriteAsync(inbound, cancellationToken).ConfigureAwait(false);
            }
        }
    }
}
