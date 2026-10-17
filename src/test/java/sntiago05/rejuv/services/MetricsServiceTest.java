package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MetricsServiceTest {

    private static final long ACCOUNT_ID = 1000001L;
    private static final long OTHER_ACCOUNT_ID = 1000002L;
    private static final long MATCH_ID = 900000003L;
    private static final int HERO_ID = 1;
    private static final int DURATION_S = 1800;
    private static final long BUFF_ERA_START = Instant.parse("2026-10-05T12:00:00Z").getEpochSecond();
    private static final long BEFORE_BUFF_ERA = Instant.parse("2026-09-20T12:00:00Z").getEpochSecond();
    private static final MatchRank RANK = new MatchRank(7, 3);

    private static final List<String> HISTORY_KEYS = List.of(
            "souls_per_min", "kills", "deaths", "assists", "deaths_per_10_min", "last_hits", "denies");
    private static final List<String> ALL_KEYS = List.of(
            "souls_per_min", "kills", "deaths", "assists", "deaths_per_10_min", "last_hits", "denies",
            "player_damage_per_min", "net_worth_at_10_min", "net_worth_at_20_min",
            "permanent_buffs", "first_permanent_buff_min");

    private static final List<StatSnapshot> SNAPSHOTS = List.of(
            new StatSnapshot(180, 1500, 2000, 0),
            new StatSnapshot(360, 3500, 4500, 0),
            new StatSnapshot(540, 6000, 7000, 0),
            new StatSnapshot(720, 9000, 10000, 0),
            new StatSnapshot(900, 12000, 13000, 0),
            new StatSnapshot(1200, 18000, 18000, 0),
            new StatSnapshot(1500, 24000, 22000, 0),
            new StatSnapshot(1800, 30000, 27000, 0));

    private static final List<ItemAsset> ITEMS = List.of(
            new ItemAsset(101, "upgrade_clip_size", "Extended Magazine", "upgrade", 1, "weapon", 800),
            new ItemAsset(102, "upgrade_swift_striker", "Swift Striker", "upgrade", 2, "weapon", 1600),
            new ItemAsset(103, "upgrade_mystic_reach", "Mystic Reach", "upgrade", 1, "spirit", 800),
            new ItemAsset(104, "upgrade_enduring_speed", "Enduring Speed", "upgrade", 2, "vitality", 1600),
            new ItemAsset(105, "upgrade_unnamed", null, "upgrade", 1, "vitality", 800),
            new ItemAsset(301, "upgrade_toxic_bullets", "Toxic Bullets", "upgrade", 3, "weapon", 3200),
            new ItemAsset(302, "upgrade_escalating_exposure", "Escalating Exposure", "upgrade", 3, "spirit", 3200),
            new ItemAsset(501, "ability_golden_idol", "Soul Urn", "ability", null, null, null),
            new ItemAsset(601, "weapon_alternative", "weapon_alternative", "weapon", null, null, null));
    private static final List<HeroAsset> HEROES = List.of(
            new HeroAsset(1, "hero_inferno", "Infernus"),
            new HeroAsset(2, "hero_gigawatt", null));
    private static final List<RankAsset> RANKS = List.of(
            new RankAsset(0, "Obscurus"),
            new RankAsset(7, "Archon"));

    private static final Map<String, MetricPercentiles> BENCHMARKS = Map.of(
            "net_worth_per_min", new MetricPercentiles(1155.86, 1043.34, 1153.07, 1274.36),
            "kills", new MetricPercentiles(8.0, 5.04, 7.96, 11.0),
            "deaths", new MetricPercentiles(5.0, 3.0, 5.0, 7.0),
            "assists", new MetricPercentiles(10.0, 7.0, 10.0, 14.0),
            "last_hits", new MetricPercentiles(200.9, 159.19, 194.44, 237.49),
            "denies", new MetricPercentiles(15.0, 9.6, 14.2, 19.81),
            "player_damage_per_min", new MetricPercentiles(891.4, 685.51, 854.21, 1064.42),
            "permanent_buffs", new MetricPercentiles(2.5, 1.0, 2.0, 4.0),
            "first_permanent_buff_time_s", new MetricPercentiles(420.0, 270.0, 390.0, 540.0));
    private static final Map<Long, ItemStat> ITEM_STATS = Map.of(
            101L, itemStat(101, 150),
            102L, itemStat(102, 330),
            103L, itemStat(103, 500),
            301L, itemStat(301, 1000));

    private final MetricsService service = new MetricsService();

    // 1. latestMatch

    @Test
    void latestMatchIsNewestByStartTime() {
        MatchHistoryEntry older = entry(1L, 1000);
        MatchHistoryEntry newest = entry(2L, 3000);
        MatchHistoryEntry middle = entry(3L, 2000);
        MatchHistoryEntry tie = entry(4L, 3000);

        assertThat(service.latestMatch(List.of(older, newest, middle, tie))).contains(newest);
    }

    @Test
    void latestMatchEmptyForEmptyHistory() {
        assertThat(service.latestMatch(List.of())).isEmpty();
    }

    // 2. benchmarkRank

    @Test
    void benchmarkRankUsesMatchBadgeWhenRanked() {
        assertThat(service.benchmarkRank(match(73), new PlayerRank(45, 4, 5))).contains(new MatchRank(7, 3));
    }

    @Test
    void benchmarkRankFallsBackToCurrentRankForUnrankedMatch() {
        assertThat(service.benchmarkRank(match(null), new PlayerRank(45, 4, 5))).contains(new MatchRank(4, 5));
        assertThat(service.benchmarkRank(match(0), new PlayerRank(45, 4, 5))).contains(new MatchRank(4, 5));
    }

    @Test
    void benchmarkRankEmptyWhenUnrankedMatchAndUnrankedPlayer() {
        assertThat(service.benchmarkRank(match(0), null)).isEmpty();
        assertThat(service.benchmarkRank(match(null), new PlayerRank(0, 0, 0))).isEmpty();
    }

    @Test
    void benchmarkRankEmptyWhenTierOutOfRange() {
        assertThat(service.benchmarkRank(match(125), null)).isEmpty();
        assertThat(service.benchmarkRank(match(null), new PlayerRank(125, 12, 5))).isEmpty();
    }

    // 3. result

    @Test
    void resultIsWinWhenMatchResultEqualsPlayerTeam() {
        AnalysisInput input = service.buildInput(historyOnly(match(BUFF_ERA_START, DURATION_S, 36000, 1, 1)));

        assertThat(input.result()).isEqualTo("win");
    }

    @Test
    void resultIsLossWhenMatchResultDiffersFromPlayerTeam() {
        AnalysisInput input = service.buildInput(historyOnly(match(BUFF_ERA_START, DURATION_S, 36000, 0, 1)));

        assertThat(input.result()).isEqualTo("loss");
    }

    // 4-5. history metrics

    @Test
    void historyMetricsAndRoundingAreComputed() {
        AnalysisInput input = service.buildInput(historyOnly(match(BUFF_ERA_START, 2104, 41250, 1, 1)));

        assertThat(input.durationMin()).isEqualTo(35.1);
        assertThat(input.metrics())
                .containsEntry("souls_per_min", new MetricComparison(1176.3, 1043.3, 1153.1, 1274.4))
                .containsEntry("kills", new MetricComparison(9.0, 5.0, 8.0, 11.0))
                .containsEntry("deaths", new MetricComparison(4.0, 3.0, 5.0, 7.0))
                .containsEntry("assists", new MetricComparison(12.0, 7.0, 10.0, 14.0))
                .containsEntry("deaths_per_10_min", new MetricComparison(1.1, null, null, null))
                .containsEntry("last_hits", new MetricComparison(233.0, 159.2, 194.4, 237.5))
                .containsEntry("denies", new MetricComparison(21.0, 9.6, 14.2, 19.8));
        assertThat(input.language()).isEqualTo(MetricsService.LANGUAGE).isEqualTo("en");
    }

    @Test
    void missingBenchmarkKeyGivesNullPercentiles() {
        Map<String, MetricPercentiles> withoutKills = BENCHMARKS.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("kills"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        AnalysisInput input = service.buildInput(request(defaultMatch(), null, RANK, withoutKills, ITEM_STATS));

        assertThat(input.metrics()).containsEntry("kills", new MetricComparison(9.0, null, null, null));
        assertThat(input.metrics().get("deaths").p50()).isEqualTo(5.0);
    }

    // 6-9. order, degraded mode, rejections

    @Test
    void metricsKeepTableOrder() {
        AnalysisInput input = service.buildInput(full(defaultMatch(),
                player(List.of(), SNAPSHOTS, List.of(new PowerUpBuff("perm_a", true, List.of(400))))));

        assertThat(input.metrics().keySet()).containsExactlyElementsOf(ALL_KEYS);
    }

    @Test
    void degradedModeWithoutTelemetryHasOnlyHistoryMetricsAndNoItems() {
        AnalysisInput input = service.buildInput(historyOnly(defaultMatch()));

        assertThat(input.metrics().keySet()).containsExactlyElementsOf(HISTORY_KEYS);
        assertThat(input.metrics().get("souls_per_min")).isEqualTo(new MetricComparison(1200.0, 1043.3, 1153.1, 1274.4));
        assertThat(input.items()).isEmpty();
    }

    @Test
    void playerMissingFromTelemetryFallsBackToDegradedMode() {
        PlayerTelemetry other = new PlayerTelemetry(OTHER_ACCOUNT_ID, 1, HERO_ID,
                List.of(new ItemPurchase(101, 120, null)), SNAPSHOTS, List.of());

        AnalysisInput input = service.buildInput(full(defaultMatch(), other));

        assertThat(input.metrics().keySet()).containsExactlyElementsOf(HISTORY_KEYS);
        assertThat(input.items()).isEmpty();
    }

    @Test
    void telemetryForAnotherMatchIsRejected() {
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID + 1, DURATION_S,
                List.of(player(List.of(), SNAPSHOTS, List.of())));

        assertThatThrownBy(() -> service.buildInput(request(defaultMatch(), telemetry, RANK, BENCHMARKS, ITEM_STATS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveDurationIsRejected() {
        assertThatThrownBy(() -> service.buildInput(historyOnly(match(BUFF_ERA_START, 0, 36000, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.buildInput(historyOnly(match(BUFF_ERA_START, -60, 36000, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 10-12. snapshot metrics

    @Test
    void playerDamagePerMinUsesLastSnapshot() {
        AnalysisInput input = service.buildInput(full(defaultMatch(), player(List.of(), SNAPSHOTS, List.of())));

        assertThat(input.metrics()).containsEntry("player_damage_per_min",
                new MetricComparison(900.0, 685.5, 854.2, 1064.4));

        AnalysisInput noSnapshots = service.buildInput(full(defaultMatch(), player(List.of(), List.of(), List.of())));
        assertThat(noSnapshots.metrics()).doesNotContainKeys(
                "player_damage_per_min", "net_worth_at_10_min", "net_worth_at_20_min");
    }

    @Test
    void netWorthAt10MinIsInterpolatedBetween540And720() {
        AnalysisInput input = service.buildInput(full(defaultMatch(), player(List.of(), SNAPSHOTS, List.of())));

        assertThat(input.metrics()).containsEntry("net_worth_at_10_min", new MetricComparison(7000.0, null, null, null));
    }

    @Test
    void netWorthAt20MinUsesExactSnapshot() {
        AnalysisInput input = service.buildInput(full(defaultMatch(), player(List.of(), SNAPSHOTS, List.of())));

        assertThat(input.metrics()).containsEntry("net_worth_at_20_min", new MetricComparison(18000.0, null, null, null));
    }

    @Test
    void netWorthAtTimeOmittedWhenMatchTooShortOrNoSnapshotAfter() {
        List<StatSnapshot> shortMatch = List.of(
                new StatSnapshot(180, 1500, 2000, 0),
                new StatSnapshot(360, 3500, 4500, 0),
                new StatSnapshot(540, 6000, 7000, 0),
                new StatSnapshot(590, 6500, 7500, 0));
        AnalysisInput tooShort = service.buildInput(full(match(BUFF_ERA_START, 590, 6500, 1, 1),
                player(List.of(), shortMatch, List.of())));
        assertThat(tooShort.metrics()).doesNotContainKeys("net_worth_at_10_min", "net_worth_at_20_min");

        List<StatSnapshot> endsBefore20 = SNAPSHOTS.subList(0, 5);
        AnalysisInput noSnapshotAfter = service.buildInput(full(match(BUFF_ERA_START, 1300, 20000, 1, 1),
                player(List.of(), endsBefore20, List.of())));
        assertThat(noSnapshotAfter.metrics()).containsKey("net_worth_at_10_min")
                .doesNotContainKey("net_worth_at_20_min");

        List<StatSnapshot> startsAfter10 = SNAPSHOTS.subList(3, SNAPSHOTS.size());
        AnalysisInput noSnapshotBefore = service.buildInput(full(defaultMatch(),
                player(List.of(), startsAfter10, List.of())));
        assertThat(noSnapshotBefore.metrics()).doesNotContainKey("net_worth_at_10_min")
                .containsKey("net_worth_at_20_min");
    }

    // 13. buff metrics

    @Test
    void buffMetricsInBuffEra() {
        List<PowerUpBuff> buffs = List.of(
                new PowerUpBuff("perm_a", true, List.of(420, 900)),
                new PowerUpBuff("perm_b", true, List.of(300)),
                new PowerUpBuff("temp_a", false, List.of(120)));

        AnalysisInput input = service.buildInput(full(defaultMatch(), player(List.of(), SNAPSHOTS, buffs)));

        assertThat(input.metrics())
                .containsEntry("permanent_buffs", new MetricComparison(3.0, 1.0, 2.0, 4.0))
                .containsEntry("first_permanent_buff_min", new MetricComparison(5.0, 4.5, 6.5, 9.0));
    }

    @Test
    void noPermanentPickupGivesZeroAndNoFirstBuff() {
        long eraStart = MetricsService.BUFF_DATA_SINCE.getEpochSecond();

        AnalysisInput input = service.buildInput(full(match(eraStart, DURATION_S, 36000, 1, 1),
                player(List.of(), SNAPSHOTS, List.of())));

        assertThat(input.metrics()).containsEntry("permanent_buffs", new MetricComparison(0.0, 1.0, 2.0, 4.0))
                .doesNotContainKey("first_permanent_buff_min");
    }

    @Test
    void buffMetricsOmittedBeforeBuffEra() {
        List<PowerUpBuff> buffs = List.of(new PowerUpBuff("perm_a", true, List.of(420)));

        AnalysisInput input = service.buildInput(full(match(BEFORE_BUFF_ERA, DURATION_S, 36000, 1, 1),
                player(List.of(), SNAPSHOTS, buffs)));

        assertThat(input.metrics()).doesNotContainKeys("permanent_buffs", "first_permanent_buff_min")
                .containsKey("player_damage_per_min");
    }

    @Test
    void nonPermanentBuffsAreIgnored() {
        List<PowerUpBuff> buffs = List.of(
                new PowerUpBuff("temp_a", false, List.of(60, 90)),
                new PowerUpBuff("perm_a", true, List.of(600)));

        AnalysisInput input = service.buildInput(full(defaultMatch(), player(List.of(), SNAPSHOTS, buffs)));

        assertThat(input.metrics())
                .containsEntry("permanent_buffs", new MetricComparison(1.0, 1.0, 2.0, 4.0))
                .containsEntry("first_permanent_buff_min", new MetricComparison(10.0, 4.5, 6.5, 9.0));
    }

    // 14. items

    @Test
    void itemsAreFirstThreeUpgradesPlusFirstTierThree() {
        List<ItemPurchase> purchases = List.of(
                new ItemPurchase(501, 0, null),
                new ItemPurchase(101, 120, null),
                new ItemPurchase(999, 150, null),
                new ItemPurchase(601, 200, null),
                new ItemPurchase(102, 300, null),
                new ItemPurchase(103, 480, null),
                new ItemPurchase(104, 600, null),
                new ItemPurchase(301, 900, null),
                new ItemPurchase(302, 1100, null));

        AnalysisInput input = service.buildInput(full(defaultMatch(), player(purchases, SNAPSHOTS, List.of())));

        assertThat(input.items()).containsExactly(
                new ItemTiming("Extended Magazine", 2.0, 2.5),
                new ItemTiming("Swift Striker", 5.0, 5.5),
                new ItemTiming("Mystic Reach", 8.0, 8.3),
                new ItemTiming("Toxic Bullets", 15.0, 16.7));
        assertThat(input.items()).isUnmodifiable();
    }

    @Test
    void tierThreeAmongFirstThreeIsNotDuplicated() {
        List<ItemPurchase> purchases = List.of(
                new ItemPurchase(101, 120, null),
                new ItemPurchase(301, 300, null),
                new ItemPurchase(102, 480, null),
                new ItemPurchase(104, 600, null),
                new ItemPurchase(302, 900, null));

        AnalysisInput input = service.buildInput(full(defaultMatch(), player(purchases, SNAPSHOTS, List.of())));

        assertThat(input.items()).extracting(ItemTiming::name)
                .containsExactly("Extended Magazine", "Toxic Bullets", "Swift Striker");
    }

    @Test
    void itemWithoutStatHasNullAverage() {
        List<ItemPurchase> purchases = List.of(
                new ItemPurchase(104, 125, null),
                new ItemPurchase(101, 300, null));

        AnalysisInput input = service.buildInput(full(defaultMatch(), player(purchases, SNAPSHOTS, List.of())));

        assertThat(input.items()).containsExactly(
                new ItemTiming("Enduring Speed", 2.1, null),
                new ItemTiming("Extended Magazine", 5.0, 2.5));
    }

    @Test
    void itemNameFallsBackToClassName() {
        AnalysisInput input = service.buildInput(full(defaultMatch(),
                player(List.of(new ItemPurchase(105, 60, null)), SNAPSHOTS, List.of())));

        assertThat(input.items()).containsExactly(new ItemTiming("upgrade_unnamed", 1.0, null));
    }

    @Test
    void soldItemsStillCount() {
        List<ItemPurchase> purchases = List.of(
                new ItemPurchase(101, 120, 700),
                new ItemPurchase(102, 300, null));

        AnalysisInput input = service.buildInput(full(defaultMatch(), player(purchases, SNAPSHOTS, List.of())));

        assertThat(input.items()).extracting(ItemTiming::name).containsExactly("Extended Magazine", "Swift Striker");
    }

    // 15. names

    @Test
    void heroAndRankNamesResolveWithFallbacks() {
        assertThat(service.buildInput(historyOnly(matchForHero(1))).hero()).isEqualTo("Infernus");
        assertThat(service.buildInput(historyOnly(matchForHero(2))).hero()).isEqualTo("hero_gigawatt");
        assertThat(service.buildInput(historyOnly(matchForHero(99))).hero()).isEqualTo("hero_99");

        assertThat(rankNameFor(new MatchRank(7, 3))).isEqualTo("Archon 3");
        assertThat(rankNameFor(new MatchRank(7, 0))).isEqualTo("Archon");
        assertThat(rankNameFor(new MatchRank(9, 2))).isNull();
        assertThat(service.buildInput(request(defaultMatch(), null, null, Map.of(), Map.of())).rank()).isNull();
    }

    // 16-17. fixtures and size

    @Test
    void buildsInputFromRecordedFixtures() throws IOException {
        ObjectMapper mapper = new JsonMapper();
        List<MatchHistoryEntry> history = read(mapper, "match-history.json", new TypeReference<>() { });
        Map<String, MetricPercentiles> benchmarks = read(mapper, "player-stats-metrics.json", new TypeReference<>() { });
        List<ItemStat> itemStatList = read(mapper, "item-stats.json", new TypeReference<>() { });
        List<ItemAsset> items = read(mapper, "assets-items.json", new TypeReference<>() { });
        List<HeroAsset> heroes = read(mapper, "assets-heroes.json", new TypeReference<>() { });
        Map<Long, ItemStat> itemStats = itemStatList.stream()
                .collect(Collectors.toMap(ItemStat::itemId, Function.identity()));

        MatchHistoryEntry match = history.getFirst();
        MatchRank rank = service.benchmarkRank(match, null).orElseThrow();
        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, match.playerTeam(), match.heroId(),
                List.of(new ItemPurchase(2521299219L, 0, null), new ItemPurchase(1548066885L, 240, null)),
                SNAPSHOTS, List.of());
        MatchTelemetry telemetry = new MatchTelemetry(match.matchId(), match.matchDurationS(), List.of(player));

        AnalysisInput input = service.buildInput(new MetricsRequest(ACCOUNT_ID, match, telemetry, rank,
                benchmarks, itemStats, items, heroes, List.of()));

        assertThat(input.hero()).isEqualTo(heroes.stream()
                .filter(hero -> hero.id() == match.heroId()).findFirst().orElseThrow().name());
        assertThat(input.language()).isEqualTo("en");
        assertThat(List.of("souls_per_min", "last_hits", "player_damage_per_min")).allSatisfy(key -> {
            MetricComparison metric = input.metrics().get(key);
            assertThat(metric).isNotNull();
            assertThat(metric.p25()).isNotNull();
            assertThat(metric.p50()).isNotNull();
            assertThat(metric.p75()).isNotNull();
        });
        assertThat(input.items()).hasSizeLessThanOrEqualTo(4);
    }

    @Test
    void inputSerializesWithinBudget() {
        List<ItemPurchase> purchases = List.of(
                new ItemPurchase(101, 120, null),
                new ItemPurchase(102, 300, null),
                new ItemPurchase(103, 480, null),
                new ItemPurchase(301, 900, null));
        List<PowerUpBuff> buffs = List.of(new PowerUpBuff("perm_a", true, List.of(420, 900)));
        AnalysisInput input = service.buildInput(full(defaultMatch(), player(purchases, SNAPSHOTS, buffs)));
        assertThat(input.metrics().keySet()).containsExactlyElementsOf(ALL_KEYS);
        assertThat(input.items()).hasSize(4);

        String json = new JsonMapper().writeValueAsString(input);

        assertThat(json).contains("\"duration_min\"", "\"avg_bought_at_min\"");
        assertThat(json.length()).isLessThan(6_000);
    }

    // helpers

    private String rankNameFor(MatchRank rank) {
        return service.buildInput(request(defaultMatch(), null, rank, BENCHMARKS, ITEM_STATS)).rank();
    }

    private static <T> T read(ObjectMapper mapper, String fixture, TypeReference<T> type) throws IOException {
        try (InputStream in = new ClassPathResource("fixtures/deadlock-api/" + fixture).getInputStream()) {
            return mapper.readValue(in, type);
        }
    }

    private static MetricsRequest request(MatchHistoryEntry match, MatchTelemetry telemetry, MatchRank rank,
                                          Map<String, MetricPercentiles> benchmarks, Map<Long, ItemStat> itemStats) {
        return new MetricsRequest(ACCOUNT_ID, match, telemetry, rank, benchmarks, itemStats, ITEMS, HEROES, RANKS);
    }

    private static MetricsRequest historyOnly(MatchHistoryEntry match) {
        return request(match, null, RANK, BENCHMARKS, ITEM_STATS);
    }

    private static MetricsRequest full(MatchHistoryEntry match, PlayerTelemetry player) {
        MatchTelemetry telemetry = new MatchTelemetry(match.matchId(), match.matchDurationS(), List.of(player));
        return request(match, telemetry, RANK, BENCHMARKS, ITEM_STATS);
    }

    private static PlayerTelemetry player(List<ItemPurchase> items, List<StatSnapshot> snapshots,
                                          List<PowerUpBuff> buffs) {
        return new PlayerTelemetry(ACCOUNT_ID, 1, HERO_ID, items, snapshots, buffs);
    }

    private static MatchHistoryEntry defaultMatch() {
        return match(BUFF_ERA_START, DURATION_S, 36000, 1, 1);
    }

    private static MatchHistoryEntry match(Integer badge) {
        return new MatchHistoryEntry(MATCH_ID, HERO_ID, 30, BUFF_ERA_START, DURATION_S, 4, 1, 1, 0,
                9, 4, 12, 36000, 233, 21, badge);
    }

    private static MatchHistoryEntry matchForHero(int heroId) {
        return new MatchHistoryEntry(MATCH_ID, heroId, 30, BUFF_ERA_START, DURATION_S, 4, 1, 1, 0,
                9, 4, 12, 36000, 233, 21, 73);
    }

    // playerMatchOutcome is 0 (as in older real matches): the result must come from matchResult and playerTeam.
    private static MatchHistoryEntry match(long startTime, int durationS, long netWorth, int matchResult, int playerTeam) {
        return new MatchHistoryEntry(MATCH_ID, HERO_ID, 30, startTime, durationS, 4, matchResult, playerTeam, 0,
                9, 4, 12, netWorth, 233, 21, 73);
    }

    private static MatchHistoryEntry entry(long matchId, long startTime) {
        return new MatchHistoryEntry(matchId, HERO_ID, 30, startTime, DURATION_S, 4, 1, 1, 0,
                9, 4, 12, 36000, 233, 21, 73);
    }

    private static ItemStat itemStat(long itemId, double avgBuyTimeS) {
        return new ItemStat(itemId, 100, 50, 50, avgBuyTimeS, 20.0);
    }
}
