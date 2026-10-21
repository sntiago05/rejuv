package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroDetail;
import sntiago05.rejuv.domain.HeroInfo;

/**
 * Hero details for {@code /heroinfo}. The typed text is resolved to a real hero first, so only a known canonical name
 * is ever sent to the API.
 */
@Service
public class HeroInfoService {

    static final int MAX_LORE_LENGTH = 600;
    static final String ELLIPSIS = "...";

    /** Base stats shown, in display order. Other starting stats are ignored. */
    static final List<String> STAT_KEYS = List.of("max_health", "max_move_speed", "sprint_speed", "stamina",
            "light_melee_damage", "heavy_melee_damage", "reload_speed", "base_health_regen");

    private final HeroResolver heroResolver;
    private final DeadlockApiClient deadlockApiClient;

    public HeroInfoService(HeroResolver heroResolver, DeadlockApiClient deadlockApiClient) {
        this.heroResolver = heroResolver;
        this.deadlockApiClient = deadlockApiClient;
    }

    /**
     * @throws HeroNotFoundException when the text matches no hero, or the API has no hero with that name
     * @throws sntiago05.rejuv.clients.DeadlockApiException (and its unavailable subtype) when the API call fails
     */
    public HeroInfo getInfo(String userInput) {
        HeroAsset hero = heroResolver.resolve(userInput).orElseThrow(() -> new HeroNotFoundException(userInput));
        HeroDetail detail = deadlockApiClient.getHeroDetail(hero.name())
                .orElseThrow(() -> new HeroNotFoundException(userInput));
        return toInfo(detail);
    }

    private static HeroInfo toInfo(HeroDetail detail) {
        HeroDetail.Description description = detail.description();
        String role = description == null ? null : blankToNull(description.role());
        String playstyle = description == null ? null : blankToNull(description.playstyle());
        String lore = description == null ? null : cutAtWord(blankToNull(description.lore()), MAX_LORE_LENGTH);
        boolean playable = detail.playerSelectable() && !detail.disabled() && !detail.inDevelopment();
        return new HeroInfo(detail.id(), detail.name(), role, playstyle, lore, blankToNull(detail.heroType()),
                detail.complexity(), blankToNull(detail.gunTag()), cleanTags(detail.tags()),
                detail.colors() == null ? null : blankToNull(detail.colors().styleHex()),
                detail.images() == null ? null : blankToNull(detail.images().iconHeroCard()),
                playable, stats(detail.startingStats()));
    }

    private static List<HeroInfo.Stat> stats(Map<String, HeroDetail.StatValue> startingStats) {
        if (startingStats == null || startingStats.isEmpty()) {
            return List.of();
        }
        List<HeroInfo.Stat> stats = new ArrayList<>(STAT_KEYS.size());
        for (String key : STAT_KEYS) {
            HeroDetail.StatValue value = startingStats.get(key);
            if (value != null && value.value() != null) {
                stats.add(new HeroInfo.Stat(key, value.value()));
            }
        }
        return stats;
    }

    private static List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream().filter(tag -> tag != null && !tag.isBlank()).map(String::trim).toList();
    }

    /**
     * Collapses runs of whitespace, then cuts at the last space before the limit and appends "..." only when it cut.
     * The result, including the ellipsis, is at most {@code maxLength} characters.
     */
    static String cutAtWord(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLength) {
            return clean;
        }
        String head = clean.substring(0, maxLength - ELLIPSIS.length());
        int lastSpace = head.lastIndexOf(' ');
        if (lastSpace > 0) {
            head = head.substring(0, lastSpace);
        }
        return head.stripTrailing() + ELLIPSIS;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
