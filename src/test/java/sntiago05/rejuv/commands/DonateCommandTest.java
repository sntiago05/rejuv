package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.config.DonationProperties;
import sntiago05.rejuv.services.DonationService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class DonateCommandTest {

    private Messages messages;

    @BeforeEach
    void setUp() {
        messages = MessagesFixture.english();
    }

    @Test
    void nameIsDonate() {
        DonationService donationService = new DonationService(new DonationProperties(null, null), messages);
        DonateCommand command = new DonateCommand(messages, donationService);
        assertThat(command.name()).isEqualTo("donate");
    }

    @Test
    void dataUsesCatalogDescriptionAndHasNoOptions() {
        DonationService donationService = new DonationService(new DonationProperties(null, null), messages);
        DonateCommand command = new DonateCommand(messages, donationService);
        SlashCommandData data = command.data();

        assertThat(data.getName()).isEqualTo("donate");
        assertThat(data.getDescription()).isEqualTo(messages.get("donate.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).isEmpty();
    }

    @Test
    void handleWithLinkRepliesEphemerallyWithEmbedAndButton() {
        DonationService donationService = new DonationService(
                new DonationProperties("https://example.com/donate", "Support Us"), messages);
        DonateCommand command = new DonateCommand(messages, donationService);

        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        ArgumentCaptor<ItemComponent> componentCaptor = ArgumentCaptor.forClass(ItemComponent.class);

        when(event.replyEmbeds(embedCaptor.capture())).thenReturn(reply);
        when(reply.addActionRow(componentCaptor.capture())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event, never()).deferReply();
        verify(event, never()).reply(any(String.class));
        verify(reply).setEphemeral(true);
        verify(reply).queue();

        MessageEmbed embed = embedCaptor.getValue();
        assertThat(embed.getTitle()).isEqualTo(messages.get("donate.title"));
        assertThat(embed.getDescription()).isEqualTo(messages.get("donate.body"));
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);

        ItemComponent component = componentCaptor.getValue();
        assertThat(component).isInstanceOf(Button.class);
        Button button = (Button) component;
        assertThat(button.getUrl()).isEqualTo("https://example.com/donate");
        assertThat(button.getLabel()).isEqualTo("Support Us");
    }

    @Test
    void handleWithoutLinkRepliesEphemerallyWithDisabledMessageAndNoButton() {
        DonationService donationService = new DonationService(
                new DonationProperties(null, null), messages);
        DonateCommand command = new DonateCommand(messages, donationService);

        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);

        when(event.reply(messages.get("donate.disabled"))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event, never()).deferReply();
        verify(event, never()).replyEmbeds(any(MessageEmbed.class));
        verify(event).reply(messages.get("donate.disabled"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(reply, never()).addActionRow(any(ItemComponent[].class));
    }
}
