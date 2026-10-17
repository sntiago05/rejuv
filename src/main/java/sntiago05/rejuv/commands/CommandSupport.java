package sntiago05.rejuv.commands;

import java.util.UUID;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.slf4j.Logger;
import sntiago05.rejuv.services.Messages;

/**
 * Shared helpers for Discord slash command option builders, parameter extraction, and error replies.
 */
public final class CommandSupport {

    public static final String HERO_OPTION = "hero";
    public static final String RANK_OPTION = "rank";
    public static final int HERO_MAX_LENGTH = 50;
    public static final int MIN_RANK_TIER = 1;
    public static final int MAX_RANK_TIER = 11;

    private CommandSupport() {
    }

    public static String formatPercent(double percent) {
        return String.format(java.util.Locale.ROOT, "%.1f", percent);
    }

    public static OptionData heroOption(String description) {
        return new OptionData(OptionType.STRING, HERO_OPTION, description, true)
                .setMaxLength(HERO_MAX_LENGTH);
    }

    public static OptionData rankOption(Messages messages) {
        OptionData rank = new OptionData(OptionType.INTEGER, RANK_OPTION, messages.get("meta.option.rank"), false);
        for (int tier = MIN_RANK_TIER; tier <= MAX_RANK_TIER; tier++) {
            rank.addChoice(messages.get("meta.rank." + tier), tier);
        }
        return rank;
    }

    public static String getHeroInput(SlashCommandInteractionEvent event) {
        OptionMapping opt = event.getOption(HERO_OPTION);
        return opt != null ? opt.getAsString() : "";
    }

    public static Integer getRankTier(SlashCommandInteractionEvent event) {
        OptionMapping opt = event.getOption(RANK_OPTION);
        return opt != null ? opt.getAsInt() : null;
    }

    public static String rankLabel(Messages messages, Integer rankTier) {
        return rankTier != null ? messages.get("meta.rank." + rankTier) : messages.get("meta.all_ranks");
    }

    public static void handleUnexpectedError(InteractionHook hook, Messages messages, Logger log,
                                             String commandTitle, long callerId, String heroInput,
                                             Integer rankTier, RuntimeException ex) {
        String errorId = UUID.randomUUID().toString();
        log.error("{} failed errorId={} userId={} heroInput={} rankTier={}",
                commandTitle, errorId, callerId, heroInput, rankTier, ex);
        hook.editOriginal(messages.get("error.generic", errorId)).queue();
    }
}
