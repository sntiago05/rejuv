package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.config.BotProperties;
import sntiago05.rejuv.services.MessagesFixture;

class CommandRouterTest {

    @Test
    void routesKnownCommandByName() {
        TestSlashCommand ping = new TestSlashCommand("ping");
        CommandRouter router = new CommandRouter(new BotProperties("token", "123"), List.of(ping), MessagesFixture.english());
        SlashCommandInteractionEvent event = eventNamed("ping");

        router.onSlashCommandInteraction(event);

        assertThat(ping.handledEvent).isSameAs(event);
        verify(event, never()).reply(anyString());
    }

    @Test
    void repliesEphemerallyForUnknownCommand() {
        CommandRouter router = new CommandRouter(new BotProperties("token", "123"), List.of(new TestSlashCommand("ping")), MessagesFixture.english());
        SlashCommandInteractionEvent event = eventNamed("missing");
        ReplyCallbackAction reply = replyFor(event, "Unknown command");

        router.onSlashCommandInteraction(event);

        verify(reply).setEphemeral(true);
        verify(reply).queue();
    }

    @Test
    void catchesHandlerExceptionAndRepliesEphemerally() {
        SlashCommand failing = new FailingSlashCommand("ping");
        CommandRouter router = new CommandRouter(new BotProperties("token", "123"), List.of(failing), MessagesFixture.english());
        SlashCommandInteractionEvent event = eventNamed("ping");
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(org.mockito.ArgumentMatchers.startsWith("Something went wrong. Error id: "))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        router.onSlashCommandInteraction(event);

        verify(reply).setEphemeral(true);
        verify(reply).queue();
    }

    private SlashCommandInteractionEvent eventNamed(String name) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        User user = mock(User.class);
        when(event.getName()).thenReturn(name);
        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("user-1");
        return event;
    }

    private ReplyCallbackAction replyFor(SlashCommandInteractionEvent event, String message) {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(message)).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);
        return reply;
    }

    private static final class TestSlashCommand implements SlashCommand {

        private final String name;
        private SlashCommandInteractionEvent handledEvent;

        private TestSlashCommand(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SlashCommandData data() {
            return Commands.slash(name, "Test command");
        }

        @Override
        public void handle(SlashCommandInteractionEvent event) {
            this.handledEvent = event;
        }
    }

    private static final class FailingSlashCommand implements SlashCommand {

        private final String name;

        private FailingSlashCommand(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SlashCommandData data() {
            return Commands.slash(name, "Failing command");
        }

        @Override
        public void handle(SlashCommandInteractionEvent event) {
            throw new IllegalStateException("boom");
        }
    }
}
