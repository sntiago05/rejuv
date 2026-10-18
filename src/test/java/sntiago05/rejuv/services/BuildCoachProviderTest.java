package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.BuildItem;
import sntiago05.rejuv.domain.BuildPhase;
import sntiago05.rejuv.domain.HeroBuild;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.services.BuildCoachProvider.BuildCoachPayload;
import sntiago05.rejuv.services.BuildCoachProvider.MissingItem;
import sntiago05.rejuv.services.BuildCoachProvider.OffMetaItem;

class BuildCoachProviderTest {

    private static final int HERO_ID = 1;
    private static final String HERO_NAME = "Infernus";
    private static final HeroAsset HERO_ASSET = new HeroAsset(HERO_ID, "hero_inferno", HERO_NAME);

    private static final ItemAsset UPGRADE_1 = new ItemAsset(100L, "upgrade_1", "Extended Magazine", "upgrade", 1, "weapon", 800);
    private static final ItemAsset UPGRADE_2 = new ItemAsset(200L, "upgrade_2", "Burst Fire", "upgrade", 2, "weapon", 1250);
    private static final ItemAsset UPGRADE_3 = new ItemAsset(300L, "upgrade_3", "Mystic Burst", "upgrade", 2, "spirit", 1250);
    private static final ItemAsset UPGRADE_SOLD = new ItemAsset(400L, "upgrade_4", "Extra Health", "upgrade", 1, "vitality", 500);
    private static final ItemAsset ABILITY_ITEM = new ItemAsset(500L, "ability_1", "Soul Urn", "ability", null, null, null);
    private static final ItemAsset POPULAR_MISSING_1 = new ItemAsset(101L, "upgrade_toxic", "Toxic Bullets", "upgrade", 3, "weapon", 3000);
    private static final ItemAsset POPULAR_MISSING_2 = new ItemAsset(201L, "upgrade_rebirth", "Soul Rebirth", "upgrade", 4, "vitality", 6200);

    private BuildService buildService;
    private BuildCoachProvider provider;
    private final ObjectMapper mapper = new JsonMapper();

    @BeforeEach
    void setUp() {
        buildService = mock(BuildService.class);
        provider = new BuildCoachProvider(buildService);
    }

    private CoachRequest createRequest(PlayerTelemetry player, Integer rankTier, List<HeroAsset> heroes, List<ItemAsset> items) {
        MatchHistoryEntry match = new MatchHistoryEntry(900000001L, 1, 1, 1700000000L, 1800, 4, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, 73);
        MatchTelemetry telemetry = new MatchTelemetry(900000001L, 1800, player == null ? List.of() : List.of(player));
        return new CoachRequest(1000001L, match, List.of(), telemetry, player, rankTier, heroes, items);
    }

    private PlayerTelemetry createPlayer(List<ItemPurchase> items) {
        return new PlayerTelemetry(1000001L, 0, HERO_ID, items, List.of(), List.of());
    }

    @Test
    void keyEqualsBuild() {
        assertThat(provider.key()).isEqualTo("build");
    }

    @Test
    void happyPathWithThreePhases() {
        // Build with 3 phases
        BuildPhase phase1 = new BuildPhase(0, List.of(
                new BuildItem(100L, "Extended Magazine", 100, 70.0, 54.0),
                new BuildItem(101L, "Toxic Bullets", 50, 40.0, 50.0)
        ));
        BuildPhase phase2 = new BuildPhase(1, List.of(
                new BuildItem(200L, "Burst Fire", 80, 60.0, 55.0)
        ));
        BuildPhase phase3 = new BuildPhase(2, List.of(
                new BuildItem(201L, "Soul Rebirth", 65, 48.2, 53.1)
        ));
        HeroBuild heroBuild = new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase1, phase2, phase3), List.of(), 0);
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(heroBuild);

        // Player items:
        // - 100 kept (repeated purchase collapsed)
        // - 200 kept
        // - 300 kept (off-meta)
        // - 400 bought and sold (excluded from kept, and never in missing_popular)
        // - 500 non-upgrade (ignored)
        List<ItemPurchase> purchases = List.of(
                new ItemPurchase(100L, 60, null),
                new ItemPurchase(100L, 120, null), // duplicate id
                new ItemPurchase(200L, 300, null),
                new ItemPurchase(300L, 600, null),
                new ItemPurchase(400L, 100, 500),  // sold at 500s
                new ItemPurchase(500L, 200, null)   // non-upgrade
        );
        PlayerTelemetry player = createPlayer(purchases);
        List<ItemAsset> itemAssets = List.of(UPGRADE_1, UPGRADE_2, UPGRADE_3, UPGRADE_SOLD, ABILITY_ITEM, POPULAR_MISSING_1, POPULAR_MISSING_2);
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), itemAssets);

        Optional<CoachInsight> opt = provider.provide(request);
        assertThat(opt).isPresent();

        CoachInsight insight = opt.get();
        assertThat(insight.key()).isEqualTo("build");
        assertThat(insight.payload()).isInstanceOf(BuildCoachPayload.class);

        BuildCoachPayload payload = (BuildCoachPayload) insight.payload();
        // kept: 100, 200, 300 -> size 3
        assertThat(payload.keptCount()).isEqualTo(3);
        // popular: 100, 101, 200, 201 -> size 4
        assertThat(payload.popularCount()).isEqualTo(4);
        // matching popular: 100, 200 -> size 2
        assertThat(payload.matchingPopular()).isEqualTo(2);
        // match_rate_pct: 2 / min(3, 4) * 100 = 66.7%
        assertThat(payload.matchRatePct()).isEqualTo(66.7);
        // missing_popular: 201 (48.2%) and 101 (40.0%), sorted by pick rate desc
        assertThat(payload.missingPopular()).containsExactly(
                new MissingItem("Soul Rebirth", 48.2, 53.1),
                new MissingItem("Toxic Bullets", 40.0, 50.0)
        );
        // off_meta: 300 ("Mystic Burst")
        assertThat(payload.offMeta()).containsExactly(
                new OffMetaItem("Mystic Burst")
        );
        // significance: notable (missing_popular is non-empty, but no item meets major threshold >= 55.0 & >= 52.0)
        assertThat(payload.significance()).isEqualTo("notable");
    }

    @Test
    void missingPopularThresholdAndCapWithTieBreak() {
        // Items with different pick rates and ids to test 34.9 / 35.0 threshold, cap 3, and tie-breaking
        BuildPhase phase = new BuildPhase(0, List.of(
                new BuildItem(10L, "Below Threshold", 30, 34.9, 50.0),
                new BuildItem(20L, "At Threshold", 35, 35.0, 50.0),
                new BuildItem(40L, "Tied B", 50, 50.0, 50.0),
                new BuildItem(30L, "Tied A", 50, 50.0, 50.0),
                new BuildItem(50L, "High Pick", 60, 60.0, 50.0)
        ));
        HeroBuild heroBuild = new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase), List.of(), 0);
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(heroBuild);

        // Player only kept item 100
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        List<ItemAsset> itemAssets = List.of(
                UPGRADE_1,
                new ItemAsset(10L, "item_10", "Below Threshold", "upgrade", 1, "weapon", 500),
                new ItemAsset(20L, "item_20", "At Threshold", "upgrade", 1, "weapon", 500),
                new ItemAsset(30L, "item_30", "Tied A", "upgrade", 1, "weapon", 500),
                new ItemAsset(40L, "item_40", "Tied B", "upgrade", 1, "weapon", 500),
                new ItemAsset(50L, "item_50", "High Pick", "upgrade", 1, "weapon", 500)
        );
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), itemAssets);

        Optional<CoachInsight> opt = provider.provide(request);
        assertThat(opt).isPresent();
        BuildCoachPayload payload = (BuildCoachPayload) opt.get().payload();

        // Eligible above 35.0:
        // 50L (60.0%), 30L (50.0%, id 30), 40L (50.0%, id 40), 20L (35.0%)
        // Capped at 3:
        // 1. "High Pick" (60.0%)
        // 2. "Tied A" (50.0%, id 30 < 40)
        // 3. "Tied B" (50.0%, id 40)
        // "At Threshold" is dropped by cap 3; "Below Threshold" (34.9%) was filtered out
        assertThat(payload.missingPopular()).containsExactly(
                new MissingItem("High Pick", 60.0, 50.0),
                new MissingItem("Tied A", 50.0, 50.0),
                new MissingItem("Tied B", 50.0, 50.0)
        );
    }

    @Test
    void majorSignificanceBoundaryConditions() {
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        List<ItemAsset> itemAssets = List.of(
                UPGRADE_1,
                new ItemAsset(10L, "item_10", "Candidate", "upgrade", 1, "weapon", 500)
        );

        // Case 1: Pick 54.9, Win 52.0 -> NOT major (pick < 55.0)
        BuildPhase phase1 = new BuildPhase(0, List.of(
                new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0),
                new BuildItem(10L, "Candidate", 50, 54.9, 52.0)
        ));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase1), List.of(), 0));
        BuildCoachPayload payload1 = (BuildCoachPayload) provider.provide(createRequest(player, 7, List.of(HERO_ASSET), itemAssets)).orElseThrow().payload();
        assertThat(payload1.significance()).isEqualTo("notable");

        // Case 2: Pick 55.0, Win 51.9 -> NOT major (win < 52.0)
        BuildPhase phase2 = new BuildPhase(0, List.of(
                new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0),
                new BuildItem(10L, "Candidate", 50, 55.0, 51.9)
        ));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase2), List.of(), 0));
        BuildCoachPayload payload2 = (BuildCoachPayload) provider.provide(createRequest(player, 7, List.of(HERO_ASSET), itemAssets)).orElseThrow().payload();
        assertThat(payload2.significance()).isEqualTo("notable");

        // Case 3: Pick 55.0, Win 52.0 -> MAJOR!
        BuildPhase phase3 = new BuildPhase(0, List.of(
                new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0),
                new BuildItem(10L, "Candidate", 50, 55.0, 52.0)
        ));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase3), List.of(), 0));
        BuildCoachPayload payload3 = (BuildCoachPayload) provider.provide(createRequest(player, 7, List.of(HERO_ASSET), itemAssets)).orElseThrow().payload();
        assertThat(payload3.significance()).isEqualTo("major");

        // Case 4: Pick 55.1, Win 52.1 -> MAJOR!
        BuildPhase phase4 = new BuildPhase(0, List.of(
                new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0),
                new BuildItem(10L, "Candidate", 50, 55.1, 52.1)
        ));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase4), List.of(), 0));
        BuildCoachPayload payload4 = (BuildCoachPayload) provider.provide(createRequest(player, 7, List.of(HERO_ASSET), itemAssets)).orElseThrow().payload();
        assertThat(payload4.significance()).isEqualTo("major");
    }

    @Test
    void offMetaListingCapDeterministicOrderAndItemWithoutNameSkipped() {
        // Popular build only has item 100
        BuildPhase phase = new BuildPhase(0, List.of(new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0)));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase), List.of(), 0));

        // Player kept items:
        // - 100 (popular)
        // - 10: cost 3000, id 10, name "Item Ten"
        // - 2: cost 1250, id 2, name "Item Two"
        // - 1: cost 1250, id 1, name "Item One"
        // - 5: cost null (0), id 5, name "Item Five"
        // - 99: cost 5000, id 99, name null (should be skipped)
        PlayerTelemetry player = createPlayer(List.of(
                new ItemPurchase(100L, 60, null),
                new ItemPurchase(10L, 100, null),
                new ItemPurchase(2L, 200, null),
                new ItemPurchase(1L, 300, null),
                new ItemPurchase(5L, 400, null),
                new ItemPurchase(99L, 500, null)
        ));

        List<ItemAsset> itemAssets = List.of(
                UPGRADE_1,
                new ItemAsset(10L, "item_10", "Item Ten", "upgrade", 3, "weapon", 3000),
                new ItemAsset(2L, "item_2", "Item Two", "upgrade", 2, "weapon", 1250),
                new ItemAsset(1L, "item_1", "Item One", "upgrade", 2, "weapon", 1250),
                new ItemAsset(5L, "item_5", "Item Five", "upgrade", 1, "weapon", null),
                new ItemAsset(99L, "item_99", null, "upgrade", 4, "weapon", 5000)
        );

        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), itemAssets);
        Optional<CoachInsight> opt = provider.provide(request);
        assertThat(opt).isPresent();

        BuildCoachPayload payload = (BuildCoachPayload) opt.get().payload();
        // 99 is skipped (name null).
        // 10 (cost 3000), 1 (cost 1250, id 1), 2 (cost 1250, id 2), 5 (cost 0, id 5).
        // Cap 3: 10, 1, 2.
        assertThat(payload.offMeta()).containsExactly(
                new OffMetaItem("Item Ten"),
                new OffMetaItem("Item One"),
                new OffMetaItem("Item Two")
        );
    }

    @Test
    void notableSignificanceFromOffMetaCountBoundary() {
        // Build where player has everything popular, so missing_popular is empty
        BuildPhase phase = new BuildPhase(0, List.of(new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0)));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase), List.of(), 0));

        // 2 off-meta items -> significance "none"
        PlayerTelemetry player2 = createPlayer(List.of(
                new ItemPurchase(100L, 60, null),
                new ItemPurchase(1L, 100, null),
                new ItemPurchase(2L, 200, null)
        ));
        List<ItemAsset> assets = List.of(
                UPGRADE_1,
                new ItemAsset(1L, "i1", "Item One", "upgrade", 1, "weapon", 500),
                new ItemAsset(2L, "i2", "Item Two", "upgrade", 1, "weapon", 500),
                new ItemAsset(3L, "i3", "Item Three", "upgrade", 1, "weapon", 500)
        );
        BuildCoachPayload p2 = (BuildCoachPayload) provider.provide(createRequest(player2, 7, List.of(HERO_ASSET), assets)).orElseThrow().payload();
        assertThat(p2.offMeta()).hasSize(2);
        assertThat(p2.missingPopular()).isEmpty();
        assertThat(p2.significance()).isEqualTo("none");

        // 3 off-meta items -> significance "notable"
        PlayerTelemetry player3 = createPlayer(List.of(
                new ItemPurchase(100L, 60, null),
                new ItemPurchase(1L, 100, null),
                new ItemPurchase(2L, 200, null),
                new ItemPurchase(3L, 300, null)
        ));
        BuildCoachPayload p3 = (BuildCoachPayload) provider.provide(createRequest(player3, 7, List.of(HERO_ASSET), assets)).orElseThrow().payload();
        assertThat(p3.offMeta()).hasSize(3);
        assertThat(p3.missingPopular()).isEmpty();
        assertThat(p3.significance()).isEqualTo("notable");
    }

    @Test
    void rankTierNullAndZeroReturnEmptyAndNeverCallBuildService() {
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        CoachRequest reqNull = createRequest(player, null, List.of(HERO_ASSET), List.of(UPGRADE_1));
        CoachRequest reqZero = createRequest(player, 0, List.of(HERO_ASSET), List.of(UPGRADE_1));

        assertThat(provider.provide(reqNull)).isEmpty();
        assertThat(provider.provide(reqZero)).isEmpty();

        verify(buildService, never()).getBuild(any(), any());
    }

    @Test
    void heroNotFoundExceptionReturnsEmpty() {
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));

        when(buildService.getBuild(HERO_NAME, 7)).thenThrow(new HeroNotFoundException(HERO_NAME));
        assertThat(provider.provide(request)).isEmpty();
    }

    @Test
    void illegalArgumentExceptionReturnsEmpty() {
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));

        when(buildService.getBuild(HERO_NAME, 7)).thenThrow(new IllegalArgumentException("invalid rank"));
        assertThat(provider.provide(request)).isEmpty();
    }

    @Test
    void deadlockApiUnavailableExceptionReturnsEmpty() {
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));

        when(buildService.getBuild(HERO_NAME, 7)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));
        assertThat(provider.provide(request)).isEmpty();
    }

    @Test
    void emptyHeroBuildPhasesReturnsEmpty() {
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));

        HeroBuild emptyPhasesBuild = new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(), List.of(), 0);
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(emptyPhasesBuild);

        assertThat(provider.provide(request)).isEmpty();
    }

    @Test
    void matchRatePctWhenKeptCountOrPopularCountIsZero() {
        // Case: player bought an upgrade item, but sold it!
        // hasUpgradePurchase is true, but kept is empty -> keptCount = 0
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, 300)));
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));

        BuildPhase phase = new BuildPhase(0, List.of(new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0)));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase), List.of(), 0));

        Optional<CoachInsight> opt = provider.provide(request);
        assertThat(opt).isPresent();
        BuildCoachPayload payload = (BuildCoachPayload) opt.get().payload();

        assertThat(payload.keptCount()).isEqualTo(0);
        assertThat(payload.popularCount()).isEqualTo(1);
        assertThat(payload.matchingPopular()).isEqualTo(0);
        assertThat(payload.matchRatePct()).isEqualTo(0.0);
    }

    @Test
    void exactlyOneGetBuildCallPerProvide() {
        BuildPhase phase = new BuildPhase(0, List.of(new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0)));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase), List.of(), 0));

        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));

        provider.provide(request);
        verify(buildService, times(1)).getBuild(eq(HERO_NAME), eq(7));
    }

    @Test
    void missingDataReturnsEmpty() {
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));

        // Request null
        assertThat(provider.provide(null)).isEmpty();

        // Telemetry null
        CoachRequest noTelemetry = new CoachRequest(1L, null, List.of(), null, player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));
        assertThat(provider.provide(noTelemetry)).isEmpty();

        // Player null
        CoachRequest noPlayer = createRequest(null, 7, List.of(HERO_ASSET), List.of(UPGRADE_1));
        assertThat(provider.provide(noPlayer)).isEmpty();

        // Player items empty
        PlayerTelemetry emptyItemsPlayer = createPlayer(List.of());
        assertThat(provider.provide(createRequest(emptyItemsPlayer, 7, List.of(HERO_ASSET), List.of(UPGRADE_1)))).isEmpty();

        // No upgrade purchases (only ability type)
        PlayerTelemetry nonUpgradePlayer = createPlayer(List.of(new ItemPurchase(500L, 60, null)));
        assertThat(provider.provide(createRequest(nonUpgradePlayer, 7, List.of(HERO_ASSET), List.of(ABILITY_ITEM)))).isEmpty();

        // Hero not resolved
        CoachRequest unknownHero = createRequest(player, 7, List.of(), List.of(UPGRADE_1));
        assertThat(provider.provide(unknownHero)).isEmpty();

        // Hero with blank name and blank className
        HeroAsset blankHero = new HeroAsset(HERO_ID, "  ", "");
        CoachRequest blankHeroReq = createRequest(player, 7, List.of(blankHero), List.of(UPGRADE_1));
        assertThat(provider.provide(blankHeroReq)).isEmpty();
    }

    @Test
    void heroNameFallsBackToClassNameWhenNameNullOrBlank() {
        BuildPhase phase = new BuildPhase(0, List.of(new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0)));
        when(buildService.getBuild("hero_inferno", 7)).thenReturn(new HeroBuild(HERO_ID, "hero_inferno", 7, List.of(phase), List.of(), 0));

        HeroAsset heroWithoutName = new HeroAsset(HERO_ID, "hero_inferno", "   ");
        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null)));
        CoachRequest request = createRequest(player, 7, List.of(heroWithoutName), List.of(UPGRADE_1));

        Optional<CoachInsight> opt = provider.provide(request);
        assertThat(opt).isPresent();
        verify(buildService, times(1)).getBuild("hero_inferno", 7);
    }

    @Test
    void determinismAndImmutability() {
        BuildPhase phase = new BuildPhase(0, List.of(
                new BuildItem(100L, "Extended Magazine", 100, 70.0, 50.0),
                new BuildItem(201L, "Soul Rebirth", 50, 48.0, 53.0)
        ));
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(phase), List.of(), 0));

        PlayerTelemetry player = createPlayer(List.of(new ItemPurchase(100L, 60, null), new ItemPurchase(300L, 120, null)));
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), List.of(UPGRADE_1, UPGRADE_3, POPULAR_MISSING_2));

        Optional<CoachInsight> first = provider.provide(request);
        Optional<CoachInsight> second = provider.provide(request);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().payload()).isEqualTo(second.get().payload());
    }

    @Test
    void payloadSizeUnder1200Chars() throws Exception {
        // Realistic full match with multiple popular missing items and off-meta items
        List<BuildItem> phase1Items = List.of(
                new BuildItem(100L, "Extended Magazine", 200, 75.0, 52.0),
                new BuildItem(101L, "High-Velocity Mag", 150, 55.0, 54.0),
                new BuildItem(102L, "Kinetic Dash", 120, 45.0, 51.0),
                new BuildItem(103L, "Extra Stamina", 110, 40.0, 49.0)
        );
        List<BuildItem> phase2Items = List.of(
                new BuildItem(200L, "Burst Fire", 180, 68.0, 55.0),
                new BuildItem(201L, "Soul Rebirth", 140, 52.0, 53.0),
                new BuildItem(202L, "Slowing Bullets", 100, 38.0, 50.0),
                new BuildItem(203L, "Warp Stone", 95, 36.0, 48.0)
        );
        List<BuildItem> phase3Items = List.of(
                new BuildItem(301L, "Lucky Shot", 160, 60.0, 56.0),
                new BuildItem(302L, "Crippling Headshot", 130, 49.0, 52.0),
                new BuildItem(303L, "Ricochet", 110, 42.0, 51.0),
                new BuildItem(304L, "Spiritual Overflow", 90, 35.0, 49.0)
        );

        BuildPhase p1 = new BuildPhase(0, phase1Items);
        BuildPhase p2 = new BuildPhase(1, phase2Items);
        BuildPhase p3 = new BuildPhase(2, phase3Items);
        HeroBuild heroBuild = new HeroBuild(HERO_ID, HERO_NAME, 7, List.of(p1, p2, p3), List.of(), 0);
        when(buildService.getBuild(HERO_NAME, 7)).thenReturn(heroBuild);

        // Player kept some popular and 3 off-meta
        List<ItemPurchase> purchases = List.of(
                new ItemPurchase(100L, 60, null),
                new ItemPurchase(102L, 180, null),
                new ItemPurchase(200L, 400, null),
                new ItemPurchase(202L, 600, null),
                new ItemPurchase(301L, 900, null),
                new ItemPurchase(801L, 1000, null),
                new ItemPurchase(802L, 1200, null),
                new ItemPurchase(803L, 1400, null)
        );

        List<ItemAsset> assets = new ArrayList<>(List.of(
                UPGRADE_1,
                new ItemAsset(101L, "i101", "High-Velocity Mag", "upgrade", 1, "weapon", 500),
                new ItemAsset(102L, "i102", "Kinetic Dash", "upgrade", 1, "weapon", 500),
                new ItemAsset(103L, "i103", "Extra Stamina", "upgrade", 1, "vitality", 500),
                new ItemAsset(200L, "i200", "Burst Fire", "upgrade", 2, "weapon", 1250),
                new ItemAsset(201L, "i201", "Soul Rebirth", "upgrade", 3, "vitality", 3000),
                new ItemAsset(202L, "i202", "Slowing Bullets", "upgrade", 2, "weapon", 1250),
                new ItemAsset(203L, "i203", "Warp Stone", "upgrade", 2, "weapon", 1250),
                new ItemAsset(301L, "i301", "Lucky Shot", "upgrade", 4, "weapon", 6200),
                new ItemAsset(302L, "i302", "Crippling Headshot", "upgrade", 4, "weapon", 6200),
                new ItemAsset(303L, "i303", "Ricochet", "upgrade", 4, "weapon", 6200),
                new ItemAsset(304L, "i304", "Spiritual Overflow", "upgrade", 4, "spirit", 6200),
                new ItemAsset(801L, "i801", "Off-Meta Heavy Armor", "upgrade", 3, "vitality", 3000),
                new ItemAsset(802L, "i802", "Off-Meta Phantom Strike", "upgrade", 4, "weapon", 6200),
                new ItemAsset(803L, "i803", "Off-Meta Escalating Exposure", "upgrade", 4, "spirit", 6200)
        ));

        PlayerTelemetry player = createPlayer(purchases);
        CoachRequest request = createRequest(player, 7, List.of(HERO_ASSET), assets);

        Optional<CoachInsight> opt = provider.provide(request);
        assertThat(opt).isPresent();

        String json = mapper.writeValueAsString(opt.get().payload());
        assertThat(json.length()).isLessThanOrEqualTo(1200);
        assertThat(json).contains("\"significance\":\"major\"");
        assertThat(json).contains("\"missing_popular\":");
        assertThat(json).contains("\"off_meta\":");
    }
}
