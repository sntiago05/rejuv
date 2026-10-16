package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class PingCommandTest {

    @Test
    void dataUsesCatalogDescription() {
        Messages messages = MessagesFixture.english();
        PingCommand command = new PingCommand(messages);
        SlashCommandData data = command.data();

        assertThat(data.getName()).isEqualTo("ping");
        assertThat(data.getDescription()).isEqualTo("Check the bot gateway latency");
    }

    @Test
    void repliesEphemerallyWithGatewayPing() {
        Messages messages = MessagesFixture.english();
        PingCommand command = new PingCommand(messages);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        JDA jda = mock(JDA.class);
        when(event.getJDA()).thenReturn(jda);
        when(jda.getGatewayPing()).thenReturn(42L);

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply("Pong! 42 ms")).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply("Pong! 42 ms");
        verify(reply).setEphemeral(true);
        verify(reply).queue();
    }

    @Test
    void doesNotGroupLargePingValues() {
        Messages messages = MessagesFixture.english();
        PingCommand command = new PingCommand(messages);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        JDA jda = mock(JDA.class);
        when(event.getJDA()).thenReturn(jda);
        when(jda.getGatewayPing()).thenReturn(1234L);

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply("Pong! 1234 ms")).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply("Pong! 1234 ms");
        verify(reply).setEphemeral(true);
        verify(reply).queue();
    }
}
