// One-shot bridge: Java stdin (one JSON request) -> Paseo daemon API -> stdout (one JSON reply).
// This is the API route, not the `paseo` CLI: it uses the official @getpaseo/client
// SDK, which speaks the daemon's WebSocket API (ws://127.0.0.1:6767/ws by default).
// Install once on the bot host: cd paseo-bridge && npm install
// Env: PASEO_WS_URL (default ws://127.0.0.1:6767/ws), PASEO_DAEMON_PASSWORD (if set).
// Request: { provider: "opencode/<model>", systemPrompt, prompt, images: [{data, mimeType}], cwd, timeoutMs }
// Reply:   { reply: "<final chat reply>" }
// ponytail: images[].data is raw base64 from VisionImage, not a data URI, because
// the SDK type carries mimeType separately. Verify against the live daemon if a
// provider rejects it; the fallback is a data:<mime>;base64,<data> string.
import { createPaseoClient } from "@getpaseo/client";
import { readFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const input = readFileSync(0, "utf-8");
const req = JSON.parse(input);
// Skill: prepended to the system prompt, so the agent knows where the
// chat logs live and that Java does not send history in the prompt.
let skill = "";
try {
  const skillPath = join(dirname(fileURLToPath(import.meta.url)), "pfbots-chat-skill.md");
  if (existsSync(skillPath)) skill = readFileSync(skillPath, "utf-8");
} catch {}
const systemPrompt = [skill, req.systemPrompt].filter(Boolean).join("\n\n");
// Read confinement, fail closed: no channelId means no agent. The agent may
// only read this channel's dir (ai.log + cached images). OpenCode permission
// rules are matched in order, last match wins, so the catch-all deny goes
// first and the channel-dir allows after it. Paseo strictly validates
// provider options: if the daemon rejects the pattern map, agent creation
// fails, the bridge exits non-zero, and Java falls back to Markov instead
// of running unconfined. Websearch stays allowed; webfetch is denied because
// a fetched URL can carry data out in its query string, and bash/edit/task/
// external-directory reads are denied outright. Plan mode alone is not
// enough: it only sets edit/bash to ask, and read defaults to allow.
if (typeof req.channelId !== "string" || !/^\d+$/.test(req.channelId)) {
  // A JSON number here is already lossy: JS Numbers cannot hold a Discord
  // snowflake exactly, so a numeric channelId would confine reads to a
  // rounded, wrong channel dir. Refuse rather than run unconfined/wrong.
  throw new Error("Refusing to create Paseo agent without a string channelId (read confinement requires it)");
}
const channelDir = `data/markov/${req.channelId}`;
const readAllow = { "*": "deny", [`${channelDir}/**`]: "allow", [`**/${channelDir}/**`]: "allow" };
const options = {
  permission: {
    read: readAllow,
    glob: readAllow,
    grep: readAllow,
    list: readAllow,
    edit: "deny",
    bash: "deny",
    task: "deny",
    external_directory: "deny",
    lsp: "deny",
    skill: "deny",
    todowrite: "deny",
    question: "deny",
    webfetch: "deny",
    websearch: "allow",
  },
};
const client = createPaseoClient({
  url: process.env.PASEO_WS_URL || "ws://127.0.0.1:6767/ws",
  password: process.env.PASEO_DAEMON_PASSWORD || undefined,
});
let agent = null;
try {
  await client.connect();
  // Plan mode: OpenCode's read-only mode, so the agent can read logs and
  // cached images but cannot write files or run edits. The daemon validates
  // modeId against its own provider modes; if it rejects "plan", drop this
  // field (or pass req.modeId) rather than switching to a write mode.
  agent = await client.agents.create({
    config: { provider: req.provider, systemPrompt, modeId: req.modeId || "plan", options },
    cwd: req.cwd || "/tmp",
    prompt: req.prompt,
    images: req.images && req.images.length > 0 ? req.images : undefined,
    outputSchema: {
      type: "object",
      properties: { reply: { type: "string" } },
      required: ["reply"],
      additionalProperties: false,
    },
    autoArchive: true,
    labels: { source: "pfbots" },
  });
  // No timeout, by owner instruction: a wait deadline expiring does not
  // cancel the agent, so keep waiting instead of treating it as failure.
  let result = await agent.waitForFinish(600000);
  while (result.status === "timeout") {
    result = await agent.waitForFinish(600000);
  }
  if (result.status !== "idle" || !result.lastMessage) {
    throw new Error(result.error || `Paseo turn ended with status ${result.status} and no message`);
  }
  let reply = result.lastMessage;
  try {
    const parsed = JSON.parse(result.lastMessage);
    if (typeof parsed.reply === "string") reply = parsed.reply;
  } catch {
    // Provider ignored the schema; use its raw text as the reply.
  }
  if (!reply || !reply.trim()) throw new Error("Paseo returned an empty reply");
  process.stdout.write(JSON.stringify({ reply }) + "\n");
} catch (err) {
  console.error(String(err && err.message ? err.message : err));
  process.exitCode = 1;
} finally {
  try { if (agent) await agent.archive(); } catch {}
  try { await client.close(); } catch {}
}
