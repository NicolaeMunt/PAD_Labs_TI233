namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// Handles one inbound frame on a worker thread.
/// </summary>
public interface IFrameProcessor
{
    /// <summary>
    /// Decodes, validates and dispatches <paramref name="frame"/>. Bad input is logged, not thrown.
    /// </summary>
    Task ProcessAsync(InboundFrame frame, CancellationToken cancellationToken);
}
