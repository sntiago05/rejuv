package sntiago05.rejuv.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.LinkService;
import sntiago05.rejuv.services.Messages;

@Component
public class UnlinkCommand implements SlashCommand {

    private final LinkService linkService;
    private final Messages messages;

    public UnlinkCommand(LinkService linkService, Messages messages) {
        this.linkService = linkService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "unlink";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("unlink.description"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String key = linkService.unlink(event.getUser().getIdLong()) ? "unlink.success" : "unlink.not_linked";
        event.reply(messages.get(key)).setEphemeral(true).queue();
    }
}
