using MessageBroker.Receiver.Logging;

namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// Splits a byte stream into NDJSON frames (one per <c>'\n'</c>-terminated line).
/// </summary>
/// <remarks>
/// TCP is a byte stream, so a single read can return half a frame, several frames, or the tail
/// of one frame plus the head of the next. This reader keeps unconsumed bytes between reads and
/// only yields complete lines. Lines longer than the configured limit are skipped up to their
/// terminating newline without ever being buffered in full, so a hostile or buggy peer cannot
/// exhaust memory.
/// </remarks>
public sealed class NdjsonFrameReader
{
    private const byte NewLine = (byte)'\n';
    private const byte CarriageReturn = (byte)'\r';
    private static readonly byte[] Utf8Bom = { 0xEF, 0xBB, 0xBF };

    private readonly Stream _stream;
    private readonly int _maxFrameBytes;
    private readonly ILogger _logger;

    private readonly byte[] _readBuffer = new byte[16 * 1024];
    private int _readStart;
    private int _readEnd;

    private readonly MemoryStream _line = new();
    private bool _discarding;
    private long _discardedBytes;

    /// <summary>Creates a reader over <paramref name="stream"/>.</summary>
    /// <param name="stream">The connected network stream.</param>
    /// <param name="maxFrameBytes">Maximum accepted frame length in bytes, excluding the newline.</param>
    /// <param name="logger">Logger for dropped frames.</param>
    public NdjsonFrameReader(Stream stream, int maxFrameBytes, ILogger logger)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maxFrameBytes, 1);
        _stream = stream ?? throw new ArgumentNullException(nameof(stream));
        _maxFrameBytes = maxFrameBytes;
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));
    }

    /// <summary>
    /// Reads the next non-empty frame.
    /// </summary>
    /// <returns>
    /// The frame bytes (without the line terminator), or <c>null</c> when the peer closed the connection.
    /// </returns>
    /// <exception cref="IOException">The connection failed.</exception>
    /// <exception cref="OperationCanceledException"><paramref name="cancellationToken"/> was cancelled.</exception>
    public async ValueTask<byte[]?> ReadFrameAsync(CancellationToken cancellationToken)
    {
        while (true)
        {
            if (_readStart == _readEnd)
            {
                var read = await _stream.ReadAsync(_readBuffer.AsMemory(), cancellationToken).ConfigureAwait(false);
                if (read == 0)
                {
                    ReportTruncatedTail();
                    return null;
                }

                _readStart = 0;
                _readEnd = read;
            }

            // Span-based scanning lives in a synchronous helper (C# 12 forbids spans in async methods).
            var frame = ConsumeBuffered();
            if (frame is not null)
            {
                return frame;
            }
        }
    }

    /// <summary>
    /// Consumes buffered bytes up to and including the next newline, if any.
    /// </summary>
    /// <returns>A complete frame, or <c>null</c> if more bytes are needed or the line was skipped.</returns>
    private byte[]? ConsumeBuffered()
    {
        var available = _readBuffer.AsSpan(_readStart, _readEnd - _readStart);
        var newLineIndex = available.IndexOf(NewLine);
        var chunk = newLineIndex >= 0 ? available[..newLineIndex] : available;
        _readStart += newLineIndex >= 0 ? newLineIndex + 1 : available.Length;

        Append(chunk);

        if (newLineIndex < 0)
        {
            return null; // frame not complete yet — caller reads more bytes
        }

        if (_discarding)
        {
            _logger.Warn($"Dropped oversized frame of {_discardedBytes} bytes (limit {_maxFrameBytes}).");
            _discarding = false;
            _discardedBytes = 0;
            return null;
        }

        return TakeLine();
    }

    private void Append(ReadOnlySpan<byte> chunk)
    {
        if (_discarding)
        {
            _discardedBytes += chunk.Length;
            return;
        }

        // The +1 allows for a trailing '\r' that will be stripped (CRLF-terminated lines).
        if (_line.Length + chunk.Length > _maxFrameBytes + 1)
        {
            _discarding = true;
            _discardedBytes = _line.Length + chunk.Length;
            _line.SetLength(0);
            return;
        }

        _line.Write(chunk);
    }

    /// <summary>Returns the buffered line as a frame, or <c>null</c> if it is blank.</summary>
    private byte[]? TakeLine()
    {
        var span = _line.GetBuffer().AsSpan(0, (int)_line.Length);

        if (span.StartsWith(Utf8Bom))
        {
            span = span[Utf8Bom.Length..];
        }

        if (!span.IsEmpty && span[^1] == CarriageReturn)
        {
            span = span[..^1];
        }

        if (span.Length > _maxFrameBytes)
        {
            _logger.Warn($"Dropped oversized frame of {span.Length} bytes (limit {_maxFrameBytes}).");
            _line.SetLength(0);
            return null;
        }

        var frame = span.Trim(" \t"u8).IsEmpty ? null : span.ToArray();
        _line.SetLength(0);
        return frame;
    }

    private void ReportTruncatedTail()
    {
        if (_line.Length > 0 || _discarding)
        {
            _logger.Warn($"Connection closed mid-frame; discarded {Math.Max(_line.Length, _discardedBytes)} incomplete bytes.");
        }

        _line.SetLength(0);
        _discarding = false;
        _discardedBytes = 0;
    }
}
