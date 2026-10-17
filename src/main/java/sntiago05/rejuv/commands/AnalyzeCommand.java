package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.AnalysisInput;
import sntiago05.rejuv.domain.AnalysisReport;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.services.AnalyzeService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.NoMatchHistoryException;
import sntiago05.rejuv.services.ProfileService;

@Component
public class AnalyzeCommand implements SlashCommand {

    static final int MAX_FIELD_LENGTH = 1024;
    static final String TRUNCATION_SUFFIX = "...";

    private static final Logger log = LoggerFactory.getLogger(AnalyzeCommand.class);

    private final AnalyzeService analyzeService;
    private final ProfileService profileService;
    private final Messages messages;

    public AnalyzeCommand(AnalyzeService analyzeService, ProfileService profileService, Messages messages) {
        this.analyzeService = analyzeService;
        this.profileService = profileService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "analyze";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("analyze.description"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        User caller = event.getUser();
        OptionalLong accountId = profileService.findLinkedAccountId(caller.getIdLong());
        if (accountId.isEmpty()) {
            event.reply(messages.get("analyze.not_linked")).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), caller.getIdLong(), accountId.getAsLong());
    }

    private void answer(InteractionHook hook, long callerId, long accountId) {
        try {
            AnalysisReport report = analyzeService.analyze(accountId);
            hook.editOriginalEmbeds(buildEmbed(report)).queue();
        } catch (NoMatchHistoryException ex) {
            hook.editOriginal(messages.get("analyze.no_matches")).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Analyze", callerId, "accountId=" + accountId, ex);
        }
    }

    private MessageEmbed buildEmbed(AnalysisReport report) {
        EmbedBuilder builder = new EmbedBuilder();

        String result = "win".equalsIgnoreCase(report.result())
                ? messages.get("analyze.result.win")
                : messages.get("analyze.result.loss");
        builder.setTitle(messages.get("analyze.title", report.hero(), result, CommandSupport.oneDecimal(report.durationMin())));

        if (report.degraded() && report.analysis() == null) {
            builder.setDescription(messages.get("analyze.degraded_note") + "\n" + messages.get("analyze.fallback_note"));
        } else if (report.degraded()) {
            builder.setDescription(messages.get("analyze.degraded_note"));
        } else if (report.analysis() == null) {
            builder.setDescription(messages.get("analyze.fallback_note"));
        }

        if (report.analysis() != null) {
            AnalysisResult analysis = report.analysis();
            builder.addField(messages.get("analyze.field.strength"), truncate(analysis.strength()), false);
            builder.addField(messages.get("analyze.field.leak"), truncate(analysis.biggestLeak()), false);
            builder.addField(messages.get("analyze.field.next_action"), truncate(analysis.nextGameAction()), false);
            if (analysis.keyNumbers() != null && !analysis.keyNumbers().isEmpty()) {
                builder.addField(messages.get("analyze.field.key_numbers"), truncate(String.join("\n", analysis.keyNumbers())), false);
            }
        } else {
            buildFallbackKeyNumbers(builder, report.metrics());
        }

        if (report.rank() != null) {
            builder.setFooter(messages.get("analyze.footer", report.hero(), report.rank()));
        } else {
            builder.setFooter(messages.get("analyze.footer_no_rank"));
        }

        return builder.build();
    }

    private void buildFallbackKeyNumbers(EmbedBuilder builder, Map<String, AnalysisInput.MetricComparison> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        List<Map.Entry<String, AnalysisInput.MetricComparison>> selected = metrics.entrySet().stream()
                .filter(e -> e.getValue().p50() != null)
                .limit(4)
                .toList();
        if (selected.isEmpty()) {
            selected = metrics.entrySet().stream()
                    .limit(4)
                    .toList();
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, AnalysisInput.MetricComparison> entry : selected) {
            String key = entry.getKey();
            AnalysisInput.MetricComparison comp = entry.getValue();
            String label = metricLabel(key);
            if (comp.p50() != null) {
                lines.add(messages.get("analyze.fallback_number", label,
                        formatNumber(comp.value()), formatNumber(comp.p50())));
            } else {
                lines.add(messages.get("analyze.fallback_number_no_median", label,
                        formatNumber(comp.value())));
            }
        }

        if (!lines.isEmpty()) {
            builder.addField(messages.get("analyze.field.key_numbers"), truncate(String.join("\n", lines)), false);
        }
    }

    private String metricLabel(String key) {
        try {
            return messages.get("analyze.metric." + key);
        } catch (NoSuchMessageException ex) {
            return key;
        }
    }

    static String formatNumber(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        if (formatted.endsWith(".0")) {
            return formatted.substring(0, formatted.length() - 2);
        }
        return formatted;
    }

    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_FIELD_LENGTH) {
            return text;
        }
        int maxContentLength = MAX_FIELD_LENGTH - TRUNCATION_SUFFIX.length();
        int lastSpace = text.lastIndexOf(' ', maxContentLength);
        if (lastSpace > 0) {
            return text.substring(0, lastSpace) + TRUNCATION_SUFFIX;
        }
        return text.substring(0, maxContentLength) + TRUNCATION_SUFFIX;
    }
}
