package sntiago05.rejuv.commands;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.BuildItem;
import sntiago05.rejuv.domain.BuildPhase;
import sntiago05.rejuv.domain.HeroBuild;
import sntiago05.rejuv.services.BuildService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.Messages;

@Component
public class BuildCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(BuildCommand.class);
    private static final String HERO_OPTION = "hero";
    private static final String RANK_OPTION = "rank";

    private final BuildService buildService;
    private final Messages messages;

    public BuildCommand(BuildService buildService, Messages messages) {
        this.buildService = buildService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public SlashCommandData data() {
        OptionData heroOption = new OptionData(OptionType.STRING, HERO_OPTION, messages.get("build.option.hero"), true)
                .setMaxLength(50);
        OptionData rankOption = new OptionData(OptionType.INTEGER, RANK_OPTION, messages.get("meta.option.rank"), false);
        for (int tier = 1; tier <= 11; tier++) {
            rankOption.addChoice(messages.get("meta.rank." + tier), tier);
        }
        return Commands.slash(name(), messages.get("build.description"))
                .addOptions(heroOption, rankOption);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        OptionMapping heroOpt = event.getOption(HERO_OPTION);
        String heroInput = heroOpt != null ? heroOpt.getAsString() : "";
        OptionMapping rankOpt = event.getOption(RANK_OPTION);
        Integer rankTier = rankOpt != null ? rankOpt.getAsInt() : null;

        answer(event.getHook(), event.getUser().getIdLong(), heroInput, rankTier);
    }

    private void answer(InteractionHook hook, long callerId, String heroInput, Integer rankTier) {
        try {
            HeroBuild build = buildService.getBuild(heroInput, rankTier);
            if (build.phases().isEmpty() && build.abilityOrder().isEmpty()) {
                hook.editOriginal(messages.get("build.no_data")).queue();
                return;
            }
            hook.editOriginalEmbeds(buildEmbed(build)).queue();
        } catch (HeroNotFoundException ex) {
            hook.editOriginal(messages.get("build.hero_not_found")).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            String errorId = UUID.randomUUID().toString();
            log.error("Build failed errorId={} userId={} heroInput={} rankTier={}", errorId, callerId, heroInput, rankTier, ex);
            hook.editOriginal(messages.get("error.generic", errorId)).queue();
        }
    }

    private MessageEmbed buildEmbed(HeroBuild build) {
        String rankLabel = build.rankTier() != null
                ? messages.get("meta.rank." + build.rankTier())
                : messages.get("meta.all_ranks");

        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(messages.get("build.title", build.heroName(), rankLabel));

        for (BuildPhase phase : build.phases()) {
            String fieldName = messages.get("build.phase", String.valueOf(phase.column() + 1));
            String fieldValue = phase.items().stream()
                    .map(this::itemLine)
                    .collect(Collectors.joining("\n"));
            builder.addField(fieldName, fieldValue, false);
        }

        if (!build.abilityOrder().isEmpty()) {
            String separator = messages.get("build.ability_separator");
            String joined = String.join(separator, build.abilityOrder());
            String matchesLine = "\n" + messages.get("build.ability_matches", String.valueOf(build.abilityOrderMatches()));
            int maxJoinedLength = 1024 - matchesLine.length();
            if (joined.length() > maxJoinedLength) {
                joined = joined.substring(0, Math.max(0, maxJoinedLength));
            }
            String fieldValue = joined + matchesLine;
            builder.addField(messages.get("build.abilities"), fieldValue, false);
        }

        builder.setFooter(messages.get("build.footer", String.valueOf(BuildService.MIN_ITEM_MATCHES)));
        return builder.build();
    }

    private String itemLine(BuildItem item) {
        String itemName = item.itemName() != null
                ? item.itemName()
                : messages.get("build.item_unknown", String.valueOf(item.itemId()));
        String pickRate = String.format(Locale.ROOT, "%.1f", item.pickRatePercent());
        String winRate = String.format(Locale.ROOT, "%.1f", item.winRatePercent());
        return messages.get("build.item_entry", itemName, pickRate, winRate);
    }
}
