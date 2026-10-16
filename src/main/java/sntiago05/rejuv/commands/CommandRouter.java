package sntiago05.rejuv.commands;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.config.BotProperties;
import sntiago05.rejuv.services.Messages;

@Component
public class CommandRouter extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final BotProperties botProperties;
    private final List<SlashCommand> commands;
    private final Map<String, SlashCommand> commandsByName;
    private final Messages messages;

    public CommandRouter(BotProperties botProperties, List<SlashCommand> commands, Messages messages) {
        this.botProperties = botProperties;
        this.commands = List.copyOf(commands);
        this.commandsByName = commands.stream()
                .collect(Collectors.toUnmodifiableMap(SlashCommand::name, Function.identity()));
        this.messages = messages;
    }

    @Override
    public void onReady(ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(botProperties.devGuildId());
        if (guild == null) {
            log.warn("Dev guild {} was not found; slash commands were not registered", botProperties.devGuildId());
            return;
        }

        List<SlashCommandData> commandData = commands.stream()
                .map(SlashCommand::data)
                .toList();
        guild.updateCommands().addCommands(commandData).queue();
        log.info("Registered {} slash commands in dev guild {}", commandData.size(), botProperties.devGuildId());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        long startedAt = System.nanoTime();
        String commandName = event.getName();
        SlashCommand command = commandsByName.get(commandName);
        if (command == null) {
            event.reply(messages.get("error.unknown_command")).setEphemeral(true).queue();
            log.warn("Unknown slash command userId={} command={}", event.getUser().getId(), commandName);
            log.info("Slash command userId={} command={} durationMs={} outcome=unknown", event.getUser().getId(), commandName,
                    durationMillis(startedAt));
            return;
        }

        try {
            command.handle(event);
            log.info("Slash command userId={} command={} durationMs={} outcome=success", event.getUser().getId(), commandName,
                    durationMillis(startedAt));
        } catch (RuntimeException ex) {
            String errorId = UUID.randomUUID().toString();
            log.error("Slash command failed errorId={} userId={} command={}", errorId, event.getUser().getId(), commandName, ex);
            event.reply(messages.get("error.generic", errorId)).setEphemeral(true).queue();
            log.info("Slash command userId={} command={} durationMs={} outcome=error", event.getUser().getId(), commandName,
                    durationMillis(startedAt));
        }
    }

    private long durationMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
