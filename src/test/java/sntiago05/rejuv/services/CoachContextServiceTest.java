package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;

class CoachContextServiceTest {

    private static final CoachRequest DUMMY_REQUEST = new CoachRequest(
            1000001L,
            new MatchHistoryEntry(900000001L, 1, 1, 1700000000L, 1800, 4, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, 73),
            List.of(), null, null, 7, List.of(), List.of());

    @Test
    void noProvidersGivesEmptyMap() {
        CoachContextService service = new CoachContextService(List.of());
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result).isEmpty();
    }

    @Test
    void nullProvidersListInConstructorGivesEmptyMap() {
        CoachContextService service = new CoachContextService(null);
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result).isEmpty();
    }

    @Test
    void twoProvidersReturnKeysInSortedOrder() {
        CoachInsightProvider providerB = new FakeProvider("phase_stats", new CoachInsight("phase_stats", Map.of("p50", 100)));
        CoachInsightProvider providerA = new FakeProvider("deaths", new CoachInsight("deaths", List.of("death_1")));

        // Injected in reverse order: providerB ("phase_stats") before providerA ("deaths")
        CoachContextService service = new CoachContextService(List.of(providerB, providerA));
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        // Result keys should be sorted ascending: "deaths" before "phase_stats"
        assertThat(result.keySet()).containsExactly("deaths", "phase_stats");
        assertThat(result.get("deaths")).isEqualTo(List.of("death_1"));
        assertThat(result.get("phase_stats")).isEqualTo(Map.of("p50", 100));
    }

    @Test
    void emptyOptionalSkipped() {
        CoachInsightProvider providerWithData = new FakeProvider("deaths", new CoachInsight("deaths", List.of("death_1")));
        CoachInsightProvider emptyProvider = new FakeProvider("phase_stats", null);

        CoachContextService service = new CoachContextService(List.of(providerWithData, emptyProvider));
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result.keySet()).containsExactly("deaths");
    }

    @Test
    void throwingProviderSkippedWhileOthersStillRun() {
        CoachInsightProvider throwingProvider = new CoachInsightProvider() {
            @Override
            public String key() {
                return "a_throwing";
            }

            @Override
            public Optional<CoachInsight> provide(CoachRequest request) {
                throw new DeadlockApiException("api unavailable");
            }
        };
        CoachInsightProvider workingProvider = new FakeProvider("z_working", new CoachInsight("z_working", "ok"));

        CoachContextService service = new CoachContextService(List.of(throwingProvider, workingProvider));
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result).containsExactly(Map.entry("z_working", "ok"));
    }

    @Test
    void keyMismatchDiscarded() {
        // Provider declares key "expected_key", but returns insight with "wrong_key"
        CoachInsightProvider mismatchedProvider = new FakeProvider("expected_key", new CoachInsight("wrong_key", "payload"));
        CoachInsightProvider validProvider = new FakeProvider("valid_key", new CoachInsight("valid_key", "payload"));

        CoachContextService service = new CoachContextService(List.of(mismatchedProvider, validProvider));
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result).containsExactly(Map.entry("valid_key", "payload"));
    }

    @Test
    void nullOrBlankKeyOrNullPayloadDiscarded() {
        CoachInsightProvider nullKeyProvider = new CoachInsightProvider() {
            @Override
            public String key() {
                return "key1";
            }

            @Override
            public Optional<CoachInsight> provide(CoachRequest request) {
                return Optional.of(new CoachInsight(null, "payload"));
            }
        };
        CoachInsightProvider nullPayloadProvider = new FakeProvider("key2", new CoachInsight("key2", null));

        CoachContextService service = new CoachContextService(List.of(nullKeyProvider, nullPayloadProvider));
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result).isEmpty();
    }

    @Test
    void duplicateKeyHandledFirstInSortedOrderWins() {
        CoachInsightProvider first = new FakeProvider("dup_key", new CoachInsight("dup_key", "first_value"));
        CoachInsightProvider second = new FakeProvider("dup_key", new CoachInsight("dup_key", "second_value"));

        CoachContextService service = new CoachContextService(List.of(first, second));
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result).containsExactly(Map.entry("dup_key", "first_value"));
    }

    @Test
    void providersCalledInKeyOrder() {
        List<String> executionOrder = new ArrayList<>();

        CoachInsightProvider providerC = new RecordingProvider("zeta", executionOrder);
        CoachInsightProvider providerA = new RecordingProvider("alpha", executionOrder);
        CoachInsightProvider providerB = new RecordingProvider("beta", executionOrder);

        // Injected out of order: C, A, B
        CoachContextService service = new CoachContextService(List.of(providerC, providerA, providerB));
        service.build(DUMMY_REQUEST);

        assertThat(executionOrder).containsExactly("alpha", "beta", "zeta");
    }

    @Test
    void significanceNoneSkippedWhileNotableMajorAndPayloadWithoutFieldKept() {
        CoachInsightProvider noneProvider = new FakeProvider("deaths",
                new CoachInsight("deaths", Map.of("significance", "none", "count", 5)));
        CoachInsightProvider notableProvider = new FakeProvider("phases",
                new CoachInsight("phases", Map.of("significance", "notable", "z", 1.5)));
        CoachInsightProvider majorProvider = new FakeProvider("build",
                new CoachInsight("build", Map.of("significance", "major", "item", "toxic")));
        CoachInsightProvider noFieldProvider = new FakeProvider("recent_form",
                new CoachInsight("recent_form", Map.of("games", 10)));

        CoachContextService service = new CoachContextService(
                List.of(noneProvider, notableProvider, majorProvider, noFieldProvider));
        Map<String, Object> result = service.build(DUMMY_REQUEST);

        assertThat(result.keySet()).containsExactly("build", "phases", "recent_form");
        assertThat(result.get("build")).isEqualTo(Map.of("significance", "major", "item", "toxic"));
        assertThat(result.get("phases")).isEqualTo(Map.of("significance", "notable", "z", 1.5));
        assertThat(result.get("recent_form")).isEqualTo(Map.of("games", 10));
    }

    private static class FakeProvider implements CoachInsightProvider {
        private final String key;
        private final CoachInsight insight;

        FakeProvider(String key, CoachInsight insight) {
            this.key = key;
            this.insight = insight;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Optional<CoachInsight> provide(CoachRequest request) {
            return Optional.ofNullable(insight);
        }
    }

    private static class RecordingProvider implements CoachInsightProvider {
        private final String key;
        private final List<String> callLog;

        RecordingProvider(String key, List<String> callLog) {
            this.key = key;
            this.callLog = callLog;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Optional<CoachInsight> provide(CoachRequest request) {
            callLog.add(key);
            return Optional.of(new CoachInsight(key, "data"));
        }
    }
}
