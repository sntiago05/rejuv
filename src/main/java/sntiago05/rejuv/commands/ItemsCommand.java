package sntiago05.rejuv.commands;

import java.util.OptionalLong;

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
import sntiago05.rejuv.domain.ItemComparison;
import sntiago05.rejuv.domain.ItemsReport;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.ItemsService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.NoRankException;
import sntiago05.rejuv.services.ProfileService;

@Component
public class ItemsCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(ItemsCommand.class);

    private final ItemsService itemsService;
    private final ProfileService profileService;
    private final Messages messages;
    private final AssetImages assetImages;
    private final IconCatalog iconCatalog;

    public ItemsCommand(ItemsService itemsService,
                        ProfileService profileService,
                        Messages messages,
                        AssetImages assetImages,
                        IconCatalog iconCatalog) {
        this.itemsService = itemsService;
        this.profileService = profileService;
        this.messages = messages;
        this.assetImages = assetImages;
        this.iconCatalog = iconCatalog;
    }

    @Override
    public String name() {
        return "items";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("items.description"))
                .addOptions(CommandSupport.heroOption(messages.get("items.option.hero")), CommandSupport.rankOption(messages));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        long callerId = event.getUser().getIdLong();

        OptionalLong accountId = profileService.findLinkedAccountId(callerId);
        if (accountId.isEmpty()) {
            event.reply(messages.get("items.not_linked")).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), callerId, accountId.getAsLong(), CommandSupport.getHeroInput(event), CommandSupport.getRankTier(event));
    }

    private void answer(InteractionHook hook, long callerId, long accountId, String heroInput, Integer rankTier) {
        try {
            ItemsReport report = itemsService.compare(accountId, heroInput, rankTier);
            if (report.underperforming().isEmpty() && report.outperforming().isEmpty()) {
                log.info("Items command for callerId={} hero={} rank={} produced no underperforming/outperforming items, itemsConsidered={}",
                        callerId, heroInput, rankTier, report.itemsConsidered());
                hook.editOriginal(messages.get("items.no_data")).queue();
                return;
            }
            hook.editOriginalEmbeds(buildEmbed(report)).queue();
        } catch (HeroNotFoundException ex) {
            hook.editOriginal(messages.get("items.hero_not_found")).queue();
        } catch (NoRankException ex) {
            hook.editOriginal(messages.get("items.no_rank")).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Items", callerId,
                    "accountId=" + accountId + " hero=" + heroInput + " rank=" + rankTier, ex);
        }
    }

    private MessageEmbed buildEmbed(ItemsReport report) {
        String rankLabel = CommandSupport.rankLabel(messages, report.rankTier());

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("items.title", report.heroName(), rankLabel));

        assetImages.heroCardUrl(report.heroId()).ifPresent(builder::setThumbnail);

        EmbedStyle.addFittedField(builder, messages.get("items.field.underperforming"),
                report.underperforming().stream().map(this::itemLine).toList());
        EmbedStyle.addFittedField(builder, messages.get("items.field.outperforming"),
                report.outperforming().stream().map(this::itemLine).toList());

        builder.setFooter(messages.get("items.footer", ItemsService.MIN_PLAYER_MATCHES, ItemsService.MIN_META_MATCHES));
        return builder.build();
    }

    private String itemLine(ItemComparison item) {
        String signedDelta = (item.deltaPoints() > 0 ? "+" : "") + CommandSupport.oneDecimal(item.deltaPoints());
        String text = messages.get("items.entry",
                item.itemName(),
                CommandSupport.oneDecimal(item.playerWinRatePercent()),
                item.playerMatches(),
                CommandSupport.oneDecimal(item.metaWinRatePercent()),
                signedDelta);
        if (item.playerBuyMin() != null && item.metaBuyMin() != null) {
            text += messages.get("items.entry_buy_time",
                    CommandSupport.oneDecimal(item.playerBuyMin()),
                    CommandSupport.oneDecimal(item.metaBuyMin()));
        }
        return EmbedStyle.withIcon(iconCatalog.item(item.itemId()), text);
    }
}
