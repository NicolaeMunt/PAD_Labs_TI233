using System.Globalization;
using System.Text;

namespace MessageBroker.Receiver.Handling;

/// <summary>
/// Formats messages as single human-readable lines, e.g.
/// <c>[2026-09-16 14:03:07] [news] publisher-news #3: hello world</c>, with a
/// <c>(history)</c> suffix for messages replayed from the topic's stored history.
/// </summary>
public static class MessageFormatter
{
    private const string Missing = "?";

    /// <summary>Formats <paramref name="message"/> as one line (no trailing newline).</summary>
    public static string Format(ReceivedMessage message)
    {
        var history = message.Replayed ? "  (history)" : string.Empty;
        return $"[{FormatTimestamp(message.Timestamp)}] [{Sanitize(message.Topic)}] " +
               $"{Sanitize(message.PublisherId)} #{message.Sequence}: {Sanitize(message.Payload)}{history}";
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
