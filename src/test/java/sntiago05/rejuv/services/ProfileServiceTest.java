package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.domain.HeroUsage;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.domain.ProfileSummary;
import sntiago05.rejuv.repositories.LinkedAccountRepository;

class ProfileServiceTest {

    private static final long ACCOUNT_ID = 1000001L;
    private static final long DISCORD_USER_ID = 222L;
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final long WINDOW_START = NOW.minus(Duration.ofDays(30)).getEpochSecond();
    private static final long RECENT = NOW.minus(Duration.ofDays(1)).getEpochSecond();

    private LinkedAccountRepository repository;
    private DeadlockApiClient client;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        repository = mock(LinkedAccountRepository.class);
        client = mock(DeadlockApiClient.class);
        service = new ProfileService(repository, client, new GameAssets(client), Clock.fixed(NOW, ZoneOffset.UTC));
        when(client.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(73, 7, 3));
        when(client.getRanks()).thenReturn(List.of(new RankAsset(0, "Obscurus"), new RankAsset(7, "Archon")));
        when(client.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(2, "hero_gigawatt", "Seven"),
                new HeroAsset(3, "hero_hornet", "Vindicta"),
                new HeroAsset(4, "hero_atlas", "Abrams")));
    }

    @Test
    void findLinkedAccountIdReturnsAccountWhenLinked() {
        when(repository.findById(DISCORD_USER_ID))
                .thenReturn(Optional.of(new LinkedAccount(DISCORD_USER_ID, ACCOUNT_ID, NOW)));

        assertThat(service.findLinkedAccountId(DISCORD_USER_ID)).hasValue(ACCOUNT_ID);
    }

    @Test
    void findLinkedAccountIdReturnsEmptyWhenNotLinked() {
        when(repository.findById(DISCORD_USER_ID)).thenReturn(Optional.empty());

        assertThat(service.findLinkedAccountId(DISCORD_USER_ID)).isEmpty();
    }

    @Test
    void emptyHistoryThrowsNoMatchHistoryAndCallsNothingElse() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getProfile(ACCOUNT_ID)).isInstanceOf(NoMatchHistoryException.class);

        verify(client, never()).getPlayerRank(ACCOUNT_ID);
        verify(client, never()).getRanks();
        verify(client, never()).getHeroes();
    }

    @Test
    void callsTheClientSequentiallyInTheSpecifiedOrder() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(win(1, RECENT, 1, 1, 1)));

        service.getProfile(ACCOUNT_ID);

        InOrder order = inOrder(client);
        order.verify(client).getMatchHistory(ACCOUNT_ID);
        order.verify(client).getPlayerRank(ACCOUNT_ID);
        order.verify(client).getRanks();
        order.verify(client).getHeroes();
    }

    @Test
    void usesOnlyTheTenNewestMatchesForRecentStats() {
        List<MatchHistoryEntry> history = new ArrayList<>();
        // 10 newest: 6 wins, kills 0..9 (avg 4.5), deaths 2 each, assists 10 each.
        for (int i = 0; i < 10; i++) {
            history.add(i < 6 ? win(1, RECENT, i, 2, 10) : loss(1, RECENT, i, 2, 10));
        }
        // 2 older losses with extreme numbers that must be ignored.
        history.add(loss(1, RECENT, 100, 100, 100));
        history.add(loss(1, RECENT, 100, 100, 100));

        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(history);
        ProfileSummary summary = service.getProfile(ACCOUNT_ID);

        assertThat(summary.recentMatches()).isEqualTo(10);
        assertThat(summary.recentWins()).isEqualTo(6);
        assertThat(summary.winRatePercent()).isEqualTo(60);
        assertThat(summary.avgKills()).isEqualTo(4.5);
        assertThat(summary.avgDeaths()).isEqualTo(2.0);
        assertThat(summary.avgAssists()).isEqualTo(10.0);
    }

    @Test
    void fewerThanTenMatchesUsesAll() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(
                win(1, RECENT, 3, 1, 4),
                loss(1, RECENT, 5, 2, 6),
                loss(1, RECENT, 7, 4, 9)));

        ProfileSummary summary = service.getProfile(ACCOUNT_ID);

        assertThat(summary.recentMatches()).isEqualTo(3);
        assertThat(summary.recentWins()).isEqualTo(1);
        assertThat(summary.avgKills()).isEqualTo(5.0);
        assertThat(summary.avgDeaths()).isEqualTo(7.0 / 3);
        assertThat(summary.avgAssists()).isEqualTo(19.0 / 3);
    }

    @Test
    void winIsMatchResultEqualsPlayerTeam() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(
                entry(1, RECENT, 1, 1, 0, 1, 1, 1),   // win by the rule, outcome 0 (old match)
                entry(1, RECENT, 0, 0, 0, 1, 1, 1),   // win by the rule, outcome 0
                entry(1, RECENT, 0, 1, 1, 1, 1, 1),   // loss, even with outcome 1
                entry(1, RECENT, 1, 0, 0, 1, 1, 1))); // loss

        assertThat(service.getProfile(ACCOUNT_ID).recentWins()).isEqualTo(2);
    }

    @Test
    void winRateIsRounded() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(
                win(1, RECENT, 1, 1, 1), win(1, RECENT, 1, 1, 1), loss(1, RECENT, 1, 1, 1)));

        assertThat(service.getProfile(ACCOUNT_ID).winRatePercent()).isEqualTo(67);
    }

    @Test
    void topHeroesCountsOnlyLast30DaysSortedByCountThenId() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(
                win(3, RECENT, 1, 1, 1),
                win(3, RECENT, 1, 1, 1),
                win(2, RECENT, 1, 1, 1),
                win(2, RECENT, 1, 1, 1),
                win(4, RECENT, 1, 1, 1),
                win(4, WINDOW_START, 1, 1, 1),     // just inside the window
                win(1, RECENT, 1, 1, 1),
                win(1, WINDOW_START - 1, 1, 1, 1), // just outside the window
                win(1, WINDOW_START - 1, 1, 1, 1),
                win(1, WINDOW_START - 1, 1, 1, 1)));

        assertThat(service.getProfile(ACCOUNT_ID).topHeroes()).containsExactly(
                new HeroUsage(2, "Seven", 2),
                new HeroUsage(3, "Vindicta", 2),
                new HeroUsage(4, "Abrams", 2));
    }

    @Test
    void topHeroesEmptyWhenNoMatchInWindow() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(win(1, WINDOW_START - 1, 1, 1, 1)));

        ProfileSummary summary = service.getProfile(ACCOUNT_ID);

        assertThat(summary.topHeroes()).isEmpty();
        assertThat(summary.recentMatches()).isEqualTo(1);
    }

    @Test
    void unknownHeroIdHasNullName() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(win(99, RECENT, 1, 1, 1)));

        assertThat(service.getProfile(ACCOUNT_ID).topHeroes()).containsExactly(new HeroUsage(99, null, 1));
    }

    @Test
    void unrankedPlayerHasNullTierAndName() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(win(1, RECENT, 1, 1, 1)));
        when(client.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        ProfileSummary summary = service.getProfile(ACCOUNT_ID);

        assertThat(summary.rankTier()).isNull();
        assertThat(summary.rankName()).isNull();
        assertThat(summary.subrank()).isZero();
    }

    @Test
    void rankNameResolvedFromAssets() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(win(1, RECENT, 1, 1, 1)));

        ProfileSummary summary = service.getProfile(ACCOUNT_ID);

        assertThat(summary.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(summary.rankTier()).isEqualTo(7);
        assertThat(summary.rankName()).isEqualTo("Archon");
        assertThat(summary.subrank()).isEqualTo(3);
    }

    @Test
    void missingTierInAssetsGivesNullName() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(win(1, RECENT, 1, 1, 1)));
        when(client.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(115, 11, 5));

        ProfileSummary summary = service.getProfile(ACCOUNT_ID);

        assertThat(summary.rankTier()).isEqualTo(11);
        assertThat(summary.rankName()).isNull();
        assertThat(summary.subrank()).isEqualTo(5);
    }

    @Test
    void propagatesUnavailableException() {
        when(client.getMatchHistory(ACCOUNT_ID)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        assertThatThrownBy(() -> service.getProfile(ACCOUNT_ID)).isInstanceOf(DeadlockApiUnavailableException.class);
    }

    private static MatchHistoryEntry win(int heroId, long startTime, int kills, int deaths, int assists) {
        return entry(heroId, startTime, 1, 1, 1, kills, deaths, assists);
    }

    private static MatchHistoryEntry loss(int heroId, long startTime, int kills, int deaths, int assists) {
        return entry(heroId, startTime, 0, 1, 2, kills, deaths, assists);
    }

    private static MatchHistoryEntry entry(int heroId, long startTime, int matchResult, int playerTeam,
                                           int playerMatchOutcome, int kills, int deaths, int assists) {
        return new MatchHistoryEntry(1L, heroId, 20, startTime, 1800, 1, matchResult, playerTeam,
                playerMatchOutcome, kills, deaths, assists, 30000L, 150, 10, null);
    }
}
