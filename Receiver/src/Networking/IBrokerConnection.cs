using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// One established, long-lived connection to the broker.
/// Reads are expected from a single reader loop; sends may come from any thread.
/// </summary>
public interface IBrokerConnection : IAsyncDisposable
{
    /// <summary>Monotonically increasing number identifying this connection in logs.</summary>
    long Id { get; }

    /// <summary>Remote endpoint description, e.g. <c>127.0.0.1:5000</c>.</summary>
    string RemoteEndPoint { get; }

    /// <summary>
    /// Reads the next raw NDJSON frame, or returns <c>null</c> when the broker closed the connection.
    /// </summary>
    ValueTask<byte[]?> ReadFrameAsync(CancellationToken cancellationToken);

    /// <summary>
    /// Serialises and writes one envelope. Concurrent calls are serialised so frames never interleave.
    /// </summary>
    Task SendAsync(Envelope envelope, CancellationToken cancellationToken);
}
