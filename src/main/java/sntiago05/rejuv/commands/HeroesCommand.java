package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.HeroPerformance;
import sntiago05.rejuv.domain.HeroPoolSummary;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.HeroPoolService;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.ProfileService;

@Component
public class HeroesCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(HeroesCommand.class);
    private static final String USER_OPTION = "user";

    private final ProfileService profileService;
    private final HeroPoolService heroPoolService;
    private final Messages messages;
    private final AssetImages assetImages;
    private final IconCatalog iconCatalog;

    public HeroesCommand(ProfileService profileService, HeroPoolService heroPoolService, Messages messages,
                         AssetImages assetImages, IconCatalog iconCatalog) {
        this.profileService = profileService;
        this.heroPoolService = heroPoolService;
        this.messages = messages;
        this.assetImages = assetImages;
        this.iconCatalog = iconCatalog;
    }

    @Override
    public String name() {
        return "heroes";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("heroes.description"))
                .addOption(OptionType.USER, USER_OPTION, messages.get("heroes.option.user"), false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        User caller = event.getUser();
        OptionMapping option = event.getOption(USER_OPTION);
        User target = option == null ? caller : option.getAsUser();

        OptionalLong accountId = profileService.findLinkedAccountId(target.getIdLong());
        if (accountId.isEmpty()) {
            String text = target.getIdLong() == caller.getIdLong()
                    ? messages.get("heroes.not_linked_self")
                    : messages.get("heroes.not_linked_other", target.getAsMention());
            event.reply(text).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), caller.getIdLong(), target, accountId.getAsLong());
    }

    private void answer(InteractionHook hook, long callerId, User target, long accountId) {
        try {
            HeroPoolSummary summary = heroPoolService.getHeroPool(accountId);
            if (summary.topHeroes().isEmpty()) {
                hook.editOriginal(messages.get("heroes.no_matches")).queue();
                return;
            }
            hook.editOriginalEmbeds(embed(summary, target)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Heroes", callerId, "accountId=" + accountId, ex);
        }
    }

    private MessageEmbed embed(HeroPoolSummary summary, User target) {
        String topLines = EmbedStyle.fitField(summary.topHeroes().stream()
                .map(this::formatHeroEntry)
                .toList());

        List<String> tipLines = new ArrayList<>();
        if (summary.best() != null) {
            String bestName = summary.best().heroName() != null
                    ? summary.best().heroName()
                    : messages.get("heroes.hero_unknown", String.valueOf(summary.best().heroId()));
            tipLines.add(messages.get("heroes.tip_best", bestName,
                    String.format(Locale.ROOT, "%+.1f", summary.best().deltaPoints())));
        }
        if (summary.weakest() != null) {
            String weakestName = summary.weakest().heroName() != null
                    ? summary.weakest().heroName()
                    : messages.get("heroes.hero_unknown", String.valueOf(summary.weakest().heroId()));
            tipLines.add(messages.get("heroes.tip_weakest", weakestName,
                    String.format(Locale.ROOT, "%+.1f", summary.weakest().deltaPoints())));
        }
        String tipsText = tipLines.isEmpty() ? messages.get("heroes.tip_none") : String.join("\n", tipLines);

        String footer = messages.get("heroes.footer",
                CommandSupport.rankLabel(messages, summary.rankTier()),
                String.valueOf(HeroPoolService.MIN_BENCHMARK_MATCHES));

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("heroes.title", target.getEffectiveName()))
                .addField(messages.get("heroes.field.top"), topLines, false)
                .addField(messages.get("heroes.field.tips"), tipsText, false)
                .setFooter(footer);
        assetImages.heroCardUrl(summary.topHeroes().get(0).heroId()).ifPresent(builder::setThumbnail);
        return builder.build();
    }

    private String formatHeroEntry(HeroPerformance hero) {
        String heroName = hero.heroName() != null
                ? hero.heroName()
                : messages.get("heroes.hero_unknown", String.valueOf(hero.heroId()));
        String comparisonText = hero.deltaPoints() != null
                ? messages.get("heroes.vs_average", String.format(Locale.ROOT, "%+.1f", hero.deltaPoints()))
                : messages.get("heroes.vs_average_unknown");
        String kdaText = hero.heroAvgKda() != null
                ? messages.get("heroes.kda_vs_average", CommandSupport.oneDecimal(hero.kda()), CommandSupport.oneDecimal(hero.heroAvgKda()))
                : messages.get("heroes.kda", CommandSupport.oneDecimal(hero.kda()));

        String line = messages.get("heroes.entry",
                heroName,
                String.valueOf(hero.matches()),
                String.valueOf(Math.round(hero.winRatePercent())),
                comparisonText,
                kdaText);
        return EmbedStyle.withIcon(iconCatalog.hero(hero.heroId()), line);
    }
}
