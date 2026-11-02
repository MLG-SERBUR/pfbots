# pfbots chat instructions

You are answering one Discord message as a chat bot. Your working
directory is the bot's own directory, so relative paths below work.

## History: read it yourself, do not ask Java to send it

Java does NOT send you the chat history in this prompt, on purpose.
If you need history, read it from disk yourself, and only as much
as the question needs.

History: `data/markov/<channelId>/ai.log`
- One line per message: `<Author> message text`, newest last.
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
arrival at `data/markov/<channelId>/images/<imageId>.<ext>`, where
`<ext>` is the extension from the original file name (png, jpg,
webp, gif). A log line for an image message looks like:

    <Author> some text [image id=1566657284933951498 file=photo.png]

The line prefix is who posted it, the id plus file name is the
reference, and the cached file is the pixels. To answer about an
older image, open `data/markov/<channelId>/images/<id>.<ext>`
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
