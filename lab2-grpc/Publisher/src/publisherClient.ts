import { EventEmitter } from "node:events";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";

export type ConnectionState = "connecting" | "registering" | "registered" | "rejected" | "disconnected" | "stopped";
export type MessageStatus = "queued" | "sent" | "acked" | "rejected";
export type InvalidFrameKind = "missing-id" | "wrong-topic" | "empty-payload" | "empty-frame";
export const INVALID_FRAME_KINDS: readonly InvalidFrameKind[] = ["missing-id", "wrong-topic", "empty-payload", "empty-frame"];

export type ConnectionStatus = {
  state: ConnectionState;
  detail?: string;
  retryInMs?: number;
};

export type TrackedMessage = {
  messageId: string;
  payload: string;
  timestamp: string;
  status: MessageStatus;
  reason?: string;
  /** Position in the topic's history, from the broker's ack. */
  sequence?: number;
  /** A deliberately invalid frame, not a regular publish. */
  raw?: boolean;
};

// Shapes of the pubsub.proto messages as @grpc/proto-loader maps them (camelCase fields).
type PublishMessage = { messageId: string; topic: string; payload: string; timestamp: string };
type PublisherFrame = { register?: { publisherId: string; topic: string }; message?: PublishMessage };
type PublisherReply = {
  kind?: "registered" | "ack" | "rejected";
  registered?: { detail: string };
  ack?: { messageId: string; sequence: number; duplicate: boolean };
  rejected?: { messageId: string; reason: string };
};

type OutboundFrame =
  | { kind: "register" }
  | { kind: "message"; message: TrackedMessage; frame: PublisherFrame };

// Loaded at runtime from the .proto shared with the Broker and the Receiver (no generated code).
// Works from src/ (tsx) and dist/ (compiled): both sit next to ../../proto. PROTO_PATH overrides it.
const PROTO_PATH = process.env.PROTO_PATH ?? join(__dirname, "..", "..", "proto", "pubsub.proto");
let brokerClientType: grpc.ServiceClientConstructor | undefined;

function createBrokerClient(address: string): grpc.Client {
  if (!brokerClientType) {
    const definition = protoLoader.loadSync(PROTO_PATH, { longs: Number, defaults: true, oneofs: true });
    const loaded = grpc.loadPackageDefinition(definition) as unknown as {
      pad: { pubsub: { Broker: grpc.ServiceClientConstructor } };
    };
    brokerClientType = loaded.pad.pubsub.Broker;
  }
  return new brokerClientType(address, grpc.credentials.createInsecure(), {
    // HTTP/2 pings detect a dead connection even when nothing is being published.
    "grpc.keepalive_time_ms": 20_000,
    "grpc.keepalive_timeout_ms": 10_000,
    "grpc.keepalive_permit_without_calls": 1
  });
}

/**
 * One publisher: opens the broker's bidirectional `Publish` stream, registers `publisherId` for
 * `topic`, publishes messages, and opens a new stream with exponential backoff (1–30 s) when it
 * breaks. Messages published while not registered are queued and flushed after registration.
 *
 * The broker answers every frame on a stream exactly once and in order, so replies are matched
 * to requests with a FIFO of frames awaiting an answer. Messages that were sent but not confirmed
 * when the stream breaks are re-queued and resent (same messageId); the broker stores each id once.
 *
 * Events: `status` (ConnectionStatus) and `message` (TrackedMessage, on every status change).
 */
export class PublisherClient extends EventEmitter {
  private readonly client: grpc.Client;
  private call: grpc.ClientDuplexStream<PublisherFrame, PublisherReply> | undefined;
  private registered = false;
  private stopped = false;
  private reconnectDelayMs = 1_000;
  private reconnectTimer: NodeJS.Timeout | undefined;
  private lastError: string | undefined;
  private readonly outbox: Extract<OutboundFrame, { kind: "message" }>[] = [];
  private awaitingReply: OutboundFrame[] = [];
  private currentStatus: ConnectionStatus = { state: "connecting" };

  constructor(
    readonly publisherId: string,
    readonly topic: string,
    readonly host: string,
    readonly port: number
  ) {
    super();
    this.client = createBrokerClient(`${host}:${port}`);
  }

  get status(): ConnectionStatus {
    return this.currentStatus;
  }

  get isRegistered(): boolean {
    return this.registered;
  }

  start(): void {
    this.connect();
  }

  publish(payload: string): TrackedMessage {
    const message: TrackedMessage = {
      messageId: randomUUID(),
      payload,
      timestamp: new Date().toISOString(),
      status: "queued"
    };
    const frame: PublisherFrame = {
      message: { messageId: message.messageId, topic: this.topic, payload, timestamp: message.timestamp }
    };
    this.outbox.push({ kind: "message", message, frame });
    this.emit("message", message);
    this.flush();
    return message;
  }

  /** Sends a deliberately invalid frame right away, so the broker's validation can be demonstrated. */
  sendInvalid(kind: InvalidFrameKind): TrackedMessage {
    const timestamp = new Date().toISOString();
    const valid: PublishMessage = { messageId: randomUUID(), topic: this.topic, payload: "invalid on purpose", timestamp };
    const [label, frame]: [string, PublisherFrame] = (() => {
      switch (kind) {
        case "missing-id":
          return ["Invalid: publish without message_id", { message: { ...valid, messageId: "" } }];
        case "wrong-topic":
          return [`Invalid: publish to '${this.topic}-other' (not this publisher's topic)`, { message: { ...valid, topic: `${this.topic}-other` } }];
        case "empty-payload":
          return ["Invalid: empty payload", { message: { ...valid, payload: "" } }];
        case "empty-frame":
          return ["Invalid: frame with neither register nor message", {}];
      }
    })();

    const message: TrackedMessage = {
      messageId: `raw-${randomUUID().slice(0, 8)}`,
      payload: label,
      timestamp,
      status: "queued",
      raw: true
    };
    if (this.write(frame)) {
      message.status = "sent";
      this.awaitingReply.push({ kind: "message", message, frame });
    } else {
      message.status = "rejected";
      message.reason = "Not connected to the broker";
    }
    this.emit("message", message);
    return message;
  }

  /** Cancels the stream as if the network failed; the normal reconnect logic takes over. */
  dropConnection(): void {
    if (!this.call) return;
    this.lastError = "Connection dropped manually";
    this.call.cancel();
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.call?.end();
    this.call = undefined;
    this.client.close();
    this.setStatus({ state: "stopped" });
  }

  private connect(): void {
    if (this.stopped) return;
    this.registered = false;
    this.lastError = undefined;
    this.setStatus({ state: "connecting", detail: `${this.host}:${this.port}` });

    const call = (this.client as unknown as {
      publish(): grpc.ClientDuplexStream<PublisherFrame, PublisherReply>;
    }).publish();
    this.call = call;
    let finished = false;
    const finish = (error?: grpc.ServiceError) => {
      if (finished) return;
      finished = true;
      if (this.call === call) this.call = undefined;
      this.onStreamEnded(error);
    };

    call.on("data", (reply: PublisherReply) => this.handleReply(reply));
    call.on("error", (error: grpc.ServiceError) => finish(error));
    call.on("end", () => finish());

    // gRPC connects lazily: the TCP/HTTP/2 connection is opened by this first write.
    call.write({ register: { publisherId: this.publisherId, topic: this.topic } });
    this.awaitingReply.push({ kind: "register" });
    this.setStatus({ state: "registering", detail: `Registering '${this.publisherId}' for '${this.topic}'` });
  }

  private onStreamEnded(error?: grpc.ServiceError): void {
    this.registered = false;
    const refused = error && (error.code === grpc.status.ALREADY_EXISTS || error.code === grpc.status.INVALID_ARGUMENT);
    if (refused && this.awaitingReply[0]?.kind === "register") {
      // The broker refused the registration; retrying would be refused the same way.
      this.awaitingReply = [];
      this.setStatus({ state: "rejected", detail: error.details });
      return;
    }

    this.requeueUnconfirmed();
    if (this.stopped) return;
    if (error && error.code !== grpc.status.CANCELLED) this.lastError = error.details || error.message;
    this.scheduleReconnect();
  }

  private handleReply(reply: PublisherReply): void {
    const request = this.awaitingReply.shift();
    switch (reply.kind) {
      case "registered":
        this.registered = true;
        this.reconnectDelayMs = 1_000;
        this.setStatus({ state: "registered", detail: reply.registered?.detail ?? "Publisher registration accepted." });
        this.flush();
        break;
      case "ack":
        if (request?.kind === "message") {
          request.message.sequence = reply.ack?.sequence;
          this.updateMessage(request.message, "acked");
        }
        break;
      case "rejected":
        if (request?.kind === "message") {
          this.updateMessage(request.message, "rejected", reply.rejected?.reason ?? "unknown error");
        } else {
          this.emit("notice", `Broker rejected a frame: ${reply.rejected?.reason ?? "unknown error"}`);
        }
        break;
      default:
        this.emit("notice", `Unexpected broker reply: ${JSON.stringify(reply)}`);
    }
  }

  private flush(): void {
    if (!this.registered) return;
    while (this.outbox.length > 0) {
      const entry = this.outbox[0];
      if (!this.write(entry.frame)) return;
      this.outbox.shift();
      this.awaitingReply.push(entry);
      this.updateMessage(entry.message, "sent");
    }
  }

  /** Sent-but-unconfirmed publishes go back to the front of the outbox, in their original order. */
  private requeueUnconfirmed(): void {
    const unconfirmed = this.awaitingReply.filter(
      (frame): frame is Extract<OutboundFrame, { kind: "message" }> => frame.kind === "message"
    );
    this.awaitingReply = [];
    const resend = unconfirmed.filter((frame) => !frame.message.raw);
    for (const frame of unconfirmed) {
      if (frame.message.raw) this.updateMessage(frame.message, "rejected", "Stream closed before the broker replied");
    }
    this.outbox.unshift(...resend);
    for (const frame of resend) this.updateMessage(frame.message, "queued");
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;
    const delay = this.reconnectDelayMs;
    this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, 30_000);
    this.setStatus({ state: "disconnected", detail: this.lastError ?? "Stream closed", retryInMs: delay });
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  /** Returns false when there is no open stream. */
  private write(frame: PublisherFrame): boolean {
    if (!this.call || this.call.writableEnded || this.call.destroyed) return false;
    this.call.write(frame);
    return true;
  }

  private updateMessage(message: TrackedMessage, status: MessageStatus, reason?: string): void {
    message.status = status;
    message.reason = reason;
    this.emit("message", message);
  }

  private setStatus(status: ConnectionStatus): void {
    this.currentStatus = status;
    this.emit("status", status);
  }
}
