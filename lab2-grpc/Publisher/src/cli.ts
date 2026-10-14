import { createInterface } from "node:readline";
import { ConnectionStatus, PublisherClient, TrackedMessage } from "./publisherClient.js";

/** Interactive terminal publisher: every non-empty line typed is published to the topic. */
export function runCli(publisherId: string, topic: string, host: string, port: number): void {
  const client = new PublisherClient(publisherId, topic, host, port);

  client.on("status", (status: ConnectionStatus) => {
    switch (status.state) {
      case "registering":
        console.log(`Opened gRPC stream to broker at ${host}:${port}; registering '${publisherId}' for '${topic}'.`);
        break;
      case "registered":
        console.log(status.detail);
        break;
      case "rejected":
        console.error(`Broker rejected request: ${status.detail}`);
        break;
      case "disconnected":
        console.error(`Broker stream error: ${status.detail}`);
        console.log(`Disconnected. Reconnecting in ${(status.retryInMs ?? 0) / 1_000}s...`);
        break;
    }
  });

  client.on("message", (message: TrackedMessage) => {
    if (message.status === "acked") console.log(`Published ${message.messageId} to '${topic}' (#${message.sequence}).`);
    if (message.status === "rejected") console.error(`Broker rejected request: ${message.reason}`);
  });

  client.on("notice", (text: string) => console.log(text));

  const readline = createInterface({ input: process.stdin, output: process.stdout, prompt: "> " });
  readline.on("line", (line: string) => {
    if (line.trim() === "/quit") {
      client.stop();
      readline.close();
      return;
    }
    if (line.trim()) {
      client.publish(line);
      if (!client.isRegistered) console.log("Message queued until publisher registration succeeds.");
    }
    readline.prompt();
  });
  readline.on("close", () => process.exit(0));

  process.on("SIGINT", () => readline.close());
  console.log("Type a message and press Enter. Type /quit to exit.");
  readline.prompt();
  client.start();
}
