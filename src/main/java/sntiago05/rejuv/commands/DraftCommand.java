package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.services.DraftService;
import sntiago05.rejuv.services.DraftService.DraftRequest;
import sntiago05.rejuv.services.DraftService.DraftResult;
import sntiago05.rejuv.services.DraftService.PickSuggestion;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;

@Component
public class DraftCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(DraftCommand.class);

    public static final String ALLIES_OPTION = "allies";
    public static final String ENEMIES_OPTION = "enemies";
    static final int MAX_ALLIES_INPUT = 5;
    static final int MAX_ENEMIES_INPUT = 6;
    private static final int BAR_SEGMENTS = 10;

    private final DraftService draftService;
    private final HeroResolver heroResolver;
    private final GameAssets gameAssets;
    private final Messages messages;
    private final IconCatalog iconCatalog;

    public DraftCommand(DraftService draftService, HeroResolver heroResolver,
                        GameAssets gameAssets, Messages messages,
                        IconCatalog iconCatalog) {
        this.draftService = Objects.requireNonNull(draftService, "draftService must not be null");
        this.heroResolver = Objects.requireNonNull(heroResolver, "heroResolver must not be null");
        this.gameAssets = Objects.requireNonNull(gameAssets, "gameAssets must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.iconCatalog = Objects.requireNonNull(iconCatalog, "iconCatalog must not be null");
    }

    @Override
    public String name() {
        return "draft";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("draft.description"))
                .addOptions(
                        new OptionData(OptionType.STRING, ALLIES_OPTION, messages.get("draft.option.allies"), true),
                        new OptionData(OptionType.STRING, ENEMIES_OPTION, messages.get("draft.option.enemies"), true),
                        CommandSupport.rankOption(messages)
                );
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        OptionMapping alliesOpt = event.getOption(ALLIES_OPTION);
        OptionMapping enemiesOpt = event.getOption(ENEMIES_OPTION);
        String alliesInput = alliesOpt != null ? alliesOpt.getAsString() : "";
        String enemiesInput = enemiesOpt != null ? enemiesOpt.getAsString() : "";
        Integer rankTier = CommandSupport.getRankTier(event);

        answer(event.getHook(), event.getUser().getIdLong(), alliesInput, enemiesInput, rankTier);
    }

    private void answer(InteractionHook hook, long callerId, String alliesInput, String enemiesInput, Integer rankTier) {
        try {
            List<String> allyTokens = parseTokens(alliesInput);
            if (allyTokens.size() > MAX_ALLIES_INPUT) {
                hook.editOriginal(messages.get("draft.error.too_many_allies")).queue();
                return;
            }

            List<String> enemyTokens = parseTokens(enemiesInput);
            if (enemyTokens.size() > MAX_ENEMIES_INPUT) {
                hook.editOriginal(messages.get("draft.error.too_many_enemies")).queue();
                return;
            }

            List<Integer> allyHeroIds = new ArrayList<>(allyTokens.size());
            for (String token : allyTokens) {
                Optional<HeroAsset> heroOpt = heroResolver.resolve(token);
                if (heroOpt.isEmpty()) {
                    hook.editOriginal(messages.get("counter.hero_not_found")).queue();
                    return;
                }
                allyHeroIds.add(heroOpt.get().id());
            }

            List<Integer> enemyHeroIds = new ArrayList<>(enemyTokens.size());
            for (String token : enemyTokens) {
                Optional<HeroAsset> heroOpt = heroResolver.resolve(token);
                if (heroOpt.isEmpty()) {
                    hook.editOriginal(messages.get("counter.hero_not_found")).queue();
                    return;
                }
                enemyHeroIds.add(heroOpt.get().id());
            }

            if (new HashSet<>(allyHeroIds).size() != allyHeroIds.size()) {
                hook.editOriginal(messages.get("draft.error.duplicate_hero")).queue();
                return;
            }
            if (new HashSet<>(enemyHeroIds).size() != enemyHeroIds.size()) {
                hook.editOriginal(messages.get("draft.error.duplicate_hero")).queue();
                return;
            }

            DraftResult result = draftService.estimate(new DraftRequest(allyHeroIds, enemyHeroIds, rankTier));
            hook.editOriginalEmbeds(buildEmbed(result, allyHeroIds, enemyHeroIds, rankTier)).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Draft", callerId,
                    "allies=" + alliesInput + " enemies=" + enemiesInput + " rankTier=" + rankTier, ex);
        }
    }

    private MessageEmbed buildEmbed(DraftResult result, List<Integer> allyHeroIds,
                                    List<Integer> enemyHeroIds, Integer rankTier) {
        String rankLabel = CommandSupport.rankLabel(messages, rankTier);
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setTitle(messages.get("draft.title", rankLabel));

        int filled = (int) Math.round(result.winProbability() * BAR_SEGMENTS);
        filled = Math.max(0, Math.min(BAR_SEGMENTS, filled));
        String bar = "[" + "█".repeat(filled) + "░".repeat(BAR_SEGMENTS - filled) + "]";
        String winChance = CommandSupport.oneDecimal(result.winProbability() * 100.0);

        String description = messages.get("draft.win_chance", winChance, bar);
        if (result.lowConfidence()) {
            description = description + "\n\n" + messages.get("draft.low_confidence");
        }
        builder.setDescription(description);

        Map<Integer, String> heroNames = gameAssets.heroNames();

        List<String> allyLines = formatHeroTeam(allyHeroIds, heroNames);
        EmbedStyle.addFittedField(builder, messages.get("draft.field.allies", String.valueOf(result.allyCount())), allyLines);

        List<String> enemyLines = formatHeroTeam(enemyHeroIds, heroNames);
        EmbedStyle.addFittedField(builder, messages.get("draft.field.enemies", String.valueOf(result.enemyCount())), enemyLines);

        if (!result.suggestions().isEmpty()) {
            List<String> suggestionLines = result.suggestions().stream()
                    .map(s -> suggestionLine(s, heroNames))
                    .toList();
            EmbedStyle.addFittedField(builder, messages.get("draft.field.suggestions"), suggestionLines);
        }

        builder.setFooter(messages.get("draft.footer"));
        return builder.build();
    }

    private List<String> formatHeroTeam(List<Integer> heroIds, Map<Integer, String> heroNames) {
        if (heroIds.isEmpty()) {
            return List.of(messages.get("draft.none"));
        }
        return heroIds.stream()
                .map(id -> {
                    String name = heroNames.get(id);
                    if (name == null) {
                        name = messages.get("synergy.hero_unknown", String.valueOf(id));
                    }
                    return EmbedStyle.withIcon(iconCatalog.hero(id), name);
                })
                .toList();
    }

    private String suggestionLine(PickSuggestion suggestion, Map<Integer, String> heroNames) {
        String heroName = heroNames.get(suggestion.heroId());
        if (heroName == null) {
            heroName = messages.get("synergy.hero_unknown", String.valueOf(suggestion.heroId()));
        }
        String prob = CommandSupport.oneDecimal(suggestion.winProbability() * 100.0);
        String text = messages.get("draft.suggestion_entry", heroName, prob, String.valueOf(suggestion.sampleMatches()));
        return EmbedStyle.withIcon(iconCatalog.hero(suggestion.heroId()), text);
    }

    private static List<String> parseTokens(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("none") || trimmed.equals("-")) {
            return List.of();
        }
        String[] parts = trimmed.split(",");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            String t = part.trim();
            if (!t.isEmpty() && !t.equalsIgnoreCase("none") && !t.equals("-")) {
                tokens.add(t);
            }
        }
        return List.copyOf(tokens);
    }
}
