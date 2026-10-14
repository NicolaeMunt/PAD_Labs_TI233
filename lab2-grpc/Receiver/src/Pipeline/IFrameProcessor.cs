namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// Handles one delivered message on a worker thread.
/// </summary>
public interface IFrameProcessor
{
    /// <summary>
    /// Validates and handles <paramref name="frame"/>. Bad input is logged, not thrown.
    /// </summary>
    Task ProcessAsync(InboundFrame frame, CancellationToken cancellationToken);
}
