package sntiago05.rejuv.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.domain.AnalysisInput;
import sntiago05.rejuv.domain.AnalysisInput.ItemTiming;
import sntiago05.rejuv.domain.AnalysisInput.MetricComparison;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchRank;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.PowerUpBuff;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Turns the data of one match (history entry, optional telemetry, benchmarks, item stats, assets) into the
 * {@link AnalysisInput} sent to the model. Pure: no I/O, no API call.
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    public static final String LANGUAGE = "en";
    static final Instant BUFF_DATA_SINCE = Instant.parse("2026-09-29T00:00:00Z");

    static final int MIN_RANK_TIER = 0;
    static final int MAX_RANK_TIER = 11;

    // Metric keys sent to the model, and the deadlock-api benchmark keys they are compared with.
    static final String SOULS_PER_MIN = "souls_per_min";
    static final String KILLS = "kills";
    static final String DEATHS = "deaths";
    static final String ASSISTS = "assists";
    static final String DEATHS_PER_10_MIN = "deaths_per_10_min";
    static final String LAST_HITS = "last_hits";
    static final String DENIES = "denies";
    static final String PLAYER_DAMAGE_PER_MIN = "player_damage_per_min";
    static final String NET_WORTH_AT_10_MIN = "net_worth_at_10_min";
    static final String NET_WORTH_AT_20_MIN = "net_worth_at_20_min";
    static final String PERMANENT_BUFFS = "permanent_buffs";
    static final String FIRST_PERMANENT_BUFF_MIN = "first_permanent_buff_min";

    static final String BENCHMARK_NET_WORTH_PER_MIN = "net_worth_per_min";
    static final String BENCHMARK_KILLS = "kills";
    static final String BENCHMARK_DEATHS = "deaths";
    static final String BENCHMARK_ASSISTS = "assists";
    static final String BENCHMARK_LAST_HITS = "last_hits";
    static final String BENCHMARK_DENIES = "denies";
    static final String BENCHMARK_PLAYER_DAMAGE_PER_MIN = "player_damage_per_min";
    static final String BENCHMARK_PERMANENT_BUFFS = "permanent_buffs";
    static final String BENCHMARK_FIRST_PERMANENT_BUFF_TIME_S = "first_permanent_buff_time_s";

    static final int TEN_MIN_S = 600;
    static final int TWENTY_MIN_S = 1200;

    static final String UPGRADE_TYPE = "upgrade";
    static final int FIRST_ITEMS = 3;
    static final int CORE_ITEM_TIER = 3;

    /** Newest entry by startTime (ties: first in list order), or empty for an empty history. */
    public Optional<MatchHistoryEntry> latestMatch(List<MatchHistoryEntry> history) {
        return history.stream().reduce((newest, entry) -> entry.startTime() > newest.startTime() ? entry : newest);
    }

    /**
     * Rank whose benchmarks the match is compared with:
     * 1. match.rankedDisplayBadge() non-null and > 0 → MatchRank(badge / 10, badge % 10);
     * 2. else currentRank non-null and currentRank.badge() > 0 → MatchRank(currentRank.rank(), currentRank.subrank());
     * 3. else empty (unranked match and unranked or unknown player).
     * A tier outside 0..11 gives empty.
     */
    public Optional<MatchRank> benchmarkRank(MatchHistoryEntry match, PlayerRank currentRank) {
        Integer badge = match.rankedDisplayBadge();
        MatchRank rank;
        if (badge != null && badge > 0) {
            rank = new MatchRank(badge / 10, badge % 10);
        } else if (currentRank != null && currentRank.badge() > 0) {
            rank = new MatchRank(currentRank.rank(), currentRank.subrank());
        } else {
            return Optional.empty();
        }
        if (rank.tier() < MIN_RANK_TIER || rank.tier() > MAX_RANK_TIER) {
            return Optional.empty();
        }
        return Optional.of(rank);
    }

    /**
     * Builds the AI input. Pure: no I/O, never calls the API.
     * @throws IllegalArgumentException if match.matchDurationS() <= 0, or telemetry is non-null
     *         and telemetry.matchId() != match.matchId()
     */
    public AnalysisInput buildInput(MetricsRequest request) {
        MatchHistoryEntry match = request.match();
        if (match.matchDurationS() <= 0) {
            throw new IllegalArgumentException("matchDurationS must be positive, was " + match.matchDurationS());
        }
        MatchTelemetry telemetry = request.telemetry();
        if (telemetry != null && telemetry.matchId() != match.matchId()) {
            throw new IllegalArgumentException("telemetry is for match " + telemetry.matchId()
                    + ", not match " + match.matchId());
        }
        PlayerTelemetry player = null;
        if (telemetry != null) {
            player = telemetry.player(request.accountId()).orElse(null);
            if (player == null) {
                log.warn("Player missing from match telemetry, using history data only: match={} account={}",
                        match.matchId(), request.accountId());
            }
        }

        double durationMin = match.matchDurationS() / 60.0;
        return new AnalysisInput(
                heroName(match.heroId(), request.heroes()),
                rankName(request.rank(), request.ranks()),
                match.matchResult() == match.playerTeam() ? "win" : "loss",
                round(durationMin),
                Collections.unmodifiableMap(metrics(match, player, durationMin, request.benchmarks())),
                player == null ? List.of() : items(player, request.items(), request.itemStats()),
                LANGUAGE);
    }

    private static Map<String, MetricComparison> metrics(MatchHistoryEntry match, PlayerTelemetry player,
                                                         double durationMin, Map<String, MetricPercentiles> benchmarks) {
        Map<String, MetricComparison> metrics = new LinkedHashMap<>();
        metrics.put(SOULS_PER_MIN, compare(match.netWorth() / durationMin, benchmarks.get(BENCHMARK_NET_WORTH_PER_MIN)));
        metrics.put(KILLS, compare(match.playerKills(), benchmarks.get(BENCHMARK_KILLS)));
        metrics.put(DEATHS, compare(match.playerDeaths(), benchmarks.get(BENCHMARK_DEATHS)));
        metrics.put(ASSISTS, compare(match.playerAssists(), benchmarks.get(BENCHMARK_ASSISTS)));
        metrics.put(DEATHS_PER_10_MIN, compare(match.playerDeaths() / durationMin * 10, null));
        metrics.put(LAST_HITS, compare(match.lastHits(), benchmarks.get(BENCHMARK_LAST_HITS)));
        metrics.put(DENIES, compare(match.denies(), benchmarks.get(BENCHMARK_DENIES)));
        if (player == null) {
            return metrics;
        }

        List<StatSnapshot> snapshots = player.snapshots();
        if (!snapshots.isEmpty()) {
            metrics.put(PLAYER_DAMAGE_PER_MIN, compare(snapshots.getLast().playerDamage() / durationMin,
                    benchmarks.get(BENCHMARK_PLAYER_DAMAGE_PER_MIN)));
        }
        if (match.matchDurationS() >= TEN_MIN_S) {
            netWorthAt(snapshots, TEN_MIN_S).ifPresent(value -> metrics.put(NET_WORTH_AT_10_MIN, compare(value, null)));
        }
        if (match.matchDurationS() >= TWENTY_MIN_S) {
            netWorthAt(snapshots, TWENTY_MIN_S).ifPresent(value -> metrics.put(NET_WORTH_AT_20_MIN, compare(value, null)));
        }

        // Pickup data exists only since BUFF_DATA_SINCE; before it an empty buff list means "no data", not zero.
        if (!Instant.ofEpochSecond(match.startTime()).isBefore(BUFF_DATA_SINCE)) {
            List<Integer> permanentPickups = player.powerUpBuffs().stream()
                    .filter(PowerUpBuff::permanent)
                    .flatMap(buff -> buff.pickupTimesS().stream())
                    .toList();
            metrics.put(PERMANENT_BUFFS, compare(permanentPickups.size(), benchmarks.get(BENCHMARK_PERMANENT_BUFFS)));
            permanentPickups.stream().mapToInt(Integer::intValue).min().ifPresent(firstS ->
                    metrics.put(FIRST_PERMANENT_BUFF_MIN, compare(firstS / 60.0,
                            benchmarks.get(BENCHMARK_FIRST_PERMANENT_BUFF_TIME_S), 60.0)));
        }
        return metrics;
    }

    /** Net worth at timeS: exact snapshot, else linear interpolation between the nearest snapshots around it. */
    private static OptionalDouble netWorthAt(List<StatSnapshot> snapshots, int timeS) {
        StatSnapshot before = null;
        StatSnapshot after = null;
        for (StatSnapshot snapshot : snapshots) {
            if (snapshot.timeS() == timeS) {
                return OptionalDouble.of(snapshot.netWorth());
            }
            if (snapshot.timeS() < timeS) {
                before = snapshot;
            } else if (after == null) {
                after = snapshot;
            }
        }
        if (before == null || after == null) {
            return OptionalDouble.empty();
        }
        double fraction = (double) (timeS - before.timeS()) / (after.timeS() - before.timeS());
        return OptionalDouble.of(before.netWorth() + fraction * (after.netWorth() - before.netWorth()));
    }

    /** First FIRST_ITEMS shop upgrades, plus the first tier-3 upgrade when it was bought later. */
    private static List<ItemTiming> items(PlayerTelemetry player, List<ItemAsset> assets, Map<Long, ItemStat> itemStats) {
        Map<Long, ItemAsset> upgrades = assets.stream()
                .filter(asset -> UPGRADE_TYPE.equals(asset.type()))
                .collect(Collectors.toMap(ItemAsset::id, Function.identity(), (first, second) -> first));
        List<ItemPurchase> candidates = player.items().stream()
                .filter(purchase -> upgrades.containsKey(purchase.itemId()))
                .toList();

        List<ItemPurchase> selected = new ArrayList<>(candidates.subList(0, Math.min(FIRST_ITEMS, candidates.size())));
        for (int i = 0; i < candidates.size(); i++) {
            Integer tier = upgrades.get(candidates.get(i).itemId()).itemTier();
            if (tier != null && tier == CORE_ITEM_TIER) {
                if (i >= FIRST_ITEMS) {
                    selected.add(candidates.get(i));
                }
                break;
            }
        }

        return selected.stream()
                .map(purchase -> {
                    ItemAsset asset = upgrades.get(purchase.itemId());
                    ItemStat stat = itemStats.get(purchase.itemId());
                    return new ItemTiming(asset.name() != null ? asset.name() : asset.className(),
                            round(purchase.boughtAtS() / 60.0),
                            stat == null ? null : round(stat.avgBuyTimeS() / 60.0));
                })
                .toList();
    }

    private static String heroName(int heroId, List<HeroAsset> heroes) {
        return heroes.stream()
                .filter(hero -> hero.id() == heroId)
                .map(hero -> hero.name() != null ? hero.name() : hero.className())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("hero_" + heroId);
    }

    private static String rankName(MatchRank rank, List<RankAsset> ranks) {
        if (rank == null) {
            return null;
        }
        return ranks.stream()
                .filter(asset -> asset.tier() == rank.tier() && asset.name() != null)
                .map(asset -> rank.subrank() > 0 ? asset.name() + " " + rank.subrank() : asset.name())
                .findFirst()
                .orElse(null);
    }

    private static MetricComparison compare(double value, MetricPercentiles benchmark) {
        return compare(value, benchmark, 1.0);
    }

    /** Missing benchmark → null percentiles; each percentile is divided by benchmarkDivisor (unit conversion). */
    private static MetricComparison compare(double value, MetricPercentiles benchmark, double benchmarkDivisor) {
        if (benchmark == null) {
            return new MetricComparison(round(value), null, null, null);
        }
        return new MetricComparison(round(value),
                round(benchmark.p25() / benchmarkDivisor),
                round(benchmark.p50() / benchmarkDivisor),
                round(benchmark.p75() / benchmarkDivisor));
    }

    /** One decimal, half up (values are non-negative). */
    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
