package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
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
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.ProfileService;
import sntiago05.rejuv.services.TrendsService;
import sntiago05.rejuv.services.TrendsService.HeroLine;
import sntiago05.rejuv.services.TrendsService.TrendsSummary;

@Component
public class TrendsCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(TrendsCommand.class);
    private static final String GAMES_OPTION = "games";
    private static final String USER_OPTION = "user";
    public static final int DEFAULT_GAMES = 20;

    private final ProfileService profileService;
    private final TrendsService trendsService;
    private final GameAssets gameAssets;
    private final IconCatalog iconCatalog;
    private final Messages messages;

    public TrendsCommand(ProfileService profileService,
                         TrendsService trendsService,
                         GameAssets gameAssets,
                         IconCatalog iconCatalog,
                         Messages messages) {
        this.profileService = profileService;
        this.trendsService = trendsService;
        this.gameAssets = gameAssets;
        this.iconCatalog = iconCatalog;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "trends";
    }

    @Override
    public SlashCommandData data() {
        OptionData gamesOption = new OptionData(OptionType.INTEGER, GAMES_OPTION, messages.get("trends.option.games"), false)
                .addChoice("10", 10)
                .addChoice("20", 20)
                .addChoice("50", 50);

        return Commands.slash(name(), messages.get("trends.description"))
                .addOptions(gamesOption)
                .addOption(OptionType.USER, USER_OPTION, messages.get("trends.option.user"), false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        User caller = event.getUser();
        OptionMapping userOption = event.getOption(USER_OPTION);
        User target = userOption == null ? caller : userOption.getAsUser();

        OptionalLong accountId = profileService.findLinkedAccountId(target.getIdLong());
        if (accountId.isEmpty()) {
            String text = target.getIdLong() == caller.getIdLong()
                    ? messages.get("trends.not_linked_self")
                    : messages.get("trends.not_linked_other", target.getAsMention());
            event.reply(text).setEphemeral(true).queue();
            return;
        }

        int games = DEFAULT_GAMES;
        OptionMapping gamesOpt = event.getOption(GAMES_OPTION);
        if (gamesOpt != null) {
            int selected = gamesOpt.getAsInt();
            if (selected == 10 || selected == 20 || selected == 50) {
                games = selected;
            }
        }

        event.deferReply().queue();
        answer(event.getHook(), caller.getIdLong(), target, accountId.getAsLong(), games);
    }

    private void answer(InteractionHook hook, long callerId, User target, long accountId, int games) {
        try {
            TrendsSummary summary = trendsService.summarize(accountId, games);
            if (summary.games() < TrendsService.MIN_GAMES_FOR_TREND) {
                hook.editOriginal(messages.get("trends.not_enough_games", String.valueOf(summary.games()))).queue();
                return;
            }
            hook.editOriginalEmbeds(embed(summary, target, accountId)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Trends", callerId, "accountId=" + accountId, ex);
        }
    }

    private MessageEmbed embed(TrendsSummary summary, User target, long accountId) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("trends.title", target.getEffectiveName()))
                .setFooter(messages.get("trends.footer", String.valueOf(accountId)));

        int wins = (int) Math.round(summary.winRate() * summary.games() / 100.0);
        String overview = messages.get("trends.overview_value",
                String.valueOf(Math.round(summary.winRate())),
                String.valueOf(wins),
                String.valueOf(summary.games()),
                CommandSupport.oneDecimal(summary.avgKills()),
                CommandSupport.oneDecimal(summary.avgDeaths()),
                CommandSupport.oneDecimal(summary.avgAssists()),
                CommandSupport.oneDecimal(summary.kdaRatio()),
                String.format(Locale.ROOT, "%,d", summary.avgNetWorth()));
        builder.addField(messages.get("trends.field.overview", String.valueOf(summary.games())), overview, false);

        String trendValue;
        long recentWinRateRound = Math.round(summary.winRateRecentHalf());
        long olderWinRateRound = Math.round(summary.winRateOlderHalf());
        double diff = summary.pointDifference();
        switch (summary.direction()) {
            case IMPROVING -> trendValue = messages.get("trends.trend_improving",
                    CommandSupport.oneDecimal(diff),
                    String.valueOf(recentWinRateRound),
                    String.valueOf(olderWinRateRound));
            case DECLINING -> trendValue = messages.get("trends.trend_declining",
                    CommandSupport.oneDecimal(diff),
                    String.valueOf(recentWinRateRound),
                    String.valueOf(olderWinRateRound));
            case STEADY -> {
                String signed = (diff > 0 ? "+" : "") + CommandSupport.oneDecimal(diff);
                trendValue = messages.get("trends.trend_steady",
                        signed,
                        String.valueOf(recentWinRateRound),
                        String.valueOf(olderWinRateRound));
            }
            default -> trendValue = "";
        }
        builder.addField(messages.get("trends.field.trend"), trendValue, false);

        if (summary.topHeroes().isEmpty()) {
            builder.addField(messages.get("trends.field.heroes"), messages.get("trends.no_top_heroes"), false);
        } else {
            Map<Integer, String> heroNames = gameAssets.heroNames();
            List<String> lines = new ArrayList<>();
            for (HeroLine hero : summary.topHeroes()) {
                String heroName = heroNames.getOrDefault(hero.heroId(),
                        messages.get("trends.hero_unknown", String.valueOf(hero.heroId())));
                String line = messages.get("trends.hero_entry",
                        heroName,
                        String.valueOf(hero.games()),
                        String.valueOf(Math.round(hero.winRate())));
                lines.add(EmbedStyle.withIcon(iconCatalog.hero(hero.heroId()), line));
            }
            builder.addField(messages.get("trends.field.heroes"), EmbedStyle.fitField(lines), false);
        }

        return builder.build();
    }
}
