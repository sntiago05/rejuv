package sntiago05.rejuv.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Spend guard limits for new AI analyses. A value {@code <= 0} (or a zero cooldown) turns that single limit off;
 * {@code enabled=false} turns the whole guard off.
 */
@ConfigurationProperties("rejuv.analysis-limits")
public record AnalysisLimitsProperties(@DefaultValue("true") boolean enabled,
                                       @DefaultValue("60s") Duration cooldown,
                                       @DefaultValue("10") int userDaily,
                                       @DefaultValue("150") int globalDaily) {
}
