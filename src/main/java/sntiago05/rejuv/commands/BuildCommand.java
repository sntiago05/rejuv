package sntiago05.rejuv.commands;

import java.util.ArrayList;
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
import sntiago05.rejuv.domain.BuildItem;
import sntiago05.rejuv.domain.BuildPhase;
import sntiago05.rejuv.domain.HeroBuild;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.BuildService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;

@Component
public class BuildCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(BuildCommand.class);

    private final BuildService buildService;
    private final Messages messages;
    private final AssetImages assetImages;
    private final IconCatalog iconCatalog;

    public BuildCommand(BuildService buildService, Messages messages,
                        AssetImages assetImages, IconCatalog iconCatalog) {
        this.buildService = buildService;
        this.messages = messages;
        this.assetImages = assetImages;
        this.iconCatalog = iconCatalog;
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("build.description"))
                .addOptions(CommandSupport.heroOption(messages.get("build.option.hero")), CommandSupport.rankOption(messages));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        answer(event.getHook(), event.getUser().getIdLong(), CommandSupport.getHeroInput(event), CommandSupport.getRankTier(event));
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
            CommandSupport.handleUnexpectedError(hook, messages, log, "Build", callerId,
                    "heroInput=" + heroInput + " rankTier=" + rankTier, ex);
        }
    }

    private MessageEmbed buildEmbed(HeroBuild build) {
        String rankLabel = CommandSupport.rankLabel(messages, build.rankTier());

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("build.title", build.heroName(), rankLabel));

        assetImages.heroCardUrl(build.heroId()).ifPresent(builder::setThumbnail);

        for (BuildPhase phase : build.phases()) {
            String fieldName = messages.get("build.phase", String.valueOf(phase.column() + 1));
            List<String> itemLines = phase.items().stream()
                    .map(this::itemLine)
                    .toList();
            builder.addField(fieldName, EmbedStyle.fitField(itemLines), false);
        }

        if (!build.abilityOrder().isEmpty()) {
            List<String> steps = new ArrayList<>(build.abilityOrder().size());
            for (int i = 0; i < build.abilityOrder().size(); i++) {
                steps.add(stepLabel(i, build.abilityOrder().get(i)));
            }
            String separator = messages.get("build.ability_separator");
            String joined = String.join(separator, steps);
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

    private static final String[] STEP_BADGES = {
            "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"
    };

    private static String stepLabel(int index, String abilityName) {
        String badge = index < STEP_BADGES.length ? STEP_BADGES[index] : "[" + (index + 1) + "]";
        return badge + " " + abilityName;
    }

    private String itemLine(BuildItem item) {
        String itemName = item.itemName() != null
                ? item.itemName()
                : messages.get("build.item_unknown", String.valueOf(item.itemId()));
        String pickRate = CommandSupport.oneDecimal(item.pickRatePercent());
        String winRate = CommandSupport.oneDecimal(item.winRatePercent());
        String text = messages.get("build.item_entry", itemName, pickRate, winRate);
        return EmbedStyle.withIcon(iconCatalog.item(item.itemId()), text);
    }
}
