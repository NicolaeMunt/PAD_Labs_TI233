namespace MessageBroker.Receiver.Networking;

/// <summary>
/// Doubling back-off: <c>initial × 2^(n-1)</c>, capped at a maximum — 1s, 2s, 4s, 8s, 16s, 30s, 30s, …
/// </summary>
public sealed class ExponentialBackoffPolicy : IBackoffPolicy
{
    private readonly TimeSpan _initial;
    private readonly TimeSpan _maximum;

    /// <summary>Creates the policy.</summary>
    /// <param name="initial">Delay after the first failure.</param>
    /// <param name="maximum">Upper bound for any delay.</param>
    public ExponentialBackoffPolicy(TimeSpan initial, TimeSpan maximum)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(initial, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThan(maximum, initial);
        _initial = initial;
        _maximum = maximum;
    }

    /// <inheritdoc />
    public TimeSpan GetDelay(int consecutiveFailures)
    {
        // Clamp the exponent so the multiplication can never overflow.
        var exponent = Math.Clamp(consecutiveFailures - 1, 0, 30);
        var ticks = _initial.Ticks * (double)(1L << exponent);
        return ticks >= _maximum.Ticks ? _maximum : TimeSpan.FromTicks((long)ticks);
    }
}
