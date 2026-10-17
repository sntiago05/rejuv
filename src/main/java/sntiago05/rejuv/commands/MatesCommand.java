package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.MatesSummary;
import sntiago05.rejuv.domain.Teammate;
import sntiago05.rejuv.services.MatesService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.ProfileService;

@Component
public class MatesCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(MatesCommand.class);
    private static final String USER_OPTION = "user";

    private final ProfileService profileService;
    private final MatesService matesService;
    private final Messages messages;

    public MatesCommand(ProfileService profileService, MatesService matesService, Messages messages) {
        this.profileService = profileService;
        this.matesService = matesService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "mates";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("mates.description"))
                .addOption(OptionType.USER, USER_OPTION, messages.get("mates.option.user"), false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        User caller = event.getUser();
        OptionMapping option = event.getOption(USER_OPTION);
        User target = option == null ? caller : option.getAsUser();

        OptionalLong accountId = profileService.findLinkedAccountId(target.getIdLong());
        if (accountId.isEmpty()) {
            String text = target.getIdLong() == caller.getIdLong()
                    ? messages.get("mates.not_linked_self")
                    : messages.get("mates.not_linked_other", target.getAsMention());
            event.reply(text).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), caller.getIdLong(), target, accountId.getAsLong());
    }

    private void answer(InteractionHook hook, long callerId, User target, long accountId) {
        try {
            MatesSummary summary = matesService.getMates(accountId);
            if (summary.teammates().isEmpty()) {
                hook.editOriginal(messages.get("mates.no_mates", MatesService.MIN_MATCHES_TOGETHER)).queue();
                return;
            }
            hook.editOriginalEmbeds(embed(summary, target)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Mates", callerId, "accountId=" + accountId, ex);
        }
    }

    private MessageEmbed embed(MatesSummary summary, User target) {
        List<String> entryLines = new ArrayList<>();
        for (int i = 0; i < summary.teammates().size(); i++) {
            Teammate teammate = summary.teammates().get(i);
            int position = i + 1;
            String label = messages.get("mates.teammate_label", String.valueOf(teammate.accountId()));
            long winRateWhole = Math.round(teammate.winRatePercent());
            entryLines.add(messages.get("mates.entry", String.valueOf(position), label,
                    String.valueOf(teammate.matches()), String.valueOf(winRateWhole)));
        }

        String lines = EmbedStyle.fitField(entryLines);

        return new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("mates.title", target.getEffectiveName()))
                .addField(messages.get("mates.field.top"), lines, false)
                .setFooter(messages.get("mates.footer"))
                .build();
    }
}
