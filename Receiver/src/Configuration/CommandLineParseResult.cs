namespace MessageBroker.Receiver.Configuration;

/// <summary>
/// Outcome of parsing the command line.
/// </summary>
/// <param name="Options">The parsed options, or <c>null</c> if parsing failed or help was requested.</param>
/// <param name="Error">A human-readable error, or <c>null</c> on success.</param>
/// <param name="ShowHelp">Whether the user asked for the usage text.</param>
public sealed record CommandLineParseResult(ReceiverOptions? Options, string? Error, bool ShowHelp);
