package sntiago05.rejuv.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.domain.UsageStatus;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.UsageService;

@Component
public class UsageCommand implements SlashCommand {

    private final UsageService usageService;
    private final Messages messages;

    public UsageCommand(UsageService usageService, Messages messages) {
        this.usageService = usageService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("usage.description"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        UsageStatus status = usageService.status(event.getUser().getIdLong());
        String text;
        if (!status.limitsEnabled()) {
            text = messages.get("usage.disabled");
        } else if (status.premium()) {
            text = messages.get("usage.premium");
        } else {
            String resetTimestamp = "<t:" + status.resetsAt().getEpochSecond() + ":R>";
            text = messages.get("usage.status", status.used(), status.limit(), resetTimestamp);
        }
        event.reply(text).setEphemeral(true).queue();
    }
}
