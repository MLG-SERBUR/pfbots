// One-shot bridge: Java stdin (one JSON request) -> Paseo daemon API -> stdout (one JSON reply).
// This is the API route, not the `paseo` CLI: it uses the official @getpaseo/client
// SDK, which speaks the daemon's WebSocket API (ws://127.0.0.1:6767/ws by default).
// Install once on the bot host: cd paseo-bridge && npm install
// Env: PASEO_WS_URL (default ws://127.0.0.1:6767/ws), PASEO_DAEMON_PASSWORD (if set).
// Request: { provider: "opencode/<model>", systemPrompt, prompt, images: [{data, mimeType}], cwd, timeoutMs, archiveExisting }
// archiveExisting (first reply after bot start only, sent by Java): archive
// every existing pfbots session before creating the new one, clearing the
// pile built up before this change. Sessions are otherwise NOT archived on
// finish: they stay visible until more than 10 unarchived pfbots sessions
// exist in the workspace, then the oldest are archived, keeping the newest 10.
// Reply:   { reply: "<final chat reply>" }
// ponytail: images[].data is raw base64 from VisionImage, not a data URI, because
// the SDK type carries mimeType separately. Verify against the live daemon if a
// provider rejects it; the fallback is a data:<mime>;base64,<data> string.
import { createPaseoClient } from "@getpaseo/client";
import { readFileSync } from "node:fs";

const input = readFileSync(0, "utf-8");
const req = JSON.parse(input);
// Instructions live here, in code, not in a separate file: a missing file
// used to silently degrade to no instructions at all. They go in the
// SYSTEM prompt, not the first user prompt, for three reasons: behavior
// constraints ("read the log yourself, return only via the schema, edit
// nothing") belong in system instructions, not in task data; the user
// prompt is what shows up as the User message in a Paseo export, and
// instructions there would clutter that transcript and risk the model
// treating them as something the user said; and system prompt is where
// the per-bot voice already lives, so this merges with it. This is plain
// system-prompt text, NOT an OpenCode skill: there is no skill tool call
// for it in a session export, and the provider's `skill` tool stays
// denied in the permission map below.
const instructions = `
You are answering one Discord message as a chat bot. Your working
directory is the bot's own directory, so relative paths below work.

## History: read it yourself, do not ask Java to send it

Java does NOT send you the chat history in this prompt, on purpose.
If you need history, read it from disk yourself, and only as much
as the question needs.

History: "data/markov/<channelId>/ai.log"
- One line per message: "<Author> message text", newest last.
- Author is the poster's Discord display name, so a line already
  tells you who posted it. There is no timestamp on a line: order
  is chronological, newest last, and that is the only "when" the
  log carries.
- This is the AI-facing log. Scrubbing removes the bot's own lines
  from it over time, on purpose, so bot messages do not pollute
  context. Do not look for bot replies elsewhere.

Read the tail of the AI log first; grep for a name, phrase, or
image id only if the question refers to something older. Never
paste the whole log into your reply, and never edit a log file.

## Images

Images attached to THIS run are attached to your session already.
Use them directly if the question is about them. Do not guess
image content.

Older images: every image posted in the channel is cached on
arrival at "data/markov/<channelId>/images/<imageId>.<ext>", where
"<ext>" is the extension from the original file name (png, jpg,
webp, gif). A log line for an image message looks like:

    <Author> some text [image id=1566657284933951498 file=photo.png]

The line prefix is who posted it, the id plus file name is the
reference, and the cached file is the pixels. To answer about an
older image, open "data/markov/<channelId>/images/<id>.<ext>"
from the log reference. If that file is missing (posted before
caching existed, or the download failed), say you cannot see it
instead of describing it from the placeholder or from an earlier
image's description.

## Answering

- Answer only the triggering message, in the bot's voice from the
  system prompt. Do not use tools to edit files, run commands, or
  contact anyone. Reading the AI log and cached images above is
  the only file access this job needs.
- Return your answer only through the reply schema. Do not output
  a vision marker, a tool call transcript, or reasoning.
`;
const systemPrompt = [instructions, req.systemPrompt].filter(Boolean).join("\n\n");
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
// Session retention helpers. Cleanup/prune failures are logged to stderr
// only, never fail the reply: archiving is housekeeping, not the answer.
async function listPfbotsSessions() {
  const result = await client.agents.list({
    filter: { labels: { source: "pfbots" }, includeArchived: false },
    sort: [{ key: "created_at", direction: "asc" }],
    page: { limit: 500 },
  });
  return (result.entries || []).map((e) => e.agent || e).filter((a) => a && a.id);
}
async function archiveSessions(ids) {
  for (const id of ids) {
    try { await client.agents.ref(id).archive(); } catch (err) {
      console.error(`Failed to archive Paseo session ${id}: ${err && err.message ? err.message : err}`);
    }
  }
}
let agent = null;
try {
  await client.connect();
  // One workspace per bot dir, not one per reply: client.agents.create
  // makes a fresh workspace behind every agent, which is why each reply
  // left a workspace behind. workspaces.open reuses the active workspace
  // for a directory or creates it, and workspace.agents.create then puts
  // each reply's session inside that same workspace. Sessions stay
  // separate (one per reply, archived after it finishes, still visible
  // in the workspace's session list); only the workspace is shared.
  // Old workspaces from before this change stay where they are; archive
  // them in the Paseo UI if you want them gone, this does not delete them.
  const workspace = await client.workspaces.open({ cwd: req.cwd || "/tmp" });
  if (req.archiveExisting) {
    // First reply after bot start: clear the pre-existing pile. The new
    // session is not created yet, so it cannot be caught in this archive.
    try {
      const existing = await listPfbotsSessions();
      await archiveSessions(existing.map((a) => a.id));
    } catch (err) {
      console.error(`Startup session archive failed: ${err && err.message ? err.message : err}`);
    }
  }
  // Plan mode: OpenCode's read-only mode, so the agent can read logs and
  // cached images but cannot write files or run edits. The daemon validates
  // modeId against its own provider modes; if it rejects "plan", drop this
  // field (or pass req.modeId) rather than switching to a write mode.
  agent = await workspace.agents.create({
    config: { provider: req.provider, systemPrompt, modeId: req.modeId || "plan", options },
    prompt: req.prompt,
    images: req.images && req.images.length > 0 ? req.images : undefined,
    outputSchema: {
      type: "object",
      properties: { reply: { type: "string" } },
      required: ["reply"],
      additionalProperties: false,
    },
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
  // Rolling retention: keep the newest 10 unarchived sessions in this
  // workspace, archive the oldest excess (the current session is newest,
  // so it is never the one archived here). Prune after the reply, so a
  // prune failure cannot lose an answer that already exists.
  try {
    const sessions = (await listPfbotsSessions())
      .filter((a) => a.workspaceId === workspace.id)
      .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
    if (sessions.length > 10) {
      const excess = sessions.slice(0, sessions.length - 10)
        .map((a) => a.id).filter((id) => id !== agent.id);
      await archiveSessions(excess);
    }
  } catch (err) {
    console.error(`Session prune failed: ${err && err.message ? err.message : err}`);
  }
  process.stdout.write(JSON.stringify({ reply }) + "\n");
} catch (err) {
  console.error(String(err && err.message ? err.message : err));
  process.exitCode = 1;
} finally {
  // No agent.archive() here on purpose: the session stays visible until
  // the rolling prune above archives it once more than 10 exist.
  try { await client.close(); } catch {}
}
