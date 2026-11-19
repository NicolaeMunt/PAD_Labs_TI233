using System.Text;
using MessageBroker.Receiver.Protocol;

namespace MessageBroker.Receiver.Handling;

/// <summary>
/// Appends each message as one line to a local UTF-8 text file.
/// </summary>
public sealed class FileMessageAppender : IMessageHandler, IAsyncDisposable
{
    private readonly StreamWriter _writer;
    private readonly SemaphoreSlim _lock = new(1, 1);

    /// <summary>Opens (or creates) <paramref name="path"/> for appending.</summary>
    /// <exception cref="IOException">The file cannot be opened.</exception>
    /// <exception cref="UnauthorizedAccessException">Access to the file is denied.</exception>
    public FileMessageAppender(string path)
    {
        var fullPath = Path.GetFullPath(path);
        var directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var stream = new FileStream(fullPath, FileMode.Append, FileAccess.Write, FileShare.Read);
        _writer = new StreamWriter(stream, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
        FullPath = fullPath;
    }

    /// <summary>Absolute path of the log file.</summary>
    public string FullPath { get; }

    /// <inheritdoc />
    public async Task HandleAsync(Envelope message, CancellationToken cancellationToken)
    {
        var line = MessageFormatter.Format(message);

        await _lock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await _writer.WriteLineAsync(line).ConfigureAwait(false);
            await _writer.FlushAsync(CancellationToken.None).ConfigureAwait(false);
        }
        finally
        {
            _lock.Release();
        }
    }

    /// <summary>Flushes and closes the file.</summary>
    public async ValueTask DisposeAsync()
    {
        await _lock.WaitAsync().ConfigureAwait(false);
        try
        {
            await _writer.DisposeAsync().ConfigureAwait(false);
        }
        finally
        {
            _lock.Release();
        }
    }
}
