package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchSummary;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.MatchSearchService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.ProfileService;

@Component
public class MatchesCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(MatchesCommand.class);

    private final MatchSearchService matchSearchService;
    private final MatchAnalysisRepository matchAnalysisRepository;
    private final ProfileService profileService;
    private final Messages messages;
    private final GameAssets gameAssets;
    private final IconCatalog iconCatalog;
    private final HeroResolver heroResolver;

    public MatchesCommand(MatchSearchService matchSearchService,
                          MatchAnalysisRepository matchAnalysisRepository,
                          ProfileService profileService,
                          Messages messages,
                          GameAssets gameAssets,
                          IconCatalog iconCatalog,
                          HeroResolver heroResolver) {
        this.matchSearchService = matchSearchService;
        this.matchAnalysisRepository = matchAnalysisRepository;
        this.profileService = profileService;
        this.messages = messages;
        this.gameAssets = gameAssets;
        this.iconCatalog = iconCatalog;
        this.heroResolver = heroResolver;
    }

    @Override
    public String name() {
        return "matches";
    }

    @Override
    public SlashCommandData data() {
        OptionData heroOption = new OptionData(OptionType.STRING, "hero", messages.get("matches.option.hero"), false)
                .setAutoComplete(true);
        OptionData resultOption = new OptionData(OptionType.STRING, "result", messages.get("matches.option.result"), false)
                .addChoice(messages.get("matches.result.choice.win"), "win")
                .addChoice(messages.get("matches.result.choice.loss"), "loss");
        OptionData userOption = new OptionData(OptionType.USER, "user", messages.get("matches.option.user"), false);

        return Commands.slash(name(), messages.get("matches.description"))
                .addOptions(heroOption, resultOption, userOption);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        OptionMapping userOpt = event.getOption("user");
        User target = userOpt != null ? userOpt.getAsUser() : event.getUser();
        boolean isSelf = target.getIdLong() == event.getUser().getIdLong();

        OptionalLong accountIdOpt = profileService.findLinkedAccountId(target.getIdLong());
        if (accountIdOpt.isEmpty()) {
            String msg = isSelf
                    ? messages.get("matches.not_linked_self")
                    : messages.get("matches.not_linked_other", target.getEffectiveName());
            event.reply(msg).setEphemeral(true).queue();
            return;
        }

        Integer heroId = null;
        OptionMapping heroOpt = event.getOption("hero");
        if (heroOpt != null && !heroOpt.getAsString().isBlank()) {
            Optional<HeroAsset> hero = heroResolver.resolve(heroOpt.getAsString());
            heroId = hero.map(HeroAsset::id).orElse(-1);
        }

        Boolean win = null;
        OptionMapping resultOpt = event.getOption("result");
        if (resultOpt != null) {
            if ("win".equalsIgnoreCase(resultOpt.getAsString())) {
                win = Boolean.TRUE;
            } else if ("loss".equalsIgnoreCase(resultOpt.getAsString())) {
                win = Boolean.FALSE;
            }
        }

        MatchFilter filter = (heroId == null && win == null) ? MatchFilter.LATEST : new MatchFilter(null, heroId, win);

        event.deferReply().queue();
        answer(event.getHook(), event.getUser().getIdLong(), accountIdOpt.getAsLong(), target, filter);
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        try {
            if ("hero".equals(event.getFocusedOption().getName())) {
                autocompleteHero(event);
            } else {
                event.replyChoices(List.of()).queue();
            }
        } catch (RuntimeException ex) {
            event.replyChoices(List.of()).queue();
        }
    }

    private void autocompleteHero(CommandAutoCompleteInteractionEvent event) {
        String query = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = gameAssets.heroNames().values().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(query))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(25)
                .map(name -> new Command.Choice(name, name))
                .toList();
        event.replyChoices(choices).queue();
    }

    private void answer(InteractionHook hook, long callerId, long accountId, User target, MatchFilter filter) {
        try {
            List<MatchSummary> matches = matchSearchService.find(accountId, filter, 10);
            if (matches.isEmpty()) {
                List<MatchSummary> unfiltered = (filter == MatchFilter.LATEST)
                        ? matches
                        : matchSearchService.find(accountId, MatchFilter.LATEST, 1);
                if (unfiltered.isEmpty()) {
                    hook.editOriginal(messages.get("matches.no_history")).queue();
                } else {
                    hook.editOriginal(messages.get("matches.no_results")).queue();
                }
                return;
            }

            List<Long> matchIds = matches.stream().map(MatchSummary::matchId).toList();
            Set<Long> analyzedIds = Set.copyOf(matchAnalysisRepository.findAnalyzedMatchIds(accountId, matchIds));

            hook.editOriginalEmbeds(buildEmbed(target, matches, analyzedIds)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Matches", callerId, "accountId=" + accountId, ex);
        }
    }

    private MessageEmbed buildEmbed(User target, List<MatchSummary> matches, Set<Long> analyzedIds) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("matches.title", target.getEffectiveName()));

        List<String> lines = new ArrayList<>(matches.size());
        for (MatchSummary m : matches) {
            String heroName = m.heroName() != null ? m.heroName() : "#" + m.heroId();
            String heroWithIcon = EmbedStyle.withIcon(iconCatalog.hero(m.heroId()), heroName);
            String resultText = m.win()
                    ? messages.get("matches.result.choice.win")
                    : messages.get("matches.result.choice.loss");
            String kda = m.kills() + "/" + m.deaths() + "/" + m.assists();
            String duration = formatDuration(m.durationS());
            String entry = messages.get("matches.entry",
                    "<t:" + m.startTimeEpochS() + ":d>",
                    heroWithIcon,
                    resultText,
                    kda,
                    duration,
                    String.valueOf(m.matchId()));
            if (analyzedIds.contains(m.matchId())) {
                entry += messages.get("matches.analyzed_marker");
            }
            lines.add(entry);
        }

        builder.setDescription(EmbedStyle.fitField(lines));
        builder.setFooter(messages.get("matches.footer"));
        return builder.build();
    }

    private static String formatDuration(int durationS) {
        return String.format(Locale.ROOT, "%d:%02d", durationS / 60, durationS % 60);
    }
}
