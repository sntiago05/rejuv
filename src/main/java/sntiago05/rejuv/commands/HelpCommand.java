package sntiago05.rejuv.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.Messages;

@Component
public class HelpCommand implements SlashCommand {

    private final Messages messages;

    public HelpCommand(Messages messages) {
        this.messages = messages;
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
        MessageEmbed embed = new EmbedBuilder()
                .setTitle(messages.get("help.title"))
                .setDescription(messages.get("help.body"))
                .setColor(EmbedStyle.COLOR)
                .build();

        event.replyEmbeds(embed)
                .setEphemeral(true)
                .queue();
    }
}
