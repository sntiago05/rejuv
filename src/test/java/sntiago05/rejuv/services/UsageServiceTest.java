package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.config.UsageProperties;
import sntiago05.rejuv.domain.AnalysisUsage;
import sntiago05.rejuv.domain.UsageStatus;
import sntiago05.rejuv.repositories.AnalysisUsageRepository;

class UsageServiceTest {

    private static final long USER_ID = 123456789L;
    private static final long MATCH_ID = 900000001L;
    private static final Instant FIXED_NOW = Instant.parse("2026-10-21T15:30:00Z"); // Wednesday
    private static final Instant EXPECTED_WEEK_START = Instant.parse("2026-10-19T00:00:00Z"); // Monday
    private static final Instant EXPECTED_RESETS_AT = Instant.parse("2026-10-26T00:00:00Z"); // Next Monday

    private AnalysisUsageRepository repository;
    private PremiumChecker premiumChecker;
    private Clock clock;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisUsageRepository.class);
        premiumChecker = mock(PremiumChecker.class);
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }

    @Test
    void nonPositiveLimitRejectedAtCreation() {
        assertThatThrownBy(() -> new UsageProperties(true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weeklyFreeAnalyses must be positive");

        assertThatThrownBy(() -> new UsageProperties(true, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weeklyFreeAnalyses must be positive");
    }

    @Test
    void flagOffNeverThrowsAndNeverRecords() {
        UsageProperties properties = new UsageProperties(false, 3);
        UsageService service = new UsageService(properties, repository, premiumChecker, clock);

        service.checkAllowance(USER_ID);
        service.recordAnalysis(USER_ID, MATCH_ID);

        verify(repository, never()).countByDiscordUserIdAndCreatedAtGreaterThanEqual(anyLong(), any());
        verify(repository, never()).save(any());

        UsageStatus status = service.status(USER_ID);
        assertThat(status.limitsEnabled()).isFalse();
        assertThat(status.premium()).isFalse();
        assertThat(status.used()).isEqualTo(0);
        assertThat(status.limit()).isEqualTo(3);
        assertThat(status.resetsAt()).isEqualTo(EXPECTED_RESETS_AT);
        assertThat(status.unlimited()).isTrue();
    }

    @Test
    void limitReachedThrowsWithRightResetsAtAndLimit() {
        UsageProperties properties = new UsageProperties(true, 3);
        UsageService service = new UsageService(properties, repository, premiumChecker, clock);
        when(premiumChecker.isPremium(USER_ID)).thenReturn(false);
        when(repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(USER_ID, EXPECTED_WEEK_START)).thenReturn(3L);

        assertThatThrownBy(() -> service.checkAllowance(USER_ID))
                .isInstanceOf(UsageLimitExceededException.class)
                .satisfies(ex -> {
                    UsageLimitExceededException u = (UsageLimitExceededException) ex;
                    assertThat(u.limit()).isEqualTo(3);
                    assertThat(u.resetsAt()).isEqualTo(EXPECTED_RESETS_AT);
                });
    }

    @Test
    void underLimitDoesNotThrowAndRecordsFreshAnalysis() {
        UsageProperties properties = new UsageProperties(true, 3);
        UsageService service = new UsageService(properties, repository, premiumChecker, clock);
        when(premiumChecker.isPremium(USER_ID)).thenReturn(false);
        when(repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(USER_ID, EXPECTED_WEEK_START)).thenReturn(2L);

        service.checkAllowance(USER_ID);
        service.recordAnalysis(USER_ID, MATCH_ID);

        ArgumentCaptor<AnalysisUsage> captor = ArgumentCaptor.forClass(AnalysisUsage.class);
        verify(repository).save(captor.capture());
        AnalysisUsage saved = captor.getValue();
        assertThat(saved.getDiscordUserId()).isEqualTo(USER_ID);
        assertThat(saved.getMatchId()).isEqualTo(MATCH_ID);
        assertThat(saved.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void premiumNeverThrowsAndRecordsWhenLimitsEnabled() {
        UsageProperties properties = new UsageProperties(true, 3);
        UsageService service = new UsageService(properties, repository, premiumChecker, clock);
        when(premiumChecker.isPremium(USER_ID)).thenReturn(true);

        service.checkAllowance(USER_ID);
        verify(repository, never()).countByDiscordUserIdAndCreatedAtGreaterThanEqual(anyLong(), any());

        service.recordAnalysis(USER_ID, MATCH_ID);
        verify(repository).save(any(AnalysisUsage.class));

        UsageStatus status = service.status(USER_ID);
        assertThat(status.limitsEnabled()).isTrue();
        assertThat(status.premium()).isTrue();
        assertThat(status.unlimited()).isTrue();
    }

    @Test
    void weekBoundarySunday235959CountsInOldWeekAndMonday000000StartsNewOne() {
        UsageProperties properties = new UsageProperties(true, 3);

        // Sunday 23:59:59 UTC
        Instant sundayNight = Instant.parse("2026-10-18T23:59:59Z");
        Clock sundayClock = Clock.fixed(sundayNight, ZoneOffset.UTC);
        UsageService sundayService = new UsageService(properties, repository, premiumChecker, sundayClock);

        sundayService.checkAllowance(USER_ID);
        verify(repository).countByDiscordUserIdAndCreatedAtGreaterThanEqual(eq(USER_ID), eq(Instant.parse("2026-10-12T00:00:00Z")));
        UsageStatus sundayStatus = sundayService.status(USER_ID);
        assertThat(sundayStatus.resetsAt()).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));

        // Monday 00:00:00 UTC
        Instant mondayMorning = Instant.parse("2026-10-19T00:00:00Z");
        Clock mondayClock = Clock.fixed(mondayMorning, ZoneOffset.UTC);
        UsageService mondayService = new UsageService(properties, repository, premiumChecker, mondayClock);

        mondayService.checkAllowance(USER_ID);
        verify(repository).countByDiscordUserIdAndCreatedAtGreaterThanEqual(eq(USER_ID), eq(Instant.parse("2026-10-19T00:00:00Z")));
        UsageStatus mondayStatus = mondayService.status(USER_ID);
        assertThat(mondayStatus.resetsAt()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
    }

    @Test
    void statusValuesWhenEnabledAndUnderLimit() {
        UsageProperties properties = new UsageProperties(true, 3);
        UsageService service = new UsageService(properties, repository, premiumChecker, clock);
        when(premiumChecker.isPremium(USER_ID)).thenReturn(false);
        when(repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(USER_ID, EXPECTED_WEEK_START)).thenReturn(1L);

        UsageStatus status = service.status(USER_ID);
        assertThat(status.limitsEnabled()).isTrue();
        assertThat(status.premium()).isFalse();
        assertThat(status.used()).isEqualTo(1);
        assertThat(status.limit()).isEqualTo(3);
        assertThat(status.resetsAt()).isEqualTo(EXPECTED_RESETS_AT);
        assertThat(status.unlimited()).isFalse();
    }
}
