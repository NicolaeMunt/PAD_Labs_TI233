using System.Threading.Channels;
using MessageBroker.Receiver.Pipeline;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Owns the broker connection: connects, performs the SUBSCRIBE handshake, reads frames,
/// and reconnects when the link drops.
/// </summary>
public interface ISubscriptionClient : IAsyncDisposable
{
    /// <summary>
    /// Runs the connect → subscribe → read loop until <paramref name="cancellationToken"/> fires.
    /// Every complete frame is written to <paramref name="sink"/>. All failures are logged and
    /// retried; the method returns normally once cancellation is requested.
    /// </summary>
    /// <remarks>
    /// When this method returns, the current connection is intentionally left open so that
    /// workers can still send ACKs while draining. Dispose the client to close it.
    /// </remarks>
    Task RunAsync(ChannelWriter<InboundFrame> sink, CancellationToken cancellationToken);
}
