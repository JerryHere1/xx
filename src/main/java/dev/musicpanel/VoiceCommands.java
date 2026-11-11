package dev.musicpanel;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import org.bukkit.configuration.file.FileConfiguration;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoken commands: "Jarvis, play lofi hip hop".
 *
 * <p>Discord sends the bot one audio stream per speaking user, so only the user IDs listed in the
 * config are transcribed - everyone else's audio is thrown away immediately. Speech recognition runs
 * offline through Vosk, on its own thread: the Discord audio thread must never be made to wait.
 *
 * <p>Created through {@link #start}, which returns {@code null} when the feature is off or unavailable,
 * so a missing speech library can never stop the music bot from working.
 */
final class VoiceCommands implements AudioReceiveHandler {

    /** Discord's receive format: 48 kHz, 16-bit, stereo, big-endian. Vosk wants 16 kHz mono. */
    private static final int DOWNSAMPLE = 3;          // 48000 / 16000
    private static final float VOSK_SAMPLE_RATE = 16_000f;
    private static final int QUEUE_FRAMES = 250;      // ~5 seconds of 20 ms frames
    private static final Pattern TEXT_FIELD = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** One model for the whole server - it's big, read-only and safe to share between recognizers. */
    private static Model sharedModel;                 // guarded by VoiceCommands.class

    private final AudioService service;
    private final GuildMusic gm;
    private final Logger log;

    private final Set<Long> listenTo;
    private final String wakeWord;
    private final boolean requireWakeWord;
    private final long silenceMs;
    private final boolean echoHeard;

    private final Recognizer recognizer;
    private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(QUEUE_FRAMES);
    private final Thread worker;

    private volatile boolean running = true;
    private volatile boolean warnedFull;

    private record Frame(short[] samples, String speaker) { }

    // ───────────────────────── setup ─────────────────────────

    /** @return a listener, or {@code null} if voice commands are off, misconfigured or unavailable. */
    static VoiceCommands start(AudioService service, GuildMusic gm) {
        FileConfiguration cfg = service.cfg();
        Logger log = service.log();
        if (!cfg.getBoolean("voice-commands.enabled", false)) return null;

        Set<Long> listenTo = readListenTo(cfg, log);
        if (listenTo.isEmpty()) {
            log.warning("voice-commands.enabled is true but no user IDs are set. Put your Discord user ID in "
                    + "voice-commands.user-ids (or discord.allowed-user-ids). Voice commands stay off: the bot will "
                    + "not transcribe a whole voice channel.");
            return null;
        }

        try {
            return new VoiceCommands(service, gm, listenTo);
        } catch (Throwable t) {
            // Includes NoClassDefFoundError when the speech library isn't there: music must keep working.
            log.warning("Voice commands could not start (music is unaffected): " + t);
            return null;
        }
    }

    private VoiceCommands(AudioService service, GuildMusic gm, Set<Long> listenTo) throws Exception {
        this.service = service;
        this.gm = gm;
        this.log = service.log();
        this.listenTo = listenTo;

        FileConfiguration cfg = service.cfg();
        this.wakeWord = cfg.getString("voice-commands.wake-word", "jarvis").trim().toLowerCase(Locale.ROOT);
        this.requireWakeWord = cfg.getBoolean("voice-commands.require-wake-word", true) && !wakeWord.isEmpty();
        this.silenceMs = Math.max(200, cfg.getInt("voice-commands.silence-ms", 800));
        this.echoHeard = cfg.getBoolean("voice-commands.show-what-i-heard", true);

        Model model = model(cfg);
        this.recognizer = new Recognizer(model, VOSK_SAMPLE_RATE);

        this.worker = new Thread(this::run, "DiscordMusicPanel-VoiceCommands");
        this.worker.setDaemon(true);
        this.worker.start();

        log.info("Voice commands on: listening to user ID(s) " + listenTo
                + (requireWakeWord ? " for \"" + wakeWord + " <command>\"." : " for spoken commands (no wake word)."));
    }

    /** Loads (once) the offline speech model from disk. */
    private static synchronized Model model(FileConfiguration cfg) throws Exception {
        if (sharedModel != null) return sharedModel;

        String path = cfg.getString("voice-commands.model-path", "plugins/DiscordMusicPanel/vosk-model");
        File dir = new File(path);
        if (!dir.isDirectory()) {
            throw new IllegalStateException("No speech model at \"" + dir.getAbsolutePath()
                    + "\". Download a small model from https://alphacephei.com/vosk/models"
                    + " (for example vosk-model-small-en-us-0.15.zip), unzip it, and put the folder there"
                    + " (it must contain the files am/, conf/, graph/).");
        }
        quietLogs();
        sharedModel = new Model(dir.getAbsolutePath());
        return sharedModel;
    }

    /** Vosk prints a wall of Kaldi logs by default. Done by name because the enum differs between versions. */
    private static void quietLogs() {
        try {
            Class<?> levelType = Class.forName("org.vosk.LogLevel");
            Object quiet = null;
            for (Object constant : levelType.getEnumConstants()) {
                if ("WARNINGS".equals(constant.toString())) quiet = constant;
            }
            if (quiet != null) LibVosk.class.getMethod("setLogLevel", levelType).invoke(null, quiet);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    private static Set<Long> readListenTo(FileConfiguration cfg, Logger log) {
        Object raw = cfg.get("voice-commands.user-ids");
        if (raw == null || (raw instanceof List<?> list && list.isEmpty())) {
            raw = cfg.get("discord.allowed-user-ids");
        }
        if (raw == null) return Set.of();

        List<Object> entries = raw instanceof List<?> list ? new ArrayList<>(list) : List.of(raw);
        Set<Long> ids = new HashSet<>();
        for (Object entry : entries) {
            String text = String.valueOf(entry).trim();
            if (text.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(text));
            } catch (NumberFormatException e) {
                log.warning("voice-commands.user-ids: \"" + text + "\" is not a Discord user ID - ignored.");
            }
        }
        return ids;
    }

    void close() {
        running = false;
        worker.interrupt();
        try {
            recognizer.close();
        } catch (Throwable ignored) {
            // shutting down anyway
        }
    }

    // ───────────────────────── receiving audio ─────────────────────────

    @Override
    public boolean canReceiveUser() {
        return true;      // one stream per speaker, so we can pick out just the owner
    }

    @Override
    public boolean canReceiveCombined() {
        return false;
    }

    @Override
    public boolean canReceiveEncoded() {
        return false;
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        // Runs on Discord's audio thread every 20 ms: convert and hand off, never block.
        if (!listenTo.contains(userAudio.getUser().getIdLong())) return;

        Frame frame = new Frame(toMono16k(userAudio.getAudioData(1.0)), userAudio.getUser().getName());
        if (!frames.offer(frame) && !warnedFull) {
            warnedFull = true;
            log.warning("Speech recognition can't keep up with the audio on this machine - some speech will be missed.");
        }
    }

    /** 48 kHz stereo 16-bit big-endian bytes -> 16 kHz mono samples. */
    private static short[] toMono16k(byte[] data) {
        int stereoFrames = data.length / 4;                 // 2 channels x 2 bytes
        short[] out = new short[stereoFrames / DOWNSAMPLE];
        int at = 0;
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int k = 0; k < DOWNSAMPLE; k++) {
                int left = (data[at] << 8) | (data[at + 1] & 0xFF);
                int right = (data[at + 2] << 8) | (data[at + 3] & 0xFF);
                at += 4;
                sum += (left + right) / 2;
            }
            out[i] = (short) (sum / DOWNSAMPLE);
        }
        return out;
    }

    // ───────────────────────── recognition ─────────────────────────

    private void run() {
        long lastAudioAt = 0;
        String speaker = null;

        while (running) {
            try {
                Frame frame = frames.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    lastAudioAt = System.currentTimeMillis();
                    speaker = frame.speaker();
                    if (recognizer.acceptWaveForm(frame.samples(), frame.samples().length)) {
                        handle(textOf(recognizer.getResult()), speaker);
                    }
                } else if (lastAudioAt != 0 && System.currentTimeMillis() - lastAudioAt > silenceMs) {
                    // Discord stops sending frames when the person stops talking, so flush on silence.
                    lastAudioAt = 0;
                    handle(textOf(recognizer.getFinalResult()), speaker);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.warning("Voice command error: " + t);
            }
        }
    }

    /** Vosk returns {@code {"text" : "lofi hip hop"}}. Pulled out by hand: it's always this one field. */
    private static String textOf(String json) {
        if (json == null) return "";
        Matcher m = TEXT_FIELD.matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").trim().toLowerCase(Locale.ROOT);
    }

    // ───────────────────────── commands ─────────────────────────

    private void handle(String heard, String speaker) {
        if (heard.isEmpty()) return;

        String command = heard;
        if (requireWakeWord) {
            int at = heard.indexOf(wakeWord);
            if (at < 0) return;                                   // not talking to the bot
            command = heard.substring(at + wakeWord.length()).trim();
        }
        command = strip(command, "please", "can you", "could you", "i want you to", ",");
        if (command.isEmpty()) return;

        if (echoHeard) gm.say("🎤 Heard: *" + Panel.esc(Panel.cut(heard, 150)) + "*");
        log.info("Voice command from " + speaker + ": \"" + command + "\"");

        String query = after(command, "play", "put on", "queue", "start");
        if (query != null && !query.isBlank()) {
            String requester = speaker == null ? "voice" : speaker;
            service.load(gm, query, requester, gm::say);
            return;
        }

        int step = service.cfg().getInt("player.volume-step", 10);
        if (matches(command, "skip", "next", "next song", "next one")) {
            gm.skip();
            gm.say("⏭ Skipped.");
        } else if (matches(command, "stop", "stop the music", "shut up", "be quiet")) {
            gm.destroy("Stopped by voice command.");
        } else if (matches(command, "pause", "hold on", "wait")) {
            gm.setPaused(true);
            gm.say("⏸ Paused.");
        } else if (matches(command, "resume", "continue", "unpause", "keep going")) {
            gm.setPaused(false);
            gm.say("▶ Playing again.");
        } else if (matches(command, "louder", "volume up", "turn it up", "turn up")) {
            gm.say("🔊 Volume " + gm.changeVolume(step) + "%.");
        } else if (matches(command, "quieter", "volume down", "turn it down", "turn down", "lower")) {
            gm.say("🔉 Volume " + gm.changeVolume(-step) + "%.");
        } else if (matches(command, "loop", "repeat")) {
            gm.say("🔁 Loop: " + gm.cycleLoop().name().toLowerCase(Locale.ROOT) + ".");
        } else if (matches(command, "shuffle", "mix it up")) {
            gm.shuffle();
            gm.say("🔀 Shuffled the queue.");
        } else if (echoHeard) {
            gm.say("❓ I didn't understand that. Try \"" + (requireWakeWord ? wakeWord + " " : "")
                    + "play <song>\", skip, stop, pause, resume, louder, quieter, loop or shuffle.");
            return;
        } else {
            return;
        }
        gm.refreshPanel();
    }

    /** @return the text after any of {@code verbs}, or null if none of them starts the command. */
    private static String after(String command, String... verbs) {
        for (String verb : verbs) {
            if (command.equals(verb)) return "";
            if (command.startsWith(verb + " ")) return command.substring(verb.length()).trim();
        }
        return null;
    }

    private static boolean matches(String command, String... options) {
        for (String option : options) {
            if (command.equals(option) || command.startsWith(option + " ")) return true;
        }
        return false;
    }

    private static String strip(String command, String... fillers) {
        String out = command.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String filler : fillers) {
                if (out.startsWith(filler)) {
                    out = out.substring(filler.length()).trim();
                    changed = true;
                }
            }
        }
        return out;
    }
}
