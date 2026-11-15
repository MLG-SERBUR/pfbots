// One-shot bridge: Java stdin (one JSON request) -> Paseo daemon API -> stdout (one JSON reply).
// This is the API route, not the `paseo` CLI: it uses the official @getpaseo/client
// SDK, which speaks the daemon's WebSocket API (ws://127.0.0.1:6767/ws by default).
// Install once on the bot host: cd paseo-bridge && npm install
// Env: PASEO_WS_URL (default ws://127.0.0.1:6767/ws), PASEO_DAEMON_PASSWORD (if set).
// Request: { provider: "opencode/<model>", systemPrompt, prompt, images: [{data, mimeType}], cwd, timeoutMs, archiveExisting }
// archiveExisting (first reply after bot start only, sent by Java): archive
// every existing pfbots session before creating the new one, clearing the
// pile built up before this change. Sessions are archived on finish:
// after a successful reply all but the newest 5 pfbots sessions in the
// workspace are archived, so the last 5 tabs stay open. Other failed turns
// stay for debugging; exhausted-usage turns are stopped immediately.
// Reply:   { reply: "<final chat reply>", react: "r1" | "" }
// react is optional and empty when no reaction candidate was chosen.
// Existing-only reaction candidates feed it; Java applies rN ids locally.
// ponytail: images[].data is raw base64 from VisionImage, not a data URI, because
// the SDK type carries mimeType separately. Verify against the live daemon if a
// provider rejects it; the fallback is a data:<mime>;base64,<data> string.
import { createPaseoClient } from "@getpaseo/client";
import { readFileSync } from "node:fs";

const input = readFileSync(0, "utf-8");
const req = JSON.parse(input);

function isUsageLimitError(message) {
  return /(?:free\s+usage\s+exceeded|usage\s+(?:limit|quota)\s+(?:exceeded|reached)|quota\s+(?:exceeded|exhausted)|insufficient\s+(?:credits|balance))/i
    .test(message || "");
}
// System prompt comes complete from Java (the profile's
// paseoSystemPrompt) and is used exactly as sent: no concatenation
// here, no file read, no bridge-side instructions. That prompt is plain
// system-prompt text, NOT an OpenCode skill: there is no skill tool call
// for it in a session export. Skills are denied in the permission map
// below, as a pattern map ({"*": "deny"}) — the form OpenCode's skill
// docs use — not the bare "deny" that was there before and did not stop
// the agent trying to use the user's installed OpenCode skills.
const systemPrompt = req.systemPrompt || "";
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
    // grep/glob permissions are matched against the regex/glob pattern,
    // not the searched files, so they cannot be path-scoped. They are
    // allowed and confinement comes from the workspace cwd below: the
    // agent's working directory is this channel's dir, so a default
    // search can only hit its own ai.log/images. read stays path-scoped.
    glob: "allow",
    grep: "allow",
    list: readAllow,
    edit: "deny",
    bash: "deny",
    task: "deny",
    external_directory: "deny",
    lsp: "deny",
    skill: { "*": "deny" },
    todowrite: "deny",
    question: "deny",
    webfetch: "deny",
    websearch: "allow",
    // Remaining OpenCode tools from Paseo's provider-options doc: none
    // of these are needed to read a log and return a reply.
    codesearch: "deny",
    doom_loop: "deny",
    repo_clone: "deny",
    repo_overview: "deny",
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
    // SDK max is 200: 500 threw ZodError, silently breaking every archive.
    page: { limit: 200 },
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
  // One workspace per channel dir, not one per reply: client.agents.create
  // makes a fresh workspace behind every agent, which is why each reply
  // left a workspace behind. workspaces.open reuses the active workspace
  // for a directory or creates it, and workspace.agents.create then puts
  // each reply's session inside that same workspace. Sessions stay
  // separate (one per reply, archived after it finishes, tab closed);
  // only the workspace is shared. The workspace cwd is the channel dir
  // itself, which is what confines default grep/glob searches to this
  // channel's ai.log/images (see the permission map above). Java prompts
  // use ai.log / images/... relative to it.
  // Old workspaces from before this change stay where they are; archive
  // them in the Paseo UI if you want them gone, this does not delete them.
  const workspace = await client.workspaces.open({ cwd: `${req.cwd || "/tmp"}/${channelDir}` });
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
  // No modeId on purpose, and never "plan" again: plan mode made the
  // agent treat the job as presenting a plan for sending a Discord
  // message itself, and that plan text then leaked to Discord as the
  // reply (see the strict reply parsing below). The agent's job is only
  // to return reply text through the schema; Java sends it to Discord.
  // Write/execute protection comes from the permission map above
  // (edit/bash/task denied), not from plan mode.
  agent = await workspace.agents.create({
    config: { provider: req.provider, systemPrompt, options },
    prompt: req.prompt,
    images: req.images && req.images.length > 0 ? req.images : undefined,
    outputSchema: {
      type: "object",
      properties: { reply: { type: "string" }, react: { type: "string" } },
      required: ["reply"],
      additionalProperties: false,
    },
    labels: { source: "pfbots" },
  });
  let reportUsageLimit;
  const usageLimitFailure = new Promise((resolve) => {
    reportUsageLimit = (message) => resolve({ type: "usage_limit", message });
  });
  const timeline = agent.timeline.subscribe(({ event }) => {
    const message = event.type === "turn_failed"
      ? event.error
      : event.type === "timeline" && event.item.type === "error"
        ? event.item.message
        : "";
    if (isUsageLimitError(message)) reportUsageLimit(message);
  });
  let result;
  try {
    await timeline.ready;
    const recent = await agent.timeline.refetch({ direction: "tail", limit: 100 });
    const missedUsageLimit = recent.entries.find(({ item }) =>
      item.type === "error" && isUsageLimitError(item.message));
    if (missedUsageLimit) reportUsageLimit(missedUsageLimit.item.message);

    while (true) {
      const outcome = await Promise.race([
        agent.waitForFinish(600000).then((finished) => ({ type: "result", finished })),
        usageLimitFailure,
      ]);
      const usageLimitMessage = outcome.type === "usage_limit"
        ? outcome.message
        : isUsageLimitError(outcome.finished.error) ? outcome.finished.error : null;
      if (usageLimitMessage) {
        try {
          await agent.archive();
          agent = null;
        } catch (archiveError) {
          console.error(`Failed to stop quota-rejected Paseo agent: ${archiveError && archiveError.message ? archiveError.message : archiveError}`);
        }
        throw new Error(usageLimitMessage);
      }
      if (outcome.finished.status !== "timeout") {
        result = outcome.finished;
        break;
      }
    }
  } finally {
    await timeline.release();
  }
  if (result.status !== "idle" || !result.lastMessage) {
    throw new Error(result.error || `Paseo turn ended with status ${result.status} and no message`);
  }
  // Strict schema only: the last message must be {"reply": "..."}.
  // The old raw-text fallback is what sent a plan-mode plan to Discord
  // verbatim ("Plan mode — read-only. Next step was sending Discord
  // message..."). A non-schema answer is a failed turn, not a reply:
  // throw so Java falls back to Markov instead of posting agent
  // meta-text to the channel. react is optional: absent or non-string
  // means no reaction.
  let reply = null;
  let react = "";
  try {
    const parsed = JSON.parse(result.lastMessage);
    if (typeof parsed.reply === "string") reply = parsed.reply;
    if (typeof parsed.react === "string") react = parsed.react;
  } catch {
    // Not schema JSON: fail, do not use raw text.
  }
  if (!reply || !reply.trim()) throw new Error("Paseo returned no schema reply (last message was not {\"reply\": \"...\"})");
  // Archive on finish: keep the newest 5 tabs open, archive the rest.
  // After the reply, so a prune failure cannot lose an answer that
  // already exists.
  try {
    const sessions = (await listPfbotsSessions())
      .filter((a) => a.workspaceId === workspace.id)
      .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
    await archiveSessions(sessions.slice(0, Math.max(0, sessions.length - 5)).map((a) => a.id));
  } catch (err) {
    console.error(`Session prune failed: ${err && err.message ? err.message : err}`);
  }
  process.stdout.write(JSON.stringify({ reply, react }) + "\n");
} catch (err) {
  console.error(String(err && err.message ? err.message : err));
  process.exitCode = 1;
} finally {
  // Old sessions already archived in the prune above; other failed turns
  // reach here unarchived on purpose, so their tabs stay for debugging.
  try { await client.close(); } catch {}
}
