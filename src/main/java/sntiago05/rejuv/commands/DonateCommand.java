package sntiago05.rejuv.commands;

import java.util.Objects;
import java.util.Optional;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.DonationService;
import sntiago05.rejuv.services.Messages;

@Component
public class DonateCommand implements SlashCommand {

    private final Messages messages;
    private final DonationService donationService;

    public DonateCommand(Messages messages, DonationService donationService) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.donationService = Objects.requireNonNull(donationService, "donationService must not be null");
    }

    @Override
    public String name() {
        return "donate";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("donate.description"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Optional<DonationService.Donation> donation = donationService.get();
        if (donation.isPresent()) {
            MessageEmbed embed = new EmbedBuilder()
                    .setTitle(messages.get("donate.title"))
                    .setDescription(messages.get("donate.body"))
                    .setColor(EmbedStyle.COLOR)
                    .build();

            event.replyEmbeds(embed)
                    .addActionRow(Button.link(donation.get().url(), donation.get().label()))
                    .setEphemeral(true)
                    .queue();
        } else {
            event.reply(messages.get("donate.disabled"))
                    .setEphemeral(true)
                    .queue();
        }
    }
}
