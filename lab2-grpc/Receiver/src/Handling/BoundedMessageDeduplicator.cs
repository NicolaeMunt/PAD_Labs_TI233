namespace MessageBroker.Receiver.Handling;

/// <summary>
/// In-memory de-duplicator that remembers the most recent <c>capacity</c> ids (FIFO eviction),
/// so memory stays bounded however long the receiver runs.
/// </summary>
/// <remarks>
/// State is not persisted: after a process restart, replayed messages that were already handled
/// before the crash but not yet ACKed will be shown again (at-least-once delivery).
/// </remarks>
public sealed class BoundedMessageDeduplicator : IMessageDeduplicator
{
    private readonly int _capacity;
    private readonly HashSet<string> _seen;
    private readonly Queue<string> _order;
    private readonly object _sync = new();

    /// <summary>Creates a de-duplicator remembering up to <paramref name="capacity"/> ids.</summary>
    public BoundedMessageDeduplicator(int capacity)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(capacity, 1);
        _capacity = capacity;
        _seen = new HashSet<string>(capacity, StringComparer.Ordinal);
        _order = new Queue<string>(capacity);
    }

    /// <inheritdoc />
    public bool TryRegister(string messageId)
    {
        lock (_sync)
        {
            if (!_seen.Add(messageId))
            {
                return false;
            }

            _order.Enqueue(messageId);
            while (_order.Count > _capacity)
            {
                _seen.Remove(_order.Dequeue());
            }

            return true;
        }
    }

    /// <inheritdoc />
    public void Forget(string messageId)
    {
        lock (_sync)
        {
            // The stale entry stays in the eviction queue. If the id is registered again, the
            // stale entry is evicted first, which only shortens how long the id is remembered.
            _seen.Remove(messageId);
        }
    }
}
