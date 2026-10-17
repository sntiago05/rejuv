package sntiago05.rejuv.commands;

import java.util.List;

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
import sntiago05.rejuv.domain.CounterGuide;
import sntiago05.rejuv.domain.CounterItem;
import sntiago05.rejuv.domain.CounterMatchup;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.CounterService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;

@Component
public class CounterCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(CounterCommand.class);

    private final CounterService counterService;
    private final Messages messages;
    private final AssetImages assetImages;
    private final IconCatalog iconCatalog;

    public CounterCommand(CounterService counterService, Messages messages,
                          AssetImages assetImages, IconCatalog iconCatalog) {
        this.counterService = counterService;
        this.messages = messages;
        this.assetImages = assetImages;
        this.iconCatalog = iconCatalog;
    }

    @Override
    public String name() {
        return "counter";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("counter.description"))
                .addOptions(CommandSupport.heroOption(messages.get("counter.option.hero")), CommandSupport.rankOption(messages));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        answer(event.getHook(), event.getUser().getIdLong(), CommandSupport.getHeroInput(event), CommandSupport.getRankTier(event));
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
            CommandSupport.handleUnexpectedError(hook, messages, log, "Counter", callerId,
                    "heroInput=" + heroInput + " rankTier=" + rankTier, ex);
        }
    }

    private MessageEmbed buildEmbed(CounterGuide guide) {
        String rankLabel = CommandSupport.rankLabel(messages, guide.rankTier());

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("counter.title", guide.heroName(), rankLabel));

        assetImages.heroCardUrl(guide.heroId()).ifPresent(builder::setThumbnail);

        if (!guide.counters().isEmpty()) {
            List<String> counterLines = guide.counters().stream()
                    .map(this::matchupLine)
                    .toList();
            String value = EmbedStyle.fitField(counterLines);
            if (!value.isEmpty()) {
                builder.addField(messages.get("counter.field.counters"), value, false);
            }
        }

        if (!guide.items().isEmpty()) {
            List<String> itemLines = guide.items().stream()
                    .map(this::itemLine)
                    .toList();
            String value = EmbedStyle.fitField(itemLines);
            if (!value.isEmpty()) {
                builder.addField(messages.get("counter.field.items"), value, false);
            }
        }

        if (!guide.favorable().isEmpty()) {
            List<String> favorableLines = guide.favorable().stream()
                    .map(this::matchupLine)
                    .toList();
            String value = EmbedStyle.fitField(favorableLines);
            if (!value.isEmpty()) {
                builder.addField(messages.get("counter.field.favorable"), value, false);
            }
        }

        builder.setFooter(messages.get("counter.footer", String.valueOf(CounterService.MIN_MATCHUP_MATCHES),
                String.valueOf(CounterService.MIN_ITEM_MATCHES)));

        return builder.build();
    }

    private String matchupLine(CounterMatchup matchup) {
        String heroName = matchup.heroName() != null
                ? matchup.heroName()
                : messages.get("counter.hero_unknown", String.valueOf(matchup.heroId()));
        String winRate = CommandSupport.oneDecimal(matchup.winRatePercent());
        String text = messages.get("counter.matchup_entry", heroName, winRate);
        return EmbedStyle.withIcon(iconCatalog.hero(matchup.heroId()), text);
    }

    private String itemLine(CounterItem item) {
        String winRate = CommandSupport.oneDecimal(item.winRatePercent());
        String text = messages.get("counter.item_entry", item.itemName(), winRate);
        return EmbedStyle.withIcon(iconCatalog.item(item.itemId()), text);
    }
}
