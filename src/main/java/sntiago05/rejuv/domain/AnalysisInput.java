package sntiago05.rejuv.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalysisInput(String hero, String rank, String result,
                            @JsonProperty("duration_min") double durationMin,
                            Map<String, MetricComparison> metrics,
                            List<ItemTiming> items,
                            String language,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY)
                            Map<String, Object> coach) {

    public AnalysisInput(String hero, String rank, String result, double durationMin,
                         Map<String, MetricComparison> metrics, List<ItemTiming> items, String language) {
        this(hero, rank, result, durationMin, metrics, items, language, Map.of());
    }

    public AnalysisInput {
        coach = coach == null ? Map.of() : Map.copyOf(coach);
    }

    public AnalysisInput withCoach(Map<String, Object> coach) {
        return new AnalysisInput(hero, rank, result, durationMin, metrics, items, language,
                coach == null ? Map.of() : Map.copyOf(coach));
    }

    /** p25/p50/p75 are null when no benchmark exists for the metric. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricComparison(double value, Double p25, Double p50, Double p75) {
    }

    /** avgBoughtAtMin is null when no benchmark exists for the item. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemTiming(String name,
                             @JsonProperty("bought_at_min") double boughtAtMin,
                             @JsonProperty("avg_bought_at_min") Double avgBoughtAtMin) {
    }
}
