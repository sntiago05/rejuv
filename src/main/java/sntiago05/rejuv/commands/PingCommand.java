package sntiago05.rejuv.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;

@Component
public class PingCommand implements SlashCommand {

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), "Check the bot gateway latency");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.reply("Pong! " + event.getJDA().getGatewayPing() + " ms")
                .setEphemeral(true)
                .queue();
    }
}
