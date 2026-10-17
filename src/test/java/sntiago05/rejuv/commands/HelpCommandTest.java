package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class HelpCommandTest {

    @Test
    void nameIsHelp() {
        HelpCommand command = new HelpCommand(MessagesFixture.english());
        assertThat(command.name()).isEqualTo("help");
    }

    @Test
    void dataUsesCatalogDescriptionAndHasNoOptions() {
        Messages messages = MessagesFixture.english();
        HelpCommand command = new HelpCommand(messages);
        SlashCommandData data = command.data();

        assertThat(data.getName()).isEqualTo("help");
        assertThat(data.getDescription()).isEqualTo(messages.get("help.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).isEmpty();
    }

    @Test
    void handleRepliesEphemerallyWithHelpBody() {
        Messages messages = MessagesFixture.english();
        HelpCommand command = new HelpCommand(messages);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(messages.get("help.body"))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply(messages.get("help.body"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
    }

    @Test
    void helpBodyCoversMvpTopics() {
        Messages messages = MessagesFixture.english();
        String body = messages.get("help.body");

        assertThat(body).contains(
                "/link",
                "/unlink",
                "/profile",
                "/analyze",
                "/meta",
                "/build",
                "/help",
                "7656119",
                "https://steamcommunity.com/profiles/",
                "ingest tool"
        );
        assertThat(body).doesNotContain("/ping");
    }

    @Test
    void helpBodyFitsDiscordMessageLimit() {
        Messages messages = MessagesFixture.english();
        String body = messages.get("help.body");

        assertThat(body.length()).isLessThanOrEqualTo(2000);
    }
}
