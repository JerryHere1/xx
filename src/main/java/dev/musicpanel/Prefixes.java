package dev.musicpanel;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Remembers each Discord server's command prefix in {@code plugins/DiscordMusicPanel/prefixes.yml}.
 *
 * <p>Kept out of config.yml on purpose: this file is written by the bot when an administrator runs
 * {@code +prefix}, and rewriting config.yml would throw away the owner's comments.
 */
final class Prefixes {

    /** Discord's own limit is generous, but a long prefix is a mistake, not a choice. */
    private static final int MAX_LENGTH = 5;

    private final File file;
    private final Logger log;
    private final String fallback;
    private final YamlConfiguration yaml;

    Prefixes(File dataFolder, String fallback, Logger log) {
        this.file = new File(dataFolder, "prefixes.yml");
        this.log = log;
        this.fallback = clean(fallback).isEmpty() ? "+" : clean(fallback);
        this.yaml = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    /** The prefix for one server, falling back to the one in config.yml. */
    synchronized String get(long guildId) {
        String saved = yaml.getString(String.valueOf(guildId));
        return saved == null || saved.isEmpty() ? fallback : saved;
    }

    /** The prefix every server starts with. */
    String fallback() {
        return fallback;
    }

    /** @return an error for the user, or null if the prefix was saved. */
    synchronized String set(long guildId, String prefix) {
        String wanted = clean(prefix);
        if (wanted.isEmpty()) return "Give me a prefix, like `+` or `!`.";
        if (wanted.length() > MAX_LENGTH) return "That's too long - use at most " + MAX_LENGTH + " characters.";
        if (wanted.contains("`")) return "A prefix can't contain a backtick.";

        yaml.set(String.valueOf(guildId), wanted);
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.warning("Could not save prefixes.yml: " + e);
            return "I changed it for now, but couldn't save it to disk - check the server's file permissions.";
        }
        return null;
    }

    private static String clean(String prefix) {
        return prefix == null ? "" : prefix.trim();
    }
}
