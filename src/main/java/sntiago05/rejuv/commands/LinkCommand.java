package sntiago05.rejuv.commands;

import java.util.UUID;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.services.AccountIdParser;
import sntiago05.rejuv.services.InvalidAccountInputException;
import sntiago05.rejuv.services.LinkService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.NoMatchHistoryException;

@Component
public class LinkCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(LinkCommand.class);
    private static final String ACCOUNT_OPTION = "account";
    private static final int ACCOUNT_MAX_LENGTH = 200;

    private final LinkService linkService;
    private final Messages messages;

    public LinkCommand(LinkService linkService, Messages messages) {
        this.linkService = linkService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "link";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("link.description"))
                .addOptions(new OptionData(OptionType.STRING, ACCOUNT_OPTION, messages.get("link.option.account"), true)
                        .setMaxLength(ACCOUNT_MAX_LENGTH));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption(ACCOUNT_OPTION);
        long accountId;
        try {
            accountId = AccountIdParser.parse(option == null ? null : option.getAsString());
        } catch (InvalidAccountInputException ex) {
            String key = ex.reason() == InvalidAccountInputException.Reason.VANITY_URL
                    ? "link.vanity_unsupported"
                    : "link.invalid_input";
            event.reply(messages.get(key)).setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        event.getHook().editOriginal(linkAndDescribe(event.getUser().getIdLong(), accountId)).queue();
    }

    // The reply is already deferred here, so CommandRouter cannot answer an exception: map every failure to a message.
    private String linkAndDescribe(long discordUserId, long accountId) {
        try {
            linkService.link(discordUserId, accountId);
            return messages.get("link.success", String.valueOf(accountId));
        } catch (NoMatchHistoryException ex) {
            return messages.get("link.no_matches");
        } catch (DeadlockApiUnavailableException ex) {
            return messages.get("error.data_source_busy");
        } catch (RuntimeException ex) {
            String errorId = UUID.randomUUID().toString();
            log.error("Link failed errorId={} userId={}", errorId, discordUserId, ex);
            return messages.get("error.generic", errorId);
        }
    }
}
