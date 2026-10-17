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
import sntiago05.rejuv.domain.CounterGuide;
import sntiago05.rejuv.domain.CounterItem;
import sntiago05.rejuv.domain.CounterMatchup;
import sntiago05.rejuv.services.CounterService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.Messages;

@Component
public class CounterCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(CounterCommand.class);
    private static final String HERO_OPTION = "hero";
    private static final String RANK_OPTION = "rank";
    private static final String MIN_MATCHUP_MATCHES = "50";
    private static final String MIN_ITEM_MATCHES = "100";

    private final CounterService counterService;
    private final Messages messages;

    public CounterCommand(CounterService counterService, Messages messages) {
        this.counterService = counterService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "counter";
    }

    @Override
    public SlashCommandData data() {
        OptionData heroOption = new OptionData(OptionType.STRING, HERO_OPTION, messages.get("counter.option.hero"), true)
                .setMaxLength(50);
        OptionData rankOption = new OptionData(OptionType.INTEGER, RANK_OPTION, messages.get("meta.option.rank"), false);
        for (int tier = 1; tier <= 11; tier++) {
            rankOption.addChoice(messages.get("meta.rank." + tier), tier);
        }
        return Commands.slash(name(), messages.get("counter.description"))
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
            CounterGuide guide = counterService.getCounterGuide(heroInput, rankTier);
            if (guide.counters().isEmpty() && guide.favorable().isEmpty() && guide.items().isEmpty()) {
                hook.editOriginal(messages.get("counter.no_data")).queue();
                return;
            }
            hook.editOriginalEmbeds(buildEmbed(guide)).queue();
        } catch (HeroNotFoundException ex) {
            hook.editOriginal(messages.get("counter.hero_not_found")).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            String errorId = UUID.randomUUID().toString();
            log.error("Counter failed errorId={} userId={} heroInput={} rankTier={}",
                    errorId, callerId, heroInput, rankTier, ex);
            hook.editOriginal(messages.get("error.generic", errorId)).queue();
        }
    }

    private MessageEmbed buildEmbed(CounterGuide guide) {
        String rankLabel = guide.rankTier() != null
                ? messages.get("meta.rank." + guide.rankTier())
                : messages.get("meta.all_ranks");

        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(messages.get("counter.title", guide.heroName(), rankLabel));

        if (!guide.counters().isEmpty()) {
            String value = guide.counters().stream()
                    .map(this::matchupLine)
                    .collect(Collectors.joining("\n"));
            builder.addField(messages.get("counter.field.counters"), value, false);
        }

        if (!guide.items().isEmpty()) {
            String value = guide.items().stream()
                    .map(this::itemLine)
                    .collect(Collectors.joining("\n"));
            builder.addField(messages.get("counter.field.items"), value, false);
        }

        if (!guide.favorable().isEmpty()) {
            String value = guide.favorable().stream()
                    .map(this::matchupLine)
                    .collect(Collectors.joining("\n"));
            builder.addField(messages.get("counter.field.favorable"), value, false);
        }

        builder.setFooter(messages.get("counter.footer", MIN_MATCHUP_MATCHES, MIN_ITEM_MATCHES));

        return builder.build();
    }

    private String matchupLine(CounterMatchup matchup) {
        String heroName = matchup.heroName() != null
                ? matchup.heroName()
                : messages.get("counter.hero_unknown", String.valueOf(matchup.heroId()));
        String winRate = String.format(Locale.ROOT, "%.1f", matchup.winRatePercent());
        return messages.get("counter.matchup_entry", heroName, winRate);
    }

    private String itemLine(CounterItem item) {
        String winRate = String.format(Locale.ROOT, "%.1f", item.winRatePercent());
        return messages.get("counter.item_entry", item.itemName(), winRate);
    }
}
