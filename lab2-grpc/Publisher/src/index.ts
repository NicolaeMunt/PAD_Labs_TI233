import { runCli } from "./cli.js";
import { startUi } from "./ui/server.js";

const CLI_USAGE = "Usage: npm run dev -- <publisherId> <topic> [host] [port]";
const UI_USAGE = "Usage: npm run ui -- [brokerHost] [brokerPort] [uiPort]";

function parsePort(value: string): number | undefined {
  const port = Number(value);
  return Number.isInteger(port) && port >= 1 && port <= 65535 ? port : undefined;
}

function fail(usage: string): never {
  console.error(usage);
  process.exit(1);
}

const args = process.argv.slice(2);

if (args[0] === "--ui") {
  const [brokerHost = "127.0.0.1", brokerPortArgument = "5000", uiPortArgument = "3000"] = args.slice(1);
  const brokerPort = parsePort(brokerPortArgument);
  const uiPort = parsePort(uiPortArgument);
  if (brokerPort === undefined || uiPort === undefined) fail(UI_USAGE);
  // Local-only by default; the Docker image sets UI_HOST=0.0.0.0 so the port can be published.
  startUi(brokerHost, brokerPort, uiPort, process.env.UI_HOST ?? "127.0.0.1");
} else {
  const [publisherId, topic, host = "127.0.0.1", portArgument = "5000"] = args;
  const port = parsePort(portArgument);
  if (!publisherId || !topic || port === undefined) fail(CLI_USAGE);
  runCli(publisherId, topic, host, port);
}
