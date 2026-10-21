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
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.NemesisService;
import sntiago05.rejuv.services.NemesisService.NemesisLine;
import sntiago05.rejuv.services.NemesisService.NemesisSummary;
import sntiago05.rejuv.services.ProfileService;

@Component
public class NemesisCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(NemesisCommand.class);
    private static final String USER_OPTION = "user";
    private static final int MAX_NAME_CODE_POINTS = 32;

    private final ProfileService profileService;
    private final NemesisService nemesisService;
    private final Messages messages;

    public NemesisCommand(ProfileService profileService, NemesisService nemesisService, Messages messages) {
        this.profileService = profileService;
        this.nemesisService = nemesisService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "nemesis";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("nemesis.description"))
                .addOption(OptionType.USER, USER_OPTION, messages.get("nemesis.option.user"), false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        User caller = event.getUser();
        OptionMapping option = event.getOption(USER_OPTION);
        User target = option == null ? caller : option.getAsUser();

        OptionalLong accountId = profileService.findLinkedAccountId(target.getIdLong());
        if (accountId.isEmpty()) {
            String text = target.getIdLong() == caller.getIdLong()
                    ? messages.get("nemesis.not_linked_self")
                    : messages.get("nemesis.not_linked_other", target.getAsMention());
            event.reply(text).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), caller.getIdLong(), target, accountId.getAsLong());
    }

    private void answer(InteractionHook hook, long callerId, User target, long accountId) {
        try {
            NemesisSummary summary = nemesisService.nemeses(accountId);
            if (summary.nemeses().isEmpty()) {
                hook.editOriginal(messages.get("nemesis.no_nemeses", NemesisService.MIN_NEMESIS_MATCHES)).queue();
                return;
            }
            hook.editOriginalEmbeds(embed(summary, target)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Nemesis", callerId, "accountId=" + accountId, ex);
        }
    }

    private static String sanitizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String oneLine = rawName.replaceAll("[\r\n\t]+", " ").trim();
        if (oneLine.codePointCount(0, oneLine.length()) > MAX_NAME_CODE_POINTS) {
            oneLine = oneLine.substring(0, oneLine.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)).trim();
        }
        return oneLine.isEmpty() ? "" : MarkdownSanitizer.escape(oneLine);
    }

    private MessageEmbed embed(NemesisSummary summary, User target) {
        List<String> entryLines = new ArrayList<>();
        for (int i = 0; i < summary.nemeses().size(); i++) {
            NemesisLine nemesis = summary.nemeses().get(i);
            int position = i + 1;
            String id = String.valueOf(nemesis.accountId());
            String name = sanitizeName(nemesis.name());
            String label = name.isEmpty()
                    ? messages.get("nemesis.enemy_label", id)
                    : messages.get("nemesis.enemy_named", name, id);
            long winRateWhole = Math.round(nemesis.winRate());
            entryLines.add(messages.get("nemesis.entry", String.valueOf(position), label,
                    String.valueOf(nemesis.matches()), String.valueOf(winRateWhole)));
        }

        String lines = EmbedStyle.fitField(entryLines);

        return new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("nemesis.title", target.getEffectiveName()))
                .addField(messages.get("nemesis.field.top"), lines, false)
                .setFooter(messages.get("nemesis.footer"))
                .build();
    }
}
