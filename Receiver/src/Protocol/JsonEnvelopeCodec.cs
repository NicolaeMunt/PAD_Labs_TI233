using System.Buffers;
using System.Text.Json;

namespace MessageBroker.Receiver.Protocol;

/// <summary>
/// <see cref="IEnvelopeCodec"/> based on <see cref="System.Text.Json"/>.
/// Uses the low-level DOM/writer APIs instead of reflection-based serialization so that
/// the decoder can be tolerant (e.g. a numeric <c>payload</c> is accepted as its raw text).
/// </summary>
public sealed class JsonEnvelopeCodec : IEnvelopeCodec
{
    private const byte NewLine = (byte)'\n';

    private static readonly JsonDocumentOptions ReadOptions = new()
    {
        MaxDepth = 16,
        CommentHandling = JsonCommentHandling.Disallow,
        AllowTrailingCommas = false,
    };

    // Indented = false guarantees the encoded object never contains a raw newline:
    // newlines inside string values are escaped as \n by the writer.
    private static readonly JsonWriterOptions WriteOptions = new() { Indented = false };

    /// <inheritdoc />
    public byte[] Encode(Envelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);

        var buffer = new ArrayBufferWriter<byte>(256);
        using (var writer = new Utf8JsonWriter(buffer, WriteOptions))
        {
            writer.WriteStartObject();
            writer.WriteString(WireFields.Type, MessageTypeNames.ToWire(envelope.Type));
            writer.WriteString(WireFields.Topic, envelope.Topic ?? string.Empty);
            writer.WriteString(WireFields.ClientId, envelope.ClientId ?? string.Empty);
            writer.WriteString(WireFields.Payload, envelope.Payload ?? string.Empty);
            writer.WriteString(WireFields.Timestamp, envelope.Timestamp ?? Envelope.NowIso());
            if (envelope.MessageId is not null)
            {
                writer.WriteString(WireFields.MessageId, envelope.MessageId);
            }

            writer.WriteEndObject();
        }

        var bytes = new byte[buffer.WrittenCount + 1];
        buffer.WrittenSpan.CopyTo(bytes);
        bytes[^1] = NewLine;
        return bytes;
    }

    /// <inheritdoc />
    public DecodeResult Decode(ReadOnlyMemory<byte> frame)
    {
        try
        {
            using var document = JsonDocument.Parse(frame, ReadOptions);
            var root = document.RootElement;

            if (root.ValueKind != JsonValueKind.Object)
            {
                return DecodeResult.Fail($"expected a JSON object but got {root.ValueKind}");
            }

            var rawType = ReadString(root, WireFields.Type);
            if (string.IsNullOrWhiteSpace(rawType))
            {
                return DecodeResult.Fail("missing or empty 'type' field");
            }

            return DecodeResult.Ok(new Envelope
            {
                Type = MessageTypeNames.FromWire(rawType),
                RawType = rawType,
                Topic = ReadString(root, WireFields.Topic),
                ClientId = ReadString(root, WireFields.ClientId),
                Payload = ReadString(root, WireFields.Payload),
                Timestamp = ReadString(root, WireFields.Timestamp),
                MessageId = ReadString(root, WireFields.MessageId) ?? ReadString(root, WireFields.Id),
            });
        }
        catch (JsonException ex)
        {
            // Also covers invalid UTF-8, which JsonDocument reports as a JsonException.
            return DecodeResult.Fail($"malformed JSON: {ex.Message}");
        }
        catch (ArgumentException ex)
        {
            return DecodeResult.Fail($"unreadable frame: {ex.Message}");
        }
    }

    /// <summary>
    /// Reads a property as a string. Strings are returned verbatim, JSON <c>null</c> or a missing
    /// property yields <c>null</c>, and any other kind (number, object, …) yields its raw JSON text.
    /// </summary>
    private static string? ReadString(JsonElement obj, string name)
    {
        if (!obj.TryGetProperty(name, out var value))
        {
            return null;
        }

        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Null or JsonValueKind.Undefined => null,
            _ => value.GetRawText(),
        };
    }
}
