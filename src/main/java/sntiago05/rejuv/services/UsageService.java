package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.config.UsageProperties;
import sntiago05.rejuv.domain.AnalysisUsage;
import sntiago05.rejuv.domain.UsageStatus;
import sntiago05.rejuv.repositories.AnalysisUsageRepository;

@Service
public class UsageService {

    private final UsageProperties properties;
    private final AnalysisUsageRepository repository;
    private final PremiumChecker premiumChecker;
    private final Clock clock;

    @Autowired
    public UsageService(UsageProperties properties, AnalysisUsageRepository repository, PremiumChecker premiumChecker) {
        this(properties, repository, premiumChecker, Clock.systemUTC());
    }

    UsageService(UsageProperties properties, AnalysisUsageRepository repository, PremiumChecker premiumChecker, Clock clock) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (properties.weeklyFreeAnalyses() <= 0) {
            throw new IllegalArgumentException("weeklyFreeAnalyses must be positive, got: " + properties.weeklyFreeAnalyses());
        }
        this.properties = properties;
        this.repository = repository;
        this.premiumChecker = premiumChecker;
        this.clock = clock;
    }

    /**
     * @throws UsageLimitExceededException when limits are enabled, the user is not premium and used >= limit
     */
    public void checkAllowance(long discordUserId) {
        if (!properties.enabled() || premiumChecker.isPremium(discordUserId)) {
            return;
        }

        Instant weekStart = computeWeekStart();
        int used = (int) repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(discordUserId, weekStart);
        if (used >= properties.weeklyFreeAnalyses()) {
            throw new UsageLimitExceededException(computeResetsAt(), properties.weeklyFreeAnalyses());
        }
    }

    /**
     * Stores one usage row (call only after a fresh AI analysis was saved). No-op when limits are disabled.
     */
    public void recordAnalysis(long discordUserId, long matchId) {
        if (!properties.enabled()) {
            return;
        }
        repository.save(new AnalysisUsage(discordUserId, matchId, clock.instant()));
    }

    public UsageStatus status(long discordUserId) {
        boolean enabled = properties.enabled();
        boolean premium = premiumChecker.isPremium(discordUserId);
        Instant resetsAt = computeResetsAt();

        if (!enabled) {
            return new UsageStatus(false, premium, 0, properties.weeklyFreeAnalyses(), resetsAt);
        }

        Instant weekStart = computeWeekStart();
        int used = (int) repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(discordUserId, weekStart);
        return new UsageStatus(true, premium, used, properties.weeklyFreeAnalyses(), resetsAt);
    }

    private Instant computeWeekStart() {
        ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        LocalDate monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant computeResetsAt() {
        ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        LocalDate monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
