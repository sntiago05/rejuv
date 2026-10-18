package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sntiago05.rejuv.clients.RawMetadataTestData.ACCOUNT_ID_1;
import static sntiago05.rejuv.clients.RawMetadataTestData.ACCOUNT_ID_2;
import static sntiago05.rejuv.clients.RawMetadataTestData.DEFAULT_MATCH_ID;
import static sntiago05.rejuv.clients.RawMetadataTestData.LARGE_ITEM_ID;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.google.protobuf.WireFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sntiago05.rejuv.clients.RawMetadataTestData.BuffBuilder;
import sntiago05.rejuv.clients.RawMetadataTestData.ContentsBuilder;
import sntiago05.rejuv.clients.RawMetadataTestData.ItemBuilder;
import sntiago05.rejuv.clients.RawMetadataTestData.MatchInfoBuilder;
import sntiago05.rejuv.clients.RawMetadataTestData.OuterBuilder;
import sntiago05.rejuv.clients.RawMetadataTestData.PlayerBuilder;
import sntiago05.rejuv.clients.RawMetadataTestData.StatBuilder;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.PowerUpBuff;
import sntiago05.rejuv.domain.StatSnapshot;

class RawMatchMetadataParserTest {

    @Test
    void mapsAllFieldsFromTheFieldMap() {
        byte[] payload = RawMetadataTestData.defaultValidPayload();
        MatchTelemetry telemetry = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, payload);

        assertThat(telemetry.matchId()).isEqualTo(DEFAULT_MATCH_ID);
        assertThat(telemetry.durationS()).isEqualTo(1800);
        assertThat(telemetry.players()).hasSize(2);

        PlayerTelemetry p1 = telemetry.players().getFirst();
        assertThat(p1.accountId()).isEqualTo(ACCOUNT_ID_1);
        assertThat(p1.team()).isEqualTo(0);
        assertThat(p1.heroId()).isEqualTo(1);
        assertThat(p1.items()).containsExactly(
                new ItemPurchase(LARGE_ITEM_ID, 300, null),
                new ItemPurchase(101L, 600, 700)
        );
        assertThat(p1.snapshots()).containsExactly(
                new StatSnapshot(180, 1500L, 2000L, 500L),
                new StatSnapshot(360, 3200L, 5000L, 1200L)
        );
        assertThat(p1.powerUpBuffs()).containsExactly(
                new PowerUpBuff("weapon_power", true, List.of(120, 240))
        );

        PlayerTelemetry p2 = telemetry.players().get(1);
        assertThat(p2.accountId()).isEqualTo(ACCOUNT_ID_2);
        assertThat(p2.team()).isEqualTo(1);
        assertThat(p2.heroId()).isEqualTo(2);
        assertThat(p2.items()).containsExactly(
                new ItemPurchase(201L, 250, null)
        );
        assertThat(p2.snapshots()).containsExactly(
                new StatSnapshot(180, 1400L, 1800L, 300L)
        );
        assertThat(p2.powerUpBuffs()).containsExactly(
                new PowerUpBuff("spirit_power", false, List.of(150))
        );
    }

    @Test
    void sortsItemsSnapshotsAndPickupTimes() {
        byte[] player = new PlayerBuilder()
                .accountId(ACCOUNT_ID_1)
                .team(0)
                .heroId(1)
                .addItem(new ItemBuilder().gameTimeS(600).itemId(101L).build())
                .addItem(new ItemBuilder().gameTimeS(200).itemId(102L).build())
                .addItem(new ItemBuilder().gameTimeS(400).itemId(103L).build())
                .addStat(new StatBuilder().timeStampS(360).netWorth(3000L).playerDamage(4000L).playerHealing(1000L).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(1500L).playerDamage(2000L).playerHealing(500L).build())
                .addBuff(new BuffBuilder().type("weapon_power").isPermanent(true).pickupTimes(List.of(300, 100, 200)).build())
                .build();

        byte[] payload = wrapInValidPayload(DEFAULT_MATCH_ID, player);
        MatchTelemetry telemetry = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, payload);

        PlayerTelemetry p = telemetry.players().getFirst();
        assertThat(p.items()).extracting(ItemPurchase::boughtAtS).containsExactly(200, 400, 600);
        assertThat(p.snapshots()).extracting(StatSnapshot::timeS).containsExactly(180, 360);
        assertThat(p.powerUpBuffs().getFirst().pickupTimesS()).containsExactly(100, 200, 300);
    }

    @Test
    void soldTimeZeroOrAbsentBecomesNull() {
        byte[] player = new PlayerBuilder()
                .accountId(ACCOUNT_ID_1)
                .team(0)
                .heroId(1)
                .addItem(new ItemBuilder().gameTimeS(100).itemId(1L).soldTimeS(0).build())
                .addItem(new ItemBuilder().gameTimeS(200).itemId(2L).soldTimeS(null).build())
                .addItem(new ItemBuilder().gameTimeS(300).itemId(3L).soldTimeS(450).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build())
                .build();

        byte[] payload = wrapInValidPayload(DEFAULT_MATCH_ID, player);
        MatchTelemetry telemetry = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, payload);

        List<ItemPurchase> items = telemetry.players().getFirst().items();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).soldAtS()).isNull();
        assertThat(items.get(1).soldAtS()).isNull();
        assertThat(items.get(2).soldAtS()).isEqualTo(450);
    }

    @Test
    void unsignedValuesAreNotNegative() {
        long largeAccountId = 3000000000L;
        long largeItemId = 3612874202L;
        long largeNetWorth = 3500000000L;
        long largeDamage = 4000000000L;
        long largeHealing = 3800000000L;

        byte[] player = new PlayerBuilder()
                .accountId(largeAccountId)
                .team(0)
                .heroId(1)
                .addItem(new ItemBuilder().gameTimeS(100).itemId(largeItemId).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(largeNetWorth).playerDamage(largeDamage).playerHealing(largeHealing).build())
                .build();

        byte[] payload = wrapInValidPayload(DEFAULT_MATCH_ID, player);
        MatchTelemetry telemetry = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, payload);

        PlayerTelemetry p = telemetry.players().getFirst();
        assertThat(p.accountId()).isEqualTo(largeAccountId);
        assertThat(p.items().getFirst().itemId()).isEqualTo(largeItemId);
        assertThat(p.snapshots().getFirst().netWorth()).isEqualTo(largeNetWorth);
        assertThat(p.snapshots().getFirst().playerDamage()).isEqualTo(largeDamage);
        assertThat(p.snapshots().getFirst().playerHealing()).isEqualTo(largeHealing);
    }

    @Test
    void packedAndUnpackedPickupTimesAreEqual() {
        byte[] pPacked = new PlayerBuilder()
                .accountId(ACCOUNT_ID_1)
                .team(0)
                .heroId(1)
                .addItem(new ItemBuilder().gameTimeS(100).itemId(1L).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build())
                .addBuff(new BuffBuilder().type("buff1").isPermanent(true).pickupTimes(List.of(100, 200, 300)).packed(true).build())
                .build();

        byte[] pUnpacked = new PlayerBuilder()
                .accountId(ACCOUNT_ID_1)
                .team(0)
                .heroId(1)
                .addItem(new ItemBuilder().gameTimeS(100).itemId(1L).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build())
                .addBuff(new BuffBuilder().type("buff1").isPermanent(true).pickupTimes(List.of(100, 200, 300)).packed(false).build())
                .build();

        MatchTelemetry tPacked = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, wrapInValidPayload(DEFAULT_MATCH_ID, pPacked));
        MatchTelemetry tUnpacked = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, wrapInValidPayload(DEFAULT_MATCH_ID, pUnpacked));

        assertThat(tPacked.players().getFirst().powerUpBuffs()).isEqualTo(tUnpacked.players().getFirst().powerUpBuffs());
        assertThat(tPacked.players().getFirst().powerUpBuffs().getFirst().pickupTimesS()).containsExactly(100, 200, 300);
    }

    @Test
    void buffWithoutTypeIsSkipped() {
        byte[] p = new PlayerBuilder()
                .accountId(ACCOUNT_ID_1)
                .team(0)
                .heroId(1)
                .addItem(new ItemBuilder().gameTimeS(100).itemId(1L).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build())
                .addBuff(new BuffBuilder().type(null).isPermanent(true).pickupTimes(List.of(100)).build())
                .addBuff(new BuffBuilder().type("valid_buff").isPermanent(null).pickupTimes(null).build())
                .build();

        MatchTelemetry telemetry = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, wrapInValidPayload(DEFAULT_MATCH_ID, p));
        List<PowerUpBuff> buffs = telemetry.players().getFirst().powerUpBuffs();

        assertThat(buffs).hasSize(1);
        PowerUpBuff b = buffs.getFirst();
        assertThat(b.type()).isEqualTo("valid_buff");
        assertThat(b.permanent()).isFalse();
        assertThat(b.pickupTimesS()).isEmpty();
    }

    @Test
    void unknownFieldsAreSkipped() {
        byte[] buff = new BuffBuilder().type("buff1").isPermanent(true).pickupTimes(List.of(50))
                .addExtraField(99, 12345L, WireFormat.WIRETYPE_VARINT)
                .addExtraField(98, 42, WireFormat.WIRETYPE_FIXED32)
                .addExtraField(97, 88888888L, WireFormat.WIRETYPE_FIXED64)
                .addExtraField(96, "extra_string", WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();

        byte[] item = new ItemBuilder().gameTimeS(100).itemId(1L)
                .addExtraField(99, 100L, WireFormat.WIRETYPE_VARINT)
                .addExtraField(98, 200, WireFormat.WIRETYPE_FIXED32)
                .addExtraField(97, 300L, WireFormat.WIRETYPE_FIXED64)
                .addExtraField(96, new byte[] { 1, 2, 3 }, WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();

        byte[] stat = new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L)
                .addExtraField(99, 1L, WireFormat.WIRETYPE_VARINT)
                .addExtraField(98, 2, WireFormat.WIRETYPE_FIXED32)
                .addExtraField(97, 3L, WireFormat.WIRETYPE_FIXED64)
                .addExtraField(96, "stat_extra", WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();

        byte[] player = new PlayerBuilder()
                .accountId(ACCOUNT_ID_1)
                .team(0)
                .heroId(1)
                .addItem(item)
                .addStat(stat)
                .addBuff(buff)
                .addExtraField(99, 999L, WireFormat.WIRETYPE_VARINT)
                .addExtraField(98, 888, WireFormat.WIRETYPE_FIXED32)
                .addExtraField(97, 777L, WireFormat.WIRETYPE_FIXED64)
                .addExtraField(96, "player_extra", WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();

        byte[] matchInfo = new MatchInfoBuilder()
                .durationS(1800)
                .matchId(DEFAULT_MATCH_ID)
                .addPlayer(player)
                .addExtraField(99, 11L, WireFormat.WIRETYPE_VARINT)
                .addExtraField(98, 22, WireFormat.WIRETYPE_FIXED32)
                .addExtraField(97, 33L, WireFormat.WIRETYPE_FIXED64)
                .addExtraField(96, "info_extra", WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();

        byte[] contents = new ContentsBuilder()
                .matchInfo(matchInfo)
                .addExtraField(99, 111L, WireFormat.WIRETYPE_VARINT)
                .addExtraField(98, 222, WireFormat.WIRETYPE_FIXED32)
                .addExtraField(97, 333L, WireFormat.WIRETYPE_FIXED64)
                .addExtraField(96, "contents_extra", WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();

        byte[] outer = new OuterBuilder()
                .matchId(DEFAULT_MATCH_ID)
                .matchDetails(contents)
                .addExtraField(99, 1111L, WireFormat.WIRETYPE_VARINT)
                .addExtraField(98, 2222, WireFormat.WIRETYPE_FIXED32)
                .addExtraField(97, 3333L, WireFormat.WIRETYPE_FIXED64)
                .addExtraField(96, "outer_extra", WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();

        MatchTelemetry telemetry = RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, outer);
        assertThat(telemetry.matchId()).isEqualTo(DEFAULT_MATCH_ID);
        assertThat(telemetry.players()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingRequiredFieldsCases")
    void missingRequiredFieldsThrow(String description, byte[] payload) {
        assertThatThrownBy(() -> RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, payload))
                .isInstanceOf(DeadlockApiException.class);
    }

    static Stream<Arguments> missingRequiredFieldsCases() {
        byte[] validItem = new ItemBuilder().gameTimeS(100).itemId(1L).build();
        byte[] validStat = new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build();
        byte[] validPlayer = new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1).addItem(validItem).addStat(validStat).build();
        byte[] validMatchInfo = new MatchInfoBuilder().durationS(1800).matchId(DEFAULT_MATCH_ID).addPlayer(validPlayer).build();
        byte[] validContents = new ContentsBuilder().matchInfo(validMatchInfo).build();

        return Stream.of(
                Arguments.of("missing match_details", new OuterBuilder().matchId(DEFAULT_MATCH_ID).matchDetails(null).build()),
                Arguments.of("missing match_info", new OuterBuilder().matchId(DEFAULT_MATCH_ID).matchDetails(new ContentsBuilder().matchInfo(null).build()).build()),
                Arguments.of("missing duration_s", new OuterBuilder().matchId(DEFAULT_MATCH_ID).matchDetails(
                        new ContentsBuilder().matchInfo(new MatchInfoBuilder().durationS(null).matchId(DEFAULT_MATCH_ID).addPlayer(validPlayer).build()).build()).build()),
                Arguments.of("missing match_id in match_info", new OuterBuilder().matchId(DEFAULT_MATCH_ID).matchDetails(
                        new ContentsBuilder().matchInfo(new MatchInfoBuilder().durationS(1800).matchId(null).addPlayer(validPlayer).build()).build()).build()),
                Arguments.of("empty players", new OuterBuilder().matchId(DEFAULT_MATCH_ID).matchDetails(
                        new ContentsBuilder().matchInfo(new MatchInfoBuilder().durationS(1800).matchId(DEFAULT_MATCH_ID).build()).build()).build()),
                Arguments.of("missing account_id", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(null).team(0).heroId(1).addItem(validItem).addStat(validStat).build())),
                Arguments.of("missing team", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(null).heroId(1).addItem(validItem).addStat(validStat).build())),
                Arguments.of("missing hero_id", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(null).addItem(validItem).addStat(validStat).build())),
                Arguments.of("missing item_id", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1).addItem(new ItemBuilder().gameTimeS(100).itemId(null).build()).addStat(validStat).build())),
                Arguments.of("missing game_time_s", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1).addItem(new ItemBuilder().gameTimeS(null).itemId(1L).build()).addStat(validStat).build())),
                Arguments.of("missing time_stamp_s", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1).addItem(validItem).addStat(
                                new StatBuilder().timeStampS(null).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build()).build())),
                Arguments.of("missing net_worth", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1).addItem(validItem).addStat(
                                new StatBuilder().timeStampS(180).netWorth(null).playerDamage(1000L).playerHealing(0L).build()).build())),
                Arguments.of("missing player_damage", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1).addItem(validItem).addStat(
                                new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(null).playerHealing(0L).build()).build())),
                Arguments.of("missing player_healing", wrapInValidPayload(DEFAULT_MATCH_ID,
                        new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1).addItem(validItem).addStat(
                                new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(null).build()).build()))
        );
    }

    @Test
    void matchIdMismatchThrows() {
        byte[] validInner = RawMetadataTestData.defaultValidPayload();

        // 1. Inner match_id mismatch
        byte[] innerMismatch = new OuterBuilder()
                .matchId(DEFAULT_MATCH_ID)
                .matchDetails(new ContentsBuilder().matchInfo(
                        new MatchInfoBuilder().durationS(1800).matchId(999999999L).addPlayer(
                                new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1)
                                        .addItem(new ItemBuilder().gameTimeS(100).itemId(1L).build())
                                        .addStat(new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build())
                                        .build()).build()).build())
                .build();

        assertThatThrownBy(() -> RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, innerMismatch))
                .isInstanceOf(DeadlockApiException.class)
                .hasMessageContaining("Match id mismatch");

        // 2. Outer match_id mismatch
        byte[] outerMismatch = new OuterBuilder()
                .matchId(888888888L)
                .matchDetails(new ContentsBuilder().matchInfo(
                        new MatchInfoBuilder().durationS(1800).matchId(DEFAULT_MATCH_ID).addPlayer(
                                new PlayerBuilder().accountId(ACCOUNT_ID_1).team(0).heroId(1)
                                        .addItem(new ItemBuilder().gameTimeS(100).itemId(1L).build())
                                        .addStat(new StatBuilder().timeStampS(180).netWorth(1000L).playerDamage(1000L).playerHealing(0L).build())
                                        .build()).build()).build())
                .build();

        assertThatThrownBy(() -> RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, outerMismatch))
                .isInstanceOf(DeadlockApiException.class)
                .hasMessageContaining("Match id mismatch");
    }

    @Test
    void truncatedOrGarbagePayloadThrowsApiException() {
        byte[] garbage = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        assertThatThrownBy(() -> RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, garbage))
                .isInstanceOf(DeadlockApiException.class);

        byte[] valid = RawMetadataTestData.defaultValidPayload();
        byte[] truncated = Arrays.copyOf(valid, valid.length - 10);
        assertThatThrownBy(() -> RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, truncated))
                .isInstanceOf(DeadlockApiException.class);

        assertThatThrownBy(() -> RawMatchMetadataParser.parse(DEFAULT_MATCH_ID, new byte[0]))
                .isInstanceOf(DeadlockApiException.class);
    }

    private static byte[] wrapInValidPayload(long matchId, byte[] player) {
        byte[] matchInfo = new MatchInfoBuilder()
                .durationS(1800)
                .matchId(matchId)
                .addPlayer(player)
                .build();

        byte[] contents = new ContentsBuilder()
                .matchInfo(matchInfo)
                .build();

        return new OuterBuilder()
                .matchId(matchId)
                .matchDetails(contents)
                .build();
    }
}
