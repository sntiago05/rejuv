package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.dv8tion.jda.api.entities.Message;
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
import sntiago05.rejuv.services.LinkService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.NoMatchHistoryException;

class LinkCommandTest {

    private static final long CALLER_ID = 222L;

    private final Messages messages = MessagesFixture.english();
    private LinkService linkService;
    private LinkCommand command;

    @BeforeEach
    void setUp() {
        linkService = mock(LinkService.class);
        command = new LinkCommand(linkService, messages);
    }

    @Test
    void nameIsLink() {
        assertThat(command.name()).isEqualTo("link");
    }

    @Test
    void dataHasRequiredStringOptionAccount() {
        SlashCommandData data = command.data();

        assertThat(data.getName()).isEqualTo("link");
        assertThat(data.getDescription()).isEqualTo(messages.get("link.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).hasSize(1);
        OptionData option = data.getOptions().get(0);
        assertThat(option.getName()).isEqualTo("account");
        assertThat(option.getType()).isEqualTo(OptionType.STRING);
        assertThat(option.isRequired()).isTrue();
        assertThat(option.getMaxLength()).isEqualTo(200);
        assertThat(option.getDescription()).isEqualTo(messages.get("link.option.account"));
        assertThat(option.getDescription().length()).isBetween(1, 100);
    }

    @Test
    void unrecognizedInputRepliesEphemerallyWithoutDeferring() {
        assertImmediateReply("abc", "link.invalid_input");
    }

    @Test
    void vanityUrlRepliesWithVanityMessage() {
        assertImmediateReply("https://steamcommunity.com/id/somename", "link.vanity_unsupported");
    }

    @Test
    void successDefersEphemerallyAndConfirms() {
        SlashCommandInteractionEvent event = eventWithInput("76561197961265729");
        ReplyCallbackAction defer = deferFor(event);
        WebhookMessageEditAction<Message> edit = editFor(event);

        command.handle(event);

        verify(event).deferReply(true);
        verify(defer).queue();
        verify(linkService).link(CALLER_ID, 1000001L);
        verify(event.getHook()).editOriginal(messages.get("link.success", "1000001"));
        verify(edit).queue();
    }

    @Test
    void noMatchesEditsWithNoMatchesMessage() {
        doThrow(new NoMatchHistoryException(1000001L)).when(linkService).link(anyLong(), anyLong());

        assertEditedWith(messages.get("link.no_matches"));
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        doThrow(new DeadlockApiUnavailableException("busy", null, null)).when(linkService).link(anyLong(), anyLong());

        assertEditedWith(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        doThrow(new RuntimeException("boom")).when(linkService).link(anyLong(), anyLong());
        SlashCommandInteractionEvent event = eventWithInput("1000001");
        deferFor(event);
        editFor(event);

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(event.getHook()).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
    }

    @Test
    void catalogTextsHaveRequiredContent() {
        assertThat(messages.get("link.invalid_input"))
                .contains("7656119", "https://steamcommunity.com/profiles/", "1000001");
        assertThat(messages.get("link.no_matches")).contains("ingest tool");
        assertThat(messages.get("link.success", "1000001")).contains("1000001", "/analyze");
    }

    private void assertImmediateReply(String input, String key) {
        SlashCommandInteractionEvent event = eventWithInput(input);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(messages.get(key))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply(messages.get(key));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verifyNoInteractions(linkService);
    }

    private void assertEditedWith(String expected) {
        SlashCommandInteractionEvent event = eventWithInput("1000001");
        deferFor(event);
        WebhookMessageEditAction<Message> edit = editFor(event);

        command.handle(event);

        verify(event.getHook()).editOriginal(expected);
        verify(edit).queue();
    }

    private static SlashCommandInteractionEvent eventWithInput(String input) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        OptionMapping option = mock(OptionMapping.class);
        when(option.getAsString()).thenReturn(input);
        when(event.getOption("account")).thenReturn(option);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(CALLER_ID);
        when(event.getUser()).thenReturn(user);
        return event;
    }

    private static ReplyCallbackAction deferFor(SlashCommandInteractionEvent event) {
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(event.deferReply(true)).thenReturn(defer);
        return defer;
    }

    @SuppressWarnings("unchecked")
    private static WebhookMessageEditAction<Message> editFor(SlashCommandInteractionEvent event) {
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        return edit;
    }
}
