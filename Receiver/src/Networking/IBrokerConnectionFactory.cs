namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Opens new connections to the broker. Abstracted so the reconnect logic can be tested
/// without real sockets.
/// </summary>
public interface IBrokerConnectionFactory
{
    /// <summary>Human-readable address of the broker, for logs.</summary>
    string Address { get; }

    /// <summary>
    /// Opens a connection.
    /// </summary>
    /// <exception cref="TimeoutException">The connect attempt timed out.</exception>
    /// <exception cref="System.Net.Sockets.SocketException">The broker is unreachable.</exception>
    Task<IBrokerConnection> ConnectAsync(CancellationToken cancellationToken);
}
