package sntiago05.rejuv.domain;

public record MetricGap(String metricKey,
                        double playerValue,
                        double topP50,
                        Double rankP50,
                        double gapPercent,
                        boolean playerBetter) {
}
