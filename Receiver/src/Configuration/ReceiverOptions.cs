namespace MessageBroker.Receiver.Configuration;

/// <summary>
/// Immutable runtime configuration of the receiver, built from command-line arguments.
/// </summary>
public sealed record ReceiverOptions
{
    /// <summary>Default broker host.</summary>
    public const string DefaultHost = "127.0.0.1";

    /// <summary>Default broker port.</summary>
    public const int DefaultPort = 5000;

    /// <summary>Default topic subscribed to when none is given.</summary>
    public const string DefaultTopic = "news";

    /// <summary>Default maximum size of a single NDJSON frame, in bytes (1 MiB).</summary>
    public const int DefaultMaxFrameBytes = 1024 * 1024;

    /// <summary>Broker host name or IP address.</summary>
    public string Host { get; init; } = DefaultHost;

    /// <summary>Broker TCP port.</summary>
    public int Port { get; init; } = DefaultPort;

    /// <summary>Topics to subscribe to. One SUBSCRIBE frame is sent per topic.</summary>
    public IReadOnlyList<string> Topics { get; init; } = new[] { DefaultTopic };

    /// <summary>
    /// Stable subscriber identity. The broker uses it to replay messages queued while this
    /// subscriber was offline, so it must not change between restarts.
    /// </summary>
    public string ClientId { get; init; } = DefaultClientId();

    /// <summary>Number of worker tasks draining the inbound queue.</summary>
    public int WorkerCount { get; init; } = 2;

    /// <summary>Frames longer than this (in bytes, excluding the newline) are dropped.</summary>
    public int MaxFrameBytes { get; init; } = DefaultMaxFrameBytes;

    /// <summary>
    /// Capacity of the inbound queue. <c>0</c> means unbounded, which guarantees that slow
    /// handlers never stall socket reads.
    /// </summary>
    public int QueueCapacity { get; init; }

    /// <summary>Optional path of a file every received message is appended to.</summary>
    public string? LogFilePath { get; init; }

    /// <summary>How many message ids the de-duplicator remembers.</summary>
    public int DedupCapacity { get; init; } = 10_000;

    /// <summary>First reconnect delay.</summary>
    public TimeSpan InitialReconnectDelay { get; init; } = TimeSpan.FromSeconds(1);

    /// <summary>Upper bound for the reconnect delay.</summary>
    public TimeSpan MaxReconnectDelay { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>Timeout for a single TCP connect attempt.</summary>
    public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromSeconds(10);

    /// <summary>How long workers may keep draining the queue after Ctrl+C.</summary>
    public TimeSpan DrainTimeout { get; init; } = TimeSpan.FromSeconds(10);

    /// <summary>Whether debug-level log lines are printed.</summary>
    public bool Verbose { get; init; }

    /// <summary>
    /// Builds a client id that is stable across restarts on the same machine.
    /// </summary>
    public static string DefaultClientId() => $"receiver-{Environment.MachineName.ToLowerInvariant()}";
}
