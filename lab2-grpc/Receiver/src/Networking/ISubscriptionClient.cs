using System.Threading.Channels;
using MessageBroker.Receiver.Pipeline;

namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Owns the broker stream: opens the Subscribe call, sends the subscription, reads messages,
/// and opens a new call when the stream breaks.
/// </summary>
public interface ISubscriptionClient : IAsyncDisposable
{
    /// <summary>
    /// Runs the subscribe → read loop until <paramref name="cancellationToken"/> fires.
    /// Every delivered message is written to <paramref name="sink"/>. Failures are logged and
    /// retried; the method returns normally once cancellation is requested.
    /// </summary>
    /// <remarks>
    /// When this method returns, the current stream is intentionally left open so that
    /// workers can still send ACKs while draining. Dispose the client to close it.
    /// </remarks>
    Task RunAsync(ChannelWriter<InboundFrame> sink, CancellationToken cancellationToken);
}
