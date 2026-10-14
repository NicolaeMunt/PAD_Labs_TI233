using MessageBroker.Receiver.Handling;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Networking;

namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// Handles one delivered message: validate → de-duplicate → run the handlers → ACK.
/// </summary>
public sealed class FrameProcessor : IFrameProcessor
{
    private readonly IMessageDeduplicator _deduplicator;
    private readonly IReadOnlyList<IMessageHandler> _handlers;
    private readonly IMessageSender _sender;
    private readonly ILogger _logger;

    /// <summary>Creates the processor.</summary>
    public FrameProcessor(
        IMessageDeduplicator deduplicator,
        IReadOnlyList<IMessageHandler> handlers,
        IMessageSender sender,
        ILogger logger)
    {
        _deduplicator = deduplicator;
        _handlers = handlers;
        _sender = sender;
        _logger = logger;
    }

    /// <inheritdoc />
    public async Task ProcessAsync(InboundFrame frame, CancellationToken cancellationToken)
    {
        var message = ReceivedMessage.From(frame.Delivery);

        // Protobuf has no "required": a missing string arrives as "". Check what the handlers rely on.
        if (string.IsNullOrWhiteSpace(message.MessageId) || string.IsNullOrWhiteSpace(message.Topic))
        {
            _logger.Warn($"Ignoring message without id or topic on stream #{frame.StreamId}: {message.Describe()}");
            return;
        }

        if (!_deduplicator.TryRegister(message.MessageId))
        {
            // Seen earlier in this run, e.g. replayed again after the stream reconnected.
            _logger.Debug($"Duplicate {message.Describe()} skipped.");
            await SendAckAsync(message, cancellationToken).ConfigureAwait(false);
            return;
        }

        try
        {
            foreach (var handler in _handlers)
            {
                await handler.HandleAsync(message, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            // Not ACKed and forgotten, so a later redelivery of the same id is processed again.
            _deduplicator.Forget(message.MessageId);
            _logger.Error($"Handler failed for {message.Describe()}", ex);
            return;
        }

        await SendAckAsync(message, cancellationToken).ConfigureAwait(false);
    }

    private async Task SendAckAsync(ReceivedMessage message, CancellationToken cancellationToken)
    {
        if (!await _sender.TrySendAckAsync(message.MessageId, cancellationToken).ConfigureAwait(false))
        {
            _logger.Debug($"ACK for {message.Describe()} not sent (no open stream).");
        }
    }
}
