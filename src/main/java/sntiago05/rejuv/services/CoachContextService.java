package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates coaching insights from all registered {@link CoachInsightProvider}s sequentially
 * and deterministically.
 */
@Service
public class CoachContextService {

    private static final Logger log = LoggerFactory.getLogger(CoachContextService.class);

    private final List<CoachInsightProvider> providers;

    public CoachContextService(List<CoachInsightProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            this.providers = List.of();
            return;
        }

        List<CoachInsightProvider> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparing(CoachInsightProvider::key, Comparator.nullsLast(String::compareTo)));

        List<CoachInsightProvider> deduplicated = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (CoachInsightProvider provider : sorted) {
            String key = provider.key();
            if (key == null || key.isBlank()) {
                log.warn("Skipping coach provider with null or blank key: {}", provider.getClass().getSimpleName());
                continue;
            }
            if (!seenKeys.add(key)) {
                log.warn("Duplicate coach provider key '{}', skipping provider {}", key, provider.getClass().getSimpleName());
                continue;
            }
            deduplicated.add(provider);
        }
        this.providers = List.copyOf(deduplicated);
    }

    /**
     * Builds the aggregated coach insights map sequentially and deterministically. Never throws.
     *
     * @param request the coaching context
     * @return unmodifiable map of key -> payload, empty if no providers or no insights
     */
    public Map<String, Object> build(CoachRequest request) {
        if (providers.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (CoachInsightProvider provider : providers) {
            try {
                Optional<CoachInsight> opt = provider.provide(request);
                if (opt == null || opt.isEmpty()) {
                    continue;
                }
                CoachInsight insight = opt.get();
                if (insight.key() == null || insight.key().isBlank()) {
                    log.warn("Discarding coach insight with null or blank key from provider {}", provider.key());
                    continue;
                }
                if (!provider.key().equals(insight.key())) {
                    log.warn("Discarding coach insight with mismatched key '{}' from provider {}", insight.key(), provider.key());
                    continue;
                }
                if (insight.payload() == null) {
                    log.warn("Discarding coach insight with null payload from provider {}", provider.key());
                    continue;
                }
                result.put(insight.key(), insight.payload());
            } catch (RuntimeException ex) {
                log.warn("Coach provider '{}' failed with {}", provider.key(), ex.getClass().getSimpleName());
            } catch (Exception ex) {
                log.warn("Coach provider '{}' failed with unexpected {}", provider.key(), ex.getClass().getSimpleName());
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
