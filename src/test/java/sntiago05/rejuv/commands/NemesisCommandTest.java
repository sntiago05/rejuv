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
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.NemesisService;
import sntiago05.rejuv.services.NemesisService.NemesisLine;
import sntiago05.rejuv.services.NemesisService.NemesisSummary;
import sntiago05.rejuv.services.ProfileService;

class NemesisCommandTest {

    private static final long CALLER_ID = 222L;
    private static final long OTHER_ID = 333L;
    private static final long ACCOUNT_ID = 1000001L;

    private final Messages messages = MessagesFixture.english();
    private ProfileService profileService;
    private NemesisService nemesisService;
    private NemesisCommand command;
    private User caller;
    private User other;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        nemesisService = mock(NemesisService.class);
        command = new NemesisCommand(profileService, nemesisService, messages);
        caller = user(CALLER_ID, "Caller");
        other = user(OTHER_ID, "Other");
    }

    @Test
    void nameIsNemesisWithOptionalUserOption() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("nemesis");
        assertThat(data.getName()).isEqualTo("nemesis");
        assertThat(data.getDescription()).isEqualTo(messages.get("nemesis.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).hasSize(1);
        OptionData option = data.getOptions().get(0);
        assertThat(option.getName()).isEqualTo("user");
        assertThat(option.getType()).isEqualTo(OptionType.USER);
        assertThat(option.isRequired()).isFalse();
        assertThat(option.getDescription()).isEqualTo(messages.get("nemesis.option.user"));
        assertThat(option.getDescription().length()).isBetween(1, 100);
    }

    @Test
    void callerNotLinkedRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        assertImmediateReply(event, messages.get("nemesis.not_linked_self"));
    }

    @Test
    void otherUserNotLinkedRepliesWithMention() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.empty());

        String text = assertImmediateReply(event, messages.get("nemesis.not_linked_other", other.getAsMention()));
        assertThat(text).contains("<@333>");
    }

    @Test
    void optionUserIsUsedAsTarget() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(nemesisService.nemeses(ACCOUNT_ID)).thenReturn(sampleSummary());
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(profileService).findLinkedAccountId(OTHER_ID);
        verify(profileService, never()).findLinkedAccountId(CALLER_ID);
        assertThat(capturedEmbed(hook).getTitle()).isEqualTo(messages.get("nemesis.title", "Other"));
    }

    @Test
    void successDefersPubliclyAndEditsWithEmbed() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(nemesisService.nemeses(ACCOUNT_ID)).thenReturn(sampleSummary());
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo(messages.get("nemesis.title", "Caller"));
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("nemesis.field.top"));
        assertThat(fields.get(0).getValue()).contains("1. Viper (#2000001) - 10 matches, 0% win");
        assertThat(fields.get(0).getValue()).contains("2. Player #2000002 - 8 matches, 25% win");
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("nemesis.footer"));
    }

    @Test
    void emptyNemesesEditsWithNoNemesesMessage() {
        when(nemesisService.nemeses(ACCOUNT_ID)).thenReturn(new NemesisSummary(ACCOUNT_ID, List.of()));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("nemesis.no_nemeses", NemesisService.MIN_NEMESIS_MATCHES));
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        when(nemesisService.nemeses(ACCOUNT_ID))
                .thenThrow(new DeadlockApiUnavailableException("busy", Duration.ofSeconds(5), null));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        when(nemesisService.nemeses(ACCOUNT_ID)).thenThrow(new RuntimeException("boom"));

        assertThat(editedTextForLinkedCaller()).startsWith("Something went wrong. Error id: ");
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

    private String editedTextForLinkedCaller() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
        return text.getValue();
    }

    private static NemesisSummary sampleSummary() {
        NemesisLine n1 = new NemesisLine(2000001L, "Viper", 10, 0, 0.0);
        NemesisLine n2 = new NemesisLine(2000002L, null, 8, 2, 25.0);
        return new NemesisSummary(ACCOUNT_ID, List.of(n1, n2));
    }

    private MessageEmbed capturedEmbed(InteractionHook hook) {
        ArgumentCaptor<MessageEmbed> embed = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embed.capture());
        verify(edit).queue();
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
    private InteractionHook hookFor(SlashCommandInteractionEvent event) {
        InteractionHook hook = mock(InteractionHook.class);
        edit = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        return hook;
    }
}
