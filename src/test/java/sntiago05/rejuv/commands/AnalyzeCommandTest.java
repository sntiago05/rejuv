package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import sntiago05.rejuv.config.DonationProperties;
import sntiago05.rejuv.config.WebProperties;
import sntiago05.rejuv.services.DonationService;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.domain.AnalysisInput.MetricComparison;
import sntiago05.rejuv.domain.AnalysisReport;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchSummary;
import sntiago05.rejuv.services.AnalysisLimitExceededException;
import sntiago05.rejuv.services.AnalyzeService;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.ChartRenderer;
import sntiago05.rejuv.domain.MatchVisuals;
import net.dv8tion.jda.api.utils.FileUpload;
import sntiago05.rejuv.services.MatchNotFoundException;
import sntiago05.rejuv.services.MatchSearchService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.NoMatchHistoryException;
import sntiago05.rejuv.services.ProfileService;

class AnalyzeCommandTest {

    private static final long CALLER_ID = 222L;
    private static final long ACCOUNT_ID = 1000001L;

    private final Messages messages = MessagesFixture.english();
    private AnalyzeService analyzeService;
    private ProfileService profileService;
    private AssetImages assetImages;
    private MatchSearchService matchSearchService;
    private GameAssets gameAssets;
    private HeroResolver heroResolver;
    private IconCatalog iconCatalog;
    private ChartRenderer chartRenderer;
    private AnalyzeCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        analyzeService = mock(AnalyzeService.class);
        profileService = mock(ProfileService.class);
        assetImages = mock(AssetImages.class);
        matchSearchService = mock(MatchSearchService.class);
        gameAssets = mock(GameAssets.class);
        heroResolver = mock(HeroResolver.class);
        iconCatalog = new IconCatalog();
        chartRenderer = mock(ChartRenderer.class);
        command = new AnalyzeCommand(analyzeService, profileService, messages, assetImages,
                matchSearchService, gameAssets, heroResolver, iconCatalog, chartRenderer);
        caller = user(CALLER_ID, "Caller");
    }

    @Test
    void nameAndOptionsMatchSpec() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("analyze");
        assertThat(data.getName()).isEqualTo("analyze");
        assertThat(data.getDescription()).isEqualTo(messages.get("analyze.description"));
        assertThat(data.getOptions()).hasSize(3);

        OptionData matchOpt = data.getOptions().get(0);
        assertThat(matchOpt.getName()).isEqualTo("match");
        assertThat(matchOpt.getType()).isEqualTo(OptionType.STRING);
        assertThat(matchOpt.isRequired()).isFalse();
        assertThat(matchOpt.isAutoComplete()).isTrue();

        OptionData heroOpt = data.getOptions().get(1);
        assertThat(heroOpt.getName()).isEqualTo("hero");
        assertThat(heroOpt.getType()).isEqualTo(OptionType.STRING);
        assertThat(heroOpt.isRequired()).isFalse();
        assertThat(heroOpt.isAutoComplete()).isTrue();

        OptionData resultOpt = data.getOptions().get(2);
        assertThat(resultOpt.getName()).isEqualTo("result");
        assertThat(resultOpt.getType()).isEqualTo(OptionType.STRING);
        assertThat(resultOpt.isRequired()).isFalse();
        assertThat(resultOpt.getChoices()).hasSize(2);
        assertThat(resultOpt.getChoices()).extracting(Command.Choice::getAsString).containsExactly("win", "loss");
    }

    @Test
    void callerNotLinkedRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(event).reply(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("analyze.not_linked"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(analyzeService, never()).analyze(anyLong(), anyLong(), any());
    }

    @Test
    void successWithAiRepliesWithPublicEmbed() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult(
                "Great farming", "Too many deaths", "Ward more", List.of("10 kills", "20k souls"));
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);
        when(assetImages.heroCardUrl(1)).thenReturn(Optional.of("https://assets-bucket.deadlock-api.com/hero.png"));

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColor().getRGB() & 0xFFFFFF).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail()).isNotNull();
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets-bucket.deadlock-api.com/hero.png");
        assertThat(embed.getTitle()).isEqualTo("Infernus - Victory (30.0 min)");
        assertThat(embed.getDescription()).isNull();
        assertThat(embed.getFields()).hasSize(4);
        assertThat(embed.getFields().get(0).getName()).isEqualTo(messages.get("analyze.field.leak"));
        assertThat(embed.getFields().get(0).getValue()).isEqualTo("Too many deaths");
        assertThat(embed.getFields().get(1).getName()).isEqualTo(messages.get("analyze.field.next_action"));
        assertThat(embed.getFields().get(1).getValue()).isEqualTo("Ward more");
        assertThat(embed.getFields().get(2).getName()).isEqualTo(messages.get("analyze.field.strength"));
        assertThat(embed.getFields().get(2).getValue()).isEqualTo("Great farming");
        assertThat(embed.getFields().get(3).getName()).isEqualTo(messages.get("analyze.field.key_numbers"));
        assertThat(embed.getFields().get(3).getValue()).isEqualTo("```\n10 kills\n20k souls\n```");
        assertThat(embed.getFooter()).isNotNull();
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("analyze.footer", "Infernus", "Archon 3"));
    }

    private static final MatchVisuals.ChartData CHART = new MatchVisuals.ChartData(
            List.of(0, 180), List.of(0L, 500L), List.of(0L, 800L), List.of(0L, 700L));

    private MatchVisuals visualsWithTwoItems(MatchVisuals.ChartData chart) {
        return new MatchVisuals(List.of(
                new MatchVisuals.PurchasedItem(101L, "Basic Magazine", 65),
                new MatchVisuals.PurchasedItem(102L, "Headshot Booster", 600)), chart);
    }

    private InteractionHook runWithReport(AnalysisReport report) {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);
        when(edit.setFiles(any(java.util.Collection.class))).thenReturn(edit);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);
        command.handle(event);
        return hook;
    }

    private AnalysisReport reportWith(MatchVisuals visuals) {
        AnalysisResult aiResult = new AnalysisResult("Great farming", "Too many deaths", "Ward more", List.of("10 kills"));
        return new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, visuals);
    }

    @Test
    void visualsAddBuildFieldWithEmojiOrNameOnlyAndAttachChart() {
        iconCatalog.replace(Map.of("i_101", "<:i_101:555>"));
        byte[] png = {1, 2, 3};
        when(chartRenderer.render(CHART)).thenReturn(Optional.of(png));

        InteractionHook hook = runWithReport(reportWith(visualsWithTwoItems(CHART)));

        MessageEmbed embed = capturedEmbed(hook);
        MessageEmbed.Field build = embed.getFields().stream()
                .filter(f -> messages.get("analyze.field.build").equals(f.getName())).findFirst().orElseThrow();
        assertThat(build.getValue()).isEqualTo("<:i_101:555> Basic Magazine (1:05)\nHeadshot Booster (10:00)");
        assertThat(embed.getImage()).isNotNull();
        assertThat(embed.getImage().getUrl()).isEqualTo("attachment://match.png");
        ArgumentCaptor<java.util.Collection<FileUpload>> files = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(edit).setFiles(files.capture());
        assertThat(files.getValue()).hasSize(1);
        assertThat(files.getValue().iterator().next().getName()).isEqualTo("match.png");
    }

    @Test
    void rendererReturningEmptyKeepsBuildFieldButNoImageOrFile() {
        when(chartRenderer.render(CHART)).thenReturn(Optional.empty());

        InteractionHook hook = runWithReport(reportWith(visualsWithTwoItems(CHART)));

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getImage()).isNull();
        assertThat(embed.getFields()).hasSize(5);
        verify(edit, never()).setFiles(any(java.util.Collection.class));
    }

    @Test
    void noVisualsKeepsTheCurrentEmbed() {
        InteractionHook hook = runWithReport(reportWith(null));

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields()).hasSize(4);
        assertThat(embed.getImage()).isNull();
        verify(edit, never()).setFiles(any(java.util.Collection.class));
        verifyNoInteractions(chartRenderer);
    }

    @Test
    void newCatalogKeysResolve() {
        for (String key : List.of("analyze.field.build", "analyze.chart.lead_title", "analyze.chart.networth_title",
                "analyze.chart.legend_you", "analyze.chart.legend_team")) {
            assertThat(messages.get(key)).isNotBlank();
        }
        assertThat(messages.get("analyze.build.item", "Name", "1:05")).isEqualTo("Name (1:05)");
    }

    @Test
    void degradedAnalysisShowsDegradedNoteInDescription() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), true);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getDescription()).isEqualTo(messages.get("analyze.degraded_note"));
        assertThat(embed.getFields()).hasSize(3); // Empty key numbers skipped
    }

    @Test
    void fallbackAnalysisShowsFallbackNoteAndKeyNumbersWithAndWithoutMedians() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        Map<String, MetricComparison> metrics = new LinkedHashMap<>();
        metrics.put("kills", new MetricComparison(10.0, 3.0, 5.0));
        metrics.put("deaths", new MetricComparison(2.0, 1.0, 3.0));
        metrics.put("souls_per_min", new MetricComparison(750.5, 600.0, 700.0));
        metrics.put("player_damage_per_min", new MetricComparison(1200.0, null, null));

        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "loss", 25.4, null, metrics, false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo("Infernus - Defeat (25.4 min)");
        assertThat(embed.getDescription()).isEqualTo(messages.get("analyze.fallback_note"));
        assertThat(embed.getFields()).hasSize(1);
        assertThat(embed.getFields().get(0).getName()).isEqualTo(messages.get("analyze.field.key_numbers"));

        String keyNumbersValue = embed.getFields().get(0).getValue();
        assertThat(keyNumbersValue).contains("Kills: 10 (median 5)");
        assertThat(keyNumbersValue).contains("Deaths: 2 (median 3)");
        assertThat(keyNumbersValue).contains("Souls / min: 750.5 (median 700)");
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("analyze.footer", "Infernus", "Archon 3"));
    }

    @Test
    void degradedAndFallbackShowsBothNotesJoinedByNewline() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        Map<String, MetricComparison> metrics = Map.of(
                "kills", new MetricComparison(10.0, null, null));
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, null, metrics, true);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        String expectedDescription = messages.get("analyze.degraded_note") + "\n" + messages.get("analyze.fallback_note");
        assertThat(embed.getDescription()).isEqualTo(expectedDescription);
        assertThat(embed.getFields().get(0).getValue()).isEqualTo("Kills: 10");
    }

    @Test
    void unrankedFooterWhenRankIsNull() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of("1"));
        AnalysisReport report = new AnalysisReport(1, "Infernus", null, "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFooter()).isNotNull();
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("analyze.footer_no_rank"));
    }

    @Test
    void longAiFieldIsCutToAtMost1024Characters() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        String longField = "word ".repeat(600); // 3000 chars
        AnalysisResult aiResult = new AnalysisResult(longField, "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        String strength = embed.getFields().get(2).getValue();
        assertThat(strength.length()).isLessThanOrEqualTo(1024);
        assertThat(strength).endsWith("...");
    }

    @Test
    void noMatchHistoryExceptionEditsOriginalWithNoMatchesMessage() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenThrow(new NoMatchHistoryException(ACCOUNT_ID));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("analyze.no_matches"));
    }

    @Test
    void deadlockApiUnavailableExceptionEditsOriginalWithDataSourceBusy() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedRuntimeExceptionEditsOriginalWithGenericErrorAndDoesNotRethrow() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).contains("Something went wrong. Error id:");
    }

    @Test
    void matchOptionWinsOverHeroAndResult() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        hookFor(event);

        OptionMapping matchOpt = mock(OptionMapping.class);
        when(matchOpt.getAsString()).thenReturn("900000005");
        when(event.getOption("match")).thenReturn(matchOpt);

        OptionMapping heroOpt = mock(OptionMapping.class);
        when(heroOpt.getAsString()).thenReturn("Infernus");
        when(event.getOption("hero")).thenReturn(heroOpt);

        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        ArgumentCaptor<MatchFilter> filterCaptor = ArgumentCaptor.forClass(MatchFilter.class);
        verify(analyzeService).analyze(eq(CALLER_ID), eq(ACCOUNT_ID), filterCaptor.capture());
        MatchFilter usedFilter = filterCaptor.getValue();
        assertThat(usedFilter.matchId()).isEqualTo(900000005L);
        assertThat(usedFilter.heroId()).isNull();
        assertThat(usedFilter.win()).isNull();
    }

    @Test
    void heroAndResultFilterCombinedWhenMatchAbsent() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        hookFor(event);

        OptionMapping heroOpt = mock(OptionMapping.class);
        when(heroOpt.getAsString()).thenReturn("Infernus");
        when(event.getOption("hero")).thenReturn(heroOpt);
        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(new HeroAsset(1, "hero_infernus", "Infernus")));

        OptionMapping resultOpt = mock(OptionMapping.class);
        when(resultOpt.getAsString()).thenReturn("win");
        when(event.getOption("result")).thenReturn(resultOpt);

        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        ArgumentCaptor<MatchFilter> filterCaptor = ArgumentCaptor.forClass(MatchFilter.class);
        verify(analyzeService).analyze(eq(CALLER_ID), eq(ACCOUNT_ID), filterCaptor.capture());
        MatchFilter usedFilter = filterCaptor.getValue();
        assertThat(usedFilter.matchId()).isNull();
        assertThat(usedFilter.heroId()).isEqualTo(1);
        assertThat(usedFilter.win()).isTrue();
    }

    @Test
    void unknownHeroRepliesEphemeralBeforeDeferring() {
        SlashCommandInteractionEvent event = mockEvent();
        OptionMapping heroOpt = mock(OptionMapping.class);
        when(heroOpt.getAsString()).thenReturn("UnknownHero");
        when(event.getOption("hero")).thenReturn(heroOpt);
        when(heroResolver.resolve("UnknownHero")).thenReturn(Optional.empty());

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).reply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("analyze.hero_not_found"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(analyzeService, never()).analyze(anyLong(), anyLong(), any());
    }

    @Test
    void matchNotFoundExceptionEditsOriginalWithMatchNotFound() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenThrow(new MatchNotFoundException("Not found"));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("analyze.match_not_found"));
    }

    @Test
    void autocompleteMatchChoicesFormatsNamesLimitsAndValues() {
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));

        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        when(focused.getName()).thenReturn("match");
        when(event.getFocusedOption()).thenReturn(focused);

        List<MatchSummary> summaries = List.of(
                new MatchSummary(900000001L, 1, "Infernus", 1700000000L, 1800, true, 5, 3, 7),
                new MatchSummary(900000002L, 2, "Seven", 1699900000L, 1200, false, 2, 8, 1)
        );
        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10))).thenReturn(summaries);

        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.replyChoices(anyList())).thenReturn(action);

        command.autocomplete(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Command.Choice>> choicesCaptor = ArgumentCaptor.forClass(List.class);
        verify(event).replyChoices(choicesCaptor.capture());
        verify(action).queue();

        List<Command.Choice> choices = choicesCaptor.getValue();
        assertThat(choices).hasSize(2);
        assertThat(choices.get(0).getName()).isEqualTo("Infernus Win (2023-11-14) - 5/3/7");
        assertThat(choices.get(0).getAsString()).isEqualTo("900000001");
        assertThat(choices.get(0).getName().length()).isLessThanOrEqualTo(100);

        assertThat(choices.get(1).getName()).isEqualTo("Seven Loss (2023-11-13) - 2/8/1");
        assertThat(choices.get(1).getAsString()).isEqualTo("900000002");
        assertThat(choices.get(1).getName().length()).isLessThanOrEqualTo(100);
    }

    @Test
    void autocompleteMatchTruncatesHeroNameWhenTooLong() {
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));

        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        when(focused.getName()).thenReturn("match");
        when(event.getFocusedOption()).thenReturn(focused);

        String veryLongHeroName = "SuperExtraLongHeroNameThatExceedsTheLimitByFarAndNeedsToBeTruncatedToFitUnderOneHundredCharactersWithoutCrashing";
        List<MatchSummary> summaries = List.of(
                new MatchSummary(900000001L, 1, veryLongHeroName, 1700000000L, 1800, true, 5, 3, 7)
        );
        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10))).thenReturn(summaries);

        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.replyChoices(anyList())).thenReturn(action);

        command.autocomplete(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Command.Choice>> choicesCaptor = ArgumentCaptor.forClass(List.class);
        verify(event).replyChoices(choicesCaptor.capture());

        List<Command.Choice> choices = choicesCaptor.getValue();
        assertThat(choices).hasSize(1);
        assertThat(choices.getFirst().getName().length()).isLessThanOrEqualTo(100);
    }

    @Test
    void autocompleteHeroChoicesLimitsTo25() {
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        when(focused.getName()).thenReturn("hero");
        when(focused.getValue()).thenReturn("hero");
        when(event.getFocusedOption()).thenReturn(focused);

        Map<Integer, String> heroMap = new LinkedHashMap<>();
        for (int i = 1; i <= 30; i++) {
            heroMap.put(i, "Hero " + i);
        }
        when(gameAssets.heroNames()).thenReturn(heroMap);

        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.replyChoices(anyList())).thenReturn(action);

        command.autocomplete(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Command.Choice>> choicesCaptor = ArgumentCaptor.forClass(List.class);
        verify(event).replyChoices(choicesCaptor.capture());
        verify(action).queue();

        List<Command.Choice> choices = choicesCaptor.getValue();
        assertThat(choices).hasSize(25);
    }

    @Test
    void autocompleteNotLinkedRepliesEmptyChoices() {
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        when(focused.getName()).thenReturn("match");
        when(event.getFocusedOption()).thenReturn(focused);

        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.replyChoices(List.of())).thenReturn(action);

        command.autocomplete(event);

        verify(event).replyChoices(List.of());
        verify(action).queue();
    }

    @Test
    void autocompleteServiceFailureRepliesEmptyChoices() {
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));

        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        when(focused.getName()).thenReturn("match");
        when(event.getFocusedOption()).thenReturn(focused);

        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10)))
                .thenThrow(new DeadlockApiException("API down"));

        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.replyChoices(List.of())).thenReturn(action);

        command.autocomplete(event);

        verify(event).replyChoices(List.of());
        verify(action).queue();
    }

    @Test
    void autocompleteNeverCallsAi() {
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));

        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        when(focused.getName()).thenReturn("match");
        when(event.getFocusedOption()).thenReturn(focused);

        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10))).thenReturn(List.of());

        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.replyChoices(anyList())).thenReturn(action);

        command.autocomplete(event);

        verify(analyzeService, never()).analyze(anyLong(), anyLong(), any());
    }

    @Test
    void typedNumericMatchIdPassesFilterWithCallerId() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        hookFor(event);
        OptionMapping matchOpt = mock(OptionMapping.class);
        when(matchOpt.getAsString()).thenReturn("118802457");
        when(event.getOption("match")).thenReturn(matchOpt);
        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        ArgumentCaptor<MatchFilter> filterCaptor = ArgumentCaptor.forClass(MatchFilter.class);
        verify(analyzeService).analyze(eq(CALLER_ID), eq(ACCOUNT_ID), filterCaptor.capture());
        assertThat(filterCaptor.getValue()).isEqualTo(new MatchFilter(118802457L, null, null));
    }

    @Test
    void pastedLabelRepliesEphemeralInvalidAndTouchesNothing() {
        SlashCommandInteractionEvent event = mockEvent();
        OptionMapping matchOpt = mock(OptionMapping.class);
        when(matchOpt.getAsString()).thenReturn("17/10/2026 :h_16: Calico - Victory - 13/4/18 - 26:08 - 118802457");
        when(event.getOption("match")).thenReturn(matchOpt);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply(messages.get("analyze.match_invalid"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verifyNoInteractions(profileService, analyzeService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "-5", "0", "12 34", "1234567890123456789", "1e9"})
    void invalidMatchValuesAreRejected(String raw) {
        SlashCommandInteractionEvent event = mockEvent();
        OptionMapping matchOpt = mock(OptionMapping.class);
        when(matchOpt.getAsString()).thenReturn(raw);
        when(event.getOption("match")).thenReturn(matchOpt);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply(messages.get("analyze.match_invalid"));
        verifyNoInteractions(profileService, analyzeService);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "' 118802457 ',118802457", "123456789012345678,123456789012345678"})
    void validMatchValuesAreAccepted(String raw, long expected) {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        hookFor(event);
        OptionMapping matchOpt = mock(OptionMapping.class);
        when(matchOpt.getAsString()).thenReturn(raw);
        when(event.getOption("match")).thenReturn(matchOpt);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any()))
                .thenThrow(new MatchNotFoundException("Not found"));

        command.handle(event);

        ArgumentCaptor<MatchFilter> filterCaptor = ArgumentCaptor.forClass(MatchFilter.class);
        verify(analyzeService).analyze(eq(CALLER_ID), eq(ACCOUNT_ID), filterCaptor.capture());
        assertThat(filterCaptor.getValue().matchId()).isEqualTo(expected);
    }

    @Test
    void resolvesEveryAnalyzeKey() {
        List<String> requiredKeys = List.of(
                "analyze.description",
                "analyze.not_linked",
                "analyze.no_matches",
                "analyze.title",
                "analyze.result.win",
                "analyze.result.loss",
                "analyze.field.strength",
                "analyze.field.leak",
                "analyze.field.next_action",
                "analyze.field.key_numbers",
                "analyze.footer",
                "analyze.footer_no_rank",
                "analyze.degraded_note",
                "analyze.fallback_note",
                "analyze.fallback_number",
                "analyze.fallback_number_no_median",
                "analyze.metric.souls_per_min",
                "analyze.metric.kills",
                "analyze.metric.deaths",
                "analyze.metric.assists",
                "analyze.metric.deaths_per_10_min",
                "analyze.metric.last_hits",
                "analyze.metric.denies",
                "analyze.metric.player_damage_per_min",
                "analyze.metric.net_worth_at_10_min",
                "analyze.metric.net_worth_at_20_min",
                "analyze.metric.permanent_buffs",
                "analyze.metric.first_permanent_buff_min",
                "analyze.option.match",
                "analyze.option.hero",
                "analyze.option.result",
                "analyze.result.choice.win",
                "analyze.result.choice.loss",
                "analyze.hero_not_found",
                "analyze.match_not_found",
                "analyze.match_invalid",
                "analyze.autocomplete.match",
                "analyze.report_button",
                "analyze.severity.major",
                "analyze.severity.notable",
                "analyze.severity.minor"
        );

        for (String key : requiredKeys) {
            assertThat(messages.get(key)).as("key %s should resolve", key).isNotBlank();
        }
    }

    @Test
    void severityMajorPrefixesLeakFieldWithSeverityLabel() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult(
                "Great farming", "Too many deaths", "Ward more", List.of("10 kills"), "deaths", "major");
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        MessageEmbed.Field leakField = embed.getFields().get(0);
        assertThat(leakField.getName()).isEqualTo(messages.get("analyze.field.leak"));
        assertThat(leakField.getValue()).isEqualTo(messages.get("analyze.severity.major") + " Too many deaths");
    }

    @Test
    void severityMajorWithLongLeakStaysWithinFieldLimit() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        String longLeak = "a very long explanation of deaths in match ".repeat(30);
        AnalysisResult aiResult = new AnalysisResult(
                "Great farming", longLeak, "Ward more", List.of("10 kills"), "deaths", "major");
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        MessageEmbed.Field leakField = embed.getFields().get(0);
        assertThat(leakField.getValue().length()).isLessThanOrEqualTo(1024);
        assertThat(leakField.getValue()).startsWith(messages.get("analyze.severity.major") + " ");
        assertThat(leakField.getValue()).endsWith("...");
    }

    @Test
    void severityNullPreservesUnprefixedLeak() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult(
                "Great farming", "Too many deaths", "Ward more", List.of("10 kills"), "deaths", null);
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        MessageEmbed.Field leakField = embed.getFields().get(0);
        assertThat(leakField.getValue()).isEqualTo("Too many deaths");
    }

    @Test
    void webEnabledWithPublicIdAttachesSingleLinkButtonWithProperUrl() {
        UUID publicId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        AnalyzeCommand cmd = new AnalyzeCommand(analyzeService, profileService, messages, assetImages,
                matchSearchService, gameAssets, heroResolver, iconCatalog, chartRenderer,
                new WebProperties("http://localhost:8080"));

        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult("Great farming", "Too many deaths", "Ward more", List.of("10 kills"));
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, null, publicId);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        cmd.handle(event);

        ArgumentCaptor<ItemComponent[]> captor = ArgumentCaptor.forClass(ItemComponent[].class);
        verify(edit).setActionRow(captor.capture());
        ItemComponent[] components = captor.getValue();
        assertThat(components).hasSize(1);
        assertThat(components[0]).isInstanceOf(Button.class);
        Button button = (Button) components[0];
        assertThat(button.getUrl()).isEqualTo("http://localhost:8080/r/12345678-1234-1234-1234-123456789abc");
        assertThat(button.getLabel()).isEqualTo(messages.get("analyze.report_button"));
    }

    @Test
    void webEnabledStripsTrailingSlashFromBaseUrl() {
        UUID publicId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        AnalyzeCommand cmd = new AnalyzeCommand(analyzeService, profileService, messages, assetImages,
                matchSearchService, gameAssets, heroResolver, iconCatalog, chartRenderer,
                new WebProperties("https://rejuv.gg/"));

        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult("Great farming", "Too many deaths", "Ward more", List.of("10 kills"));
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, null, publicId);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        cmd.handle(event);

        ArgumentCaptor<ItemComponent[]> captor = ArgumentCaptor.forClass(ItemComponent[].class);
        verify(edit).setActionRow(captor.capture());
        Button button = (Button) captor.getValue()[0];
        assertThat(button.getUrl()).isEqualTo("https://rejuv.gg/r/12345678-1234-1234-1234-123456789abc");
    }

    @Test
    void webDisabledOrNullPublicIdAttachesNoComponent() {
        UUID publicId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        AnalyzeCommand cmdDisabled = new AnalyzeCommand(analyzeService, profileService, messages, assetImages,
                matchSearchService, gameAssets, heroResolver, iconCatalog, chartRenderer,
                new WebProperties(""));

        SlashCommandInteractionEvent event1 = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event1);
        InteractionHook hook1 = hookFor(event1);

        AnalysisResult aiResult = new AnalysisResult("Great farming", "Too many deaths", "Ward more", List.of("10 kills"));
        AnalysisReport reportWithId = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, null, publicId);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(reportWithId);

        cmdDisabled.handle(event1);
        verify(edit, never()).setActionRow((ItemComponent[]) any());

        // Test with web enabled but publicId == null
        AnalyzeCommand cmdEnabled = new AnalyzeCommand(analyzeService, profileService, messages, assetImages,
                matchSearchService, gameAssets, heroResolver, iconCatalog, chartRenderer,
                new WebProperties("http://localhost:8080"));

        SlashCommandInteractionEvent event2 = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event2);
        InteractionHook hook2 = hookFor(event2);

        AnalysisReport reportNullId = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, null, null);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(reportNullId);

        cmdEnabled.handle(event2);
        verify(edit, never()).setActionRow((ItemComponent[]) any());
    }

    @Test
    void embedFieldOrderMatchesTask047Spec() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult(
                "Great farming", "Too many deaths", "Ward more", List.of("Kills: 10 / 5"));
        MatchVisuals visuals = visualsWithTwoItems(CHART);
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, visuals, null);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(5);
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("analyze.field.leak"));
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("analyze.field.next_action"));
        assertThat(fields.get(2).getName()).isEqualTo(messages.get("analyze.field.strength"));
        assertThat(fields.get(3).getName()).isEqualTo(messages.get("analyze.field.key_numbers"));
        assertThat(fields.get(4).getName()).isEqualTo(messages.get("analyze.field.build"));
    }

    @Test
    void fieldNamesHaveEmojiPrefixes() {
        assertThat(messages.get("analyze.field.leak")).startsWith("⚠️ ");
        assertThat(messages.get("analyze.field.next_action")).startsWith("🎯 ");
        assertThat(messages.get("analyze.field.strength")).startsWith("💪 ");
        assertThat(messages.get("analyze.field.key_numbers")).startsWith("📊 ");
        assertThat(messages.get("analyze.field.build")).startsWith("🎒 ");
    }

    @Test
    void keyNumbersTableAlignsColumnsAndKeepsMalformedLinesVerbatim() {
        List<String> rawLines = List.of(
                "Kills: 10 / 5",
                "Souls / min: 750.5 / 600.0",
                "Custom note without pattern",
                "Deaths: 2 / 3"
        );
        String table = EmbedStyle.formatKeyNumbersTable(rawLines);

        assertThat(table).startsWith("```\n").endsWith("\n```");
        assertThat(table).contains("Kills       | 10    | 5");
        assertThat(table).contains("Souls / min | 750.5 | 600.0");
        assertThat(table).contains("Custom note without pattern");
        assertThat(table).contains("Deaths      | 2     | 3");

        assertThat(EmbedStyle.formatKeyNumbersTable(null)).isEmpty();
        assertThat(EmbedStyle.formatKeyNumbersTable(List.of())).isEmpty();
    }

    @Test
    void keyNumbersTableDropsWholeTrailingLinesAtLimit() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            lines.add("Metric " + String.format("%02d", i) + ": 100 / 200");
        }
        String table = EmbedStyle.formatKeyNumbersTable(lines);

        assertThat(table.length()).isLessThanOrEqualTo(1024);
        assertThat(table).startsWith("```\n").endsWith("\n```");
        assertThat(table).contains("Metric 00");
    }

    @Test
    void titleDescriptionAndFooterAreCappedAtDiscordLimits() {
        String longTitle = "A".repeat(300);
        String cappedTitle = AnalyzeCommand.truncate(longTitle, AnalyzeCommand.MAX_TITLE_LENGTH);
        assertThat(cappedTitle.length()).isLessThanOrEqualTo(AnalyzeCommand.MAX_TITLE_LENGTH);
        assertThat(cappedTitle).endsWith("...");

        String longDescription = "B".repeat(5000);
        String cappedDesc = AnalyzeCommand.truncate(longDescription, AnalyzeCommand.MAX_DESCRIPTION_LENGTH);
        assertThat(cappedDesc.length()).isLessThanOrEqualTo(AnalyzeCommand.MAX_DESCRIPTION_LENGTH);
        assertThat(cappedDesc).endsWith("...");

        String longFooter = "C".repeat(2500);
        String cappedFooter = AnalyzeCommand.truncate(longFooter, AnalyzeCommand.MAX_FOOTER_LENGTH);
        assertThat(cappedFooter.length()).isLessThanOrEqualTo(AnalyzeCommand.MAX_FOOTER_LENGTH);
        assertThat(cappedFooter).endsWith("...");

        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        String hugeHero = "VeryLongHeroName ".repeat(25);
        String hugeRank = "VeryLongRankName ".repeat(200);
        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, hugeHero, hugeRank, "win", 30.0, aiResult, Map.of(), true);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle().length()).isLessThanOrEqualTo(AnalyzeCommand.MAX_TITLE_LENGTH);
        assertThat(embed.getTitle()).endsWith("...");
        assertThat(embed.getDescription().length()).isLessThanOrEqualTo(AnalyzeCommand.MAX_DESCRIPTION_LENGTH);
        assertThat(embed.getFooter().getText().length()).isLessThanOrEqualTo(AnalyzeCommand.MAX_FOOTER_LENGTH);
        assertThat(embed.getFooter().getText()).endsWith("...");
    }

    @Test
    void supportFieldPresentWhenLinkExists() {
        DonationService donationService = new DonationService(
                new DonationProperties("https://example.com/donate", "Support Us"), messages);
        AnalyzeCommand cmd = new AnalyzeCommand(analyzeService, profileService, messages, assetImages,
                matchSearchService, gameAssets, heroResolver, iconCatalog, chartRenderer,
                new WebProperties(null), donationService);

        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, null, null);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        cmd.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).isNotEmpty();
        MessageEmbed.Field lastField = fields.get(fields.size() - 1);
        assertThat(lastField.getName()).isEqualTo(messages.get("analyze.field.support"));
        assertThat(lastField.getValue()).isEqualTo(messages.get("analyze.support.line", "Support Us", "https://example.com/donate"));
    }

    @Test
    void supportFieldAbsentWhenLinkAbsent() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false, null, null);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields()).noneMatch(f -> f.getName().equals(messages.get("analyze.field.support")));
    }

    @Test
    void supportFieldSkippedWhenEmbedWouldExceedSizeLimit() {
        DonationService donationService = new DonationService(
                new DonationProperties("https://example.com/donate", "Support Us"), messages);
        AnalyzeCommand cmd = new AnalyzeCommand(analyzeService, profileService, messages, assetImages,
                matchSearchService, gameAssets, heroResolver, iconCatalog, chartRenderer,
                new WebProperties(null), donationService);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        String leak = "word ".repeat(204);
        String action = "word ".repeat(204);
        String strength = "word ".repeat(204);
        List<MatchVisuals.PurchasedItem> items = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            items.add(new MatchVisuals.PurchasedItem((long) (100 + i), "Item Name " + i, 300));
        }
        MatchVisuals visuals = new MatchVisuals(items, null);
        String hugeRank = "Rank ".repeat(450);
        List<String> keyNumbers = List.of("Note: " + "word ".repeat(60));
        AnalysisResult aiResult = new AnalysisResult(strength, leak, action, keyNumbers);
        AnalysisReport report = new AnalysisReport(1, "Infernus", hugeRank, "win", 30.0, aiResult, Map.of(), false, visuals, null);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenReturn(report);

        // Baseline: same report with donations disabled. It must fit, but only just.
        SlashCommandInteractionEvent baselineEvent = mockEvent();
        deferFor(baselineEvent);
        InteractionHook baselineHook = hookFor(baselineEvent);
        command.handle(baselineEvent);
        MessageEmbed baseline = capturedEmbed(baselineHook);

        int supportLength = messages.get("analyze.field.support").length()
                + messages.get("analyze.support.line", "Support Us", "https://example.com/donate").length();
        assertThat(baseline.getLength()).isLessThanOrEqualTo(MessageEmbed.EMBED_MAX_LENGTH_BOT);
        assertThat(baseline.getLength() + supportLength).isGreaterThan(MessageEmbed.EMBED_MAX_LENGTH_BOT);
        assertThat(baseline.getFields().size()).isLessThan(25);

        // With donations enabled the support field is skipped and the embed is unchanged.
        SlashCommandInteractionEvent event = mockEvent();
        deferFor(event);
        InteractionHook hook = hookFor(event);
        cmd.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getLength()).isEqualTo(baseline.getLength());
        assertThat(embed.getFields()).noneMatch(f -> f.getName().equals(messages.get("analyze.field.support")));
    }

    private String runWithLimit(AnalysisLimitExceededException ex) {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);
        when(analyzeService.analyze(eq(CALLER_ID), eq(ACCOUNT_ID), any())).thenThrow(ex);

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        return captor.getValue();
    }

    @Test
    void cooldownLimitRepliesWithSecondsRoundedUp() {
        String text = runWithLimit(new AnalysisLimitExceededException(
                AnalysisLimitExceededException.Reason.COOLDOWN, java.time.Duration.ofMillis(41_200), 0));

        assertThat(text).isEqualTo(messages.get("analyze.limit.cooldown", 42));
        assertThat(text).contains("42 seconds");
    }

    @Test
    void userDailyLimitRepliesWithLimitAndWaitInMinutesOrHours() {
        String minutes = runWithLimit(new AnalysisLimitExceededException(
                AnalysisLimitExceededException.Reason.USER_DAILY, java.time.Duration.ofMinutes(25), 10));
        assertThat(minutes).contains("10 new analyses").contains("25 min");

        String hours = runWithLimit(new AnalysisLimitExceededException(
                AnalysisLimitExceededException.Reason.USER_DAILY, java.time.Duration.ofMinutes(130), 10));
        assertThat(hours).contains("3 h");
    }

    @Test
    void globalDailyLimitRepliesWithBudgetMessage() {
        String text = runWithLimit(new AnalysisLimitExceededException(
                AnalysisLimitExceededException.Reason.GLOBAL_DAILY, java.time.Duration.ofSeconds(5), 150));

        assertThat(text).contains("daily AI budget").contains("1 min");
    }

    @Test
    void limitAndTipKeysResolve() {
        assertThat(messages.get("analyze.limit.wait_hours", 2)).isEqualTo("2 h");
        assertThat(messages.get("analyze.field.tips")).isNotBlank();
    }

    private AnalysisReport reportWithTips(List<AnalysisResult.Tip> tips) {
        AnalysisResult aiResult = new AnalysisResult("Great farming", "Too many deaths", "Ward more",
                List.of("Kills: 10 / 5"), "deaths", "major", tips);
        return new AnalysisReport(1, "Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
    }

    @Test
    void tipsFieldFollowsNextActionAndListsEveryTip() {
        InteractionHook hook = runWithReport(reportWithTips(List.of(
                new AnalysisResult.Tip("Retreat early.", "deaths: 9 / 5", "Max 5 deaths."),
                new AnalysisResult.Tip("Buy health.", "bought at 2.5 min", "Before minute 3."),
                new AnalysisResult.Tip("Keep farming.", "souls: 750 / 680", "Stay above 700."))));

        MessageEmbed embed = capturedEmbed(hook);
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("analyze.field.next_action"));
        assertThat(fields.get(2).getName()).isEqualTo(messages.get("analyze.field.tips"));
        assertThat(fields.get(2).getValue())
                .contains("1. Retreat early.", "Evidence: deaths: 9 / 5", "Drill: Max 5 deaths.", "3. Keep farming.");
        assertThat(fields.get(3).getName()).isEqualTo(messages.get("analyze.field.strength"));
    }

    @Test
    void noTipsFieldWhenTipsAreEmpty() {
        InteractionHook hook = runWithReport(reportWithTips(List.of()));

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields()).extracting(MessageEmbed.Field::getName)
                .doesNotContain(messages.get("analyze.field.tips"));
    }

    @Test
    void longTipsStayWithinFieldAndEmbedLimits() {
        String long400 = "x".repeat(400);
        InteractionHook hook = runWithReport(reportWithTips(List.of(
                new AnalysisResult.Tip(long400, long400, long400),
                new AnalysisResult.Tip(long400, long400, long400),
                new AnalysisResult.Tip(long400, long400, long400))));

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getLength()).isLessThanOrEqualTo(MessageEmbed.EMBED_MAX_LENGTH_BOT);
        embed.getFields().forEach(f -> assertThat(f.getValue().length()).isLessThanOrEqualTo(1024));
    }

    private SlashCommandInteractionEvent mockEvent() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        return event;
    }

    private static User user(long id, String name) {
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(id);
        when(user.getEffectiveName()).thenReturn(name);
        return user;
    }

    private static ReplyCallbackAction deferFor(SlashCommandInteractionEvent event) {
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);
        return defer;
    }

    @SuppressWarnings("unchecked")
    private InteractionHook hookFor(SlashCommandInteractionEvent event) {
        InteractionHook hook = mock(InteractionHook.class);
        edit = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(edit);
        when(edit.setActionRow(any(ItemComponent[].class))).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        return hook;
    }

    private MessageEmbed capturedEmbed(InteractionHook hook) {
        ArgumentCaptor<MessageEmbed> embed = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embed.capture());
        verify(edit).queue();
        return embed.getValue();
    }
}
