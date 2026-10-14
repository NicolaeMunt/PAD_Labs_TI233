# Lab 2 — Publish/Subscribe over gRPC, with in-memory message history

The same three programs as [Lab 1](../Lab1/README.md), in the same languages, but they talk
**gRPC** (HTTP/2 + Protocol Buffers) instead of NDJSON over raw TCP. The broker also keeps every
topic's recent messages **in memory**: any receiver that subscribes gets the topic's history first,
then live messages.

| Module | Language | Role | Folder |
|---|---|---|---|
| **Broker** | Java 17, grpc-java | gRPC server. Stores the last 1000 messages per topic and fans new ones out to subscribers | [`Broker/`](Broker) |
| **Publisher** | Node.js + TypeScript, @grpc/grpc-js | Web UI (several publishers on one page) or terminal mode | [`Publisher/`](Publisher) |
| **Receiver** | C# / .NET 8, Grpc.Net.Client | Subscribes to topics, prints the history and then live messages, ACKs each one | [`Receiver/`](Receiver) |

All three are generated from one contract, [`proto/pubsub.proto`](proto/pubsub.proto):

```
Publisher ══ Publish (bidi stream) ══►  Broker  ══ Subscribe (bidi stream) ══► Receiver
   Register, PublishMessage ...  ──►            ◄──  Subscribe, Ack ...
   ◄── Registered, PublishAck / PublishRejected     Subscribed, Delivery (history, then live) ──►
```

The broker listens on port **5000** (gRPC, plaintext HTTP/2).

## What the message history does

- A receiver that subscribes to a topic, **new or returning**, first receives every stored message
  of that topic, oldest first. These are printed in cyan with a `(history)` suffix. Then it receives
  live messages, printed in green.
- So a receiver that was stopped and started again sees the old messages again, including the ones
  published while it was offline. A brand-new receiver on `games` sees what was published on
  `games` before it existed.
- The broker keeps the last **1000 messages per topic** (`HISTORY_LIMIT` in `docker-compose.yml`,
  or the second broker argument). History is in memory only: restarting the broker clears it.
- Each message has a per-topic sequence number (`#1`, `#2`, …), shown by the receiver and the UI.

## Quick start (Docker)

You only need Docker Desktop. Run everything from this `lab2-grpc/` folder.

**1. Build the images** (once, and again after code changes):

```bash
docker compose --profile clients build
```

**2. Start the broker and the publisher web UI:**

```bash
docker compose up broker publisher-ui
```

Open **http://localhost:3000** and add a publisher, for example `publisher-games` on `games`.

**3. Start receivers** (one terminal each):

```bash
docker compose run --rm receiver -t games -c alice -w 1
docker compose run --rm receiver -t news,games -c bob
```

Receiver options: `-h host`, `-p port` (default `5000`), `-t topics` (comma-separated or repeated),
`-c clientId`, `-w workers`, `-l logfile`, `--verbose`. Run `--help` for the full list.

**4. Optional: a terminal publisher** instead of the UI. Type a line to publish it, and `/quit` to exit:

```bash
docker compose run --rm publisher publisher-news news broker
```

**5. Stop everything:** Ctrl+C in each terminal, then `docker compose --profile clients down`.

## Running natively

You need JDK 17+ with Maven, Node.js 18+, and the .NET 8 SDK. The `protoc` compiler is downloaded
automatically by Maven and by the `Grpc.Tools` NuGet package; the Publisher loads the `.proto` at
runtime.

```bash
# Broker
cd Broker && mvn package && java -jar target/Broker-2.0-SNAPSHOT.jar      # [port] [historyLimit]

# Receiver
cd Receiver/src && dotnet run -- -t games -c alice

# Publisher
cd Publisher && npm install && npm run ui                                  # [brokerHost] [brokerPort] [uiPort]
cd Publisher && npm run dev -- publisher-games games                       # terminal mode
```

> On Windows, `protoc` fails if the project path contains non-ASCII characters (for example a
> Desktop folder with a localized name). Build from a path with only ASCII characters, or use Docker.

## What to try

Start the broker and the UI. In the UI, add `publisher-games` on `games`.

| # | Feature | How to see it |
|---|---|---|
| 1 | **History for a new receiver** | Send two messages from `publisher-games` **before** starting any receiver. Then start `-t games -c alice`. Alice prints both, marked `(history)`. The broker logs `Replaying history - ... count=2`. |
| 2 | **Live delivery** | Send another message. Alice prints it in green, without the marker. |
| 3 | **Reconnect sees old messages again** | Stop alice (Ctrl+C), send a few messages, start her again with the same `-c alice`. She prints the topic's whole history, including what she missed, then continues live. |
| 4 | **Topic isolation and fan-out** | Add `publisher-news` on `news`. Start `bob` on `news,games`. Bob gets both topics; alice only `games`. |
| 5 | **Ordering** | Click *Send 10*. A receiver started with `-w 1` prints #1 … #10 in order. |
| 6 | **ACKs** | The broker logs `Ack received - subscriberId=alice messageId=...` for each message. |
| 7 | **One publisher per topic** | Add `intruder` on `games`. Its panel shows *Rejected: Topic 'games' already has a registered publisher*. The gRPC call ends with status `ALREADY_EXISTS`. |
| 8 | **Publisher reconnect** | Click *Drop connection* (cancels the gRPC stream) and send a message right away. It waits as *queued*, then turns *acked* once the publisher has a new stream. A resent message keeps its id, and the broker stores it only once. |
| 9 | **Validation + Dead Letter Queue** | Pick an invalid frame and click *Send invalid*. The panel shows the broker's reason, and the broker logs `[DLQ] Message rejected`. |
| 10 | **Broker restart** | Restart the broker (`docker compose restart broker`). Receivers and the UI publishers reconnect on their own. The history starts empty, because it is kept in memory. |
| 11 | **Same id twice** | Start a second receiver with `-c alice` while the first is running. The broker gives the stream to the newer one, and the older one stops with a message saying so. |

## How it works

### gRPC contract

Both RPCs are **bidirectional streams**, which replace Lab 1's long-lived TCP connections:

- **`Publish(stream PublisherFrame) returns (stream PublisherReply)`**: the first frame registers the
  publisher for a topic. The broker answers every following frame exactly once and in order, with a
  `PublishAck` (including the sequence number) or a `PublishRejected` (with the reason). A refused
  registration ends the call with a gRPC status (`ALREADY_EXISTS`, `INVALID_ARGUMENT`).
- **`Subscribe(stream SubscriberFrame) returns (stream SubscriberEvent)`**: the receiver sends one
  `Subscribe` with its id and topics. The broker answers `Subscribed` (with how many stored messages
  follow), then the history (`replayed = true`), then live `Delivery` messages. The receiver sends an
  `Ack` for each message on the same stream.

Protobuf has no "required" fields: a missing string arrives as `""`. The broker's validator checks
every field it relies on.

### Persistence and replay (Broker)

- `MessageStore` holds one `TopicLog` per topic: a bounded queue of `StoredMessage`s, a map from
  message id to message (so a publisher's resend is not stored twice), and the topic's live
  listeners.
- One lock per topic guards both the history and the listener set. Subscribing copies the history
  and adds the listener in one step, and publishing stores and fans out in one step. So every
  message reaches a subscriber exactly once: either in its history snapshot or live, never both and
  never neither.
- Live messages that arrive while a subscriber's history is still being collected are held back and
  sent right after the history, so the order is always: history, then live.
- A gRPC response stream must not be written from two threads at once. Each subscriber therefore has
  its own single-thread writer that sends its events in order.

### Connections

- Each program keeps one HTTP/2 connection to the broker, and gRPC reconnects it automatically. When
  a stream breaks, the Receiver and the Publisher open a new call, with exponential backoff
  (1s, 2s, 4s … 30s).
- HTTP/2 keepalive pings (clients every 20s, broker every 30s) detect connections that died without
  closing, for example after a laptop went to sleep.
- The broker allows one open stream per subscriber id. A reconnect that arrives before the broker
  noticed the old connection was dead replaces the stale stream.

### Differences from Lab 1

| | Lab 1 | Lab 2 |
|---|---|---|
| Transport | Raw TCP, newline-delimited JSON | gRPC over HTTP/2, Protocol Buffers |
| Contract | Implicit (two JSON dialects the broker translates) | One `.proto` file; code generated for Java and C#, loaded at runtime in Node |
| Errors | `error` frames with a message | gRPC status codes plus `PublishRejected` replies |
| Stored messages | Per subscriber, only unacked ones; replayed to the same id | Per topic, last 1000; replayed to **every** subscriber, new or returning |
| Subscribing | One `SUBSCRIBE` frame per topic | One `Subscribe` with all topics |
