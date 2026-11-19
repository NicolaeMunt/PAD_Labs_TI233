using System.Globalization;
using System.Text;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Handling;

/// <summary>
/// Formats messages as single human-readable lines, e.g.
/// <c>[2026-09-16 14:03:07] [news] alice: hello world</c>.
/// </summary>
public static class MessageFormatter
{
    private const string Missing = "?";

    /// <summary>Formats <paramref name="message"/> as one line (no trailing newline).</summary>
    public static string Format(Envelope message)
    {
        var id = message.MessageId is null ? string.Empty : $" #{Sanitize(message.MessageId)}";
        return $"[{FormatTimestamp(message.Timestamp)}] [{Sanitize(message.Topic ?? Missing)}] " +
               $"{Sanitize(message.ClientId ?? Missing)}{id}: {Sanitize(message.Payload ?? string.Empty)}";
    }

    /// <summary>
    /// Converts an ISO-8601 timestamp to local time; returns the raw text if it cannot be parsed.
    /// </summary>
    public static string FormatTimestamp(string? timestamp)
    {
        if (string.IsNullOrWhiteSpace(timestamp))
        {
            return Missing;
        }

        return DateTimeOffset.TryParse(
            timestamp,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal,
            out var parsed)
            ? parsed.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture)
            : Sanitize(timestamp);
    }

    /// <summary>
    /// Escapes control characters (newlines, ANSI escape sequences, …) so untrusted text
    /// cannot break the one-line-per-message layout or manipulate the terminal.
    /// </summary>
    public static string Sanitize(string text)
    {
        if (!text.Any(char.IsControl))
        {
            return text;
        }

        var builder = new StringBuilder(text.Length + 8);
        foreach (var c in text)
        {
            builder.Append(c switch
            {
                '\n' => "\\n",
                '\r' => "\\r",
                '\t' => "\\t",
                _ when char.IsControl(c) => $"\\u{(int)c:x4}",
                _ => c.ToString(),
            });
        }

        return builder.ToString();
    }
}
