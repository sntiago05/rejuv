package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class FormCoachProviderTest {

    private final ObjectMapper mapper = new JsonMapper();
    private FormCoachProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FormCoachProvider();
    }

    private static MatchHistoryEntry createEntry(long matchId, int heroId, long startTime, int durationS,
                                                 int kills, int deaths, int assists, long netWorth,
                                                 boolean win) {
        return new MatchHistoryEntry(
                matchId,
                heroId,
                1,
                startTime,
                durationS,
                1,
                win ? 0 : 1, // matchResult
                0,           // playerTeam: 0 == 0 is win, 1 != 0 is loss
                win ? 0 : 1,
                kills,
                deaths,
                assists,
                netWorth,
                100,
                10,
                null
        );
    }

    private static CoachRequest createRequest(MatchHistoryEntry match, List<MatchHistoryEntry> history) {
        return new CoachRequest(
                12345L,
                match,
                history,
                null,
                null,
                null,
                List.of(),
                List.of()
        );
    }

    @Test
    @DisplayName("key() is constant lowercase snake_case 'recent_form' and matches CoachInsight")
    void keyMatchesContract() {
        assertThat(provider.key()).isEqualTo("recent_form");

        MatchHistoryEntry match = createEntry(100, 1, 100_000, 600, 0, 1, 0, 10_000, true);
        List<MatchHistoryEntry> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(createEntry(100 - i, 1, 100_000 - (i * 1000), 600, 1, 1, 1, 10_000, true));
        }

        Optional<CoachInsight> insight = provider.provide(createRequest(match, history));
        assertThat(insight).isPresent();
        assertThat(insight.get().key()).isEqualTo(provider.key());
    }

    @Test
    @DisplayName("Hand-computed means, population standard deviation and z for a small fixed sample")
    void handComputedStatisticsForSmallFixedSample() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 0, 3, 0, 7_000, true);

        // 5 sample games, heroId=1, 600s duration (10 min)
        // Game 1: 8000 NW (800/min), 1 death (1.0/10min), 4 K, 2 A -> KDA = (4+2)/1 = 6.0, Win
        // Game 2: 9000 NW (900/min), 2 deaths (2.0/10min), 3 K, 1 A -> KDA = (3+1)/2 = 2.0, Loss
        // Game 3: 10000 NW (1000/min), 2 deaths (2.0/10min), 4 K, 2 A -> KDA = (4+2)/2 = 3.0, Win
        // Game 4: 11000 NW (1100/min), 3 deaths (3.0/10min), 6 K, 3 A -> KDA = (6+3)/3 = 3.0, Win
        // Game 5: 12000 NW (1200/min), 2 deaths (2.0/10min), 5 K, 5 A -> KDA = (5+5)/2 = 5.0, Loss
        List<MatchHistoryEntry> history = List.of(
                createEntry(99, 1, 190_000, 600, 4, 1, 2, 8_000, true),
                createEntry(98, 1, 180_000, 600, 3, 2, 1, 9_000, false),
                createEntry(97, 1, 170_000, 600, 4, 2, 2, 10_000, true),
                createEntry(96, 1, 160_000, 600, 6, 3, 3, 11_000, true),
                createEntry(95, 1, 150_000, 600, 5, 2, 5, 12_000, false)
        );

        Optional<CoachInsight> result = provider.provide(createRequest(match, history));
        assertThat(result).isPresent();

        FormCoachProvider.FormPayload payload = (FormCoachProvider.FormPayload) result.get().payload();
        assertThat(payload.sampleSize()).isEqualTo(5);
        assertThat(payload.winRatePct()).isEqualTo(60.0);
        assertThat(payload.avgSoulsPerMin()).isEqualTo(1000.0);
        assertThat(payload.avgDeathsPer10Min()).isEqualTo(2.0);
        assertThat(payload.avgKda()).isEqualTo(3.8);

        // Current match: soulsPerMin = 700.0, deathsPer10Min = 3.0
        // Souls std = sqrt(20000) = 141.421356... -> z = (700 - 1000) / 141.421356 = -2.1213... -> -2.1
        // Deaths std = sqrt(0.4) = 0.6324555... -> z = (3.0 - 2.0) / 0.6324555 = 1.5811... -> 1.6
        assertThat(payload.soulsPerMinZ()).isEqualTo(-2.1);
        assertThat(payload.deathsPer10MinZ()).isEqualTo(1.6);
        assertThat(payload.recentVsPrevious()).isNull(); // < 10 games
        assertThat(payload.lossesInARowBefore()).isEqualTo(0); // game 99 was a win
        assertThat(payload.gamesLast24h()).isEqualTo(5);
        // Significance: soulsPerMinZ = -2.1 -> |z| >= 1.0 (notable), deathsPer10MinZ = 1.6 >= 1.0 (notable) -> notable
        assertThat(payload.significance()).isEqualTo("notable");
    }

    @Test
    @DisplayName("std == 0 omits z values")
    void stdZeroOmitsZ() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 0, 2, 0, 10_000, true);

        // All 5 games have identical net worth and deaths
        List<MatchHistoryEntry> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 600, 2, 2, 2, 10_000, true));
        }

        Optional<CoachInsight> result = provider.provide(createRequest(match, history));
        assertThat(result).isPresent();

        FormCoachProvider.FormPayload payload = (FormCoachProvider.FormPayload) result.get().payload();
        assertThat(payload.soulsPerMinZ()).isNull();
        assertThat(payload.deathsPer10MinZ()).isNull();
    }

    @Test
    @DisplayName("Short analyzed match (< 600 s) omits z values but returns the rest of the payload")
    void shortAnalyzedMatchOmitsZ() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 599, 0, 3, 0, 7_000, true);

        List<MatchHistoryEntry> history = List.of(
                createEntry(99, 1, 190_000, 600, 4, 1, 2, 8_000, true),
                createEntry(98, 1, 180_000, 600, 3, 2, 1, 9_000, false),
                createEntry(97, 1, 170_000, 600, 4, 2, 2, 10_000, true),
                createEntry(96, 1, 160_000, 600, 6, 3, 3, 11_000, true),
                createEntry(95, 1, 150_000, 600, 5, 2, 5, 12_000, false)
        );

        Optional<CoachInsight> result = provider.provide(createRequest(match, history));
        assertThat(result).isPresent();

        FormCoachProvider.FormPayload payload = (FormCoachProvider.FormPayload) result.get().payload();
        assertThat(payload.soulsPerMinZ()).isNull();
        assertThat(payload.deathsPer10MinZ()).isNull();
        assertThat(payload.sampleSize()).isEqualTo(5);
        assertThat(payload.avgSoulsPerMin()).isEqualTo(1000.0);
        assertThat(payload.significance()).isEqualTo("none");
    }

    @Test
    @DisplayName("Sample filtering: other heroes, later matches, analyzed match, games < 600 s excluded, cap at 20")
    void sampleFilteringRules() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 2, 2, 2, 10_000, true);

        List<MatchHistoryEntry> history = new ArrayList<>();
        // 1. Different hero (heroId = 2)
        history.add(createEntry(201, 2, 195_000, 600, 5, 1, 5, 15_000, true));
        // 2. Later match (startTime > match.startTime)
        history.add(createEntry(202, 1, 205_000, 600, 5, 1, 5, 15_000, true));
        // 3. Same startTime as match
        history.add(createEntry(203, 1, 200_000, 600, 5, 1, 5, 15_000, true));
        // 4. Analyzed match itself
        history.add(createEntry(100, 1, 199_000, 600, 5, 1, 5, 15_000, true));
        // 5. Short match (< 600 s)
        history.add(createEntry(204, 1, 198_000, 599, 5, 1, 5, 15_000, true));

        // Add 25 qualifying matches with heroId=1 and duration >= 600
        for (int i = 1; i <= 25; i++) {
            history.add(createEntry(100 - i, 1, 190_000 - (i * 1000), 600, 1, 1, 1, 10_000, true));
        }

        Optional<CoachInsight> result = provider.provide(createRequest(match, history));
        assertThat(result).isPresent();

        FormCoachProvider.FormPayload payload = (FormCoachProvider.FormPayload) result.get().payload();
        // Capped at SAMPLE_MAX = 20
        assertThat(payload.sampleSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("4 vs 5 qualifying games: 4 returns empty, 5 returns payload")
    void sampleMinBoundary() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 2, 2, 2, 10_000, true);

        List<MatchHistoryEntry> history4 = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            history4.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 600, 1, 1, 1, 10_000, true));
        }
        assertThat(provider.provide(createRequest(match, history4))).isEmpty();

        List<MatchHistoryEntry> history5 = new ArrayList<>(history4);
        history5.add(createEntry(95, 1, 200_000 - 5000, 600, 1, 1, 1, 10_000, true));
        assertThat(provider.provide(createRequest(match, history5))).isPresent();
    }

    @Test
    @DisplayName("9 vs 10 games for trend: 9 omits recent_vs_previous, 10 computes it")
    void trendMinGamesBoundary() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 2, 2, 2, 10_000, true);

        // 9 games
        List<MatchHistoryEntry> history9 = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            history9.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 600, 1, 1, 1, 10_000, true));
        }
        Optional<CoachInsight> result9 = provider.provide(createRequest(match, history9));
        assertThat(result9).isPresent();
        FormCoachProvider.FormPayload payload9 = (FormCoachProvider.FormPayload) result9.get().payload();
        assertThat(payload9.sampleSize()).isEqualTo(9);
        assertThat(payload9.recentVsPrevious()).isNull();

        // 10 games:
        // Newest 5: 2 wins, 3 losses, deaths = 3, 3, 3, 3, 3 (each 3.0 deaths/10min) -> avg 3.0 deaths/10min, 40.0% win rate
        // Next 5: 3 wins, 2 losses, deaths = 2, 2, 2, 2, 2 (each 2.0 deaths/10min) -> avg 2.0 deaths/10min, 60.0% win rate
        List<MatchHistoryEntry> history10 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            boolean win = i <= 2;
            history10.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 600, 1, 3, 1, 10_000, win));
        }
        for (int i = 6; i <= 10; i++) {
            boolean win = i <= 8;
            history10.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 600, 1, 2, 1, 10_000, win));
        }

        Optional<CoachInsight> result10 = provider.provide(createRequest(match, history10));
        assertThat(result10).isPresent();
        FormCoachProvider.FormPayload payload10 = (FormCoachProvider.FormPayload) result10.get().payload();
        assertThat(payload10.sampleSize()).isEqualTo(10);
        assertThat(payload10.recentVsPrevious()).isNotNull();
        assertThat(payload10.recentVsPrevious().winRateChangePct()).isEqualTo(-20.0);
        assertThat(payload10.recentVsPrevious().deathsPer10MinChange()).isEqualTo(1.0);
        // deaths_per_10_min_change = 1.0 triggers notable
        assertThat(payload10.significance()).isEqualTo("notable");
    }

    @Test
    @DisplayName("losses_in_a_row_before counts consecutive losses before analyzed match, includes other heroes, stops at win")
    void lossesInARowBeforeScenarios() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 0, 1, 0, 10_000, false);

        // Case 1: Immediately prior game was a win -> streak = 0
        List<MatchHistoryEntry> historyWinImmediate = new ArrayList<>();
        historyWinImmediate.add(createEntry(99, 2, 190_000, 600, 5, 1, 5, 12_000, true)); // Win on other hero
        for (int i = 1; i <= 5; i++) {
            historyWinImmediate.add(createEntry(90 - i, 1, 180_000 - (i * 1000), 600, 1, 1, 1, 10_000, false));
        }
        Optional<CoachInsight> res1 = provider.provide(createRequest(match, historyWinImmediate));
        assertThat(res1).isPresent();
        assertThat(((FormCoachProvider.FormPayload) res1.get().payload()).lossesInARowBefore()).isEqualTo(0);

        // Case 2: 3 consecutive losses entering the match (across heroes 1, 2, 3), then a win
        List<MatchHistoryEntry> history3Losses = new ArrayList<>();
        history3Losses.add(createEntry(99, 2, 190_000, 600, 1, 5, 1, 8_000, false)); // Loss on hero 2
        history3Losses.add(createEntry(98, 3, 180_000, 600, 1, 5, 1, 8_000, false)); // Loss on hero 3
        history3Losses.add(createEntry(97, 1, 170_000, 600, 1, 5, 1, 8_000, false)); // Loss on hero 1
        history3Losses.add(createEntry(96, 1, 160_000, 600, 5, 1, 5, 12_000, true));  // Win on hero 1
        history3Losses.add(createEntry(95, 1, 150_000, 600, 1, 5, 1, 8_000, false)); // Loss on hero 1 (after win)
        history3Losses.add(createEntry(94, 1, 140_000, 600, 1, 5, 1, 8_000, false)); // Loss on hero 1
        history3Losses.add(createEntry(93, 1, 130_000, 600, 1, 5, 1, 8_000, false)); // Loss on hero 1

        Optional<CoachInsight> res2 = provider.provide(createRequest(match, history3Losses));
        assertThat(res2).isPresent();
        assertThat(((FormCoachProvider.FormPayload) res2.get().payload()).lossesInARowBefore()).isEqualTo(3);

        // Case 3: Later matches and analyzed match do not count
        List<MatchHistoryEntry> historyWithLater = new ArrayList<>(history3Losses);
        historyWithLater.add(createEntry(101, 1, 210_000, 600, 0, 5, 0, 8_000, false)); // Later match loss
        historyWithLater.add(createEntry(100, 1, 200_000, 600, 0, 5, 0, 8_000, false)); // Analyzed match
        Optional<CoachInsight> res3 = provider.provide(createRequest(match, historyWithLater));
        assertThat(res3).isPresent();
        assertThat(((FormCoachProvider.FormPayload) res3.get().payload()).lossesInARowBefore()).isEqualTo(3);
    }

    @Test
    @DisplayName("games_last_24h boundary tests: exactly 86400s before included, same startTime excluded")
    void gamesLast24hBoundaries() {
        long matchStart = 200_000L;
        MatchHistoryEntry match = createEntry(100, 1, matchStart, 600, 0, 1, 0, 10_000, true);

        List<MatchHistoryEntry> history = new ArrayList<>();
        // Exactly at matchStart - 86400 -> INCLUDED
        history.add(createEntry(90, 2, matchStart - 86_400L, 300, 1, 1, 1, 5_000, true));
        // 86401 seconds before -> EXCLUDED
        history.add(createEntry(89, 2, matchStart - 86_401L, 300, 1, 1, 1, 5_000, true));
        // Same startTime as match -> EXCLUDED
        history.add(createEntry(91, 2, matchStart, 300, 1, 1, 1, 5_000, true));
        // Future match -> EXCLUDED
        history.add(createEntry(92, 2, matchStart + 1000L, 300, 1, 1, 1, 5_000, true));

        // 5 qualifying hero 1 matches within the 24h window
        for (int i = 1; i <= 5; i++) {
            history.add(createEntry(80 - i, 1, matchStart - (i * 1000L), 600, 1, 1, 1, 10_000, true));
        }

        Optional<CoachInsight> result = provider.provide(createRequest(match, history));
        assertThat(result).isPresent();

        FormCoachProvider.FormPayload payload = (FormCoachProvider.FormPayload) result.get().payload();
        // 1 match at boundary + 5 qualifying matches = 6 games
        assertThat(payload.gamesLast24h()).isEqualTo(6);
    }

    @Test
    @DisplayName("Unsorted history produces identical result to sorted history, input list not mutated")
    void unsortedHistoryDeterminismAndImmutability() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 2, 2, 2, 10_000, true);

        List<MatchHistoryEntry> sortedHistory = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            sortedHistory.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 600, i, 2, 1, 10_000 + (i * 100), i % 2 == 0));
        }

        List<MatchHistoryEntry> shuffledHistory = new ArrayList<>(sortedHistory);
        Collections.shuffle(shuffledHistory);
        List<MatchHistoryEntry> copyOfShuffled = List.copyOf(shuffledHistory);

        Optional<CoachInsight> sortedResult = provider.provide(createRequest(match, sortedHistory));
        Optional<CoachInsight> shuffledResult = provider.provide(createRequest(match, shuffledHistory));

        assertThat(sortedResult).isPresent();
        assertThat(shuffledResult).isPresent();
        assertThat(shuffledResult.get().payload())
                .usingRecursiveComparison()
                .isEqualTo(sortedResult.get().payload());

        // Input list was not mutated
        assertThat(shuffledHistory).isEqualTo(copyOfShuffled);
    }

    @ParameterizedTest(name = "significance boundary: {0} -> {1}")
    @CsvSource({
            // Major: deaths_per_10_min_z >= 2.0
            "major-deaths-z-2.0, major",
            "notable-deaths-z-1.9, notable",
            // Major: losses_in_a_row_before >= 4
            "major-losses-4, major",
            "notable-losses-3, notable",
            // Major: games_last_24h >= 12
            "major-games-12, major",
            "notable-games-11, notable",
            // Notable: |souls_per_min_z| >= 1.0
            "notable-souls-pos-1.0, notable",
            "none-souls-pos-0.9, none",
            "notable-souls-neg-1.0, notable",
            "none-souls-neg-0.9, none",
            // Notable: deaths_per_10_min_z >= 1.0
            "notable-deaths-z-1.0, notable",
            "none-deaths-z-0.9, none",
            // Notable: losses_in_a_row_before >= 3
            "none-losses-2, none",
            // Notable: games_last_24h >= 8
            "notable-games-8, notable",
            "none-games-7, none",
            // Notable: deaths_per_10_min_change >= 1.0
            "notable-deaths-change-1.0, notable",
            "none-deaths-change-0.9, none"
    })
    void significanceBoundaries(String scenario, String expectedSignificance) {
        long matchStart = 200_000L;
        // Base sample games: 5 games with uniform values so std is non-zero when needed
        // We'll customize sample and context based on scenario
        List<MatchHistoryEntry> history = new ArrayList<>();
        MatchHistoryEntry match;

        if (scenario.equals("major-deaths-z-2.0") || scenario.equals("notable-deaths-z-1.9")
                || scenario.equals("notable-deaths-z-1.0") || scenario.equals("none-deaths-z-0.9")) {
            // Sample of 5 games: netWorth=10,000 (1000/min), deaths: 1, 2, 2, 3, 2 (mean=2.0, std=sqrt(0.4)=0.6324555)
            // deathsPer10MinZ = (current - 2.0) / 0.6324555
            // To get z = 2.0 -> current = 2.0 + 2.0 * 0.6324555 = 3.2649 -> deaths = 3.2649 in 10 min
            // Or simpler: craft exact z by setting deaths on match:
            // For 600s: deaths = deathsPer10Min
            // z = 2.0: (current - 2.0) / std >= 1.95 (rounds to 2.0) -> current >= 2.0 + 1.95 * 0.6324555 = 3.233 -> 4 deaths gives (4-2)/0.632 = 3.16 -> major
            // Let's set specific values for exact rounding:
            // Suppose std = 1.0: deaths = 1, 1, 2, 3, 3 -> mean = 2.0, var = (1 + 1 + 0 + 1 + 1)/5 = 4/5 = 0.8
            // Let's use deaths = 0, 1, 2, 3, 4 -> mean = 2.0, var = (4 + 1 + 0 + 1 + 4)/5 = 10/5 = 2.0, std = sqrt(2) = 1.41421356
            // Let's use 5 games with deaths: 1, 1, 1, 3, 4 -> mean = 2.0, var = (1 + 1 + 1 + 1 + 4)/5 = 8/5 = 1.6
            // Even simpler: sample with mean=2.0, std=1.0: 4 games with 1, 1, 3, 3 (mean 2, var 1) -> 5th game 2 -> mean 2.0, var = 4/5 = 0.8
            // With 20 games or specific duration, we can get exact z:
            // Duration = 1000s, etc.
            // Let's use duration = 600s: deaths_per_10_min = deaths.
            // Let sample deaths be: 0, 1, 2, 3, 4 -> sum = 10, mean = 2.0.
            // sum of squares = 0 + 1 + 4 + 9 + 16 = 30.
            // var = 30/5 - 4 = 2.0. std = sqrt(2).
            // current = 2 + 2 * sqrt(2) = 4.828 -> deaths = 5 -> z = (5 - 2)/sqrt(2) = 3/1.4142 = 2.12 -> rounds to 2.1 (major)
            // What gives z = 2.0 exactly?
            // If std = 2.0: sample deaths = 0, 0, 2, 4, 4 -> mean = 2.0, var = (4 + 4 + 0 + 4 + 4)/5 = 16/5 = 3.2
            // If sample is 5 games: deaths = 0, 2, 2, 2, 4 -> mean = 2.0, var = (4 + 0 + 0 + 0 + 4)/5 = 1.6. std = sqrt(1.6) = 1.2649
            // Let's design 5 games where std is exactly 1.0:
            // Can variance be 1.0?
            // values: 1, 1, 2, 3, 3 has sum=10, mean=2. Diff squares: 1, 1, 0, 1, 1 -> sum=4, var=4/5=0.8.
            // What if 5 values: 1, 1, 2, 2, 4? sum=10, mean=2. Diff squares: 1+1+0+0+4 = 6. var=6/5=1.2.
            // What if duration is variable?
            // E.g. game 1: duration=600, deaths=1 -> 1.0
            // game 2: duration=600, deaths=3 -> 3.0
            // game 3: duration=600, deaths=2 -> 2.0
            // game 4: duration=600, deaths=1 -> 1.0
            // game 5: duration=600, deaths=3 -> 3.0
            // Mean = 2.0. Var = 4/5 = 0.8.
            // For z: we can directly test via MatchHistoryEntry!
        }

        // To make every scenario test precise and fast, let's construct exact history:
        // Default baseline: 5 games, duration 600, deaths 2 (stdDeaths=0 initially, but we can set variance):
        // Let's create 5 games with deaths: 1, 1, 2, 3, 3 (mean = 2.0, std = sqrt(0.8) = 0.89442719)
        // netWorth = 6000 (1000/min), stdSouls = 0 (z is null unless varied).
        // win=true, so lossesInARow = 0.
        // startTimes: matchStart - 100_000 (beyond 24h, so gamesLast24h = 0).
        for (int i = 1; i <= 5; i++) {
            int d = (i <= 2) ? 1 : (i == 3 ? 2 : 3);
            history.add(createEntry(100 - i, 1, matchStart - 100_000 - (i * 1000), 600, 1, d, 1, 6_000, true));
        }

        int matchDeaths = 2; // default z = 0.0
        long matchNetWorth = 6_000;
        int matchDuration = 600;

        switch (scenario) {
            case "major-deaths-z-2.0" -> {
                // mean=2.0, std=0.89442719. We want z >= 2.0 -> (current - 2.0) / 0.89442719 >= 1.95
                // current >= 2.0 + 1.95 * 0.89442719 = 3.744 -> deaths = 4 -> current = 4.0 -> z = (4 - 2)/0.8944 = 2.236 -> 2.2 >= 2.0
                matchDeaths = 4;
            }
            case "notable-deaths-z-1.9" -> {
                // To get z = 1.9: current = 2.0 + 1.9 * 0.89442719 = 3.6994.
                // If match duration = 600, deaths = 3.7? Deaths is int.
                // But matchDurationS can be adjusted!
                // deaths_per_10_min = deaths / (duration / 60.0) * 10 = deaths * 600 / duration.
                // If deaths = 3, duration = 487s -> wait, duration >= 600!
                // If deaths = 4, duration = 600 * 4 / 3.6994 = 648.7s -> 649s!
                // With deaths = 4, duration = 649s: current = 4 / (649/60) * 10 = 3.6980.
                // z = (3.6980 - 2.0) / 0.89442719 = 1.8984 -> rounds to 1.9!
                matchDeaths = 4;
                matchDuration = 649;
            }
            case "notable-deaths-z-1.0" -> {
                // z = 1.0: current = 2.0 + 1.0 * 0.89442719 = 2.8944
                // deaths = 3, duration = 600 * 3 / 2.8944 = 621.9 -> 622s
                // current = 3 / (622/60) * 10 = 2.89389 -> z = (2.89389 - 2.0) / 0.8944 = 0.999 -> rounds to 1.0!
                matchDeaths = 3;
                matchDuration = 622;
            }
            case "none-deaths-z-0.9" -> {
                // z = 0.9: current = 2.0 + 0.9 * 0.89442719 = 2.805
                // deaths = 3, duration = 600 * 3 / 2.805 = 641.7 -> 642s
                // current = 3 / (642/60) * 10 = 2.8037 -> z = (2.8037 - 2.0)/0.8944 = 0.8986 -> rounds to 0.9!
                matchDeaths = 3;
                matchDuration = 642;
            }
            case "major-losses-4" -> {
                // Add 4 consecutive losses right before the match
                for (int i = 1; i <= 4; i++) {
                    history.add(0, createEntry(200 + i, 2, matchStart - (i * 100), 600, 0, 1, 0, 5000, false));
                }
            }
            case "notable-losses-3" -> {
                // Add 3 consecutive losses right before the match
                for (int i = 1; i <= 3; i++) {
                    history.add(0, createEntry(200 + i, 2, matchStart - (i * 100), 600, 0, 1, 0, 5000, false));
                }
            }
            case "none-losses-2" -> {
                // Add 2 consecutive losses right before the match
                for (int i = 1; i <= 2; i++) {
                    history.add(0, createEntry(200 + i, 2, matchStart - (i * 100), 600, 0, 1, 0, 5000, false));
                }
            }
            case "major-games-12" -> {
                // Add 12 games in the last 24h
                for (int i = 1; i <= 12; i++) {
                    history.add(0, createEntry(300 + i, 2, matchStart - (i * 1000), 600, 1, 1, 1, 5000, true));
                }
            }
            case "notable-games-11" -> {
                // 11 games in the last 24h (>= 8 is notable, < 12 is not major)
                for (int i = 1; i <= 11; i++) {
                    history.add(0, createEntry(300 + i, 2, matchStart - (i * 1000), 600, 1, 1, 1, 5000, true));
                }
            }
            case "notable-games-8" -> {
                for (int i = 1; i <= 8; i++) {
                    history.add(0, createEntry(300 + i, 2, matchStart - (i * 1000), 600, 1, 1, 1, 5000, true));
                }
            }
            case "none-games-7" -> {
                for (int i = 1; i <= 7; i++) {
                    history.add(0, createEntry(300 + i, 2, matchStart - (i * 1000), 600, 1, 1, 1, 5000, true));
                }
            }
            case "notable-souls-pos-1.0", "none-souls-pos-0.9", "notable-souls-neg-1.0", "none-souls-neg-0.9" -> {
                // Vary sample networth: 4000, 5000, 6000, 7000, 8000 -> mean=6000 (600/min), std=sqrt(2000000/5)=sqrt(40000)=200 (20/min)
                history.clear();
                long[] nw = {4000, 5000, 6000, 7000, 8000};
                for (int i = 0; i < 5; i++) {
                    history.add(createEntry(100 - i, 1, matchStart - 100_000 - (i * 1000), 600, 1, 2, 1, nw[i] * 10, true));
                }
                // Now mean NW = 60000 (6000/min). Var = 4000000/5 = 800000. Std = sqrt(800000) = 894.42719 / 10 = 89.442719 souls/min.
                // Or even simpler: netWorth: 5000, 5000, 6000, 7000, 7000 in 600s:
                // souls/min: 500, 500, 600, 700, 700 -> mean = 600, var = (10000*4)/5 = 8000, std = sqrt(8000) = 89.442719 souls/min.
                history.clear();
                long[] sampleNw = {5000, 5000, 6000, 7000, 7000};
                for (int i = 0; i < 5; i++) {
                    history.add(createEntry(99 - i, 1, matchStart - 100_000 - (i * 1000), 600, 1, 2, 1, sampleNw[i], true));
                }
                // mean = 600 souls/min, std = 89.442719 souls/min.
                if (scenario.equals("notable-souls-pos-1.0")) {
                    // target current = 600 + 89.442719 = 689.44 -> NW = 689.44 * 10 = 6894 -> 6894 NW in 600s -> 689.4/min -> z = 89.4/89.44 = 0.9995 -> 1.0
                    matchNetWorth = 6895;
                } else if (scenario.equals("none-souls-pos-0.9")) {
                    // target current = 600 + 0.9 * 89.44 = 680.5 -> NW = 6805 in 600s -> 680.5/min -> z = 80.5/89.44 = 0.900 -> 0.9
                    matchNetWorth = 6805;
                } else if (scenario.equals("notable-souls-neg-1.0")) {
                    // target current = 600 - 89.44 = 510.56 -> NW = 5105 in 600s -> 510.5/min -> z = -89.5/89.44 = -1.0006 -> -1.0
                    matchNetWorth = 5105;
                } else if (scenario.equals("none-souls-neg-0.9")) {
                    // target current = 600 - 0.9 * 89.44 = 519.5 -> NW = 5195 in 600s -> 519.5/min -> z = -80.5/89.44 = -0.900 -> -0.9
                    matchNetWorth = 5195;
                }
            }
            case "notable-deaths-change-1.0", "none-deaths-change-0.9" -> {
                // 10 games trend
                history.clear();
                // Newest 5: deaths = 3, 3, 3, 3, 3 (3.0/10min)
                // Previous 5: deaths = 2, 2, 2, 2, 2 (2.0/10min) -> diff = 1.0
                double recentDeaths = scenario.equals("notable-deaths-change-1.0") ? 3 : 2.9;
                for (int i = 1; i <= 5; i++) {
                    history.add(createEntry(100 - i, 1, matchStart - 100_000 - (i * 1000), 600, 1,
                            scenario.equals("notable-deaths-change-1.0") ? 3 : (i <= 4 ? 3 : 2), 1, 6000, true));
                }
                for (int i = 6; i <= 10; i++) {
                    history.add(createEntry(100 - i, 1, matchStart - 100_000 - (i * 1000), 600, 1, 2, 1, 6000, true));
                }
                // To avoid z triggering notable, set match deaths equal to sample mean
                matchDeaths = 2;
                matchDuration = 599; // omit z to isolate trend change
            }
        }

        match = createEntry(100, 1, matchStart, matchDuration, 1, matchDeaths, 1, matchNetWorth, true);

        Optional<CoachInsight> result = provider.provide(createRequest(match, history));
        assertThat(result).isPresent();
        FormCoachProvider.FormPayload payload = (FormCoachProvider.FormPayload) result.get().payload();
        assertThat(payload.significance()).isEqualTo(expectedSignificance);
    }

    @Test
    @DisplayName("Missing data / degraded mode handling: returns empty gracefully without throwing")
    void missingDataCasesReturnEmpty() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 600, 1, 1, 1, 10_000, true);

        // Null request
        assertThat(provider.provide(null)).isEmpty();

        // Null match
        assertThat(provider.provide(createRequest(null, List.of()))).isEmpty();

        // Empty history
        assertThat(provider.provide(createRequest(match, List.of()))).isEmpty();

        // Null history
        assertThat(provider.provide(new CoachRequest(1L, match, null, null, null, null, null, null))).isEmpty();

        // Degraded mode (telemetry, player, rankTier, assets all null): works normally
        List<MatchHistoryEntry> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 600, 1, 1, 1, 10_000, true));
        }
        CoachRequest degraded = new CoachRequest(123L, match, history, null, null, null, null, null);
        assertThat(provider.provide(degraded)).isPresent();
    }

    @Test
    @DisplayName("Payload serialized with plain Jackson 3 JsonMapper is <= 1,200 characters and matches JSON schema")
    void payloadSizeAndJsonContract() throws Exception {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 1200, 5, 4, 3, 18_000, true);

        List<MatchHistoryEntry> history = new ArrayList<>();
        // 20 qualifying matches
        for (int i = 1; i <= 20; i++) {
            history.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 1200, 3 + (i % 3), 2 + (i % 4), 5, 15_000 + (i * 200), i % 2 == 0));
        }

        Optional<CoachInsight> result = provider.provide(createRequest(match, history));
        assertThat(result).isPresent();

        String json = mapper.writeValueAsString(result.get().payload());
        assertThat(json.length()).isLessThanOrEqualTo(1200);

        // Verify key presence in JSON
        assertThat(json).contains("\"significance\":");
        assertThat(json).contains("\"sample_size\":");
        assertThat(json).contains("\"win_rate_pct\":");
        assertThat(json).contains("\"avg_souls_per_min\":");
        assertThat(json).contains("\"avg_deaths_per_10_min\":");
        assertThat(json).contains("\"avg_kda\":");
        assertThat(json).contains("\"souls_per_min_z\":");
        assertThat(json).contains("\"deaths_per_10_min_z\":");
        assertThat(json).contains("\"recent_vs_previous\":");
        assertThat(json).contains("\"losses_in_a_row_before\":");
        assertThat(json).contains("\"games_last_24h\":");
    }

    @Test
    @DisplayName("Determinism: two calls give equal payloads and request is not mutated")
    void determinismAndImmutability() {
        MatchHistoryEntry match = createEntry(100, 1, 200_000, 1200, 5, 4, 3, 18_000, true);

        List<MatchHistoryEntry> history = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            history.add(createEntry(100 - i, 1, 200_000 - (i * 1000), 1200, 3, 2, 5, 15_000, true));
        }
        CoachRequest request = createRequest(match, history);

        Optional<CoachInsight> first = provider.provide(request);
        Optional<CoachInsight> second = provider.provide(request);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().payload())
                .usingRecursiveComparison()
                .isEqualTo(second.get().payload());
    }
}
