package sntiago05.rejuv.commands;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.domain.AnalysisInput;
import sntiago05.rejuv.domain.AnalysisReport;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchSummary;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.services.AnalyzeService;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.ChartRenderer;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.config.WebProperties;
import sntiago05.rejuv.services.MatchNotFoundException;
import sntiago05.rejuv.services.MatchSearchService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.NoMatchHistoryException;
import sntiago05.rejuv.services.ProfileService;
import sntiago05.rejuv.services.UsageLimitExceededException;

@Component
@EnableConfigurationProperties(WebProperties.class)
public class AnalyzeCommand implements SlashCommand {

    static final int MAX_FIELD_LENGTH = 1024;
    static final String CHART_FILE = "match.png";
    static final String TRUNCATION_SUFFIX = "...";

    private static final Logger log = LoggerFactory.getLogger(AnalyzeCommand.class);

    private final AnalyzeService analyzeService;
    private final ProfileService profileService;
    private final Messages messages;
    private final AssetImages assetImages;
    private final MatchSearchService matchSearchService;
    private final GameAssets gameAssets;
    private final HeroResolver heroResolver;
    private final IconCatalog iconCatalog;
    private final ChartRenderer chartRenderer;
    private final WebProperties webProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public AnalyzeCommand(AnalyzeService analyzeService, ProfileService profileService, Messages messages,
                          AssetImages assetImages, MatchSearchService matchSearchService, GameAssets gameAssets,
                          HeroResolver heroResolver, IconCatalog iconCatalog, ChartRenderer chartRenderer,
                          WebProperties webProperties) {
        this.analyzeService = analyzeService;
        this.profileService = profileService;
        this.messages = messages;
        this.assetImages = assetImages;
        this.matchSearchService = matchSearchService;
        this.gameAssets = gameAssets;
        this.heroResolver = heroResolver;
        this.iconCatalog = iconCatalog;
        this.chartRenderer = chartRenderer;
        this.webProperties = webProperties != null ? webProperties : new WebProperties(null);
    }

    public AnalyzeCommand(AnalyzeService analyzeService, ProfileService profileService, Messages messages,
                          AssetImages assetImages, MatchSearchService matchSearchService, GameAssets gameAssets,
                          HeroResolver heroResolver, IconCatalog iconCatalog, ChartRenderer chartRenderer) {
        this(analyzeService, profileService, messages, assetImages, matchSearchService, gameAssets,
                heroResolver, iconCatalog, chartRenderer, new WebProperties(null));
    }

    @Override
    public String name() {
        return "analyze";
    }

    @Override
    public SlashCommandData data() {
        OptionData matchOption = new OptionData(OptionType.STRING, "match", messages.get("analyze.option.match"), false)
                .setAutoComplete(true);
        OptionData heroOption = new OptionData(OptionType.STRING, "hero", messages.get("analyze.option.hero"), false)
                .setAutoComplete(true);
        OptionData resultOption = new OptionData(OptionType.STRING, "result", messages.get("analyze.option.result"), false)
                .addChoice(messages.get("analyze.result.choice.win"), "win")
                .addChoice(messages.get("analyze.result.choice.loss"), "loss");

        return Commands.slash(name(), messages.get("analyze.description"))
                .addOptions(matchOption, heroOption, resultOption);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        MatchFilter filter;
        OptionMapping matchOpt = event.getOption("match");
        if (matchOpt != null) {
            OptionalLong matchId = parseMatchId(matchOpt.getAsString());
            if (matchId.isEmpty()) {
                event.reply(messages.get("analyze.match_invalid")).setEphemeral(true).queue();
                return;
            }
            filter = new MatchFilter(matchId.getAsLong(), null, null);
        } else {
            Integer heroId = null;
            OptionMapping heroOpt = event.getOption("hero");
            if (heroOpt != null && !heroOpt.getAsString().isBlank()) {
                Optional<HeroAsset> hero = heroResolver.resolve(heroOpt.getAsString());
                if (hero.isEmpty()) {
                    event.reply(messages.get("analyze.hero_not_found")).setEphemeral(true).queue();
                    return;
                }
                heroId = hero.get().id();
            }

            Boolean win = CommandSupport.winFilter(event.getOption("result"));
            filter = (heroId == null && win == null) ? MatchFilter.LATEST : new MatchFilter(null, heroId, win);
        }

        User caller = event.getUser();
        OptionalLong accountId = profileService.findLinkedAccountId(caller.getIdLong());
        if (accountId.isEmpty()) {
            event.reply(messages.get("analyze.not_linked")).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), caller.getIdLong(), accountId.getAsLong(), filter);
    }

    /** Accepts only 1 to 18 ASCII digits (after trim) with a value above zero. */
    private static OptionalLong parseMatchId(String raw) {
        String value = raw.trim();
        int length = value.length();
        if (length == 0 || length > 18) {
            return OptionalLong.empty();
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return OptionalLong.empty();
            }
        }
        long id = Long.parseLong(value);
        return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        try {
            String focused = event.getFocusedOption().getName();
            if ("hero".equals(focused)) {
                autocompleteHero(event);
            } else if ("match".equals(focused)) {
                autocompleteMatch(event);
            } else {
                event.replyChoices(List.of()).queue();
            }
        } catch (RuntimeException ex) {
            event.replyChoices(List.of()).queue();
        }
    }

    private void autocompleteHero(CommandAutoCompleteInteractionEvent event) {
        event.replyChoices(CommandSupport.heroChoices(gameAssets.heroNames().values(),
                event.getFocusedOption().getValue())).queue();
    }

    private void autocompleteMatch(CommandAutoCompleteInteractionEvent event) {
        long userId = event.getUser().getIdLong();
        OptionalLong accountIdOpt = profileService.findLinkedAccountId(userId);
        if (accountIdOpt.isEmpty()) {
            event.replyChoices(List.of()).queue();
            return;
        }
        long accountId = accountIdOpt.getAsLong();

        Integer heroId = null;
        OptionMapping heroOpt = event.getOption("hero");
        if (heroOpt != null && !heroOpt.getAsString().isBlank()) {
            Optional<HeroAsset> hero = heroResolver.resolve(heroOpt.getAsString());
            if (hero.isPresent()) {
                heroId = hero.get().id();
            } else {
                event.replyChoices(List.of()).queue();
                return;
            }
        }

        MatchFilter filter = new MatchFilter(null, heroId, CommandSupport.winFilter(event.getOption("result")));
        List<MatchSummary> matches = matchSearchService.find(accountId, filter, 10);
        if (matches.isEmpty()) {
            event.replyChoices(List.of()).queue();
            return;
        }

        List<Command.Choice> choices = new ArrayList<>(matches.size());
        for (MatchSummary m : matches) {
            String heroName = m.heroName() != null ? m.heroName() : "#" + m.heroId();
            String resultText = m.win() ? "Win" : "Loss";
            String date = Instant.ofEpochSecond(m.startTimeEpochS())
                    .atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            String kda = m.kills() + "/" + m.deaths() + "/" + m.assists();

            String choiceName = formatMatchChoiceName(heroName, resultText, date, kda);
            choices.add(new Command.Choice(choiceName, String.valueOf(m.matchId())));
        }
        event.replyChoices(choices).queue();
    }

    private String formatMatchChoiceName(String heroName, String resultText, String date, String kda) {
        String text = messages.get("analyze.autocomplete.match", heroName, resultText, date, kda);
        if (text.length() <= Command.Choice.MAX_NAME_LENGTH) {
            return text;
        }
        int overflow = text.length() - Command.Choice.MAX_NAME_LENGTH;
        if (heroName.length() > overflow) {
            String truncatedHero = heroName.substring(0, heroName.length() - overflow);
            text = messages.get("analyze.autocomplete.match", truncatedHero, resultText, date, kda);
        }
        if (text.length() > Command.Choice.MAX_NAME_LENGTH) {
            return text.substring(0, Command.Choice.MAX_NAME_LENGTH);
        }
        return text;
    }

    private void answer(InteractionHook hook, long callerId, long accountId, MatchFilter filter) {
        try {
            AnalysisReport report = analyzeService.analyze(callerId, accountId, filter);
            Optional<byte[]> chart = report.visuals() != null && report.visuals().chart() != null
                    ? chartRenderer.render(report.visuals().chart())
                    : Optional.empty();
            MessageEmbed embed = buildEmbed(report, chart.isPresent());
            WebhookMessageEditAction<Message> action = hook.editOriginalEmbeds(embed);
            if (chart.isPresent()) {
                action = action.setFiles(List.of(FileUpload.fromData(chart.get(), CHART_FILE)));
            }
            if (webProperties != null && webProperties.enabled() && report.publicId() != null) {
                String base = webProperties.baseUrl().trim();
                while (base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                String url = base + "/r/" + report.publicId();
                action = action.setActionRow(Button.link(url, messages.get("analyze.report_button")));
            }
            action.queue();
        } catch (UsageLimitExceededException ex) {
            String resetTimestamp = "<t:" + ex.resetsAt().getEpochSecond() + ":R>";
            hook.editOriginal(messages.get("analyze.limit_reached", ex.limit(), resetTimestamp)).queue();
        } catch (NoMatchHistoryException ex) {
            hook.editOriginal(messages.get("analyze.no_matches")).queue();
        } catch (MatchNotFoundException ex) {
            hook.editOriginal(messages.get("analyze.match_not_found")).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Analyze", callerId, "accountId=" + accountId, ex);
        }
    }

    private MessageEmbed buildEmbed(AnalysisReport report, boolean withChart) {
        EmbedBuilder builder = new EmbedBuilder().setColor(EmbedStyle.COLOR);
        assetImages.heroCardUrl(report.heroId()).ifPresent(builder::setThumbnail);

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
            String leak = analysis.biggestLeak();
            if (analysis.severity() != null) {
                leak = messages.get("analyze.severity." + analysis.severity()) + " " + (leak != null ? leak : "");
            }
            builder.addField(messages.get("analyze.field.leak"), truncate(leak), false);
            builder.addField(messages.get("analyze.field.next_action"), truncate(analysis.nextGameAction()), false);
            if (analysis.keyNumbers() != null && !analysis.keyNumbers().isEmpty()) {
                builder.addField(messages.get("analyze.field.key_numbers"), truncate(String.join("\n", analysis.keyNumbers())), false);
            }
        } else {
            buildFallbackKeyNumbers(builder, report.metrics());
        }

        addBuildField(builder, report.visuals());
        if (withChart) {
            builder.setImage("attachment://" + CHART_FILE);
        }

        if (report.rank() != null) {
            builder.setFooter(messages.get("analyze.footer", report.hero(), report.rank()));
        } else {
            builder.setFooter(messages.get("analyze.footer_no_rank"));
        }

        return builder.build();
    }

    private void addBuildField(EmbedBuilder builder, MatchVisuals visuals) {
        if (visuals == null || visuals.items().isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>(visuals.items().size());
        for (MatchVisuals.PurchasedItem item : visuals.items()) {
            String icon = iconCatalog.item(item.itemId()).map(i -> i + " ").orElse("");
            lines.add(messages.get("analyze.build.item", icon + item.name(),
                    CommandSupport.minutesSeconds(item.boughtAtS())));
        }
        EmbedStyle.addFittedField(builder, messages.get("analyze.field.build"), lines);
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
