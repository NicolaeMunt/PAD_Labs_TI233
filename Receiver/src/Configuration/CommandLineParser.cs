namespace MessageBroker.Receiver.Configuration;

/// <summary>
/// Minimal, dependency-free parser for the receiver's command-line arguments.
/// </summary>
public static class CommandLineParser
{
    /// <summary>Usage text printed for <c>--help</c> or on invalid input.</summary>
    public const string Usage = """
        Usage: Receiver [options]

          -h, --host <host>          Broker host                       (default 127.0.0.1)
          -p, --port <port>          Broker port                       (default 5000)
          -t, --topic <topic>        Topic to subscribe to; repeat the option or pass a
                                     comma-separated list for several  (default news)
          -c, --client-id <id>       Stable subscriber id              (default receiver-<machine>)
          -w, --workers <n>          Number of handler workers         (default 2)
          -l, --log-file <path>      Also append messages to this file
              --max-frame <bytes>    Maximum frame length              (default 1048576)
              --queue-capacity <n>   Inbound queue bound, 0=unbounded  (default 0)
              --verbose              Print debug-level log lines
              --help                 Show this text

        Example:
          Receiver --host 127.0.0.1 --port 5000 --topic news --topic sports --client-id alice
        """;

    /// <summary>
    /// Parses <paramref name="args"/> into a <see cref="ReceiverOptions"/> instance.
    /// </summary>
    /// <param name="args">Raw command-line arguments.</param>
    /// <returns>The parse result; never throws for bad input.</returns>
    public static CommandLineParseResult Parse(IReadOnlyList<string> args)
    {
        var options = new ReceiverOptions();
        var topics = new List<string>();

        for (var i = 0; i < args.Count; i++)
        {
            var arg = args[i];

            if (arg is "--help" or "-?" or "/?")
            {
                return new CommandLineParseResult(null, null, ShowHelp: true);
            }

            if (arg == "--verbose")
            {
                options = options with { Verbose = true };
                continue;
            }

            if (i + 1 >= args.Count)
            {
                return Fail($"Missing value for option '{arg}'.");
            }

            var value = args[++i];

            switch (arg)
            {
                case "-h" or "--host":
                    if (string.IsNullOrWhiteSpace(value)) return Fail("Host must not be empty.");
                    options = options with { Host = value.Trim() };
                    break;

                case "-p" or "--port":
                    if (!int.TryParse(value, out var port) || port is < 1 or > 65535)
                        return Fail($"Invalid port '{value}'. Expected 1-65535.");
                    options = options with { Port = port };
                    break;

                case "-t" or "--topic" or "--topics":
                    topics.AddRange(value.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
                    break;

                case "-c" or "--client-id":
                    if (string.IsNullOrWhiteSpace(value)) return Fail("Client id must not be empty.");
                    options = options with { ClientId = value.Trim() };
                    break;

                case "-w" or "--workers":
                    if (!int.TryParse(value, out var workers) || workers is < 1 or > 64)
                        return Fail($"Invalid worker count '{value}'. Expected 1-64.");
                    options = options with { WorkerCount = workers };
                    break;

                case "-l" or "--log-file":
                    if (string.IsNullOrWhiteSpace(value)) return Fail("Log file path must not be empty.");
                    options = options with { LogFilePath = value };
                    break;

                case "--max-frame":
                    if (!int.TryParse(value, out var maxFrame) || maxFrame < 64)
                        return Fail($"Invalid max frame size '{value}'. Expected at least 64 bytes.");
                    options = options with { MaxFrameBytes = maxFrame };
                    break;

                case "--queue-capacity":
                    if (!int.TryParse(value, out var capacity) || capacity < 0)
                        return Fail($"Invalid queue capacity '{value}'. Expected 0 or more.");
                    options = options with { QueueCapacity = capacity };
                    break;

                default:
                    return Fail($"Unknown option '{arg}'.");
            }
        }

        if (topics.Count > 0)
        {
            options = options with { Topics = topics.Distinct(StringComparer.Ordinal).ToArray() };
        }

        return new CommandLineParseResult(options, null, ShowHelp: false);
    }

    private static CommandLineParseResult Fail(string error) => new(null, error, ShowHelp: false);
}
