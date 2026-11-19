using System.Text;
using MessageBroker.Receiver.Handling;
using MessageBroker.Receiver.Logging;
using MessageBroker.Receiver.Networking;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// Routes decoded frames by type: MESSAGE → de-duplicate, handle, ACK; ACK/ERROR → log.
/// </summary>
public sealed class FrameProcessor : IFrameProcessor
{
    private const int PreviewLength = 120;

    private readonly IEnvelopeCodec _codec;
    private readonly IEnvelopeValidator _validator;
    private readonly IMessageDeduplicator _deduplicator;
    private readonly IReadOnlyList<IMessageHandler> _handlers;
    private readonly IMessageSender _sender;
    private readonly string _clientId;
    private readonly ILogger _logger;

    /// <summary>Creates the processor.</summary>
    public FrameProcessor(
        IEnvelopeCodec codec,
        IEnvelopeValidator validator,
        IMessageDeduplicator deduplicator,
        IReadOnlyList<IMessageHandler> handlers,
        IMessageSender sender,
        string clientId,
        ILogger logger)
    {
        _codec = codec;
        _validator = validator;
        _deduplicator = deduplicator;
        _handlers = handlers;
        _sender = sender;
        _clientId = clientId;
        _logger = logger;
    }

    /// <inheritdoc />
    public async Task ProcessAsync(InboundFrame frame, CancellationToken cancellationToken)
    {
        var decoded = _codec.Decode(frame.Data);
        if (!decoded.IsSuccess)
        {
            _logger.Warn($"Ignoring bad frame on connection #{frame.ConnectionId}: {decoded.Error}. Frame: {Preview(frame.Data)}");
            return;
        }

        var envelope = decoded.Envelope;
        if (!_validator.IsValid(envelope, out var validationError))
        {
            _logger.Warn($"Ignoring invalid frame on connection #{frame.ConnectionId}: {validationError}. Frame: {Preview(frame.Data)}");
            return;
        }

        switch (envelope.Type)
        {
            case MessageType.Message:
                await HandleMessageAsync(envelope, cancellationToken).ConfigureAwait(false);
                break;

            case MessageType.Ack:
                _logger.Debug($"Broker ACK: topic='{envelope.Topic}' payload='{envelope.Payload}'");
                break;

            case MessageType.Error:
                _logger.Warn($"Broker ERROR (topic='{envelope.Topic}'): {envelope.Payload}");
                break;
        }
    }

    private async Task HandleMessageAsync(Envelope message, CancellationToken cancellationToken)
    {
        var messageId = message.MessageId;

        if (messageId is not null && !_deduplicator.TryRegister(messageId))
        {
            _logger.Debug($"Duplicate {message.Describe()} skipped.");
            // Re-ACK: the broker evidently did not see our first ACK (e.g. it was lost in a disconnect).
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
            // Not ACKed and forgotten, so a later replay of the same id is processed again.
            if (messageId is not null)
            {
                _deduplicator.Forget(messageId);
            }

            _logger.Error($"Handler failed for {message.Describe()}", ex);
            return;
        }

        await SendAckAsync(message, cancellationToken).ConfigureAwait(false);
    }

    private async Task SendAckAsync(Envelope message, CancellationToken cancellationToken)
    {
        if (!await _sender.TrySendAsync(Envelope.AckFor(message, _clientId), cancellationToken).ConfigureAwait(false))
        {
            _logger.Debug($"ACK for {message.Describe()} not sent (no live connection); broker may replay it.");
        }
    }

    private static string Preview(byte[] data)
    {
        var text = Encoding.UTF8.GetString(data, 0, Math.Min(data.Length, PreviewLength * 4));
        text = MessageFormatter.Sanitize(text);
        return text.Length <= PreviewLength ? text : string.Concat(text.AsSpan(0, PreviewLength), "…");
    }
}
