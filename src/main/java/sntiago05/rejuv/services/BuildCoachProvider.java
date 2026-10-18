package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.domain.BuildItem;
import sntiago05.rejuv.domain.BuildPhase;
import sntiago05.rejuv.domain.HeroBuild;
import sntiago05.rejuv.domain.ItemPurchase;

@Component
public class BuildCoachProvider implements CoachInsightProvider {

    public static final String KEY = "build";

    /** Minimum pick rate for a popular item to be considered when omitted from the build. */
    static final double MISSING_MIN_PICK_PERCENT = 35.0;

    /** Major threshold: pick rate for a missing high-priority item. */
    static final double MAJOR_MISSING_PICK_PERCENT = 55.0;

    /** Major threshold: win rate for a missing high-priority item. */
    static final double MAJOR_MISSING_WIN_PERCENT = 52.0;

    /** Notable threshold: minimum number of off-meta items in the final build. */
    static final int NOTABLE_OFF_META_COUNT = 3;

    /** Maximum number of items reported in missing_popular list. */
    static final int MAX_MISSING_ITEMS = 3;

    /** Maximum number of items reported in off_meta list. */
    static final int MAX_OFF_META_ITEMS = 3;

    private static final Logger log = LoggerFactory.getLogger(BuildCoachProvider.class);

    private static final Comparator<BuildItem> MISSING_POPULAR_COMPARATOR = Comparator
            .comparingDouble(BuildItem::pickRatePercent).reversed()
            .thenComparingLong(BuildItem::itemId);

    private static final Comparator<OffMetaCandidate> OFF_META_COMPARATOR = Comparator
            .comparingInt(OffMetaCandidate::cost).reversed()
            .thenComparingLong(OffMetaCandidate::itemId);

    private final BuildService buildService;

    public BuildCoachProvider(BuildService buildService) {
        this.buildService = buildService;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<CoachInsight> provide(CoachRequest request) {
        if (request == null || request.telemetry() == null || request.player() == null) {
            return Optional.empty();
        }
        if (request.rankTier() == null || request.rankTier() == 0) {
            return Optional.empty();
        }
        if (request.player().items() == null || request.player().items().isEmpty()) {
            return Optional.empty();
        }

        String heroName = resolveHeroName(request.player().heroId(), request.heroes());
        if (heroName == null || heroName.isBlank()) {
            return Optional.empty();
        }

        Map<Long, ItemAsset> itemAssets = new HashMap<>();
        for (ItemAsset asset : request.items()) {
            if (asset != null) {
                itemAssets.put(asset.id(), asset);
            }
        }

        boolean hasUpgradePurchase = false;
        Set<Long> kept = new LinkedHashSet<>();
        Set<Long> allBoughtItemIds = new HashSet<>();

        for (ItemPurchase p : request.player().items()) {
            if (p == null) {
                continue;
            }
            allBoughtItemIds.add(p.itemId());
            ItemAsset asset = itemAssets.get(p.itemId());
            boolean isUpgrade = asset != null && "upgrade".equalsIgnoreCase(asset.type());
            if (isUpgrade) {
                hasUpgradePurchase = true;
                if (p.soldAtS() == null) {
                    kept.add(p.itemId());
                }
            }
        }

        if (!hasUpgradePurchase) {
            return Optional.empty();
        }

        HeroBuild heroBuild;
        try {
            heroBuild = buildService.getBuild(heroName, request.rankTier());
        } catch (HeroNotFoundException | IllegalArgumentException | DeadlockApiException ex) {
            log.warn("Failed to retrieve build for hero '{}', rank {}: {}", heroName, request.rankTier(), ex.getClass().getSimpleName());
            return Optional.empty();
        }

        if (heroBuild == null || heroBuild.phases() == null || heroBuild.phases().isEmpty()) {
            return Optional.empty();
        }

        Map<Long, BuildItem> popularById = new LinkedHashMap<>();
        for (BuildPhase phase : heroBuild.phases()) {
            if (phase == null || phase.items() == null) {
                continue;
            }
            for (BuildItem item : phase.items()) {
                if (item == null) {
                    continue;
                }
                popularById.merge(item.itemId(), item, (existing, replacement) -> {
                    if (replacement.pickRatePercent() > existing.pickRatePercent()) {
                        return replacement;
                    }
                    if (Double.compare(replacement.pickRatePercent(), existing.pickRatePercent()) == 0
                            && replacement.matches() > existing.matches()) {
                        return replacement;
                    }
                    return existing;
                });
            }
        }

        List<MissingItem> missingPopular = popularById.values().stream()
                .filter(b -> b.pickRatePercent() >= MISSING_MIN_PICK_PERCENT)
                .filter(b -> !allBoughtItemIds.contains(b.itemId()))
                .filter(b -> resolveItemName(b.itemId(), b.itemName(), itemAssets) != null)
                .sorted(MISSING_POPULAR_COMPARATOR)
                .limit(MAX_MISSING_ITEMS)
                .map(b -> new MissingItem(
                        resolveItemName(b.itemId(), b.itemName(), itemAssets),
                        round(b.pickRatePercent()),
                        round(b.winRatePercent())
                ))
                .toList();

        List<OffMetaCandidate> offMetaCandidates = new ArrayList<>();
        for (Long keptId : kept) {
            if (!popularById.containsKey(keptId)) {
                ItemAsset asset = itemAssets.get(keptId);
                String name = (asset != null && asset.name() != null && !asset.name().isBlank())
                        ? asset.name()
                        : null;
                if (name != null) {
                    int cost = (asset.cost() != null) ? asset.cost() : 0;
                    offMetaCandidates.add(new OffMetaCandidate(keptId, name, cost));
                }
            }
        }

        List<OffMetaItem> offMeta = offMetaCandidates.stream()
                .sorted(OFF_META_COMPARATOR)
                .limit(MAX_OFF_META_ITEMS)
                .map(c -> new OffMetaItem(c.name()))
                .toList();

        int keptCount = kept.size();
        int popularCount = popularById.size();
        int matchingPopular = 0;
        for (Long keptId : kept) {
            if (popularById.containsKey(keptId)) {
                matchingPopular++;
            }
        }

        int minCount = Math.min(keptCount, popularCount);
        double matchRatePct = minCount > 0 ? round((matchingPopular * 100.0) / minCount) : 0.0;

        boolean hasMajorMissing = missingPopular.stream().anyMatch(
                m -> m.pickRate() >= MAJOR_MISSING_PICK_PERCENT && m.winRate() >= MAJOR_MISSING_WIN_PERCENT);

        String significance;
        if (hasMajorMissing) {
            significance = "major";
        } else if (!missingPopular.isEmpty() || offMeta.size() >= NOTABLE_OFF_META_COUNT) {
            significance = "notable";
        } else {
            significance = "none";
        }

        BuildCoachPayload payload = new BuildCoachPayload(
                significance,
                keptCount,
                popularCount,
                matchingPopular,
                matchRatePct,
                missingPopular,
                offMeta
        );

        return Optional.of(new CoachInsight(KEY, payload));
    }

    private static String resolveHeroName(int heroId, List<HeroAsset> heroes) {
        if (heroes == null) {
            return null;
        }
        for (HeroAsset hero : heroes) {
            if (hero != null && hero.id() == heroId) {
                if (hero.name() != null && !hero.name().isBlank()) {
                    return hero.name();
                }
                if (hero.className() != null && !hero.className().isBlank()) {
                    return hero.className();
                }
                return null;
            }
        }
        return null;
    }

    private static String resolveItemName(long itemId, String candidateName, Map<Long, ItemAsset> itemAssets) {
        if (candidateName != null && !candidateName.isBlank()) {
            return candidateName;
        }
        ItemAsset asset = itemAssets.get(itemId);
        if (asset != null && asset.name() != null && !asset.name().isBlank()) {
            return asset.name();
        }
        return null;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record OffMetaCandidate(long itemId, String name, int cost) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuildCoachPayload(
            @JsonProperty("significance") String significance,
            @JsonProperty("kept_count") int keptCount,
            @JsonProperty("popular_count") int popularCount,
            @JsonProperty("matching_popular") int matchingPopular,
            @JsonProperty("match_rate_pct") double matchRatePct,
            @JsonProperty("missing_popular") List<MissingItem> missingPopular,
            @JsonProperty("off_meta") List<OffMetaItem> offMeta
    ) {
        public BuildCoachPayload {
            missingPopular = missingPopular == null ? List.of() : List.copyOf(missingPopular);
            offMeta = offMeta == null ? List.of() : List.copyOf(offMeta);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MissingItem(
            @JsonProperty("item") String item,
            @JsonProperty("pick_rate") double pickRate,
            @JsonProperty("win_rate") double winRate
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OffMetaItem(
            @JsonProperty("item") String item
    ) {}
}
