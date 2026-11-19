# Receiver (Subscriber) — C# / .NET 8

The subscriber component of the PAD message-broker project. It keeps one long-lived TCP
connection to the (Java) broker, subscribes to one or more topics and prints every message
it receives. Everything on the wire is UTF-8 NDJSON, so no .NET-specific format crosses the socket.

- .NET 8 console app, no NuGet packages (it uses only `System.Text.Json` and `System.Threading.Channels` from the framework)
- Reconnects automatically with exponential backoff and re-subscribes with the same `clientId`
- Separate reader and worker pipelines, joined by a `Channel<T>`
- Ignores bad input instead of crashing: malformed JSON, unknown types, missing fields, oversized frames and dead connections are logged and skipped

---

## 1. Running it

Requires the [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0) (newer SDKs also build it).

```bash
cd Receiver/src
dotnet run                                   # 127.0.0.1:5000, topic "news"
dotnet run -- --port 5000 --topic news --topic sports --client-id alice
dotnet run -- -t news,sports,weather -c alice -w 4 --log-file logs/alice.log
dotnet run -- --help
```

Or build once and run the binary: `dotnet build -c Release`, then `bin/Release/net8.0/Receiver(.exe) ...`.

> If only a newer runtime (e.g. .NET 10) is installed, run with `DOTNET_ROLL_FORWARD=Major`.

| Option | Default | Meaning |
|---|---|---|
| `-h`, `--host <host>` | `127.0.0.1` | Broker host |
| `-p`, `--port <port>` | `5000` | Broker port |
| `-t`, `--topic <topic>` | `news` | Topic to subscribe to. Repeat the option or pass a comma list for several |
| `-c`, `--client-id <id>` | `receiver-<machine name>` | Stable subscriber id (see §3.4) |
| `-w`, `--workers <n>` | `2` | Number of handler workers (1–64) |
| `-l`, `--log-file <path>` | — | Also append every message to this file |
| `--max-frame <bytes>` | `1048576` | Longer frames are dropped |
| `--queue-capacity <n>` | `0` (unbounded) | Inbound queue bound (see §5.3) |
| `--verbose` | off | Also print debug lines (broker ACKs, duplicates, …) |

**Stopping:** Ctrl+C (or SIGTERM) shuts the receiver down gracefully. It stops reading, finishes
the messages already queued (up to 10 s), sends their ACKs and then closes the socket.
A second Ctrl+C exits immediately.

**Output:** received messages go to **stdout**, and diagnostics go to **stderr**:

```
18:09:01.975 INF [T15] Connecting to broker at 127.0.0.1:5000 (attempt 1)...
18:09:02.505 INF [T06] Connected as #1 to 127.0.0.1:5000; subscribed clientId='alice' to [news, sports]. Listening...
[2026-09-16 18:09:02] [news] java-pub #m1: hello 1
[2026-09-16 13:00:00] [sports] bob #m8: 42
18:09:03.301 WRN [T04] Connection #1 failed (IOException: ... forcibly closed by the remote host.)
18:09:03.302 INF [T04] Reconnecting in 1s (retry #1).
```

Message line format: `[local time] [topic] sender #messageId: payload`. Control characters
in the payload (newlines, ANSI escapes) are escaped, so each message stays on one line.

---

## 2. Project structure

```
Receiver/
├── README.md
└── src/
    ├── Receiver.csproj
    ├── Program.cs                        entry point + composition root (the only place that uses `new`)
    ├── Configuration/
    │   ├── ReceiverOptions.cs            immutable settings + defaults
    │   ├── CommandLineParser.cs          args → options
    │   └── CommandLineParseResult.cs
    ├── Logging/
    │   ├── ILogger.cs, LogLevel.cs, LoggerExtensions.cs
    │   ├── ConsoleLogger.cs              coloured, thread-safe, writes to stderr
    │   └── ConsoleLock.cs                shared lock so worker output doesn't interleave
    ├── Protocol/                         wire format only, no sockets
    │   ├── Envelope.cs                   the shared message envelope
    │   ├── MessageType.cs, MessageTypeNames.cs, WireFields.cs
    │   ├── IEnvelopeCodec.cs, JsonEnvelopeCodec.cs      JSON <-> Envelope
    │   ├── DecodeResult.cs
    │   ├── IEnvelopeValidator.cs, EnvelopeValidator.cs  required fields per type
    │   └── NdjsonFrameReader.cs          byte stream → lines, max-length enforcement
    ├── Networking/
    │   ├── IBrokerConnection.cs, TcpBrokerConnection.cs            one socket
    │   ├── IBrokerConnectionFactory.cs, TcpBrokerConnectionFactory.cs  connect timeout, keep-alive
    │   ├── IBackoffPolicy.cs, ExponentialBackoffPolicy.cs          1s,2s,4s … 30s
    │   ├── ISubscriptionClient.cs, SubscriptionClient.cs           READER PIPELINE + reconnect loop
    │   └── IMessageSender.cs             lets workers send ACKs on the current connection
    ├── Pipeline/
    │   ├── InboundFrame.cs               raw frame queued between the pipelines
    │   ├── ReceiverHost.cs               owns the Channel, orchestrates shutdown
    │   ├── WorkerPool.cs                 HANDLER PIPELINE (N workers)
    │   ├── IFrameProcessor.cs, FrameProcessor.cs  decode → validate → dedup → handle → ACK
    └── Handling/
        ├── IMessageHandler.cs
        ├── ConsoleMessagePrinter.cs, FileMessageAppender.cs
        ├── IMessageDeduplicator.cs, BoundedMessageDeduplicator.cs
        └── MessageFormatter.cs
```

### Data flow

```
             ┌──────────── Reader pipeline (1 task) ─────────────┐        ┌──── Handler pipeline (N tasks) ────┐
 broker ──TCP──► NdjsonFrameReader ──► SubscriptionClient.ReadLoop ──► Channel<InboundFrame> ──► WorkerPool
   ▲             (bytes → lines)        (no parsing, no handling)       (in-memory queue)          │
   │                                                                                               ▼
   │                                                                     FrameProcessor: decode → validate → dedup
   │                                                                                               │
   │                                                              ConsoleMessagePrinter / FileMessageAppender
   └──────────────────────── ACK (IMessageSender, current connection) ◄────────────────────────────┘
```

Most dependencies are interfaces (`IBrokerConnectionFactory`, `IBackoffPolicy`, `IEnvelopeCodec`,
`IMessageHandler`, …), and all of them are supplied through constructors in `Program.BuildHost`.
A new output (a database, or forwarding to another service) is one new `IMessageHandler`, with
no changes to networking code (open/closed principle). The reconnect logic can be tested with a
fake `IBrokerConnectionFactory`, without real sockets.

---

## 3. Wire protocol (for the broker implementers)

### 3.1 Transport and framing

| Item | Rule |
|---|---|
| Transport | TCP. The receiver opens **one** connection and keeps it open. The broker pushes messages into that same connection. |
| Framing | **NDJSON**: exactly one JSON object per line, terminated by `\n` (0x0A). |
| Encoding | UTF-8, **no BOM**. |
| Line endings | Send `\n`. The receiver also accepts `\r\n` and ignores blank lines. |
| Inside a frame | No raw newlines. A newline inside a string value must be escaped as `\n` (Jackson and Gson do this by default; do **not** enable pretty-printing). |
| Max frame size | 1 MiB by default (`--max-frame`). Longer lines are skipped up to the next `\n` and logged. |
| Reads | Frames can be split across TCP segments or coalesced. Never assume one `read()` equals one message; the receiver doesn't. |

### 3.2 Envelope

Every frame, in both directions, is this object:

```json
{
  "type":      "MESSAGE",
  "topic":     "news",
  "clientId":  "java-publisher-1",
  "payload":   "Hello, world",
  "timestamp": "2026-09-16T14:03:07.123Z",
  "messageId": "0d3c1f9e-6b1d-4a55-9a9e-8f0f7c2b1a11"
}
```

| Field | Type | Notes |
|---|---|---|
| `type` | string | `SUBSCRIBE`, `ACK`, `MESSAGE`, `ERROR`. Required. Case-insensitive on input; always upper-case on output. |
| `topic` | string | Topic name. Required on `MESSAGE`. |
| `clientId` | string | The author of the frame. On `MESSAGE` it is the **publisher's** id (printed as the sender). On frames the receiver sends, it is the receiver's own id. |
| `payload` | string | Message body. Required on `MESSAGE` (may be `""`). A non-string value (such as `42`) is accepted and shown as raw JSON text. |
| `timestamp` | string | ISO-8601, preferably UTC with `Z` (`Instant.now().toString()` in Java). Unparseable values are printed as-is. |
| `messageId` | string | **Optional extension (see §3.5).** Unique per message. `id` is accepted as an alias on input. |

Field names are case-sensitive. Unknown extra fields are ignored, so the broker may add more.

### 3.3 Frames

**Receiver → broker**

| type | When | Fields |
|---|---|---|
| `SUBSCRIBE` | Immediately after every (re)connect, **one frame per topic**, all before any other traffic | `topic` = topic, `clientId` = subscriber id, `payload` = `""`, `timestamp` |
| `ACK` | After a `MESSAGE` was handled (or recognised as a duplicate) | `topic` = message topic, `clientId` = subscriber id, `payload` = the messageId (or `""`), `messageId` = the messageId (only if the message had one), `timestamp` |

**Broker → receiver**

| type | Meaning | Receiver's reaction |
|---|---|---|
| `MESSAGE` | A published message | De-duplicate, print, optionally write to file, send `ACK` |
| `ACK` | E.g. confirmation of a `SUBSCRIBE` | Logged at debug level |
| `ERROR` | The broker rejected something. Put the reason in `payload`. | Logged as a warning. The connection stays open. |
| anything else | — | Logged and ignored |

The receiver does not wait for an ACK after `SUBSCRIBE`; it goes straight into listening mode.
The broker is free to send an ACK anyway.

### 3.4 Example session

```
→ {"type":"SUBSCRIBE","topic":"news","clientId":"alice","payload":"","timestamp":"2026-09-16T14:00:00.0000000Z"}
→ {"type":"SUBSCRIBE","topic":"sports","clientId":"alice","payload":"","timestamp":"2026-09-16T14:00:00.0010000Z"}
← {"type":"ACK","topic":"news","clientId":"broker","payload":"subscribed","timestamp":"2026-09-16T14:00:00.010Z"}
← {"type":"MESSAGE","topic":"news","clientId":"pub-1","payload":"Hello","timestamp":"2026-09-16T14:00:05.000Z","messageId":"42"}
→ {"type":"ACK","topic":"news","clientId":"alice","payload":"42","timestamp":"2026-09-16T14:00:05.0030000Z","messageId":"42"}
← {"type":"ERROR","topic":"weather","clientId":"broker","payload":"unknown topic","timestamp":"2026-09-16T14:00:06.000Z"}
```

(`→` receiver to broker, `←` broker to receiver; each line ends with `\n`.)

### 3.5 clientId, replay and delivery guarantees

- `clientId` is **stable across restarts**. It comes from `--client-id` and defaults to
  `receiver-<machine name>`. The broker should key its offline queue on it and replay the
  queued messages right after the SUBSCRIBE frames of a new connection.
- After a reconnect, the receiver expects a **burst** of replayed messages. The reader only
  moves bytes into the queue, so a burst of thousands of messages is absorbed without loss
  (tested with 2,000 replayed and 20,000 queued messages).
- **`messageId` (proposed extension).** The agreed envelope has no id field, but
  de-duplication needs one. If the broker adds a unique `messageId` to each `MESSAGE`:
  - the receiver skips duplicates (it remembers the last 10,000 ids in memory);
  - the receiver's `ACK` echoes the id, so the broker can remove exactly that message from the
    offline queue. That gives **at-least-once** delivery.
  Without `messageId`, everything still works, but duplicates are shown twice and ACKs carry
  no id.
- The ACK is sent **after** the message was handled. If handling fails, no ACK is sent and the
  id is forgotten, so a replay is processed again. A duplicate is ACKed again, because the
  broker evidently missed the first ACK.
- With `--workers` > 1, messages may be printed out of order. Use `-w 1` if strict per-connection
  order matters.

### 3.6 Checklist for the Java broker

- [ ] Writes `json + "\n"` using UTF-8 (`new OutputStreamWriter(out, StandardCharsets.UTF_8)`), with no pretty-printing
- [ ] Reads with `BufferedReader.readLine()` (or equivalent) and does not assume one read equals one message
- [ ] Accepts several `SUBSCRIBE` frames on one connection (one per topic)
- [ ] Keys subscriptions and the offline queue on `clientId`; on reconnect, replays queued messages after the SUBSCRIBE frames
- [ ] Treats a new connection with the same `clientId` as replacing the old, possibly half-open, one
- [ ] (Recommended) Adds a unique `messageId` to every `MESSAGE` and removes it from the queue when the matching `ACK` arrives
- [ ] Sends `timestamp` as ISO-8601 (`Instant.now().toString()`)
- [ ] Ignores unknown fields (`@JsonIgnoreProperties(ignoreUnknown = true)`)

---

## 4. Robustness details

| Situation | Handling |
|---|---|
| Broker not running at startup | Retry with backoff 1s → 2s → 4s → 8s → 16s → 30s → 30s … Every attempt is logged. |
| Broker restarts or the link drops | Read fails or returns EOF → close the socket → back off → reconnect → re-SUBSCRIBE with the same `clientId`. After a successful connection, the backoff restarts at 1s. |
| Half-open socket (peer vanished without FIN/RST) | TCP keep-alive (15 s idle, 5 s × 3 probes) fails the read after about 30 s, then the normal reconnect runs. |
| Connect hangs | 10 s connect timeout |
| Frame split or coalesced across reads | `NdjsonFrameReader` buffers until `\n` |
| Oversized line | Skipped byte by byte up to `\n` without being held in memory. Logged. |
| Malformed JSON, invalid UTF-8, non-object JSON | Logged with a 120-character preview, then skipped |
| Missing `type`, unknown `type`, MESSAGE without `topic`/`payload` | Logged and skipped |
| Handler throws | Logged; no ACK is sent; the worker keeps running |
| Any other exception in the read loop | Caught in `SubscriptionClient.RunAsync`, logged, and treated as a disconnect. Nothing reaches `Main`, and `Main` also has a final catch-all. |
| Ctrl+C / SIGTERM | Reader stops → queue completed → workers drain (10 s deadline) → ACKs sent → socket closed with a TCP shutdown |

---

## 5. Design rationale (for the project defense)

### 5.1 Why TCP rather than UDP

1. **Reliable, ordered delivery is required, and TCP provides it.** A broker must not silently
   lose or reorder messages. UDP may drop, duplicate or reorder datagrams. Over UDP we would
   have to rebuild sequence numbers, retransmission, acknowledgements and ordering ourselves,
   which amounts to reimplementing TCP badly.
2. **A long-lived, stateful session.** The design depends on a connection: the broker pushes
   into it, the subscription is tied to it, and a disconnect is a clear event that triggers
   reconnect and replay. UDP has no connection, so the broker could not tell whether a
   subscriber is still there without inventing heartbeats.
3. **Push through NAT and firewalls.** The receiver opens an outbound TCP connection and the
   broker writes back on it. With UDP, the broker would have to send datagrams to the client,
   and NAT mappings for that expire quickly.
4. **Message size.** A UDP datagram is limited to about 64 KB (and should stay near 1,400 bytes
   to avoid IP fragmentation, where losing one fragment loses the whole message). TCP is a
   byte stream, so messages of any length work with simple newline framing.
5. **Flow and congestion control.** If the receiver is slower than the broker, TCP's receive
   window slows the sender automatically. UDP would simply drop packets.
6. **Easy to interoperate.** Java (`Socket`/`ServerSocket`) and .NET (`TcpClient`) both
   support TCP streams directly, and newline-delimited JSON is trivial to produce and parse
   on both sides.

*UDP's trade-off:* lower latency and no head-of-line blocking. That matters for real-time
media or games, where a late packet is worthless. Here, every message matters more than
milliseconds.

*The one cost of TCP:* it is a **byte stream, not a message stream**. That is why the protocol
needs explicit framing (NDJSON), and why the receiver never assumes one `read()` equals one
message.

### 5.2 Why the reader and the handler run separately

The receiver has two independent pipelines connected by a `System.Threading.Channels` queue:

```
socket ──► [reader task] ──► Channel<InboundFrame> ──► [worker 1..N] ──► print / file / ACK
```

1. **Slow handling must not block the socket.** If the same loop both read and handled, a
   slow handler (disk I/O, a slow console, a future database write) would stop the reads.
   The OS receive buffer would fill, TCP would shrink its window to zero, and the broker's
   writes would block. One slow subscriber would then slow down the broker. With a separate
   reader, the socket is always drained quickly.
2. **Replay bursts.** Right after a reconnect, the broker sends everything queued while the
   receiver was offline. The reader only splits lines and enqueues them, so it keeps up with
   the burst, and the workers catch up at their own pace. In testing, 20,000 messages arrived
   at once; 17,939 were still queued when Ctrl+C was pressed, and all were handled and ACKed
   before the socket closed.
3. **Parallelism.** Handling scales with `--workers` while there is still exactly one reader,
   which is required, because a TCP stream can only be read sequentially.
4. **Failure isolation (single responsibility).** The reader deals with network problems
   (disconnects, reconnects, framing). Workers deal with content problems (bad JSON, handler
   errors). A malformed message cannot break the connection, and a dropped connection doesn't
   lose messages that were already read.
5. **Clean shutdown.** Separate pipelines let shutdown run in order: stop the producer,
   complete the queue, let consumers drain, then close the socket. In-flight messages are
   finished and ACKed, not dropped.

This is the classic **producer–consumer** pattern. `Channel<T>` is the modern, async-friendly
replacement for `BlockingCollection<T>`: waiting workers don't block threads (they `await`),
and it supports completion, which is how "drain, then stop" is expressed.

> Note on the `[Txx]` numbers in the log: the pipelines are async tasks on the .NET thread
> pool, not dedicated OS threads. A task can resume on any pool thread after an `await`, so
> thread ids vary. The separation is logical (independent tasks, concurrent execution),
> which is what matters. A pool thread is only occupied while there is work to do.

### 5.3 Unbounded vs bounded queue

By default the queue is **unbounded**, so reads are never blocked by handling, as the
requirements demand. The trade-off is memory: if the broker publishes faster than the receiver
can print, the queue keeps growing. `--queue-capacity N` bounds it. When the queue is full, the
reader waits, which applies back-pressure to the broker through TCP flow control. Messages are
never dropped in either mode.
