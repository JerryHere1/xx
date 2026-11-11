package dev.musicpanel;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the control panel.
 *
 * <p>Built with Discord's Components V2 layout rather than an embed: that's what gives the panel its
 * own accent bar, real dividers, a side thumbnail and buttons sitting inside the card. A message built
 * this way may not carry text content or embeds, so everything the panel shows lives in here.
 *
 * <p>Button ids all start with {@code "mp:"}.
 */
final class Panel {

    private static final int DEFAULT_COLOR = 0xF4A0C5;   // soft pink
    private static final int IDLE_COLOR = 0x8E86A8;      // muted lilac

    private static final int BAR_SLOTS = 18;

    private Panel() { }

    // ───────────────────────── the panel ─────────────────────────

    static Container view(GuildMusic gm) {
        FileConfiguration cfg = gm.service().cfg();
        Icons icons = gm.service().icons();
        AudioTrack track = gm.player().getPlayingTrack();
        boolean paused = gm.player().isPaused();

        List<ContainerChildComponent> parts = new ArrayList<>();

        if (track == null) {
            String prefix = cfg.getString("discord.prefix", "+");
            parts.add(TextDisplay.of("## " + icons.text(Icons.NOTE) + "  Music"));
            parts.add(TextDisplay.of("Nothing is playing right now.\n"
                    + "-# Put on a song with `/play` or `" + prefix + "play` — a name, a YouTube link, "
                    + "or a Spotify track."));
        } else {
            AudioTrackInfo info = track.getInfo();
            String heading = "## " + icons.text(paused ? Icons.PAUSE : Icons.PLAY) + "  "
                    + (paused ? "Paused" : "Now Playing");
            String title = esc(cut(info.title, 100));
            String song = "### " + (isUrl(info.uri) ? "[" + title + "](" + info.uri + ")" : title)
                    + "\n-# " + esc(cut(info.author, 70));
            String meta = progress(track) + "\n" + stats(gm, icons);

            if (isUrl(info.artworkUrl)) {
                parts.add(Section.of(
                        Thumbnail.fromUrl(info.artworkUrl).withDescription("Cover art"),
                        TextDisplay.of(heading),
                        TextDisplay.of(song),
                        TextDisplay.of(meta)));
            } else {
                parts.add(TextDisplay.of(heading));
                parts.add(TextDisplay.of(song));
                parts.add(TextDisplay.of(meta));
            }
        }

        List<AudioTrack> next = gm.upcoming(3);
        if (!next.isEmpty()) {
            StringBuilder sb = new StringBuilder("**Up next**\n");
            for (int i = 0; i < next.size(); i++) {
                sb.append("-# `").append(i + 1).append("`  ")
                        .append(esc(cut(next.get(i).getInfo().title, 56))).append('\n');
            }
            int rest = gm.queueSize() - next.size();
            if (rest > 0) sb.append("-# and ").append(rest).append(" more");
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(TextDisplay.of(sb.toString().trim()));
        }

        if (gm.lastError() != null) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(TextDisplay.of("-# ⚠️ " + esc(cut(gm.lastError(), 250))));
        }

        String gif = cfg.getString("panel.gif-url", "");
        if (isUrl(gif)) {
            parts.add(MediaGallery.of(MediaGalleryItem.fromUrl(gif)));
        }

        parts.add(Separator.createDivider(Separator.Spacing.SMALL));
        parts.addAll(buttons(gm, icons));

        String requester = track == null ? null : track.getUserData(String.class);
        if (requester != null) {
            parts.add(TextDisplay.of("-# " + icons.text(Icons.HEART) + " asked for by " + esc(cut(requester, 40))));
        }

        return Container.of(parts).withAccentColor(track == null ? IDLE_COLOR : color(cfg));
    }

    /** Replaces the panel once the music is stopped. */
    static Container stopped(GuildMusic gm, String reason) {
        Icons icons = gm.service().icons();
        return Container.of(
                        TextDisplay.of("## " + icons.text(Icons.STOP) + "  Stopped"),
                        TextDisplay.of(esc(cut(reason, 200)) + "\n-# Use `/play` to start the music again."))
                .withAccentColor(IDLE_COLOR);
    }

    // ───────────────────────── buttons ─────────────────────────

    private static List<ContainerChildComponent> buttons(GuildMusic gm, Icons icons) {
        boolean idle = gm.player().getPlayingTrack() == null;
        boolean paused = gm.player().isPaused();
        GuildMusic.Loop loop = gm.loop();

        Button replay = Button.secondary("mp:restart", "Replay").withEmoji(icons.of(Icons.PREV));
        Button pause = paused
                ? Button.success("mp:pause", "Resume").withEmoji(icons.of(Icons.PLAY))
                : Button.secondary("mp:pause", "Pause").withEmoji(icons.of(Icons.PAUSE));
        Button skip = Button.secondary("mp:skip", "Skip").withEmoji(icons.of(Icons.NEXT));
        Button stop = Button.danger("mp:stop", "Stop").withEmoji(icons.of(Icons.STOP));

        Button loopBtn = switch (loop) {
            case OFF -> Button.secondary("mp:loop", "Loop").withEmoji(icons.of(Icons.LOOP));
            case TRACK -> Button.success("mp:loop", "Song").withEmoji(icons.of(Icons.LOOP_ONE));
            case QUEUE -> Button.success("mp:loop", "Queue").withEmoji(icons.of(Icons.LOOP));
        };
        Button shuffle = Button.secondary("mp:shuffle", "Shuffle").withEmoji(icons.of(Icons.SHUFFLE));
        Button volDown = Button.secondary("mp:voldown", " ").withEmoji(icons.of(Icons.VOL_DOWN));
        Button volUp = Button.secondary("mp:volup", " ").withEmoji(icons.of(Icons.VOL_UP));
        Button queue = Button.secondary("mp:queue", "Queue").withEmoji(icons.of(Icons.QUEUE));

        return List.of(
                ActionRow.of(replay.withDisabled(idle), pause.withDisabled(idle), skip.withDisabled(idle), stop),
                ActionRow.of(loopBtn, shuffle.withDisabled(gm.queueSize() < 2), volDown, volUp, queue));
    }

    // ───────────────────────── helpers ─────────────────────────

    static String esc(String s) {
        return MarkdownSanitizer.escape(s == null ? "" : s);
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String stats(GuildMusic gm, Icons icons) {
        String loop = switch (gm.loop()) {
            case OFF -> "off";
            case TRACK -> "this song";
            case QUEUE -> "the queue";
        };
        return "-# " + icons.text(gm.volume() == 0 ? Icons.VOL_DOWN : Icons.VOL_UP) + " " + gm.volume() + "%"
                + "   •   " + icons.text(Icons.LOOP) + " " + loop
                + "   •   " + icons.text(Icons.QUEUE) + " " + gm.queueSize() + " waiting";
    }

    /** panel.color as "#rrggbb"; falls back to the default pink. */
    private static int color(FileConfiguration cfg) {
        String raw = cfg.getString("panel.color", "").trim().replace("#", "");
        if (raw.isEmpty()) return DEFAULT_COLOR;
        try {
            return Integer.parseInt(raw, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return DEFAULT_COLOR;
        }
    }

    private static boolean isUrl(String s) {
        return s != null && (s.startsWith("https://") || s.startsWith("http://")) && !s.contains(")") && !s.contains(" ");
    }

    private static String progress(AudioTrack track) {
        if (track.getInfo().isStream) return "`● live`";

        long position = track.getPosition();
        long duration = Math.max(1, track.getDuration());
        int knob = (int) Math.min(BAR_SLOTS - 1, position * BAR_SLOTS / duration);

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_SLOTS; i++) {
            bar.append(i == knob ? "●" : (i < knob ? "━" : "─"));
        }
        return "`" + time(position) + "`  " + bar + "  `" + time(duration) + "`";
    }

    private static String time(long millis) {
        long total = millis / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
