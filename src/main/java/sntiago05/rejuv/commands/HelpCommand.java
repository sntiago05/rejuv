package sntiago05.rejuv.commands;

import java.util.Objects;
import java.util.Optional;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.DonationService;
import sntiago05.rejuv.services.Messages;

@Component
public class HelpCommand implements SlashCommand {

    private final Messages messages;
    private final DonationService donationService;

    public HelpCommand(Messages messages, DonationService donationService) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.donationService = Objects.requireNonNull(donationService, "donationService must not be null");
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("help.description"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String description = messages.get("help.body");
        Optional<DonationService.Donation> donation = donationService.get();
        if (donation.isPresent()) {
            description = description + messages.get("help.donate", donation.get().label(), donation.get().url());
        }

        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.get("help.title"))
                .setDescription(description)
                .setColor(EmbedStyle.COLOR)
                .build();

        event.replyEmbeds(embed)
                .setEphemeral(true)
                .queue();
    }
}
