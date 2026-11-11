package dev.musicpanel;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import moe.kyokobot.libdave.DaveFactory;
import moe.kyokobot.libdave.NativeDaveFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.configuration.file.FileConfiguration;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The Discord side: logs in, registers /play and /panel, and handles the panel's buttons.
 * The panel is posted in the text chat of the voice channel the user is sitting in.
 */
final class DiscordBot extends ListenerAdapter {

    /** Permission bits for the invite link: View Channel, Send Messages, Embed Links, Connect, Speak. */
    private static final long INVITE_PERMISSIONS = 3165184L;

    private final MusicPanelPlugin plugin;
    private volatile AudioService audio;
    private volatile JDA jda;
    private volatile boolean stopped;
    /** Discord user IDs allowed to control the music. Empty = everyone in the voice channel. */
    private volatile List<Long> allowedUsers = List.of();
    private volatile Prefixes prefixes;
    /** False when Discord refused the message-content intent, so +commands are off. */
    private volatile boolean textCommands;

    DiscordBot(MusicPanelPlugin plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────── lifecycle ─────────────────────────

    void start() throws InterruptedException {
        FileConfiguration cfg = plugin.getConfig();
        checkDaveNatives();
        allowedUsers = readAllowedUsers(cfg);

        AudioService service = new AudioService(plugin);
        audio = service;

        DaveFactory dave = new NativeDaveFactory();
        prefixes = new Prefixes(plugin.getDataFolder(), cfg.getString("discord.prefix", "+"), plugin.getLogger());
        textCommands = cfg.getBoolean("discord.prefix-commands", true);

        JDA built = connect(cfg, dave, textCommands);
        if (stopped) {
            built.shutdownNow();
            return;
        }

        jda = built;
        service.attach(built);
        service.setIcons(Icons.install(built, plugin.getLogger(), cfg.getBoolean("panel.custom-icons", true)));
        registerCommands(built, cfg.getString("discord.guild-id", ""));

        plugin.getLogger().info("Music bot online as " + built.getSelfUser().getName() + ".");
        plugin.getLogger().info(textCommands
                ? "Prefix commands on - default prefix \"" + prefixes.fallback() + "\" (try " + prefixes.fallback() + "help)."
                : "Prefix commands off - slash commands only.");
        plugin.getLogger().info("Invite link: https://discord.com/oauth2/authorize?client_id=" + built.getSelfUser().getId()
                + "&scope=bot%20applications.commands&permissions=" + INVITE_PERMISSIONS);
    }

    /**
     * Logs in. Reading "+play" style commands needs Discord's privileged "Message Content" intent, and
     * Discord refuses the whole connection if the app doesn't have it switched on. So if the first attempt
     * fails, try again without it rather than leaving the owner with a bot that won't start at all.
     */
    private JDA connect(FileConfiguration cfg, DaveFactory dave, boolean withMessages) throws InterruptedException {
        JDA built = null;
        try {
            built = build(cfg, dave, withMessages);
            built.awaitReady();
            return built;
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            if (built != null) built.shutdownNow();
            if (!withMessages) throw e;

            plugin.getLogger().warning("Could not connect with the \"Message Content\" intent, so prefix commands "
                    + "(+play, +skip, ...) are OFF. Switch it on at https://discord.com/developers/applications "
                    + "-> your app -> Bot -> Privileged Gateway Intents -> MESSAGE CONTENT INTENT, then restart. "
                    + "Slash commands keep working. Reason: " + e);
            textCommands = false;

            JDA retry = build(cfg, dave, false);
            retry.awaitReady();
            return retry;
        }
    }

    private JDA build(FileConfiguration cfg, DaveFactory dave, boolean withMessages) {
        JDABuilder builder = JDABuilder.createLight(cfg.getString("discord.token", ""))
                .enableIntents(GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new DaveFix(dave, () -> jda, plugin.getLogger())))
                .setActivity(Activity.listening(cfg.getString("discord.activity", "/play")))
                .addEventListeners(this);

        if (withMessages) {
            builder.enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT);
        }
        return builder.build();
    }

    /**
     * Discord requires DAVE end-to-end encryption for voice. Its native library must load on this OS/CPU,
     * otherwise the bot connects, then gets kicked from voice (close code 4017). Fail early with a clear message.
     * Called by name because older libdave versions don't have ensureAvailable().
     */
    private void checkDaveNatives() {
        try {
            NativeDaveFactory.class.getMethod("ensureAvailable").invoke(null);
            plugin.getLogger().info("DAVE voice encryption library loaded.");
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // older libdave: the library is loaded on the first voice connection instead
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("The DAVE voice-encryption library could not be loaded on this server ("
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch") + "): " + cause
                    + ". Supported: Linux x86-64, Linux aarch64, Windows x86-64.", cause);
        }
    }

    // ───────────────────────── who may control the music ─────────────────────────

    /**
     * Reads discord.allowed-user-ids. Accepts a list or a single value, and ignores anything that
     * isn't a Discord user ID (17-20 digits) with a warning, so one typo can't lock everyone out silently.
     */
    private List<Long> readAllowedUsers(FileConfiguration cfg) {
        Object raw = cfg.get("discord.allowed-user-ids");
        if (raw == null) return List.of();

        List<Object> entries = raw instanceof List<?> list ? new ArrayList<>(list) : List.of(raw);
        List<Long> ids = new ArrayList<>();
        for (Object entry : entries) {
            String text = String.valueOf(entry).trim();
            if (text.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(text));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("discord.allowed-user-ids: \"" + text + "\" is not a Discord user ID (numbers only) - ignored.");
            }
        }

        if (ids.isEmpty()) {
            plugin.getLogger().info("Music controls are open to everyone in the voice channel.");
        } else {
            plugin.getLogger().info("Music controls are limited to user ID(s): " + ids);
        }
        return List.copyOf(ids);
    }

    private boolean mayControl(Member member) {
        List<Long> allowed = allowedUsers;
        return allowed.isEmpty() || allowed.contains(member.getIdLong());
    }

    /** For /musicpanel status. */
    String controlInfo() {
        List<Long> allowed = allowedUsers;
        return allowed.isEmpty() ? "anyone in the voice channel" : "only user ID(s) " + allowed;
    }

    /** @return the "you're not allowed" text, naming whoever is allowed. */
    private String notAllowedMessage() {
        List<Long> allowed = allowedUsers;
        if (allowed.isEmpty()) return "🔒 You can't control the music right now.";

        StringBuilder who = new StringBuilder();
        for (long id : allowed) {
            if (who.length() > 0) who.append(" or ");
            who.append("<@").append(id).append('>');
        }
        return "🔒 Only " + who + " can control the music here.";
    }

    void shutdown() {
        stopped = true;
        AudioService service = audio;
        JDA current = jda;
        if (service != null) service.shutdown();   // leaves voice + marks panels as stopped while JDA is still up
        if (current != null) {
            current.shutdown();
            try {
                if (!current.awaitShutdown(5, TimeUnit.SECONDS)) current.shutdownNow();
            } catch (InterruptedException e) {
                current.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean isOnline() {
        JDA current = jda;
        return current != null && current.getStatus() == JDA.Status.CONNECTED;
    }

    String botName() {
        JDA current = jda;
        return current == null ? "?" : current.getSelfUser().getName();
    }

    int activePlayers() {
        AudioService service = audio;
        return service == null ? 0 : service.activePlayers();
    }

    private void registerCommands(JDA j, String guildId) {
        List<SlashCommandData> commands = List.of(
                Commands.slash("play", "Play a YouTube / Spotify link, or search by name")
                        .addOption(OptionType.STRING, "query", "YouTube or Spotify link, or search words", true)
                        .setContexts(InteractionContextType.GUILD),
                Commands.slash("panel", "Post the music control panel in your voice channel's chat")
                        .setContexts(InteractionContextType.GUILD));

        if (!guildId.isBlank()) {
            Guild guild = j.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(commands).queue();
                return;
            }
            plugin.getLogger().warning("discord.guild-id " + guildId + " not found (is the bot invited there?) - registering commands globally.");
        }
        j.updateCommands().addCommands(commands).queue();
    }

    // ───────────────────────── slash commands ─────────────────────────

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();
        Member member = e.getMember();
        AudioService service = audio;
        if (guild == null || member == null || service == null) return;

        switch (e.getName()) {
            case "play" -> handlePlay(e, guild, member, service);
            case "panel" -> handlePanel(e, guild, member, service);
            default -> { }
        }
    }

    private void handlePlay(SlashCommandInteractionEvent e, Guild guild, Member member, AudioService service) {
        GuildMessageChannel typedIn = e.getChannel() instanceof GuildMessageChannel c ? c : null;
        doPlay(guild, member, typedIn, e.getOption("query", opt -> opt.getAsString()), respondTo(e), service);
    }

    /**
     * The one play routine behind both {@code /play} and {@code +play}. Every refusal happens before
     * {@link Responder#thinking}, because a slash command may only be answered once.
     */
    private void doPlay(Guild guild, Member member, GuildMessageChannel typedIn, String query,
                        Responder reply, AudioService service) {
        if (!mayControl(member)) {
            reply.deny(notAllowedMessage());
            return;
        }

        GuildVoiceState voice = member.getVoiceState();
        if (voice == null || !voice.inAudioChannel() || voice.getChannel() == null) {
            reply.deny("🔊 Join a voice channel first, then try again.");
            return;
        }
        AudioChannel vc = voice.getChannel();
        Member self = guild.getSelfMember();

        if (!self.hasPermission(vc, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            reply.deny("❌ I need **View Channel**, **Connect** and **Speak** in " + vc.getAsMention() + ".");
            return;
        }

        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();
        if (botChannel != null && botChannel.getIdLong() != vc.getIdLong() && humans(botChannel) > 0) {
            reply.deny("🎧 I'm already playing in " + botChannel.getAsMention() + ". Join that channel to add songs.");
            return;
        }

        GuildMessageChannel chat = vc instanceof GuildMessageChannel voiceChat ? voiceChat : typedIn;
        if (chat == null || !self.hasPermission(chat, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            reply.deny("❌ I need **Send Messages** and **Embed Links** in "
                    + (chat == null ? "this channel" : chat.getAsMention()) + " to show the panel.");
            return;
        }

        if (query == null || query.isBlank()) {
            reply.deny("Type a link or a song name after the command.");
            return;
        }

        // Visible so everyone sees the bot answer, then edited into the result once the song is found.
        reply.thinking("🎧 Listening... looking for **" + Panel.esc(Panel.cut(query, 100)) + "**");

        GuildMusic gm = service.get(guild);
        gm.setChatChannel(chat);   // so the first song is announced too
        gm.connect(vc);
        service.load(gm, query, member.getEffectiveName(), message -> {
            reply.result(message);
            gm.ensurePanel(chat);
        });
    }

    // ───────────────────────── answering either kind of command ─────────────────────────

    /** Lets a slash command and a typed command be answered by the same code. */
    private interface Responder {
        /** Shown immediately, before the search starts. */
        void thinking(String text);

        /** Replaces the "thinking" line once the result is known. */
        void result(String text);

        /** A refusal; private where the platform allows it. */
        void deny(String text);
    }

    private static Responder respondTo(SlashCommandInteractionEvent e) {
        return new Responder() {
            @Override
            public void thinking(String text) {
                e.reply(text).queue();
            }

            @Override
            public void result(String text) {
                e.getHook().editOriginal(text).queue(null, error -> { });
            }

            @Override
            public void deny(String text) {
                e.reply(text).setEphemeral(true).queue();
            }
        };
    }

    private static Responder respondTo(GuildMessageChannel channel) {
        AtomicReference<CompletableFuture<Message>> sent = new AtomicReference<>();
        return new Responder() {
            @Override
            public void thinking(String text) {
                sent.set(channel.sendMessage(text).submit());
            }

            @Override
            public void result(String text) {
                CompletableFuture<Message> pending = sent.get();
                if (pending == null) {
                    deny(text);
                    return;
                }
                // Chained, so the edit can't run before we know which message to edit.
                pending.thenAccept(message -> message.editMessage(text).queue(null, error -> { }))
                        .exceptionally(error -> {
                            deny(text);
                            return null;
                        });
            }

            @Override
            public void deny(String text) {
                channel.sendMessage(text).queue(null, error -> { });
            }
        };
    }

    private void handlePanel(SlashCommandInteractionEvent e, Guild guild, Member member, AudioService service) {
        if (!mayControl(member)) {
            e.reply(notAllowedMessage()).setEphemeral(true).queue();
            return;
        }

        GuildMusic gm = service.find(guild.getIdLong());
        GuildVoiceState voice = member.getVoiceState();
        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();

        if (gm == null || botChannel == null || voice == null || voice.getChannel() == null
                || voice.getChannel().getIdLong() != botChannel.getIdLong()) {
            e.reply("Nothing is playing for you. Join the voice channel I'm in, or start music with `/play`.").setEphemeral(true).queue();
            return;
        }
        GuildMessageChannel chat = panelChannel(botChannel, e);
        if (chat == null) {
            e.reply("❌ I can't post messages here.").setEphemeral(true).queue();
            return;
        }
        gm.resendPanel(chat);
        e.reply("📻 Panel posted.").setEphemeral(true).queue();
    }

    /** The voice channel's own text chat; falls back to the channel the command was typed in. */
    private static GuildMessageChannel panelChannel(AudioChannel vc, SlashCommandInteractionEvent e) {
        if (vc instanceof GuildMessageChannel voiceChat) return voiceChat;
        return e.getChannel() instanceof GuildMessageChannel typedIn ? typedIn : null;
    }

    // ───────────────────────── prefix commands (+play, +skip, ...) ─────────────────────────

    /** Commands that need music to already be playing. Anything else typed after the prefix is ignored. */
    private static final List<String> PLAYER_COMMANDS = List.of(
            "skip", "s", "next", "stop", "leave", "dc", "disconnect", "pause", "resume", "unpause",
            "volume", "vol", "v", "loop", "repeat", "shuffle", "mix", "queue", "q", "nowplaying", "np", "panel");

    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        AudioService service = audio;
        Prefixes store = prefixes;
        if (!textCommands || service == null || store == null) return;
        if (e.getAuthor().isBot() || !e.isFromGuild()) return;

        Member member = e.getMember();
        if (member == null || !(e.getChannel() instanceof GuildMessageChannel channel)) return;

        Guild guild = e.getGuild();
        String prefix = store.get(guild.getIdLong());
        String content = e.getMessage().getContentRaw().trim();
        if (!content.startsWith(prefix) || content.length() == prefix.length()) return;

        String[] parts = content.substring(prefix.length()).trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String args = parts.length > 1 ? parts[1].trim() : "";

        try {
            runTextCommand(guild, member, channel, service, prefix, command, args);
        } catch (Exception failure) {
            plugin.getLogger().warning("Command \"" + prefix + command + "\" failed: " + failure);
        }
    }

    private void runTextCommand(Guild guild, Member member, GuildMessageChannel channel, AudioService service,
                                String prefix, String command, String args) {
        Consumer<String> say = text -> channel.sendMessage(Panel.cut(text, 1900)).queue(null, error -> { });

        switch (command) {
            case "help", "commands", "h" -> {
                say.accept(helpText(prefix));
                return;
            }
            case "prefix" -> {
                changePrefix(guild, member, args, prefix, say);
                return;
            }
            case "play", "p" -> {
                doPlay(guild, member, channel, args, respondTo(channel), service);
                return;
            }
            default -> {
                if (!PLAYER_COMMANDS.contains(command)) return;   // not ours - stay quiet
            }
        }

        GuildMusic gm = service.find(guild.getIdLong());
        if (gm == null) {
            say.accept("Nothing is playing right now. Start with `" + prefix + "play <song or link>`.");
            return;
        }
        if (!mayControl(member)) {
            say.accept(notAllowedMessage());
            return;
        }
        if (!sharesVoiceChannel(guild, member)) {
            say.accept("🔊 Join my voice channel first.");
            return;
        }

        int step = plugin.getConfig().getInt("player.volume-step", 10);
        switch (command) {
            case "skip", "s", "next" -> {
                gm.skip();
                say.accept("⏭️ Skipped.");
            }
            case "stop", "leave", "dc", "disconnect" -> {
                gm.destroy("Stopped by " + member.getEffectiveName() + ".");
                return;                                   // the panel is gone, nothing left to refresh
            }
            case "pause" -> {
                gm.setPaused(true);
                say.accept("⏸️ Paused.");
            }
            case "resume", "unpause" -> {
                gm.setPaused(false);
                say.accept("▶️ Playing again.");
            }
            case "volume", "vol", "v" -> volumeCommand(gm, args, step, say);
            case "loop", "repeat" -> say.accept("🔁 Loop: " + gm.cycleLoop().name().toLowerCase(Locale.ROOT) + ".");
            case "shuffle", "mix" -> {
                gm.shuffle();
                say.accept("🔀 Shuffled the queue.");
            }
            case "queue", "q" -> say.accept(queueText(gm));
            case "nowplaying", "np" -> channel.sendMessageComponents(Panel.view(gm))
                    .useComponentsV2().queue(null, error -> { });
            case "panel" -> gm.resendPanel(channel);
            default -> { }
        }
        gm.refreshPanel();
    }

    private void changePrefix(Guild guild, Member member, String args, String current, Consumer<String> say) {
        if (!member.hasPermission(Permission.ADMINISTRATOR)) {
            say.accept("🔒 Only server administrators can change the prefix.");
            return;
        }
        if (args.isEmpty()) {
            say.accept("The prefix here is `" + current + "`. Change it with `" + current + "prefix !`.");
            return;
        }
        String error = prefixes.set(guild.getIdLong(), args);
        say.accept(error == null
                ? "✅ Prefix changed to `" + prefixes.get(guild.getIdLong()) + "` - try `" + prefixes.get(guild.getIdLong()) + "help`."
                : "❌ " + error);
    }

    private static void volumeCommand(GuildMusic gm, String args, int step, Consumer<String> say) {
        String wanted = args.toLowerCase(Locale.ROOT).replace("%", "").trim();
        if (wanted.isEmpty()) {
            say.accept("🔊 Volume is **" + gm.volume() + "%**. Use `volume 50`, `volume up` or `volume down`.");
            return;
        }
        if (wanted.equals("up") || wanted.equals("+")) {
            say.accept("🔊 Volume **" + gm.changeVolume(step) + "%**.");
            return;
        }
        if (wanted.equals("down") || wanted.equals("-")) {
            say.accept("🔉 Volume **" + gm.changeVolume(-step) + "%**.");
            return;
        }
        try {
            say.accept("🔊 Volume **" + gm.setVolume(Integer.parseInt(wanted)) + "%**.");
        } catch (NumberFormatException e) {
            say.accept("Use `volume 50`, `volume up` or `volume down`.");
        }
    }

    private static boolean sharesVoiceChannel(Guild guild, Member member) {
        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();
        GuildVoiceState voice = member.getVoiceState();
        return botChannel != null && voice != null && voice.getChannel() != null
                && voice.getChannel().getIdLong() == botChannel.getIdLong();
    }

    private String helpText(String p) {
        return """
                ## 🎵 Music commands
                `%1$splay <song or link>` — play or add to the queue (also `%1$sp`)
                `%1$sskip` — next song  ·  `%1$sstop` — stop and leave
                `%1$spause` / `%1$sresume` — hold and continue
                `%1$svolume 50` — set it, or `volume up` / `volume down`
                `%1$sloop` — off → track → queue  ·  `%1$sshuffle` — mix the queue
                `%1$squeue` — what's coming up  ·  `%1$snp` — what's playing now
                `%1$spanel` — post the control panel again
                `%1$sprefix <new>` — change the prefix *(administrators only)*

                Slash commands work too: `/play` and `/panel`.""".formatted(p);
    }

    // ───────────────────────── panel buttons ─────────────────────────

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String id = e.getComponentId();
        Guild guild = e.getGuild();
        Member member = e.getMember();
        AudioService service = audio;
        if (!id.startsWith("mp:") || guild == null || member == null || service == null) return;

        GuildMusic gm = service.find(guild.getIdLong());
        if (gm == null) {
            e.reply("This panel is no longer active. Use `/play` to start again.").setEphemeral(true).queue();
            return;
        }

        if (!mayControl(member)) {
            e.reply(notAllowedMessage()).setEphemeral(true).queue();
            return;
        }

        // Only people sitting in the bot's voice channel may press the buttons.
        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();
        GuildVoiceState voice = member.getVoiceState();
        if (botChannel == null || voice == null || voice.getChannel() == null
                || voice.getChannel().getIdLong() != botChannel.getIdLong()) {
            e.reply("🔊 Join " + (botChannel == null ? "my voice channel" : botChannel.getAsMention()) + " to use the controls.")
                    .setEphemeral(true).queue();
            return;
        }

        int step = plugin.getConfig().getInt("player.volume-step", 10);
        switch (id) {
            case "mp:pause" -> gm.togglePause();
            case "mp:skip" -> gm.skip();
            case "mp:restart" -> {
                if (!gm.restart()) {
                    e.reply("This track can't be rewound.").setEphemeral(true).queue();
                    return;
                }
            }
            case "mp:loop" -> gm.cycleLoop();
            case "mp:shuffle" -> gm.shuffle();
            case "mp:voldown" -> gm.changeVolume(-step);
            case "mp:volup" -> gm.changeVolume(step);
            case "mp:stop" -> {
                e.editComponents(Panel.stopped(gm, "Stopped by " + member.getEffectiveName() + "."))
                        .useComponentsV2().queue();
                gm.destroy("Stopped by " + member.getEffectiveName() + ".");
                return;
            }
            case "mp:queue" -> {
                e.reply(queueText(gm)).setEphemeral(true).queue();
                return;
            }
            default -> {
                e.deferEdit().queue();
                return;
            }
        }

        // Answer the click by redrawing the panel that was clicked.
        e.editComponents(Panel.view(gm)).useComponentsV2().queue();
    }

    private static String queueText(GuildMusic gm) {
        List<AudioTrack> next = gm.upcoming(10);
        if (next.isEmpty()) return "📜 The queue is empty.";
        StringBuilder sb = new StringBuilder("📜 **Up next** (" + gm.queueSize() + " in queue)\n");
        for (int i = 0; i < next.size(); i++) {
            sb.append(i + 1).append(". ").append(Panel.esc(Panel.cut(next.get(i).getInfo().title, 70))).append('\n');
        }
        if (gm.queueSize() > next.size()) sb.append("...and ").append(gm.queueSize() - next.size()).append(" more");
        return sb.toString().trim();
    }

    // ───────────────────────── voice events ─────────────────────────

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent e) {
        AudioService service = audio;
        if (service == null) return;
        Guild guild = e.getGuild();
        GuildMusic gm = service.find(guild.getIdLong());
        if (gm == null) return;

        // The bot itself was kicked / disconnected.
        if (e.getMember().getIdLong() == guild.getSelfMember().getIdLong() && e.getChannelJoined() == null) {
            gm.destroy("I was disconnected from the voice channel.");
            return;
        }

        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();
        if (botChannel != null) gm.onHumanCount(humans(botChannel));
    }

    private static int humans(AudioChannel channel) {
        int n = 0;
        for (Member m : channel.getMembers()) {
            if (!m.getUser().isBot()) n++;
        }
        return n;
    }
}
