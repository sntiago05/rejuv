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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.ProfileService;
import sntiago05.rejuv.services.TrendsService;
import sntiago05.rejuv.services.TrendsService.HeroLine;
import sntiago05.rejuv.services.TrendsService.TrendsSummary;

class TrendsCommandTest {

    private static final long CALLER_ID = 222L;
    private static final long OTHER_ID = 333L;
    private static final long ACCOUNT_ID = 1000001L;

    private final Messages messages = MessagesFixture.english();
    private ProfileService profileService;
    private TrendsService trendsService;
    private GameAssets gameAssets;
    private IconCatalog iconCatalog;
    private TrendsCommand command;
    private User caller;
    private User other;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        trendsService = mock(TrendsService.class);
        gameAssets = mock(GameAssets.class);
        iconCatalog = new IconCatalog();
        command = new TrendsCommand(profileService, trendsService, gameAssets, iconCatalog, messages);
        caller = user(CALLER_ID, "Caller");
        other = user(OTHER_ID, "Other");
    }

    @Test
    void nameIsTrendsWithGamesAndUserOptions() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("trends");
        assertThat(data.getName()).isEqualTo("trends");
        assertThat(data.getDescription()).isEqualTo(messages.get("trends.description"));
        assertThat(data.getOptions()).hasSize(2);

        OptionData gamesOption = data.getOptions().get(0);
        assertThat(gamesOption.getName()).isEqualTo("games");
        assertThat(gamesOption.getType()).isEqualTo(OptionType.INTEGER);
        assertThat(gamesOption.isRequired()).isFalse();
        assertThat(gamesOption.getChoices()).extracting(Command.Choice::getAsLong)
                .containsExactly(10L, 20L, 50L);

        OptionData userOption = data.getOptions().get(1);
        assertThat(userOption.getName()).isEqualTo("user");
        assertThat(userOption.getType()).isEqualTo(OptionType.USER);
        assertThat(userOption.isRequired()).isFalse();
    }

    @Test
    void callerNotLinkedRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = event(null, null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        assertImmediateReply(event, messages.get("trends.not_linked_self"));
    }

    @Test
    void otherUserNotLinkedRepliesWithMention() {
        SlashCommandInteractionEvent event = event(null, other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.empty());

        String text = assertImmediateReply(event, messages.get("trends.not_linked_other", other.getAsMention()));
        assertThat(text).contains("<@333>");
    }

    @Test
    void defaultsToTwentyGames() {
        SlashCommandInteractionEvent event = event(null, null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(trendsService.summarize(ACCOUNT_ID, 20)).thenReturn(sampleSummary(20));
        deferFor(event);
        hookFor(event);

        command.handle(event);

        verify(trendsService).summarize(ACCOUNT_ID, 20);
    }

    @Test
    void optionGamesIsUsedWhenProvided() {
        SlashCommandInteractionEvent event = event(10, null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(trendsService.summarize(ACCOUNT_ID, 10)).thenReturn(sampleSummary(10));
        deferFor(event);
        hookFor(event);

        command.handle(event);

        verify(trendsService).summarize(ACCOUNT_ID, 10);
    }

    @Test
    void fewerThanFiveGamesEditsWithNotEnoughGamesMessage() {
        SlashCommandInteractionEvent event = event(null, null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(trendsService.summarize(ACCOUNT_ID, 20)).thenReturn(new TrendsSummary(3, 33.3, 2.0, 3.0, 4.0, 2.0, 15000L, 0.0, 0.0, List.of()));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("trends.not_enough_games", "3"));
    }

    @Test
    void successDefersPubliclyAndEditsWithEmbed() {
        SlashCommandInteractionEvent event = event(null, null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(trendsService.summarize(ACCOUNT_ID, 20)).thenReturn(sampleSummary(20));
        when(gameAssets.heroNames()).thenReturn(Map.of(1, "Infernus", 2, "Seven"));
        iconCatalog.replace(Map.of("h_1", "<:h_1:111>"));
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(event).deferReply();
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo(messages.get("trends.title", "Caller"));
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("trends.footer", "1000001"));

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(3);

        // Overview field
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("trends.field.overview", "20"));
        assertThat(fields.get(0).getValue()).contains("60%", "12 wins of 20", "6.5", "4.0", "7.5", "3.5");

        // Trend field
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("trends.field.trend"));
        assertThat(fields.get(1).getValue()).contains("Improving", "+20.0 pts");

        // Heroes field
        assertThat(fields.get(2).getName()).isEqualTo(messages.get("trends.field.heroes"));
        assertThat(fields.get(2).getValue()).contains("<:h_1:111> Infernus - 10 games, 70% win");
        assertThat(fields.get(2).getValue()).contains("Seven - 5 games, 60% win");
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        SlashCommandInteractionEvent event = event(null, null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(trendsService.summarize(ACCOUNT_ID, 20))
                .thenThrow(new DeadlockApiUnavailableException("busy", Duration.ofSeconds(5), null));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        SlashCommandInteractionEvent event = event(null, null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(trendsService.summarize(ACCOUNT_ID, 20)).thenThrow(new RuntimeException("boom"));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
    }

    private String assertImmediateReply(SlashCommandInteractionEvent event, String expectedText) {
        ReplyCallbackAction action = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(action);
        when(action.setEphemeral(true)).thenReturn(action);

        command.handle(event);

        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        ArgumentCaptor<String> replyText = ArgumentCaptor.forClass(String.class);
        verify(event).reply(replyText.capture());
        verify(action).setEphemeral(true);
        verify(action).queue();
        assertThat(replyText.getValue()).isEqualTo(expectedText);
        return replyText.getValue();
    }

    private static TrendsSummary sampleSummary(int games) {
        List<HeroLine> heroes = List.of(
                new HeroLine(1, 10, 70.0),
                new HeroLine(2, 5, 60.0)
        );
        return new TrendsSummary(games, 60.0, 6.5, 4.0, 7.5, 3.5, 21000L, 70.0, 50.0, heroes);
    }

    private MessageEmbed capturedEmbed(InteractionHook hook) {
        ArgumentCaptor<MessageEmbed> embed = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embed.capture());
        verify(edit).queue();
        return embed.getValue();
    }

    private SlashCommandInteractionEvent event(Integer games, User optionUser) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        if (games != null) {
            OptionMapping option = mock(OptionMapping.class);
            when(option.getAsInt()).thenReturn(games);
            when(event.getOption("games")).thenReturn(option);
        }
        if (optionUser != null) {
            OptionMapping option = mock(OptionMapping.class);
            when(option.getAsUser()).thenReturn(optionUser);
            when(event.getOption("user")).thenReturn(option);
        }
        return event;
    }

    private static User user(long id, String name) {
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(id);
        when(user.getEffectiveName()).thenReturn(name);
        when(user.getAsMention()).thenReturn("<@" + id + ">");
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
}
