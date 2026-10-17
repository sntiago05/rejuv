package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.services.LinkService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class UnlinkCommandTest {

    private static final long CALLER_ID = 333L;

    private final Messages messages = MessagesFixture.english();
    private LinkService linkService;
    private UnlinkCommand command;

    @BeforeEach
    void setUp() {
        linkService = mock(LinkService.class);
        command = new UnlinkCommand(linkService, messages);
    }

    @Test
    void nameIsUnlinkAndHasNoOptions() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("unlink");
        assertThat(data.getName()).isEqualTo("unlink");
        assertThat(data.getDescription()).isEqualTo(messages.get("unlink.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).isEmpty();
    }

    @Test
    void linkedUserGetsEphemeralSuccess() {
        when(linkService.unlink(CALLER_ID)).thenReturn(true);
        SlashCommandInteractionEvent event = event();
        ReplyCallbackAction reply = replyFor(event);

        command.handle(event);

        verify(event).reply(messages.get("unlink.success"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
    }

    @Test
    void unlinkedUserGetsEphemeralNotLinked() {
        when(linkService.unlink(CALLER_ID)).thenReturn(false);
        SlashCommandInteractionEvent event = event();
        ReplyCallbackAction reply = replyFor(event);

        command.handle(event);

        verify(event).reply(messages.get("unlink.not_linked"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
    }

    @Test
    void serviceReceivesCallerId() {
        SlashCommandInteractionEvent event = event();
        replyFor(event);

        command.handle(event);

        verify(linkService).unlink(CALLER_ID);
    }

    private static SlashCommandInteractionEvent event() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(CALLER_ID);
        when(event.getUser()).thenReturn(user);
        return event;
    }

    private static ReplyCallbackAction replyFor(SlashCommandInteractionEvent event) {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);
        return reply;
    }
}
