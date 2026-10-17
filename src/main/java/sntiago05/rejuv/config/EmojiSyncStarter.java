package sntiago05.rejuv.config;

import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.EmojiSyncService;

/**
 * Runs emoji synchronization once in the background after JDA is ready.
 * Does not delay application startup and is skipped quietly if JDA is absent.
 */
@Component
public class EmojiSyncStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmojiSyncStarter.class);

    private final ObjectProvider<JDA> jdaProvider;
    private final EmojiSyncService emojiSyncService;

    public EmojiSyncStarter(ObjectProvider<JDA> jdaProvider, EmojiSyncService emojiSyncService) {
        this.jdaProvider = jdaProvider;
        this.emojiSyncService = emojiSyncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            log.debug("JDA bean is absent; skipping emoji sync");
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                jda.awaitReady();
                emojiSyncService.sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Emoji sync interrupted while awaiting JDA ready");
            } catch (Exception e) {
                log.warn("Unexpected error during emoji sync: {}", e.getMessage());
            }
        }, "emoji-sync");
        thread.setDaemon(true);
        thread.start();
    }
}
