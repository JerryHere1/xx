package dev.musicpanel;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The panel's own icon set.
 *
 * <p>The PNGs in {@code resources/icons} are uploaded once as <em>application emojis</em>, which belong
 * to the bot rather than to a server - so they work in every server it joins, with no emoji slots used
 * and nothing for the owner to set up. They're uploaded on first start and reused forever after.
 *
 * <p>If anything goes wrong (no permission, Discord down, an old JDA), every icon quietly falls back to
 * a plain Unicode emoji, so the panel always renders.
 */
final class Icons {

    static final String PLAY = "mp_play";
    static final String PAUSE = "mp_pause";
    static final String STOP = "mp_stop";
    static final String NEXT = "mp_next";
    static final String PREV = "mp_prev";
    static final String LOOP = "mp_loop";
    static final String LOOP_ONE = "mp_loopone";
    static final String SHUFFLE = "mp_shuffle";
    static final String VOL_DOWN = "mp_voldown";
    static final String VOL_UP = "mp_volup";
    static final String QUEUE = "mp_queue";
    static final String NOTE = "mp_note";
    static final String HEART = "mp_heart";

    private static final Map<String, String> FALLBACK = buildFallbacks();

    private final Map<String, Emoji> custom = new ConcurrentHashMap<>();

    private Icons() { }

    /** Plain Unicode only - used before login and whenever the upload can't happen. */
    static Icons plain() {
        return new Icons();
    }

    /**
     * Makes sure every icon exists on the bot's application, uploading the missing ones.
     * Blocking on purpose: this runs once, on the plugin's own startup thread.
     */
    static Icons install(JDA jda, Logger log, boolean enabled) {
        Icons icons = new Icons();
        if (!enabled) {
            log.info("Custom panel icons are off - using normal emoji.");
            return icons;
        }

        try {
            Map<String, ApplicationEmoji> existing = new HashMap<>();
            List<ApplicationEmoji> uploaded = jda.retrieveApplicationEmojis().complete();
            for (ApplicationEmoji emoji : uploaded) {
                existing.put(emoji.getName(), emoji);
            }

            int added = 0;
            for (String name : FALLBACK.keySet()) {
                ApplicationEmoji emoji = existing.get(name);
                if (emoji == null) {
                    byte[] png = read("/icons/" + name + ".png");
                    if (png == null) {
                        log.warning("Panel icon " + name + ".png is missing from the jar - using a normal emoji.");
                        continue;
                    }
                    emoji = jda.createApplicationEmoji(name, Icon.from(png, Icon.IconType.PNG)).complete();
                    added++;
                }
                icons.custom.put(name, emoji);
            }

            if (added > 0) {
                log.info("Uploaded " + added + " panel icons to the bot (this happens only once).");
            } else {
                log.info("Panel icons ready (" + icons.custom.size() + " already uploaded).");
            }
        } catch (Throwable t) {
            // Never let decoration stop the music.
            icons.custom.clear();
            log.warning("Could not set up the custom panel icons, falling back to normal emoji: " + t);
        }
        return icons;
    }

    /** The icon to put on a button. */
    Emoji of(String name) {
        Emoji emoji = custom.get(name);
        return emoji != null ? emoji : Emoji.fromUnicode(FALLBACK.getOrDefault(name, "🎵"));
    }

    /** The icon as text, for use inside a message. */
    String text(String name) {
        return of(name).getFormatted();
    }

    private static byte[] read(String path) {
        try (InputStream in = Icons.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> buildFallbacks() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(PLAY, "▶️");
        map.put(PAUSE, "⏸️");
        map.put(STOP, "⏹️");
        map.put(NEXT, "⏭️");
        map.put(PREV, "⏮️");
        map.put(LOOP, "🔁");
        map.put(LOOP_ONE, "🔂");
        map.put(SHUFFLE, "🔀");
        map.put(VOL_DOWN, "🔉");
        map.put(VOL_UP, "🔊");
        map.put(QUEUE, "📜");
        map.put(NOTE, "🎵");
        map.put(HEART, "💗");
        return java.util.Collections.unmodifiableMap(map);
    }
}
