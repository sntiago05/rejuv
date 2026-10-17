package sntiago05.rejuv.commands;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public interface SlashCommand {

    String name();

    SlashCommandData data();

    void handle(SlashCommandInteractionEvent event);

    default void autocomplete(CommandAutoCompleteInteractionEvent event) { }
}
