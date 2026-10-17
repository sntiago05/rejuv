package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.AnalysisInput.MetricComparison;
import sntiago05.rejuv.domain.AnalysisReport;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.services.AnalyzeService;
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
    private AnalyzeCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        analyzeService = mock(AnalyzeService.class);
        profileService = mock(ProfileService.class);
        command = new AnalyzeCommand(analyzeService, profileService, messages);
        caller = user(CALLER_ID, "Caller");
    }

    @Test
    void nameIsAnalyzeWithNoOptions() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("analyze");
        assertThat(data.getName()).isEqualTo("analyze");
        assertThat(data.getDescription()).isEqualTo(messages.get("analyze.description"));
        assertThat(data.getOptions()).isEmpty();
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
        verify(analyzeService, never()).analyze(anyLong());
    }

    @Test
    void successWithAiRepliesWithPublicEmbed() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult(
                "Great farming", "Too many deaths", "Ward more", List.of("10 kills", "20k souls"));
        AnalysisReport report = new AnalysisReport("Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(ACCOUNT_ID)).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo("Infernus - Victory (30.0 min)");
        assertThat(embed.getDescription()).isNull();
        assertThat(embed.getFields()).hasSize(4);
        assertThat(embed.getFields().get(0).getName()).isEqualTo("Key Strength");
        assertThat(embed.getFields().get(0).getValue()).isEqualTo("Great farming");
        assertThat(embed.getFields().get(1).getName()).isEqualTo("Biggest Leak");
        assertThat(embed.getFields().get(1).getValue()).isEqualTo("Too many deaths");
        assertThat(embed.getFields().get(2).getName()).isEqualTo("Next Game Focus");
        assertThat(embed.getFields().get(2).getValue()).isEqualTo("Ward more");
        assertThat(embed.getFields().get(3).getName()).isEqualTo("Key Numbers");
        assertThat(embed.getFields().get(3).getValue()).isEqualTo("10 kills\n20k souls");
        assertThat(embed.getFooter()).isNotNull();
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("analyze.footer", "Infernus", "Archon 3"));
    }

    @Test
    void degradedAnalysisShowsDegradedNoteInDescription() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        AnalysisResult aiResult = new AnalysisResult("Strength", "Leak", "Action", List.of());
        AnalysisReport report = new AnalysisReport("Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), true);
        when(analyzeService.analyze(ACCOUNT_ID)).thenReturn(report);

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
        metrics.put("kills", new MetricComparison(10.0, 3.0, 5.0, 8.0));
        metrics.put("deaths", new MetricComparison(2.0, 1.0, 3.0, 5.0));
        metrics.put("souls_per_min", new MetricComparison(750.5, 600.0, 700.0, 800.0));
        metrics.put("player_damage_per_min", new MetricComparison(1200.0, null, null, null));

        AnalysisReport report = new AnalysisReport("Infernus", "Archon 3", "loss", 25.4, null, metrics, false);
        when(analyzeService.analyze(ACCOUNT_ID)).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo("Infernus - Defeat (25.4 min)");
        assertThat(embed.getDescription()).isEqualTo(messages.get("analyze.fallback_note"));
        assertThat(embed.getFields()).hasSize(1);
        assertThat(embed.getFields().get(0).getName()).isEqualTo("Key Numbers");

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
                "kills", new MetricComparison(10.0, null, null, null));
        AnalysisReport report = new AnalysisReport("Infernus", "Archon 3", "win", 30.0, null, metrics, true);
        when(analyzeService.analyze(ACCOUNT_ID)).thenReturn(report);

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
        AnalysisReport report = new AnalysisReport("Infernus", null, "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(ACCOUNT_ID)).thenReturn(report);

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
        AnalysisReport report = new AnalysisReport("Infernus", "Archon 3", "win", 30.0, aiResult, Map.of(), false);
        when(analyzeService.analyze(ACCOUNT_ID)).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        String strength = embed.getFields().get(0).getValue();
        assertThat(strength.length()).isLessThanOrEqualTo(1024);
        assertThat(strength).endsWith("...");
    }

    @Test
    void noMatchHistoryExceptionEditsOriginalWithNoMatchesMessage() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);
        when(analyzeService.analyze(ACCOUNT_ID)).thenThrow(new NoMatchHistoryException(ACCOUNT_ID));

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
        when(analyzeService.analyze(ACCOUNT_ID)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

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
        when(analyzeService.analyze(ACCOUNT_ID)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).contains("Something went wrong. Error id:");
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
                "analyze.metric.first_permanent_buff_min"
        );

        for (String key : requiredKeys) {
            assertThat(messages.get(key)).as("key %s should resolve", key).isNotBlank();
        }
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
