package sntiago05.rejuv.commands;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
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
        return Commands.slash(name(), messages.get("meta.description"))
                .addOptions(CommandSupport.rankOption(messages));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        answer(event.getHook(), event.getUser().getIdLong(), CommandSupport.getRankTier(event));
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
            CommandSupport.handleUnexpectedError(hook, messages, log, "Meta", callerId, "rankTier=" + rankTier, ex);
        }
    }

    private MessageEmbed buildEmbed(MetaTierList tierList) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(messages.get("meta.title", CommandSupport.rankLabel(messages, tierList.rankTier())));

        // One pass over the heroes (already sorted); EnumMap iterates the tiers in S..D order.
        Map<HeroTier, StringJoiner> linesByTier = new EnumMap<>(HeroTier.class);
        for (MetaHero hero : tierList.heroes()) {
            linesByTier.computeIfAbsent(hero.tier(), tier -> new StringJoiner("\n")).add(heroLine(hero));
        }
        linesByTier.forEach((tier, lines) -> builder.addField(
                messages.get("meta.tier." + tier.name().toLowerCase(Locale.ROOT)), lines.toString(), false));

        builder.setFooter(messages.get("meta.footer", String.valueOf(tierList.minMatches())));
        return builder.build();
    }

    private String heroLine(MetaHero hero) {
        String heroName = hero.heroName() != null
                ? hero.heroName()
                : messages.get("meta.hero_unknown", String.valueOf(hero.heroId()));
        return messages.get("meta.hero_entry", heroName, CommandSupport.oneDecimal(hero.winRatePercent()));
    }
}
