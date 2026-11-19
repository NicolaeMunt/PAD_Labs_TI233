namespace MessageBroker.Receiver.Pipeline;

/// <summary>
/// A raw frame as read off the socket, queued for the workers.
/// </summary>
/// <param name="Data">UTF-8 bytes of one NDJSON line, without the terminator.</param>
/// <param name="ConnectionId">Which connection the frame arrived on (for logs).</param>
/// <param name="ReceivedAt">When the reader loop took the frame off the socket.</param>
public sealed record InboundFrame(byte[] Data, long ConnectionId, DateTimeOffset ReceivedAt);
