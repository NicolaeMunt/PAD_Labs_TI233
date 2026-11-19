using System.Net.Sockets;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Opens TCP connections to the broker with a connect timeout and TCP keep-alive enabled.
/// </summary>
/// <remarks>
/// Keep-alive is what detects half-open connections: if the broker host vanishes without
/// sending FIN/RST (power loss, cable pulled), the OS probes the idle link and fails the pending
/// read after roughly <c>KeepAliveTime + KeepAliveInterval × RetryCount</c> seconds, which then
/// triggers the normal reconnect path.
/// </remarks>
public sealed class TcpBrokerConnectionFactory : IBrokerConnectionFactory
{
    private const int KeepAliveTimeSeconds = 15;
    private const int KeepAliveIntervalSeconds = 5;
    private const int KeepAliveRetryCount = 3;

    private readonly string _host;
    private readonly int _port;
    private readonly TimeSpan _connectTimeout;
    private readonly int _maxFrameBytes;
    private readonly IEnvelopeCodec _codec;
    private readonly ILogger _logger;

    /// <summary>Creates the factory.</summary>
    public TcpBrokerConnectionFactory(
        string host,
        int port,
        TimeSpan connectTimeout,
        int maxFrameBytes,
        IEnvelopeCodec codec,
        ILogger logger)
    {
        _host = host;
        _port = port;
        _connectTimeout = connectTimeout;
        _maxFrameBytes = maxFrameBytes;
        _codec = codec;
        _logger = logger;
    }

    /// <inheritdoc />
    public string Address => $"{_host}:{_port}";

    /// <inheritdoc />
    public async Task<IBrokerConnection> ConnectAsync(CancellationToken cancellationToken)
    {
        var client = new TcpClient();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(_connectTimeout);

        try
        {
            await client.ConnectAsync(_host, _port, timeout.Token).ConfigureAwait(false);
            client.NoDelay = true; // ACKs are tiny; do not let Nagle delay them.
            EnableKeepAlive(client.Client);
            return new TcpBrokerConnection(client, _codec, _maxFrameBytes, _logger);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            client.Dispose();
            throw new TimeoutException($"Connecting to {Address} timed out after {_connectTimeout.TotalSeconds:0}s.");
        }
        catch
        {
            client.Dispose();
            throw;
        }
    }

    private void EnableKeepAlive(Socket socket)
    {
        try
        {
            socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
            socket.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveTime, KeepAliveTimeSeconds);
            socket.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveInterval, KeepAliveIntervalSeconds);
            socket.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveRetryCount, KeepAliveRetryCount);
        }
        catch (Exception ex) when (ex is SocketException or PlatformNotSupportedException)
        {
            _logger.Debug($"Could not fully configure TCP keep-alive on this platform: {ex.Message}");
        }
    }
}
