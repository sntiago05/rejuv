package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.config.DonationProperties;
import sntiago05.rejuv.services.DonationService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class HelpCommandTest {

    private DonationService disabledDonationService(Messages messages) {
        return new DonationService(new DonationProperties(null, null), messages);
    }

    private DonationService enabledDonationService(Messages messages) {
        return new DonationService(new DonationProperties("https://example.com/donate", "Support Us"), messages);
    }

    @Test
    void nameIsHelp() {
        Messages messages = MessagesFixture.english();
        HelpCommand command = new HelpCommand(messages, disabledDonationService(messages));
        assertThat(command.name()).isEqualTo("help");
    }

    @Test
    void dataUsesCatalogDescriptionAndHasNoOptions() {
        Messages messages = MessagesFixture.english();
        HelpCommand command = new HelpCommand(messages, disabledDonationService(messages));
        SlashCommandData data = command.data();

        assertThat(data.getName()).isEqualTo("help");
        assertThat(data.getDescription()).isEqualTo(messages.get("help.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).isEmpty();
    }

    @Test
    void handleRepliesEphemerallyWithHelpEmbedWithoutDonationWhenDisabled() {
        Messages messages = MessagesFixture.english();
        HelpCommand command = new HelpCommand(messages, disabledDonationService(messages));
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        when(event.replyEmbeds(embedCaptor.capture())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();

        MessageEmbed embed = embedCaptor.getValue();
        assertThat(embed.getTitle()).isEqualTo(messages.get("help.title"));
        assertThat(embed.getDescription()).isEqualTo(messages.get("help.body"));
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getDescription()).contains("/counter");
        assertThat(embed.getDescription()).doesNotContain("https://example.com/donate");
    }

    @Test
    void handleRepliesWithDonationLineWhenLinkExists() {
        Messages messages = MessagesFixture.english();
        HelpCommand command = new HelpCommand(messages, enabledDonationService(messages));
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        when(event.replyEmbeds(embedCaptor.capture())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        MessageEmbed embed = embedCaptor.getValue();
        String expectedSuffix = messages.get("help.donate", "Support Us", "https://example.com/donate");
        assertThat(embed.getDescription()).isEqualTo(messages.get("help.body") + expectedSuffix);
        assertThat(embed.getDescription()).contains("Support Us", "https://example.com/donate");
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
                "/matches",
                "/meta",
                "/build",
                "/heroinfo",
                "/counter",
                "/heroes",
                "/mates",
                "/trends",
                "/nemesis",
                "/leaderboard server",
                "/items",
                "/compare",
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

        String withDonation = body + messages.get("help.donate", "Support Us", "https://example.com/donate");
        assertThat(withDonation.length()).isLessThanOrEqualTo(2000);
    }
}
