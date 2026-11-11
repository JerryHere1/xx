package dev.musicpanel;

import moe.kyokobot.libdave.DaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Works around a problem in Discord's voice encryption (DAVE) that stops the bot from understanding
 * speech, which breaks {@link VoiceCommands}.
 *
 * <p>Discord encrypts voice per speaker, and the bot only receives a decryption key for people the
 * encryption library already knows about at the moment the group's keys are handed out. When the bot
 * joins a channel somebody is <em>already sitting in</em>, that person is often registered a moment
 * too late: the library then creates a decryptor for them with no key, and every packet from them
 * fails to decrypt for the rest of the call ("Failed to decrypt audio packet for user ...").
 *
 * <p>This wrapper registers everyone currently in the voice channel <em>before</em> handing each
 * key-exchange message to the real implementation, so nobody is missed. It changes no encryption
 * itself - every call is passed straight through.
 */
final class DaveFix implements DaveSessionFactory {

    private final DaveSessionFactory delegate;
    private final Supplier<JDA> jda;
    private final Logger log;

    DaveFix(DaveFactory factory, Supplier<JDA> jda, Logger log) {
        this.delegate = new LDJDADaveSessionFactory(factory);
        this.jda = jda;
        this.log = log;
    }

    @Override
    public DaveSession createDaveSession(DaveProtocolCallbacks callbacks, long userId, long channelId) {
        return new Session(delegate.createDaveSession(callbacks, userId, channelId), userId, channelId, jda, log);
    }

    /** Passes everything to the real session, but keeps its list of voice users up to date. */
    private static final class Session implements DaveSession {

        /** Roughly two seconds of failed 20 ms packets before we tell the owner something is wrong. */
        private static final int FAILURES_BEFORE_WARNING = 100;

        private final DaveSession inner;
        private final long selfUserId;
        private final long channelId;
        private final Supplier<JDA> jda;
        private final Logger log;

        private final Set<Long> registered = ConcurrentHashMap.newKeySet();
        private final Map<Long, Integer> failures = new ConcurrentHashMap<>();
        private volatile boolean warned;

        Session(DaveSession inner, long selfUserId, long channelId, Supplier<JDA> jda, Logger log) {
            this.inner = inner;
            this.selfUserId = selfUserId;
            this.channelId = channelId;
            this.jda = jda;
            this.log = log;
        }

        /** Registers anyone in the voice channel the encryption library hasn't been told about yet. */
        private void syncVoiceUsers() {
            try {
                JDA api = jda.get();
                if (api == null) return;
                AudioChannel channel = api.getChannelById(AudioChannel.class, channelId);
                if (channel == null) return;

                for (Member member : channel.getMembers()) {
                    long id = member.getIdLong();
                    if (id == selfUserId) continue;
                    if (registered.add(id)) {
                        log.info("DAVE: registering voice user " + id + " before the key exchange.");
                        inner.addUser(id);
                    }
                }
            } catch (Exception e) {
                // Never let this break the voice connection - worst case we're back to the old behaviour.
                log.warning("DAVE: could not list the voice channel's members: " + e);
            }
        }

        // ───────────── the key-exchange messages: make sure nobody is missing first ─────────────

        @Override
        public void onSelectProtocolAck(int protocolVersion) {
            syncVoiceUsers();
            inner.onSelectProtocolAck(protocolVersion);
        }

        @Override
        public void onDaveProtocolPrepareTransition(int transitionId, int protocolVersion) {
            syncVoiceUsers();
            inner.onDaveProtocolPrepareTransition(transitionId, protocolVersion);
        }

        @Override
        public void onDaveProtocolPrepareEpoch(long epoch, int protocolVersion) {
            syncVoiceUsers();
            inner.onDaveProtocolPrepareEpoch(epoch, protocolVersion);
        }

        @Override
        public void onMLSProposals(ByteBuffer proposals) {
            syncVoiceUsers();
            inner.onMLSProposals(proposals);
        }

        @Override
        public void onMLSPrepareCommitTransition(int transitionId, ByteBuffer commit) {
            syncVoiceUsers();
            inner.onMLSPrepareCommitTransition(transitionId, commit);
        }

        @Override
        public void onMLSWelcome(int transitionId, ByteBuffer welcome) {
            syncVoiceUsers();      // the case that actually goes wrong: joining an occupied channel
            inner.onMLSWelcome(transitionId, welcome);
        }

        // ───────────── user bookkeeping ─────────────

        @Override
        public void addUser(long userId) {
            registered.add(userId);
            inner.addUser(userId);
        }

        @Override
        public void removeUser(long userId) {
            registered.remove(userId);
            failures.remove(userId);
            inner.removeUser(userId);
        }

        // ───────────── audio ─────────────

        @Override
        public boolean decrypt(MediaType mediaType, long userId, ByteBuffer encrypted, ByteBuffer decrypted) {
            boolean ok = inner.decrypt(mediaType, userId, encrypted, decrypted);
            if (ok) {
                failures.remove(userId);
                return true;
            }
            if (failures.merge(userId, 1, Integer::sum) == FAILURES_BEFORE_WARNING && !warned) {
                warned = true;
                log.warning("Discord never sent voice decryption keys for user " + userId + ", so the bot cannot "
                        + "understand their speech (music is unaffected). Ask them to leave the voice channel and "
                        + "rejoin it while the bot stays in it. If that doesn't help, set voice-commands.enabled "
                        + "to false in config.yml to silence this.");
            }
            return false;
        }

        @Override
        public boolean encrypt(MediaType mediaType, int ssrc, ByteBuffer data, ByteBuffer encrypted) {
            return inner.encrypt(mediaType, ssrc, data, encrypted);
        }

        @Override
        public int getMaxProtocolVersion() {
            return inner.getMaxProtocolVersion();
        }

        @Override
        public int getMaxEncryptedFrameSize(MediaType type, int frameSize) {
            return inner.getMaxEncryptedFrameSize(type, frameSize);
        }

        @Override
        public int getMaxDecryptedFrameSize(MediaType type, long userId, int frameSize) {
            return inner.getMaxDecryptedFrameSize(type, userId, frameSize);
        }

        @Override
        public void assignSsrcToCodec(Codec codec, int ssrc) {
            inner.assignSsrcToCodec(codec, ssrc);
        }

        @Override
        public void onDaveProtocolMLSExternalSenderPackage(ByteBuffer externalSenderPackage) {
            inner.onDaveProtocolMLSExternalSenderPackage(externalSenderPackage);
        }

        @Override
        public void onDaveProtocolExecuteTransition(int transitionId) {
            inner.onDaveProtocolExecuteTransition(transitionId);
        }

        @Override
        public void initialize() {
            inner.initialize();
        }

        @Override
        public void destroy() {
            inner.destroy();
        }
    }
}
