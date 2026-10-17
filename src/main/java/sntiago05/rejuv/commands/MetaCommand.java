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
import sntiago05.rejuv.domain.HeroTier;
import sntiago05.rejuv.domain.MetaHero;
import sntiago05.rejuv.domain.MetaTierList;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MetaService;

@Component
public class MetaCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(MetaCommand.class);
    private static final String RANK_OPTION = "rank";

    private final MetaService metaService;
    private final Messages messages;

    public MetaCommand(MetaService metaService, Messages messages) {
        this.metaService = metaService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "meta";
    }

    @Override
    public SlashCommandData data() {
        OptionData rankOption = new OptionData(OptionType.INTEGER, RANK_OPTION, messages.get("meta.option.rank"), false);
        for (int tier = 1; tier <= 11; tier++) {
            rankOption.addChoice(messages.get("meta.rank." + tier), tier);
        }
        return Commands.slash(name(), messages.get("meta.description"))
                .addOptions(rankOption);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        OptionMapping option = event.getOption(RANK_OPTION);
        Integer rankTier = option != null ? option.getAsInt() : null;

        answer(event.getHook(), event.getUser().getIdLong(), rankTier);
    }

    private void answer(InteractionHook hook, long callerId, Integer rankTier) {
        try {
            MetaTierList tierList = metaService.getTierList(rankTier);
            if (tierList.heroes().isEmpty()) {
                hook.editOriginal(messages.get("meta.no_data")).queue();
                return;
            }
            hook.editOriginalEmbeds(buildEmbed(tierList)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            String errorId = UUID.randomUUID().toString();
            log.error("Meta failed errorId={} userId={} rankTier={}", errorId, callerId, rankTier, ex);
            hook.editOriginal(messages.get("error.generic", errorId)).queue();
        }
    }

    private MessageEmbed buildEmbed(MetaTierList tierList) {
        String rankLabel = tierList.rankTier() != null
                ? messages.get("meta.rank." + tierList.rankTier())
                : messages.get("meta.all_ranks");

        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(messages.get("meta.title", rankLabel));

        for (HeroTier tier : HeroTier.values()) {
            var heroesInTier = tierList.heroes().stream()
                    .filter(hero -> hero.tier() == tier)
                    .toList();
            if (heroesInTier.isEmpty()) {
                continue;
            }
            String fieldValue = heroesInTier.stream()
                    .map(this::heroLine)
                    .collect(Collectors.joining("\n"));
            builder.addField(messages.get("meta.tier." + tier.name().toLowerCase(Locale.ROOT)), fieldValue, false);
        }

        builder.setFooter(messages.get("meta.footer", String.valueOf(tierList.minMatches())));
        return builder.build();
    }

    private String heroLine(MetaHero hero) {
        String heroName = hero.heroName() != null
                ? hero.heroName()
                : messages.get("meta.hero_unknown", String.valueOf(hero.heroId()));
        String winRate = String.format(Locale.ROOT, "%.1f", hero.winRatePercent());
        return messages.get("meta.hero_entry", heroName, winRate);
    }
}
