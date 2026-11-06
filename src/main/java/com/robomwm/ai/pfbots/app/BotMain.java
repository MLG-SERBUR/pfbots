package com.robomwm.ai.pfbots.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;

import com.robomwm.ai.pfbots.bot.BotProfile;
import com.robomwm.ai.pfbots.bot.BotRegistry;
import com.robomwm.ai.pfbots.bot.BotSlashCommands;
import com.robomwm.ai.pfbots.bot.DiscordTokens;
import com.robomwm.ai.pfbots.bot.DumcordTokens;
import com.robomwm.ai.pfbots.markov.ArliAiCoordinator;
import com.robomwm.ai.pfbots.markov.ArliAiReactionResponder;
import com.robomwm.ai.pfbots.markov.ArliAiSecondChanceResponder;
import com.robomwm.ai.pfbots.markov.GenerativeAiConfig;
import com.robomwm.ai.pfbots.markov.GenerativeAiResponder;
import com.robomwm.ai.pfbots.markov.MarkovConfig;
import com.robomwm.ai.pfbots.markov.MarkovListener;
import com.robomwm.ai.pfbots.markov.MarkovManager;
import com.robomwm.ai.pfbots.markov.PaseoDaemonResponder;
import com.robomwm.ai.pfbots.markov.PaseoReactionResponder;
import com.robomwm.ai.pfbots.markov.RoundRobinGenerativeAiResponder;
import com.robomwm.ai.pfbots.masteroebot.MasterOEBotCommands;
import com.robomwm.ai.pfbots.masteroebot.connect4.Connect4CommandListener;
import com.robomwm.ai.pfbots.masteroebot.feedback.FeedbackCommandListener;
import com.robomwm.ai.pfbots.paraokabot.ParaokaBotCommands;
import com.robomwm.ai.pfbots.paraokabot.ParaokaCommandListener;
import com.robomwm.ai.pfbots.masteroebot.poll.PollCommandListener;
import com.robomwm.ai.pfbots.masteroebot.typeracer.TypeRacerCommandListener;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class BotMain {
    /** Paseo provider/model for main replies. Pulse Alpha Free, limited-time free in OpenCode. */
    private static final String PASEO_PROVIDER = "opencode/pulse-alpha-free";

    public static void main(String[] args) {
        try {
            runBot(args);
        } catch (Throwable t) {
            // An unhandled startup failure (e.g. DNS not yet available at boot)
            // kills only the main thread. JDA/OkHttp leave non-daemon worker
            // threads behind, so the JVM would hang forever as a zombie process
            // and systemd would never see an exit, meaning Restart=always would
            // never fire. Exit explicitly so the service manager restarts us.
            t.printStackTrace();
            System.err.println("pfbots failed to start. Exiting so the service manager can restart us.");
            System.exit(1);
        }
    }

    private static void runBot(String[] args) throws LoginException, InterruptedException, IOException {
        if (args.length > 0 && args[0].equals("--scrub")) {
            com.robomwm.ai.pfbots.markov.BrainScrubber.main(args);
            return;
        }
        if (args.length > 0 && args[0].equals("--scrub-ai")) {
            com.robomwm.ai.pfbots.markov.AiLogScrubber.main(args);
            return;
        }
        if (args.length > 0 && args[0].equals("--scrubboth")) {
            com.robomwm.ai.pfbots.markov.BothScrubber.main(args);
            return;
        }
        if (args.length > 0 && args[0].equals("--scrubuserurls")) {
            com.robomwm.ai.pfbots.markov.UserUrlScrubber.main(args);
            return;
        }
        Path configPath = Path.of("config.yaml");
        DumcordTokens dumcord = DumcordTokens.load(Path.of("dumcord.yml"));

        List<BotProfile> profiles = BotRegistry.PROFILES;
        List<String> tokens = new ArrayList<>();
        GenerativeAiConfig aiConfig;
        if (!dumcord.isEmpty()) {
            aiConfig = BotConfig.loadGenerativeAiConfig(configPath);
            for (BotProfile profile : profiles) {
                tokens.add(dumcord.tokenFor(profile.key()));
            }
        } else {
            // Legacy fallback for pre-dumcord deploys: config.yaml primary
            // token + discord.yaml extras.
            System.err.println("dumcord.yml not found or empty, falling back to config.yaml/discord.yaml.");
            BotConfig config = BotConfig.load(configPath);
            aiConfig = config.generativeAiConfig();
            tokens.add(config.token());
            tokens.addAll(DiscordTokens.loadExtraTokens(Path.of("discord.yaml")));
            while (tokens.size() < profiles.size()) {
                tokens.add(null);
            }
        }

        MarkovConfig markovConfig = new MarkovConfig();
        markovConfig.load();
        MarkovManager markovManager = new MarkovManager(markovConfig);

        // Shared backend: one coordinator serializes ArliAI use across bots.
        ArliAiCoordinator coordinator = new ArliAiCoordinator();

        // Retry startup so a transient outage (e.g. DNS not reachable yet right
        // after boot) doesn't take the bot down for good. Network errors surface
        // here as runtime exceptions (JDA wraps UnknownHostException in
        // ErrorResponseException). InterruptedException is never retried.
        List<BootResult> online = new ArrayList<>();
        final int maxAttempts = 5;
        for (int i = 0; i < profiles.size(); i++) {
            BotProfile profile = profiles.get(i);
            String token = tokens.get(i);
            if (token == null || token.isBlank()) {
                System.out.println("[" + profile.displayName() + "] No token configured, disabled.");
                continue;
            }

            // Main replies only, toggled in code, not by env var (owner
            // instruction). Paseo is the live path; the RoundRobin line below
            // is the toggle back, and RoundRobinGenerativeAiResponder stays in
            // the repo untouched. Model is still TBD: set PASEO_PROVIDER to
            // "opencode/<model>" when the owner picks one.
            // Toggle back: replace the PaseoDaemonResponder line with:
            //   generativeAiResponder = new RoundRobinGenerativeAiResponder(aiConfig, profile.systemPrompt());
            // Paseo calls use the profile's paseoSystemPrompt, not the
            // profile system prompt and not a bridge concatenation: the
            // bridge sends it exactly as Java gives it.
            GenerativeAiResponder generativeAiResponder = new PaseoDaemonResponder(
                    List.of("node", "paseo-bridge/paseo-bridge.mjs"),
                    PASEO_PROVIDER, profile.paseoSystemPrompt());
            System.out.println("[" + profile.displayName() + "] Main replies via Paseo daemon: " + PASEO_PROVIDER);
            // Vision models only, for on-demand NEED_VISION calls in MarkovListener.
            RoundRobinGenerativeAiResponder visionResponder =
                    RoundRobinGenerativeAiResponder.visionResponder(aiConfig);
            // Reactions: Paseo primary (same r/s candidate data, mirror-only
            // plus own-retraction), ArliAI as fallback inside the wrapper.
            ArliAiReactionResponder arliReactionResponder =
                    new ArliAiReactionResponder(aiConfig, coordinator, profile.reactionPrompt());
            GenerativeAiResponder reactionResponder = new PaseoReactionResponder(
                    List.of("node", "paseo-bridge/paseo-bridge.mjs"),
                    PASEO_PROVIDER, arliReactionResponder, profile.reactionPrompt());
            System.out.println("[" + profile.displayName() + "] Reactions via Paseo daemon, ArliAI fallback: " + PASEO_PROVIDER);
            ArliAiSecondChanceResponder secondChanceResponder =
                    new ArliAiSecondChanceResponder(aiConfig, coordinator, profile.secondChancePrompt());
            System.out.println("[" + profile.displayName() + "] Loaded system prompt (" + profile.systemPrompt().length() + " chars).");

            BootResult boot = null;
            boolean markovAvailable = false;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    boot = startBot(profile, token, true, markovManager, markovConfig, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator, visionResponder);
                    break;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                } catch (LoginException | RuntimeException e) {
                    if (attempt >= maxAttempts) {
                        System.err.println("[" + profile.displayName() + "] Startup failed after " + maxAttempts + " attempts, skipping: " + e);
                        break;
                    }
                    System.err.println("[" + profile.displayName() + "] Startup attempt " + attempt + "/" + maxAttempts + " failed: " + e);
                    System.err.println("Retrying in 15 seconds...");
                    Thread.sleep(15_000);
                }
            }
            if (boot == null) {
                // DISALLOWED_INTENTS: retrying with MESSAGE_CONTENT enabled is
                // pointless, so this second attempt is not retried on failure.
                // A failed bot is skipped so the others still come online.
                try {
                    boot = startBot(profile, token, false, markovManager, markovConfig, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator, visionResponder);
                } catch (LoginException | RuntimeException e) {
                    System.err.println("[" + profile.displayName() + "] Startup without MESSAGE_CONTENT failed, skipping: " + e);
                    continue;
                }
                markovAvailable = false;
            } else {
                markovAvailable = boot.markovListener() != null;
            }

            if (boot.listener() != null) {
                ((BotSlashCommands) boot.listener()).setMarkovAvailable(markovAvailable);
            }
            if (isParaoka(profile)) {
                ParaokaBotCommands.registerForBot(profile.displayName(), boot.jda(), (ParaokaCommandListener) boot.listener());
            } else {
                MasterOEBotCommands.registerForBot(profile, boot.jda(), (Connect4CommandListener) boot.listener(), boot.typeracerListener(), boot.feedbackListener(), boot.pollListener());
            }
            online.add(boot);
            System.out.println("[" + profile.displayName() + "] is online. Markov available: " + markovAvailable);
        }

        if (online.isEmpty()) {
            throw new IllegalStateException("No bots came online.");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (BootResult boot : online) {
                if (boot.markovListener() != null) {
                    boot.markovListener().shutdown();
                }
            }
        }));
    }

    private static boolean isParaoka(BotProfile profile) {
        return BotRegistry.PARAOKA.key().equals(profile.key());
    }

    private static BootResult startBot(BotProfile profile, String token, boolean enableMessageContent,
                                        MarkovManager markovManager, MarkovConfig markovConfig,
                                        GenerativeAiResponder generativeAiResponder,
                                        GenerativeAiResponder reactionResponder,
                                        ArliAiSecondChanceResponder secondChanceResponder,
                                        ArliAiCoordinator coordinator,
                                        RoundRobinGenerativeAiResponder visionResponder)
            throws LoginException, InterruptedException {
        boolean isParaoka = isParaoka(profile);
        ListenerAdapter listener;
        TypeRacerCommandListener typeracerListener = null;
        FeedbackCommandListener feedbackListener = null;
        PollCommandListener pollListener = null;
        if (isParaoka) {
            listener = new ParaokaCommandListener(markovManager, markovConfig);
        } else {
            listener = new Connect4CommandListener(markovManager, markovConfig);
        }
        if (profile.registersCommands() && !isParaoka) {
            typeracerListener = new TypeRacerCommandListener();
            feedbackListener = new FeedbackCommandListener(markovManager);
            pollListener = new PollCommandListener(markovManager, generativeAiResponder);
        }
        MarkovListener markovListener = null;

        if (enableMessageContent) {
            markovListener = new MarkovListener(markovManager, markovConfig, null, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator,
                    profile.botTag(), profile.reactionPrompt(), profile.key(), visionResponder);
        }

        StartupProbe probe = new StartupProbe();
        JDABuilder builder = JDABuilder.createDefault(token)
                .addEventListeners(probe);
        builder.addEventListeners(listener);
        if (typeracerListener != null) {
            builder.addEventListeners(typeracerListener);
        }
        if (feedbackListener != null) {
            builder.addEventListeners(feedbackListener);
        }
        if (pollListener != null) {
            builder.addEventListeners(pollListener);
        }

        if (markovListener != null) {
            builder.addEventListeners(markovListener);
        }

        if (enableMessageContent) {
            builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
        }

        JDA jda = builder.build();
        StartupOutcome outcome = probe.await(30, TimeUnit.SECONDS);

        if (outcome == StartupOutcome.DISALLOWED_INTENTS && enableMessageContent) {
            jda.shutdownNow();
            System.err.println("[" + profile.displayName() + "] MESSAGE_CONTENT denied by Discord. Markov feature disabled, other features remain active.");
            return null;
        }

        if (outcome == StartupOutcome.SHUTDOWN) {
            throw new IllegalStateException("JDA shutdown during startup: " + probe.closeCode());
        }
        if (outcome == StartupOutcome.TIMEOUT) {
            throw new IllegalStateException("Timed out waiting for Discord startup.");
        }

        if (markovListener != null) {
            markovListener.setJDA(jda);
        }

        return new BootResult(profile, jda, listener, typeracerListener, feedbackListener, pollListener, markovListener);
    }

    private record BootResult(BotProfile profile, JDA jda, ListenerAdapter listener, TypeRacerCommandListener typeracerListener, FeedbackCommandListener feedbackListener, PollCommandListener pollListener, MarkovListener markovListener) {
    }

    private enum StartupOutcome {
        READY,
        DISALLOWED_INTENTS,
        SHUTDOWN,
        TIMEOUT
    }

    private static final class StartupProbe extends ListenerAdapter {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile StartupOutcome outcome;
        private volatile CloseCode closeCode;

        @Override
        public void onReady(ReadyEvent event) {
            outcome = StartupOutcome.READY;
            done.countDown();
        }

        @Override
        public void onShutdown(ShutdownEvent event) {
            closeCode = event.getCloseCode();
            outcome = closeCode == CloseCode.DISALLOWED_INTENTS ? StartupOutcome.DISALLOWED_INTENTS : StartupOutcome.SHUTDOWN;
            done.countDown();
        }

        StartupOutcome await(long timeout, TimeUnit unit) throws InterruptedException {
            if (!done.await(timeout, unit)) {
                outcome = StartupOutcome.TIMEOUT;
            }
            return outcome;
        }

        CloseCode closeCode() {
            return closeCode;
        }
    }
}
