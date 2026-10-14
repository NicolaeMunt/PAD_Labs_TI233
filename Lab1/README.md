# Lab 1 — Publish/Subscribe Message Broker

Three independent programs that talk over TCP using newline-delimited JSON (NDJSON):

| Module | Language | Role | Folder |
|---|---|---|---|
| **Broker** | Java 17 | Accepts publishers and subscribers, routes messages per topic, stores unacked messages per subscriber and replays them on reconnect | [`Broker/`](Broker/README.md) |
| **Publisher** | Node.js + TypeScript | Two modes: a **web UI** (several publishers on one page, with demo buttons) or the terminal, where every line you type is published | [`Publisher/`](Publisher/README.md) |
| **Receiver** | C# / .NET 8 | Subscribes to one or more topics, prints every message and ACKs it | [`Receiver/`](Receiver/README.md) |

```
Publisher ──publish──►  Broker  ──MESSAGE──► Receiver
          ◄─publish_ack─        ◄───ACK─────
```

The broker listens on port **5000**. Start it first; the Publisher and Receiver can then be
started in any order. Both clients reconnect automatically if the broker restarts.

## Quick start: one command

With Docker Desktop installed, one script builds and starts the broker and the publisher web
UI (opened in your browser). The script's window then becomes a prompt where **you create
receivers while everything runs**. Each receiver opens in its own window.

**Windows:** double-click `start.cmd`, or run:

```powershell
powershell -ExecutionPolicy Bypass -File start.ps1                  # build, then start
powershell -ExecutionPolicy Bypass -File start.ps1 -SkipBuild       # start without rebuilding
powershell -ExecutionPolicy Bypass -File start.ps1 -DemoReceivers   # also start 4 demo receivers
powershell -ExecutionPolicy Bypass -File start.ps1 -Stop            # stop everything
```

**macOS / Linux / Git Bash:**

```bash
sh start.sh                     # build, then start
sh start.sh --skip-build        # start without rebuilding
sh start.sh --demo-receivers    # also start 4 demo receivers
sh start.sh --no-windows        # everything in this terminal (e.g. over SSH)
```

### Creating receivers at runtime

Type these at the script's `>` prompt:

| Command | What it does |
|---|---|
| `add <name> <topics>` | Creates a receiver in a new window, e.g. `add carol weather` or `add dave news,sports,weather` |
| `stop <name>` | Stops that receiver. The broker keeps the messages it misses. |
| `list` | Shows the running receivers and their topics |
| `quit` | Stops everything (Ctrl+C does too in `start.sh`) |

The name is the receiver's id. Using `add` again with the **same name** after `stop` brings the
receiver back and replays the messages it missed. That is the offline-replay demo. A new name
starts fresh.

A receiver can also be stopped with Ctrl+C in its own window, and started again there: type
`Start-Receiver` and press Enter on Windows, or just press Enter on macOS and Linux.

`-DemoReceivers` / `--demo-receivers` starts these four at once:

| Receiver | Topics | Shows |
|---|---|---|
| `alice` | `news`, `sports` | One receiver, several topics |
| `bob` | `news` | Topic isolation: he never sees `sports` or `weather` |
| `carol` | `weather` | Subscribing before any publisher exists: add `publisher-weather` / `weather` in the UI and she starts receiving |
| `dave` | `news`, `sports`, `weather` | Fan-out: he gets a copy of everything |

### Where the windows open

The broker's log, including `[DLQ]` lines, opens in its own window. With Windows Terminal as the
default terminal, the log and receiver windows open as tabs. `start.ps1` starts Docker Desktop
if it isn't running.

On macOS the windows are Terminal windows. On Linux they use gnome-terminal, konsole,
xfce4-terminal or xterm. If no window can be opened, or with `--no-windows`, everything runs in
the script's terminal, with each log line prefixed by its source (`broker |`, `carol |`, …).

Then try the steps in [What to try](#what-to-try-feature-walkthrough).

---

To start each part yourself, there are two ways:

- **Option A: Docker.** You only need Docker Desktop.
- **Option B: native.** You need JDK 17+ with Maven, Node.js 18+, and the .NET 8 SDK.

---

## Option A: Docker (recommended)

Run every command from this `Lab1/` folder. Make sure Docker Desktop is running.

**1. Build all three images** (only needed once, and again after code changes):

```bash
docker compose --profile clients build
```

**2. Start the broker and the publisher web UI** (terminal 1):

```bash
docker compose up broker publisher-ui
```

Open **http://localhost:3000**. Add a publisher (id + topic) and start sending messages.
The UI is described in [Publisher web UI](#publisher-web-ui) below.

**3. Start one or more receivers** (one terminal each):

```bash
docker compose run --rm receiver -t news,sports -c alice -w 1
docker compose run --rm receiver -t news -c bob
```

**4. Optional: terminal publishers** (one terminal each, one topic per publisher), if you
prefer the terminal to the web UI:

```bash
docker compose run --rm publisher publisher-news news broker
docker compose run --rm publisher publisher-sports sports broker
```

Publisher arguments: `<publisherId> <topic> [host] [port]`. Inside Docker the broker's host
name is `broker`. Type a line and press Enter to publish it. Type `/quit` to exit.

**5. Stop everything:** press Ctrl+C in each terminal, then run `docker compose down`.

> The broker's port 5000 is also published on the host. This means you can mix modes, for
> example a broker in Docker with a natively-run Publisher or Receiver on `127.0.0.1:5000`.

---

## Option B: native build

**1. Build everything** with the script, which skips any module whose toolchain is missing:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1     # Windows
```
```bash
sh build.sh                                             # Linux / macOS / Git Bash
```

This produces:

| Module | Artifact |
|---|---|
| Broker | `Broker/target/Broker-1.0-SNAPSHOT.jar` |
| Publisher | `Publisher/dist/index.js` |
| Receiver | `Receiver/out/Receiver.dll` |

**2. Start the broker** (terminal 1):

```bash
java -jar Broker/target/Broker-1.0-SNAPSHOT.jar          # port 5000; pass a number to change it
```

**3. Start one or more receivers** (one terminal each):

```bash
dotnet Receiver/out/Receiver.dll -t news,sports -c alice
dotnet Receiver/out/Receiver.dll -t news -c bob
```

Receiver options: `-h host` (default `127.0.0.1`), `-p port` (default `5000`), `-t topics`
(repeat or comma-separate), `-c clientId` (stable id used for replay), `-w workers`,
`-l logfile`, `--verbose`. Run `--help` for the full list.

**4. Start the publisher web UI**, then open **http://localhost:3000**:

```bash
node Publisher/dist/index.js --ui                                 # broker 127.0.0.1:5000, UI port 3000
node Publisher/dist/index.js --ui 127.0.0.1 5000 3000             # [brokerHost] [brokerPort] [uiPort]
```

Or use terminal publishers instead (one terminal each):

```bash
node Publisher/dist/index.js publisher-news news                  # defaults to 127.0.0.1:5000
node Publisher/dist/index.js publisher-sports sports 127.0.0.1 5000
```

Without the build step, you can also run each module from source: `mvn package` in `Broker/`,
`npm install` and then `npm run ui` or `npm run dev -- <publisherId> <topic>` in `Publisher/`,
and `dotnet run -- -t news -c alice` in `Receiver/src/`.

---

## Publisher web UI

![Publisher web UI](Publisher/docs/ui.png)

- **Add publisher:** each publisher gets its own panel and its own TCP connection to the
  broker. Add several to show topic isolation, or two on the same topic to show that the
  broker allows only one publisher per topic.
- **Status badge:** *Connecting*, *Registering*, *Registered*, *Rejected* (with the broker's
  reason), or *Disconnected* with a countdown to the next reconnect attempt (1s, 2s, 4s … 30s).
- **Message log:** each message moves from *queued* (waiting for registration) to *sent*
  (written to the socket) to *acked* (the broker's `publish_ack`). A *rejected* message shows
  the broker's reason. The counters above the log sum up the statuses.
- **Send 10:** publishes 10 numbered messages at once, to show ordering.
- **Drop connection:** closes the TCP connection as if the network failed. Messages sent
  meanwhile wait as *queued*. The publisher reconnects, registers again, and sends them.
  Messages that were sent but not yet acked are resent with the same `messageId`.
- **Send invalid:** sends a deliberately broken frame (missing `messageId`, wrong topic,
  unknown type, or not JSON). The broker rejects it with a reason and records it in its Dead
  Letter Queue.
- **What to try:** a short checklist on the page itself.

---

## What to try (feature walkthrough)

Start the broker and the web UI. Start receiver `alice` on `news,sports` with `-w 1`, and in
the UI add `publisher-news` on `news`, as shown above.

| # | Feature | How to see it |
|---|---|---|
| 1 | **Basic routing** | Send `hello` from the `publisher-news` panel. It turns *acked*, and alice prints `[time] [news] publisher-news #<id>: hello`. |
| 2 | **Topic isolation** | Add `publisher-sports` on `sports` and start a receiver `bob` on `news` only. Sports messages reach alice but not bob. |
| 3 | **Fan-out** | With alice and bob both on `news`, each message from `publisher-news` is printed by both. |
| 4 | **Ordering** | Click *Send 10*. Alice (started with `-w 1`) prints #1 … #10 in order. With the default 2 workers, the Receiver may print them slightly out of order; the broker still delivers them in order. |
| 5 | **Subscribe before publish** | Start a receiver on a topic that has no publisher yet, e.g. `-t weather -c carol`, then add a `weather` publisher in the UI. Carol receives its messages. |
| 6 | **Offline storage + replay (at-least-once)** | Stop alice with Ctrl+C. Send a few `news` messages, then restart alice with the **same** `-c alice`. The missed messages are printed right after she reconnects. The broker log shows `Replaying pending messages`. |
| 7 | **ACKs** | The broker log shows `Ack received - subscriberId=alice messageId=...` for every delivered message. Acked messages are never replayed again. |
| 8 | **One publisher per topic** | Add a second publisher with a different id on `news`, e.g. `intruder`. Its panel shows *Rejected: Topic 'news' already has a registered publisher*. |
| 9 | **Publisher reconnect** | Click *Drop connection* and send a message right away. It waits as *queued*, then goes to *acked* once the publisher has reconnected and registered again. Restarting the broker shows the same. |
| 10 | **Validation + Dead Letter Queue** | Pick an invalid frame and click *Send invalid*. The panel shows the broker's reason, and the broker logs `[DLQ] Message rejected - reason=...`. |
| 11 | **Receiver deduplication** | If the broker redelivers a message the receiver has already handled, the receiver skips it and re-ACKs it. Run with `--verbose` to see `Duplicate ... skipped`. |

> The broker keeps all state **in memory**. Restarting the broker clears topics,
> registrations and pending messages.

---

## How the three modules fit together

The Publisher and Receiver were written against two slightly different envelope formats:

- The **Publisher** uses the broker's native format: `register_publisher`/`publish`,
  `publisherId`, and details in `message`.
- The **Receiver** uses a compact format: `SUBSCRIBE`/`ACK`/`MESSAGE`/`ERROR`, `clientId`, and
  details in `payload`.

The broker accepts **both** formats on the same port. For each connection it detects which
format the client uses and replies in that format. Neither client needs any changes. The
mapping is documented in [Broker README §6](Broker/README.md#6-compatibility-with-the-receiver-compact-dialect).

For details on each module, see its own README:

- [Broker/README.md](Broker/README.md): wire protocol, delivery guarantees, concurrency model
- [Publisher/README.md](Publisher/README.md)
- [Receiver/README.md](Receiver/README.md): options, robustness, design rationale
