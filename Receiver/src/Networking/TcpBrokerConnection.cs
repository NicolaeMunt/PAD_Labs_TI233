using System.Net.Sockets;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// <see cref="IBrokerConnection"/> over a <see cref="TcpClient"/>.
/// </summary>
public sealed class TcpBrokerConnection : IBrokerConnection
{
    private static long _nextId;

    private readonly TcpClient _client;
    private readonly NetworkStream _stream;
    private readonly NdjsonFrameReader _reader;
    private readonly IEnvelopeCodec _codec;
    private readonly ILogger _logger;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private int _disposed;

    /// <summary>Wraps an already connected <paramref name="client"/>.</summary>
    /// <param name="client">A connected TCP client; ownership is transferred.</param>
    /// <param name="codec">Codec used for outbound frames.</param>
    /// <param name="maxFrameBytes">Maximum accepted inbound frame length.</param>
    /// <param name="logger">Logger.</param>
    public TcpBrokerConnection(TcpClient client, IEnvelopeCodec codec, int maxFrameBytes, ILogger logger)
    {
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _codec = codec ?? throw new ArgumentNullException(nameof(codec));
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));
        _stream = client.GetStream();
        _reader = new NdjsonFrameReader(_stream, maxFrameBytes, logger);
        Id = Interlocked.Increment(ref _nextId);
        RemoteEndPoint = client.Client.RemoteEndPoint?.ToString() ?? "?";
    }

    /// <inheritdoc />
    public long Id { get; }

    /// <inheritdoc />
    public string RemoteEndPoint { get; }

    /// <inheritdoc />
    public ValueTask<byte[]?> ReadFrameAsync(CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed != 0, this);
        return _reader.ReadFrameAsync(cancellationToken);
    }

    /// <inheritdoc />
    public async Task SendAsync(Envelope envelope, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed != 0, this);
        var bytes = _codec.Encode(envelope);

        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            // The write itself is not cancellable: aborting half-way would leave a partial
            // line on the wire and corrupt the framing for the broker.
            await _stream.WriteAsync(bytes, CancellationToken.None).ConfigureAwait(false);
            await _stream.FlushAsync(CancellationToken.None).ConfigureAwait(false);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>
    /// Closes the connection gracefully: sends FIN, then releases the socket.
    /// </summary>
    public ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return ValueTask.CompletedTask;
        }

        try
        {
            if (_client.Connected)
            {
                _client.Client.Shutdown(SocketShutdown.Both);
            }
        }
        catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
        {
            // The peer may already be gone; closing is best effort.
        }

        _stream.Dispose();
        _client.Dispose();
        _logger.Debug($"Connection #{Id} to {RemoteEndPoint} closed.");
        return ValueTask.CompletedTask;
    }
}
