package sntiago05.rejuv.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.Messages;

@Component
public class PingCommand implements SlashCommand {

    private final Messages messages;

    public PingCommand(Messages messages) {
        this.messages = messages;
    }

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("ping.description"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.reply(messages.get("ping.reply", String.valueOf(event.getJDA().getGatewayPing())))
                .setEphemeral(true)
                .queue();
    }
}
