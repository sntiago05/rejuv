package sntiago05.rejuv.commands;

import java.util.Map;
import java.util.Objects;

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
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.SynergyService;
import sntiago05.rejuv.services.SynergyService.Partner;
import sntiago05.rejuv.services.SynergyService.SynergyResult;

@Component
public class SynergyCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(SynergyCommand.class);

    private final SynergyService synergyService;
    private final HeroResolver heroResolver;
    private final GameAssets gameAssets;
    private final Messages messages;
    private final AssetImages assetImages;
    private final IconCatalog iconCatalog;

    public SynergyCommand(SynergyService synergyService, HeroResolver heroResolver,
                          GameAssets gameAssets, Messages messages,
                          AssetImages assetImages, IconCatalog iconCatalog) {
        this.synergyService = Objects.requireNonNull(synergyService, "synergyService must not be null");
        this.heroResolver = Objects.requireNonNull(heroResolver, "heroResolver must not be null");
        this.gameAssets = Objects.requireNonNull(gameAssets, "gameAssets must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.assetImages = Objects.requireNonNull(assetImages, "assetImages must not be null");
        this.iconCatalog = Objects.requireNonNull(iconCatalog, "iconCatalog must not be null");
    }

    @Override
    public String name() {
        return "synergy";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("synergy.description"))
                .addOptions(CommandSupport.heroOption(messages.get("synergy.option.hero")),
                        CommandSupport.rankOption(messages));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        answer(event.getHook(), event.getUser().getIdLong(),
                CommandSupport.getHeroInput(event), CommandSupport.getRankTier(event));
    }

    private void answer(InteractionHook hook, long callerId, String heroInput, Integer rankTier) {
        try {
            var heroOpt = heroResolver.resolve(heroInput);
            if (heroOpt.isEmpty()) {
                hook.editOriginal(messages.get("counter.hero_not_found")).queue();
                return;
            }
            HeroAsset targetHero = heroOpt.get();
            SynergyResult result = synergyService.forHero(targetHero.id(), rankTier);
            if (result.best().isEmpty() && result.worst().isEmpty()) {
                hook.editOriginal(messages.get("synergy.no_data")).queue();
                return;
            }
            hook.editOriginalEmbeds(buildEmbed(result, targetHero, rankTier)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Synergy", callerId,
                    "heroInput=" + heroInput + " rankTier=" + rankTier, ex);
        }
    }

    private MessageEmbed buildEmbed(SynergyResult result, HeroAsset targetHero, Integer rankTier) {
        String rankLabel = CommandSupport.rankLabel(messages, rankTier);
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("synergy.title", targetHero.name(), rankLabel));

        assetImages.heroCardUrl(targetHero.id()).ifPresent(builder::setThumbnail);

        Map<Integer, String> heroNames = gameAssets.heroNames();
        if (!result.best().isEmpty()) {
            EmbedStyle.addFittedField(builder, messages.get("synergy.field.best"),
                    result.best().stream().map(p -> partnerLine(p, heroNames)).toList());
        }
        if (!result.worst().isEmpty()) {
            EmbedStyle.addFittedField(builder, messages.get("synergy.field.worst"),
                    result.worst().stream().map(p -> partnerLine(p, heroNames)).toList());
        }

        builder.setFooter(messages.get("synergy.footer", String.valueOf(SynergyService.MIN_PAIR_MATCHES)));
        return builder.build();
    }

    private String partnerLine(Partner partner, Map<Integer, String> heroNames) {
        String heroName = heroNames.get(partner.heroId());
        if (heroName == null) {
            heroName = messages.get("synergy.hero_unknown", String.valueOf(partner.heroId()));
        }
        String winRate = CommandSupport.oneDecimal(partner.winRate() * 100.0);
        String text = messages.get("synergy.entry", heroName, winRate, String.valueOf(partner.matches()));
        return EmbedStyle.withIcon(iconCatalog.hero(partner.heroId()), text);
    }
}
