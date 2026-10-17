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

import java.util.List;
import java.util.OptionalLong;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
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
import sntiago05.rejuv.domain.HeroUsage;
import sntiago05.rejuv.domain.ProfileSummary;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.NoMatchHistoryException;
import sntiago05.rejuv.services.ProfileService;

class ProfileCommandTest {

    private static final long CALLER_ID = 222L;
    private static final long OTHER_ID = 333L;
    private static final long ACCOUNT_ID = 1000001L;

    private final Messages messages = MessagesFixture.english();
    private ProfileService profileService;
    private ProfileCommand command;
    private User caller;
    private User other;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        command = new ProfileCommand(profileService, messages);
        caller = user(CALLER_ID, "Caller");
        other = user(OTHER_ID, "Other");
    }

    @Test
    void nameIsProfileWithOptionalUserOption() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("profile");
        assertThat(data.getName()).isEqualTo("profile");
        assertThat(data.getDescription()).isEqualTo(messages.get("profile.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).hasSize(1);
        OptionData option = data.getOptions().get(0);
        assertThat(option.getName()).isEqualTo("user");
        assertThat(option.getType()).isEqualTo(OptionType.USER);
        assertThat(option.isRequired()).isFalse();
        assertThat(option.getDescription()).isEqualTo(messages.get("profile.option.user"));
        assertThat(option.getDescription().length()).isBetween(1, 100);
    }

    @Test
    void callerNotLinkedRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        assertImmediateReply(event, messages.get("profile.not_linked_self"));
    }

    @Test
    void otherUserNotLinkedRepliesWithMention() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.empty());

        String text = assertImmediateReply(event, messages.get("profile.not_linked_other", other.getAsMention()));

        assertThat(text).contains("<@333>");
    }

    @Test
    void optionUserIsUsedAsTarget() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(profileService.getProfile(ACCOUNT_ID)).thenReturn(rankedSummary());
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(profileService).findLinkedAccountId(OTHER_ID);
        verify(profileService, never()).findLinkedAccountId(CALLER_ID);
        assertThat(capturedEmbed(hook).getTitle()).isEqualTo(messages.get("profile.title", "Other"));
    }

    @Test
    void successDefersPubliclyAndEditsWithEmbed() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(profileService.getProfile(ACCOUNT_ID)).thenReturn(rankedSummary());
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();
        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo(messages.get("profile.title", "Caller"));
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(3);
        assertThat(fields).noneMatch(MessageEmbed.Field::isInline);
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("profile.field.rank"));
        assertThat(fields.get(0).getValue()).isEqualTo(messages.get("profile.rank_value", "Archon", "3"))
                .isEqualTo("Archon 3");
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("profile.field.recent", "10"));
        assertThat(fields.get(1).getValue())
                .isEqualTo(messages.get("profile.recent_value", "7", "10", "70", "6.2", "3.0", "8.5"))
                .contains("7 wins of 10", "70%", "6.2", "3.0", "8.5");
        assertThat(fields.get(2).getName()).isEqualTo(messages.get("profile.field.top_heroes"));
        assertThat(fields.get(2).getValue()).isEqualTo(
                messages.get("profile.hero_line", "Infernus", "5") + "\n" + messages.get("profile.hero_line", "Seven", "3"));
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("profile.footer", "1000001"))
                .contains("1000001");
    }

    @Test
    void embedShowsUnrankedAndNoRecentHeroes() {
        MessageEmbed embed = embedFor(new ProfileSummary(ACCOUNT_ID, null, null, 0,
                3, 1, 33, 1.0, 2.0, 3.0, List.of()));

        assertThat(embed.getFields().get(0).getValue()).isEqualTo(messages.get("profile.rank_unranked"));
        assertThat(embed.getFields().get(2).getValue()).isEqualTo(messages.get("profile.no_recent_heroes"));
    }

    @Test
    void embedShowsUnknownTierAndUnknownHero() {
        MessageEmbed embed = embedFor(new ProfileSummary(ACCOUNT_ID, 11, null, 5,
                1, 1, 100, 1.0, 2.0, 3.0, List.of(new HeroUsage(99, null, 1))));

        assertThat(embed.getFields().get(0).getValue()).isEqualTo(messages.get("profile.rank_unknown_tier", "11"));
        assertThat(embed.getFields().get(2).getValue()).isEqualTo(
                messages.get("profile.hero_line", messages.get("profile.hero_unknown", "99"), "1"));
    }

    @Test
    void embedShowsRankNameAloneWhenSubrankIsZero() {
        MessageEmbed embed = embedFor(new ProfileSummary(ACCOUNT_ID, 7, "Archon", 0,
                1, 1, 100, 1.0, 2.0, 3.0, List.of()));

        assertThat(embed.getFields().get(0).getValue()).isEqualTo("Archon");
    }

    @Test
    void noMatchesEditsWithNoMatchesMessage() {
        when(profileService.getProfile(ACCOUNT_ID)).thenThrow(new NoMatchHistoryException(ACCOUNT_ID));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("profile.no_matches"));
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        when(profileService.getProfile(ACCOUNT_ID)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        when(profileService.getProfile(ACCOUNT_ID)).thenThrow(new RuntimeException("boom"));

        assertThat(editedTextForLinkedCaller()).startsWith("Something went wrong. Error id: ");
    }

    @Test
    void catalogTextsHaveRequiredContent() {
        assertThat(messages.get("profile.no_matches")).contains("ingest tool");
        assertThat(messages.get("profile.not_linked_self")).contains("/link");
        assertThat(messages.get("profile.title", "x".repeat(32)).length()).isLessThanOrEqualTo(256);
    }

    private String assertImmediateReply(SlashCommandInteractionEvent event, String expected) {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(event).reply(text.capture());
        assertThat(text.getValue()).isEqualTo(expected);
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(profileService, never()).getProfile(anyLong());
        return text.getValue();
    }

    private String editedTextForLinkedCaller() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        return text.getValue();
    }

    private MessageEmbed embedFor(ProfileSummary summary) {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(profileService.getProfile(ACCOUNT_ID)).thenReturn(summary);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        return capturedEmbed(hook);
    }

    private static ProfileSummary rankedSummary() {
        return new ProfileSummary(ACCOUNT_ID, 7, "Archon", 3, 10, 7, 70, 6.24, 3.0, 8.5,
                List.of(new HeroUsage(1, "Infernus", 5), new HeroUsage(2, "Seven", 3)));
    }

    private static MessageEmbed capturedEmbed(InteractionHook hook) {
        ArgumentCaptor<MessageEmbed> embed = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embed.capture());
        return embed.getValue();
    }

    private SlashCommandInteractionEvent event(User optionUser) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
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
    private static InteractionHook hookFor(SlashCommandInteractionEvent event) {
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        return hook;
    }
}
