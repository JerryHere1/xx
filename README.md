# DiscordMusicPanel

A Paper **1.21.11** plugin that runs a **Discord music bot** inside your Minecraft server.
Play YouTube and Spotify links (or search by name) and control everything from a **button panel**
that appears in the text chat of the voice channel.

```
Panel:   [⏸ Pause] [⏭ Skip] [⏹ Stop] [🔁 Loop: Off] [🔀 Shuffle]
         [🔉 Vol -] [🔊 50%] [🔊 Vol +] [📜 Queue]
```

## How it works

1. Someone joins a Discord voice channel and types `/play <link or song name>`.
2. The bot joins that voice channel and posts the **panel** in that voice channel's chat.
3. Anyone sitting in the same voice channel can use the buttons. The progress bar updates by itself.

Commands: `/play <query>`, `/panel` (re-post the panel). In Minecraft: `/musicpanel <status|reload|stop>` (permission `musicpanel.admin`).

What the bot says when you run `/play lofi hip hop`:

```
🎧 Listening... looking for **lofi hip hop**        <- right away
✅ Okay, done! Starting **lofi hip hop radio** now. <- the same message, once it's found
🎵 Now playing: lofi hip hop radio  ·  asked for by Aymane
[ the control panel ]
```

Set `player.announce-now-playing: false` in `config.yml` to turn off the "Now playing" line
(the panel still shows the current song). Only the newest "Now playing" message is kept.

## Prefix commands (+play, +skip, ...)

Alongside `/play`, the bot answers typed commands. Default prefix is `+`:

```
+play lofi hip hop      +skip        +stop        +pause / +resume
+volume 50              +volume up   +volume down
+loop                   +shuffle     +queue       +np
+panel                  +help
```

**An administrator can change the prefix** for their server:

```
+prefix !
```

That's saved per server in `plugins/DiscordMusicPanel/prefixes.yml`, so each server can have its own.
Anyone without the Administrator permission gets told no. `discord.prefix` in config.yml is the
starting prefix for servers that haven't changed it.

> **One switch you must flip:** Discord treats reading message text as a "privileged intent".
> Go to <https://discord.com/developers/applications> → your app → **Bot** → **Privileged Gateway
> Intents** → turn on **MESSAGE CONTENT INTENT** → Save. Without it Discord refuses the connection,
> so the bot starts without prefix commands and prints a warning; slash commands keep working.
> Set `discord.prefix-commands: false` if you'd rather not use them at all.

## The panel

The panel is **not an embed**. It's built with Discord's Components V2 layout, which is what gives it
a coloured accent bar down the side, real dividers, cover art in the corner and buttons sitting inside
the card — the same system the polished interface bots use.

```yaml
panel:
  color: "#F5A9C7"    # the accent bar; try #A7C7E7 baby blue, #C3B1E1 lilac, #5865F2 blurple
  custom-icons: true  # the bot's own icon set (see below)
  gif-url: ""         # a GIF shown inside the panel
```

### Its own icons

The 13 icons are drawn for this plugin and live in the jar at `resources/icons`. On first start the
bot uploads them to **itself** as *application emojis*: they then work in every server it joins, take
up none of your server's emoji slots, and need no setup from you. The console says
`Uploaded 13 panel icons to the bot (this happens only once).`

If the upload fails for any reason, every icon quietly falls back to a normal emoji and the panel
still works. Set `custom-icons: false` to skip it entirely.

To use your own art instead, replace the PNGs in `src/main/resources/icons` (keep the file names),
delete the old emojis from your app's **Emojis** page in the Developer Portal, and rebuild.

### A GIF

`gif-url` needs a **direct image link** ending in `.gif`. On Tenor or Giphy, right-click the GIF
itself and choose **Copy image address** — a normal `tenor.com/view/...` page link will not work.
Leave it empty for no image.

## Lock it to one person

By default anyone sitting in the voice channel can use `/play` and the buttons. To let **only you**
(or a few people) control it, put the Discord user IDs in `config.yml`:

```yaml
discord:
  allowed-user-ids: [123456789012345678]
```

Anyone else gets "🔒 Only @you can control the music here." (only they see that message).
To find your ID: Discord **Settings → Advanced → Developer Mode** on, then right-click your name → **Copy User ID**.
`/musicpanel status` in Minecraft shows which rule is active. An empty list `[]` means everyone.

## Requirements

* Paper 1.21.11, **Java 21+**
* Linux x64 / Linux arm64 / Windows x64 host (the DAVE voice-encryption natives only exist for these as configured in `pom.xml`)
* A Discord bot token (https://discord.com/developers/applications → New Application → Bot → Reset Token). **No privileged intents needed.**
* To build: JDK 21 + Maven

## Build

**No Maven/Java on your PC? Use GitHub (what the included workflow is for):**
push this project to a GitHub repo → **Actions** tab → *Build plugin* → open the finished run →
download **DiscordMusicPanel-jar** at the bottom → unzip → `DiscordMusicPanel-1.0.0.jar`.

Or locally (JDK 21 + Maven):

```
mvn clean package
```

Copy `DiscordMusicPanel-1.0.0.jar` into your server's `plugins/` folder and start the server once.
Then edit `plugins/DiscordMusicPanel/config.yml`:

1. Paste your bot token under `discord.token`.
2. (Recommended) set `discord.guild-id` to your Discord server ID so slash commands show up instantly.
3. Run `/musicpanel reload` (or restart). The console prints an **invite link**; open it to add the bot.

The bot needs **View Channel, Connect, Speak, Send Messages, Embed Links** (the invite link already asks for these).

## Spotify — what still works

Spotify changed its developer rules in February 2026. An app can now only read the **contents of
playlists its own account owns**, so a shared or public playlist link returns an error that nothing
in this plugin can get around. Albums are blocked the same way, and the app owner now needs Spotify
Premium for the app to work at all.

| Link | Works? |
|---|---|
| Spotify **track** link | ✅ yes |
| Spotify **playlist** link | ❌ no — Spotify refuses it |
| Spotify **album** link | ❌ no |
| YouTube / YouTube Music link, playlist or search | ✅ yes |

The bot now says this in plain words instead of showing an API error. For playlists, use a YouTube
playlist, or a free Spotify→YouTube playlist converter.

## Spotify setup

Create a free app at https://developer.spotify.com/dashboard, copy the Client ID / Secret into `config.yml`
under `spotify:` and set `enabled: true`. Spotify doesn't give out audio: the bot reads title/artist/cover from
Spotify and finds the matching audio on YouTube. Albums and playlists are supported.

## Voice commands - "Jarvis, play lofi hip hop"

**Off by default.** When on, the bot listens in the voice channel, recognises **only your voice**
(matched by your Discord user ID) and runs what you say. Speech recognition runs **offline** on your
own server through [Vosk](https://alphacephei.com/vosk/) - nothing is sent to any cloud service.

**Setup**

1. Download a small English model from https://alphacephei.com/vosk/models -
   `vosk-model-small-en-us-0.15.zip` (about 40 MB).
2. Unzip it and put the folder at `plugins/DiscordMusicPanel/vosk-model`, so that
   `plugins/DiscordMusicPanel/vosk-model/am`, `/conf` and `/graph` exist.
   (If you see a folder inside a folder, move the inner one up.)
3. In `config.yml`:

```yaml
voice-commands:
  enabled: true
  user-ids: [123456789012345678]   # your Discord user ID
```

4. Restart the server (not `/musicpanel reload` the first time). On first start Paper downloads the
   speech library into the server's `libraries/` folder, so the server needs internet access.
5. Join a voice channel, run `/play` once to bring the bot in, then just talk.

**What you can say** (after the wake word):

| You say | It does |
|---|---|
| "jarvis play lofi hip hop" / "jarvis put on daft punk" | searches and queues it |
| "jarvis skip" / "jarvis next" | skip |
| "jarvis pause" / "jarvis resume" | pause / continue |
| "jarvis louder" / "jarvis turn it down" | volume |
| "jarvis loop" / "jarvis shuffle" | loop mode / shuffle the queue |
| "jarvis stop" | stop and leave |

While you're setting it up, the bot posts "🎤 Heard: ..." so you can see what it understood.
Turn that off with `show-what-i-heard: false` once it works.

**Things worth knowing before you turn it on**

* **Use headphones.** On speakers your mic picks up the music and the bot tries to transcribe the song.
* **Offline recognition is not Siri.** Short commands work well; long or unusual song names get misheard.
  If a song name never comes through, type that one with `/play`.
* **"jarvis" may be misheard**, because it isn't a common English word. If it doesn't trigger, set
  `wake-word: "computer"` or `"music"`, or set `require-wake-word: false` and just say "play ...".
* It needs roughly **300 MB of RAM and a bit of constant CPU** while someone is talking. On a small
  shared host that may be too much.
* The bot **cannot deafen itself** while this is on - that's how it hears you - so it shows as undeafened in Discord.
* **Only the IDs you list are transcribed**; everyone else's audio is dropped the moment it arrives.
  Even so, tell the people in your channel that it's on.
* Set `enabled: false` and everything above is skipped; the music side is completely unaffected.

**"Failed to decrypt audio packet for user ..." in the console**

Discord encrypts voice per speaker, and the bot needs a decryption key for each person. If it joins a
channel somebody is *already sitting in*, that person is sometimes registered a moment too late and
never gets a key, so every packet from them fails for the rest of the call. The plugin works around
this (see `DaveFix.java`) by registering everyone in the channel before the keys are handed out.

If you still see it: **leave the voice channel and rejoin while the bot stays in it.** That forces a
fresh key exchange. After about two seconds of failures the plugin prints a message saying the same.
Music is never affected by this - only the bot's ability to hear you.

**If it doesn't react**

Check the server console at startup. You should see `Voice commands on: listening to user ID(s) [...]`.
Other messages tell you what's wrong: no model folder (with the download link), no user IDs set,
or the speech library failing to load. The music keeps working either way.

## YouTube

YouTube keeps changing how it serves audio, and this is the most fragile part of any music bot. This plugin is set up
for the best odds out of the box:

* It is built against a **newer `youtube-source` commit** (pinned in `pom.xml` and `.github/workflows/build.yml`)
  instead of the old 1.18.2 release, which can't play many videos since YouTube's Sept 2026 format change.
* It uses the free public **remote cipher server** `https://cipher.kikkia.dev/` (`youtube.remote-cipher` in `config.yml`)
  so YouTube's scrambled stream URLs are unscrambled for you.
* Several YouTube "clients" are tried in order (`youtube.clients`), so if one is blocked the next one is used.

The console prints `YouTube backend: youtube-source with clients [...]` and `YouTube: using remote cipher server ...`
when it starts. If songs still fail with "All clients failed" / "sign in to confirm you're not a bot":

1. **Most reliable:** install `yt-dlp` on the host and set `youtube.backend: "yt-dlp"` in `config.yml`, then
   `/musicpanel reload`. yt-dlp is updated within days of YouTube changes (it also wants a JavaScript runtime such as Deno -
   see its docs). Keep it updated (`yt-dlp -U`).
2. Hosting providers' IP ranges are often flagged by YouTube. Enabling `youtube.oauth` with a **throwaway** Google
   account helps a lot (it can get banned, never use your main account).
3. To move to a newer youtube-source later, change the commit hash in `pom.xml` (`youtube.source.version`, keep `-SNAPSHOT`)
   **and** in `build.yml` (`YT_COMMIT`).

## Notes & troubleshooting

* **Discord requires DAVE voice encryption** (since March 2026). It is wired in via `libdave-jvm`. Voice error `4017` /
  "close code 4017" or `UnsatisfiedLinkError` means the DAVE natives for your OS are missing. Check the `natives-*`
  dependencies in `pom.xml`, or bump `libdave.version` / `libdave.natives.version`. The console prints `DAVE voice encryption library loaded.` when the native library works on your server.
* **Don't use `/reload` or PlugMan** on this plugin. Native libraries can't be loaded twice by different class loaders.
  Use `/musicpanel reload` or restart the server.
* If the build can't resolve a dependency, check the version numbers at the top of `pom.xml` (all pinned there).
* `NoSuchMethodError` / `LinkageError` from a bundled library: add a `<relocation>` for it in the shade config.
* Only YouTube and Spotify are enabled (no generic URL / local file source) so Discord users can't make your
  Minecraft host fetch arbitrary addresses.
* Streaming from YouTube/Spotify may break their terms of service. Fine for a private server; think twice before running it publicly.

## Project layout

```
src/main/java/dev/musicpanel/
  MusicPanelPlugin  - Paper plugin, /musicpanel command, starts/stops the bot
  DiscordBot        - JDA login (+DAVE), slash commands, button + voice listeners
  AudioService      - Lavaplayer, YouTube/Spotify sources, track loading
  GuildMusic        - per-server queue, loop, volume, timers, panel message
  Panel             - embed + button layout
  SendHandler       - Lavaplayer -> JDA audio bridge
```
