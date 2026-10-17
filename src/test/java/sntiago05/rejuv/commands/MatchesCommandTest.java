package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

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
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchSummary;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.MatchSearchService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.ProfileService;

class MatchesCommandTest {

    private static final long CALLER_ID = 101L;
    private static final long OTHER_ID = 202L;
    private static final long ACCOUNT_ID = 1000001L;

    private final Messages messages = MessagesFixture.english();
    private MatchSearchService matchSearchService;
    private MatchAnalysisRepository matchAnalysisRepository;
    private ProfileService profileService;
    private GameAssets gameAssets;
    private IconCatalog iconCatalog;
    private HeroResolver heroResolver;
    private MatchesCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        matchSearchService = mock(MatchSearchService.class);
        matchAnalysisRepository = mock(MatchAnalysisRepository.class);
        profileService = mock(ProfileService.class);
        gameAssets = mock(GameAssets.class);
        iconCatalog = mock(IconCatalog.class);
        heroResolver = mock(HeroResolver.class);
        command = new MatchesCommand(matchSearchService, matchAnalysisRepository, profileService,
                messages, gameAssets, iconCatalog, heroResolver);
        caller = user(CALLER_ID, "Caller");
    }

    @Test
    void nameAndOptionsMatchSpec() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("matches");
        assertThat(data.getName()).isEqualTo("matches");
        assertThat(data.getDescription()).isEqualTo(messages.get("matches.description"));
        assertThat(data.getOptions()).hasSize(3);

        OptionData heroOpt = data.getOptions().get(0);
        assertThat(heroOpt.getName()).isEqualTo("hero");
        assertThat(heroOpt.getType()).isEqualTo(OptionType.STRING);
        assertThat(heroOpt.isRequired()).isFalse();
        assertThat(heroOpt.isAutoComplete()).isTrue();

        OptionData resultOpt = data.getOptions().get(1);
        assertThat(resultOpt.getName()).isEqualTo("result");
        assertThat(resultOpt.getType()).isEqualTo(OptionType.STRING);
        assertThat(resultOpt.isRequired()).isFalse();
        assertThat(resultOpt.getChoices()).hasSize(2);
        assertThat(resultOpt.getChoices()).extracting(Command.Choice::getAsString).containsExactly("win", "loss");

        OptionData userOpt = data.getOptions().get(2);
        assertThat(userOpt.getName()).isEqualTo("user");
        assertThat(userOpt.getType()).isEqualTo(OptionType.USER);
        assertThat(userOpt.isRequired()).isFalse();
    }

    @Test
    void notLinkedSelfRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).reply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("matches.not_linked_self"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(matchSearchService, never()).find(anyLong(), any(), anyInt());
    }

    @Test
    void notLinkedOtherRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = mockEvent();
        User other = user(OTHER_ID, "OtherPlayer");
        OptionMapping userOpt = mock(OptionMapping.class);
        when(userOpt.getAsUser()).thenReturn(other);
        when(event.getOption("user")).thenReturn(userOpt);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.empty());

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).reply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("matches.not_linked_other", "OtherPlayer"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
    }

    @Test
    void successEmbedWithEntriesIconWhenPresentAndAnalyzedMarker() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        List<MatchSummary> summaries = List.of(
                new MatchSummary(900000001L, 1, "Infernus", 1700000000L, 1800, true, 5, 3, 7),
                new MatchSummary(900000002L, 2, "Seven", 1699900000L, 1205, false, 2, 8, 1)
        );
        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10))).thenReturn(summaries);
        when(matchAnalysisRepository.findAnalyzedMatchIds(ACCOUNT_ID, List.of(900000001L, 900000002L)))
                .thenReturn(List.of(900000001L)); // only first match is analyzed

        when(iconCatalog.hero(1)).thenReturn(Optional.of("<:hero_infernus:111>"));
        when(iconCatalog.hero(2)).thenReturn(Optional.empty());

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColor().getRGB() & 0xFFFFFF).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getTitle()).isEqualTo(messages.get("matches.title", "Caller"));
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("matches.footer"));

        String desc = embed.getDescription();
        assertThat(desc).isNotNull();
        String[] lines = desc.split("\n");
        assertThat(lines).hasSize(2);

        // First match has icon, victory, KDA, 30:00 duration, match ID, and [Analyzed] marker
        assertThat(lines[0]).contains("<:hero_infernus:111> Infernus");
        assertThat(lines[0]).contains("Victory");
        assertThat(lines[0]).contains("5/3/7");
        assertThat(lines[0]).contains("30:00");
        assertThat(lines[0]).contains("900000001");
        assertThat(lines[0]).endsWith(messages.get("matches.analyzed_marker"));

        // Second match has no icon, defeat, KDA, 20:05 duration, match ID, and NO analyzed marker
        assertThat(lines[1]).contains("Seven");
        assertThat(lines[1]).doesNotContain("<:hero_");
        assertThat(lines[1]).contains("Defeat");
        assertThat(lines[1]).contains("2/8/1");
        assertThat(lines[1]).contains("20:05");
        assertThat(lines[1]).contains("900000002");
        assertThat(lines[1]).doesNotEndWith(messages.get("matches.analyzed_marker"));
    }

    @Test
    void emptyHistoryEditsOriginalWithNoHistory() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10))).thenReturn(List.of());

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("matches.no_history"));
    }

    @Test
    void noResultsForFilterEditsOriginalWithNoResults() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        OptionMapping resultOpt = mock(OptionMapping.class);
        when(resultOpt.getAsString()).thenReturn("win");
        when(event.getOption("result")).thenReturn(resultOpt);

        // Filtered search gives 0 matches
        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10))).thenReturn(List.of());
        // Unfiltered search gives 1 match (so history is NOT empty)
        when(matchSearchService.find(eq(ACCOUNT_ID), eq(MatchFilter.LATEST), eq(1)))
                .thenReturn(List.of(new MatchSummary(900000001L, 1, "Infernus", 1700000000L, 1800, false, 0, 1, 0)));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("matches.no_results"));
    }

    @Test
    void deadlockApiUnavailableExceptionEditsOriginalWithDataSourceBusy() {
        SlashCommandInteractionEvent event = mockEvent();
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10)))
                .thenThrow(new DeadlockApiUnavailableException("busy", null, null));

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

        when(matchSearchService.find(eq(ACCOUNT_ID), any(), eq(10)))
                .thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).contains("Something went wrong. Error id:");
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
    void resolvesEveryMatchesKey() {
        List<String> requiredKeys = List.of(
                "matches.description",
                "matches.option.hero",
                "matches.option.result",
                "matches.option.user",
                "matches.result.choice.win",
                "matches.result.choice.loss",
                "matches.not_linked_self",
                "matches.not_linked_other",
                "matches.no_history",
                "matches.no_results",
                "matches.title",
                "matches.entry",
                "matches.analyzed_marker",
                "matches.footer"
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
