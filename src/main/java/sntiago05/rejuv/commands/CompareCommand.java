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
import sntiago05.rejuv.domain.CompareReport;
import sntiago05.rejuv.domain.MetricGap;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.CompareService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.NoMatchHistoryException;
import sntiago05.rejuv.services.NotEnoughGamesException;
import sntiago05.rejuv.services.PremiumFeatureGate;
import sntiago05.rejuv.services.ProfileService;

@Component
public class CompareCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(CompareCommand.class);

    private final CompareService compareService;
    private final ProfileService profileService;
    private final PremiumFeatureGate premiumFeatureGate;
    private final Messages messages;
    private final AssetImages assetImages;
    private final IconCatalog iconCatalog;

    public CompareCommand(CompareService compareService,
                          ProfileService profileService,
                          PremiumFeatureGate premiumFeatureGate,
                          Messages messages,
                          AssetImages assetImages,
                          IconCatalog iconCatalog) {
        this.compareService = compareService;
        this.profileService = profileService;
        this.premiumFeatureGate = premiumFeatureGate;
        this.messages = messages;
        this.assetImages = assetImages;
        this.iconCatalog = iconCatalog;
    }

    @Override
    public String name() {
        return "compare";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("compare.description"))
                .addOptions(CommandSupport.heroOption(messages.get("compare.option.hero")).setRequired(false));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        long callerId = event.getUser().getIdLong();

        OptionalLong accountId = profileService.findLinkedAccountId(callerId);
        if (accountId.isEmpty()) {
            event.reply(messages.get("compare.not_linked")).setEphemeral(true).queue();
            return;
        }

        if (!premiumFeatureGate.allowed(callerId)) {
            event.reply(messages.get("compare.premium_required")).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), callerId, accountId.getAsLong(), CommandSupport.getHeroInput(event));
    }

    private void answer(InteractionHook hook, long callerId, long accountId, String heroInput) {
        try {
            CompareReport report = compareService.compare(accountId, heroInput);
            if (report.gaps().isEmpty()) {
                log.info("Compare command for callerId={} hero={} produced no benchmark data", callerId, heroInput);
                hook.editOriginal(messages.get("compare.no_data")).queue();
                return;
            }
            hook.editOriginalEmbeds(buildEmbed(report)).queue();
        } catch (HeroNotFoundException ex) {
            hook.editOriginal(messages.get("compare.hero_not_found")).queue();
        } catch (NoMatchHistoryException ex) {
            hook.editOriginal(messages.get("compare.no_matches")).queue();
        } catch (NotEnoughGamesException ex) {
            hook.editOriginal(messages.get("compare.not_enough_games", ex.minRequired())).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Compare", callerId,
                    "accountId=" + accountId + " hero=" + heroInput, ex);
        }
    }

    private MessageEmbed buildEmbed(CompareReport report) {
        String rankLabel = CommandSupport.rankLabel(messages, report.playerRankTier());

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("compare.title", report.heroName(), report.games()));

        assetImages.heroCardUrl(report.heroId()).ifPresent(builder::setThumbnail);

        String fieldName = EmbedStyle.withIcon(iconCatalog.hero(report.heroId()), messages.get("compare.field.metrics"));
        EmbedStyle.addFittedField(builder, fieldName, report.gaps().stream().map(this::metricLine).toList());

        builder.setFooter(messages.get("compare.footer", rankLabel));
        return builder.build();
    }

    private String metricLine(MetricGap gap) {
        String metricLabel = messages.get("compare.metric." + gap.metricKey());
        String playerValue = CommandSupport.oneDecimal(gap.playerValue());
        String topMedian = CommandSupport.oneDecimal(gap.topP50());
        String signedPercent = (gap.gapPercent() > 0 ? "+" : "") + CommandSupport.oneDecimal(gap.gapPercent()) + "%";
        String line = messages.get("compare.line", metricLabel, playerValue, topMedian, signedPercent);
        if (gap.rankP50() != null) {
            line += messages.get("compare.line_rank", CommandSupport.oneDecimal(gap.rankP50()));
        }
        return line;
    }
}
