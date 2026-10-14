import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { ConnectionStatus, INVALID_FRAME_KINDS, InvalidFrameKind, PublisherClient, TrackedMessage } from "../publisherClient.js";

const MAX_MESSAGES_PER_PANEL = 200;
const MAX_BODY_BYTES = 64 * 1024;
const MAX_BURST = 100;
// Works both from src/ui (tsx) and dist/ui (compiled): the page lives in Publisher/public.
const PAGE_PATH = join(__dirname, "..", "..", "public", "index.html");

type Panel = {
  key: string;
  client: PublisherClient;
  status: ConnectionStatus;
  messages: Map<string, TrackedMessage>;
  broadcastPending: boolean;
};

class HttpError extends Error {
  constructor(readonly statusCode: number, message: string) {
    super(message);
  }
}

/** Serves the publisher web UI: a page, a Server-Sent Events stream, and a small JSON API. */
export function startUi(brokerHost: string, brokerPort: number, uiPort: number, uiHost: string): void {
  const panels = new Map<string, Panel>();
  const streams = new Set<ServerResponse>();

  function view(panel: Panel) {
    return {
      key: panel.key,
      publisherId: panel.client.publisherId,
      topic: panel.client.topic,
      status: panel.status,
      messages: [...panel.messages.values()]
    };
  }

  function broadcast(event: string, data: unknown): void {
    const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const stream of streams) stream.write(frame);
  }

  // Coalesces bursts of client events (e.g. "Send 10") into one update per panel per tick.
  function scheduleBroadcast(panel: Panel): void {
    if (panel.broadcastPending) return;
    panel.broadcastPending = true;
    setImmediate(() => {
      panel.broadcastPending = false;
      if (panels.has(panel.key)) broadcast("panel", view(panel));
    });
  }

  function addPanel(publisherId: string, topic: string): Panel {
    const client = new PublisherClient(publisherId, topic, brokerHost, brokerPort);
    const panel: Panel = { key: randomUUID(), client, status: client.status, messages: new Map(), broadcastPending: false };
    client.on("status", (status: ConnectionStatus) => {
      panel.status = status;
      scheduleBroadcast(panel);
    });
    client.on("message", (message: TrackedMessage) => {
      panel.messages.set(message.messageId, message);
      if (panel.messages.size > MAX_MESSAGES_PER_PANEL) {
        panel.messages.delete(panel.messages.keys().next().value as string);
      }
      scheduleBroadcast(panel);
    });
    client.on("notice", (text: string) => console.log(`[${publisherId}] ${text}`));
    panels.set(panel.key, panel);
    client.start();
    scheduleBroadcast(panel);
    return panel;
  }

  function removePanel(panel: Panel): void {
    panel.client.stop();
    panel.client.removeAllListeners();
    panels.delete(panel.key);
    broadcast("removed", { key: panel.key });
  }

  async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
    let size = 0;
    const chunks: Buffer[] = [];
    for await (const chunk of req) {
      size += (chunk as Buffer).length;
      if (size > MAX_BODY_BYTES) throw new HttpError(413, "Request body too large");
      chunks.push(chunk as Buffer);
    }
    if (chunks.length === 0) return {};
    try {
      const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as Record<string, unknown>;
    } catch {
      // fall through
    }
    throw new HttpError(400, "Body must be a JSON object");
  }

  function requireText(body: Record<string, unknown>, field: string): string {
    const value = body[field];
    if (typeof value !== "string" || !value.trim()) throw new HttpError(400, `'${field}' is required`);
    return value.trim();
  }

  function sendJson(res: ServerResponse, statusCode: number, data: unknown): void {
    res.writeHead(statusCode, { "Content-Type": "application/json" });
    res.end(JSON.stringify(data));
  }

  function openStream(req: IncomingMessage, res: ServerResponse): void {
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive" });
    res.write("retry: 2000\n\n");
    res.write(`event: snapshot\ndata: ${JSON.stringify({ broker: `${brokerHost}:${brokerPort}`, panels: [...panels.values()].map(view) })}\n\n`);
    streams.add(res);
    req.on("close", () => streams.delete(res));
  }

  async function route(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? "/", "http://localhost");
    const parts = url.pathname.split("/").filter(Boolean);

    if (req.method === "GET" && url.pathname === "/") {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(await readFile(PAGE_PATH));
      return;
    }
    if (req.method === "GET" && url.pathname === "/events") {
      openStream(req, res);
      return;
    }
    if (parts[0] !== "api" || parts[1] !== "publishers") throw new HttpError(404, "Not found");

    // POST /api/publishers
    if (parts.length === 2 && req.method === "POST") {
      const body = await readJson(req);
      const panel = addPanel(requireText(body, "publisherId"), requireText(body, "topic"));
      sendJson(res, 201, { key: panel.key });
      return;
    }

    const panel = panels.get(parts[2] ?? "");
    if (!panel) throw new HttpError(404, "Unknown publisher");
    const action = parts[3];

    if (parts.length === 3 && req.method === "DELETE") {
      removePanel(panel);
      sendJson(res, 200, {});
    } else if (action === "messages" && req.method === "POST") {
      const body = await readJson(req);
      const payload = requireText(body, "payload");
      const count = body.count === undefined ? 1 : Number(body.count);
      if (!Number.isInteger(count) || count < 1 || count > MAX_BURST) throw new HttpError(400, `'count' must be 1-${MAX_BURST}`);
      for (let i = 1; i <= count; i++) panel.client.publish(count === 1 ? payload : `${payload} #${i}`);
      sendJson(res, 202, {});
    } else if (action === "drop" && req.method === "POST") {
      panel.client.dropConnection();
      sendJson(res, 202, {});
    } else if (action === "invalid" && req.method === "POST") {
      const kind = requireText(await readJson(req), "kind") as InvalidFrameKind;
      if (!INVALID_FRAME_KINDS.includes(kind)) throw new HttpError(400, "Unknown invalid-frame kind");
      panel.client.sendInvalid(kind);
      sendJson(res, 202, {});
    } else {
      throw new HttpError(404, "Not found");
    }
  }

  const server = createServer((req, res) => {
    route(req, res).catch((error: unknown) => {
      const statusCode = error instanceof HttpError ? error.statusCode : 500;
      const message = error instanceof Error ? error.message : String(error);
      if (statusCode === 500) console.error(error);
      if (!res.headersSent) sendJson(res, statusCode, { error: message });
      else res.end();
    });
  });

  // Keeps idle proxies and browsers from closing the event stream.
  const heartbeat = setInterval(() => {
    for (const stream of streams) stream.write(": ping\n\n");
  }, 15_000);

  server.listen(uiPort, uiHost, () => {
    const shownHost = uiHost === "0.0.0.0" ? "localhost" : uiHost;
    console.log(`Publisher UI running at http://${shownHost}:${uiPort} (broker ${brokerHost}:${brokerPort}). Press Ctrl+C to stop.`);
  });

  const shutdown = () => {
    clearInterval(heartbeat);
    for (const panel of panels.values()) panel.client.stop();
    for (const stream of streams) stream.end();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1_000).unref();
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}
