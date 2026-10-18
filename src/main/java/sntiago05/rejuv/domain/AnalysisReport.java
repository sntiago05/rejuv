package sntiago05.rejuv.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AnalysisReport(int heroId,
                             String hero,
                             String rank,
                             String result,
                             double durationMin,
                             AnalysisResult analysis,
                             Map<String, AnalysisInput.MetricComparison> metrics,
                             boolean degraded,
                             MatchVisuals visuals) {

    public AnalysisReport(int heroId, String hero, String rank, String result, double durationMin,
                          AnalysisResult analysis, Map<String, AnalysisInput.MetricComparison> metrics, boolean degraded) {
        this(heroId, hero, rank, result, durationMin, analysis, metrics, degraded, null);
    }

    public AnalysisReport {
        metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }
}
