package sntiago05.rejuv.services;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.dto.HeroAsset;

@Component
public class HeroResolver {

    private final GameAssets gameAssets;

    public HeroResolver(GameAssets gameAssets) {
        this.gameAssets = gameAssets;
    }

    /**
     * Resolves a hero typed by a user. Uses the cached heroes asset (getHeroes()), no other call.
     * Order: (1) exact name, ignoring case, after trimming and collapsing inner whitespace to one space;
     * (2) otherwise the single hero whose name starts with the input (ignoring case);
     * (3) otherwise empty (also for blank input and for an ambiguous prefix).
     * Heroes whose name is null or blank are ignored.
     * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
     */
    public Optional<HeroAsset> resolve(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalized = input.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        List<HeroAsset> validHeroes = gameAssets.namedHeroes();

        Optional<HeroAsset> exactMatch = validHeroes.stream()
                .filter(hero -> hero.name().equalsIgnoreCase(normalized))
                .findFirst();
        if (exactMatch.isPresent()) {
            return exactMatch;
        }

        String prefix = normalized.toLowerCase(Locale.ROOT);
        List<HeroAsset> prefixMatches = validHeroes.stream()
                .filter(hero -> hero.name().toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
        if (prefixMatches.size() == 1) {
            return Optional.of(prefixMatches.getFirst());
        }

        return Optional.empty();
    }
}
