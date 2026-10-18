package sntiago05.rejuv.clients;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.github.luben.zstd.Zstd;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

/**
 * Test helper for constructing raw protobuf and Zstandard metadata payloads.
 */
final class RawMetadataTestData {

    static final long DEFAULT_MATCH_ID = 900000003L;
    static final long ACCOUNT_ID_1 = 1000001L;
    static final long ACCOUNT_ID_2 = 1000002L;
    static final long LARGE_ITEM_ID = 3612874202L;

    private RawMetadataTestData() { }

    static byte[] compress(byte[] raw) {
        return Zstd.compress(raw);
    }

    static byte[] defaultValidPayload() {
        byte[] p1 = new PlayerBuilder()
                .accountId(ACCOUNT_ID_1)
                .team(0)
                .heroId(1)
                .addItem(new ItemBuilder().gameTimeS(300).itemId(LARGE_ITEM_ID).soldTimeS(0).build())
                .addItem(new ItemBuilder().gameTimeS(600).itemId(101L).soldTimeS(700).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(1500L).playerDamage(2000L).playerHealing(500L).build())
                .addStat(new StatBuilder().timeStampS(360).netWorth(3200L).playerDamage(5000L).playerHealing(1200L).build())
                .addBuff(new BuffBuilder().type("weapon_power").isPermanent(true).pickupTimes(List.of(120, 240)).packed(true).build())
                .build();

        byte[] p2 = new PlayerBuilder()
                .accountId(ACCOUNT_ID_2)
                .team(1)
                .heroId(2)
                .addItem(new ItemBuilder().gameTimeS(250).itemId(201L).soldTimeS(null).build())
                .addStat(new StatBuilder().timeStampS(180).netWorth(1400L).playerDamage(1800L).playerHealing(300L).build())
                .addBuff(new BuffBuilder().type("spirit_power").isPermanent(false).pickupTimes(List.of(150)).packed(false).build())
                .build();

        byte[] matchInfo = new MatchInfoBuilder()
                .durationS(1800)
                .matchId(DEFAULT_MATCH_ID)
                .addPlayer(p1)
                .addPlayer(p2)
                .build();

        byte[] contents = new ContentsBuilder()
                .matchInfo(matchInfo)
                .build();

        return new OuterBuilder()
                .matchId(DEFAULT_MATCH_ID)
                .matchDetails(contents)
                .build();
    }

    static class OuterBuilder {
        private Integer version = 1;
        private byte[] matchDetails;
        private Long matchId;
        private final List<ExtraField> extraFields = new ArrayList<>();

        OuterBuilder version(Integer version) {
            this.version = version;
            return this;
        }

        OuterBuilder matchDetails(byte[] matchDetails) {
            this.matchDetails = matchDetails;
            return this;
        }

        OuterBuilder matchId(Long matchId) {
            this.matchId = matchId;
            return this;
        }

        OuterBuilder addExtraField(int fieldNumber, Object value, int wireType) {
            this.extraFields.add(new ExtraField(fieldNumber, value, wireType));
            return this;
        }

        byte[] build() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                if (version != null) {
                    cos.writeUInt32(1, version);
                }
                if (matchDetails != null) {
                    cos.writeByteArray(2, matchDetails);
                }
                if (matchId != null) {
                    cos.writeUInt64(3, matchId);
                }
                writeExtraFields(cos, extraFields);
                cos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class ContentsBuilder {
        private byte[] matchInfo;
        private final List<ExtraField> extraFields = new ArrayList<>();

        ContentsBuilder matchInfo(byte[] matchInfo) {
            this.matchInfo = matchInfo;
            return this;
        }

        ContentsBuilder addExtraField(int fieldNumber, Object value, int wireType) {
            this.extraFields.add(new ExtraField(fieldNumber, value, wireType));
            return this;
        }

        byte[] build() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                if (matchInfo != null) {
                    cos.writeByteArray(2, matchInfo);
                }
                writeExtraFields(cos, extraFields);
                cos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class MatchInfoBuilder {
        private Integer durationS;
        private Long matchId;
        private final List<byte[]> players = new ArrayList<>();
        private final List<ExtraField> extraFields = new ArrayList<>();

        MatchInfoBuilder durationS(Integer durationS) {
            this.durationS = durationS;
            return this;
        }

        MatchInfoBuilder matchId(Long matchId) {
            this.matchId = matchId;
            return this;
        }

        MatchInfoBuilder addPlayer(byte[] player) {
            this.players.add(player);
            return this;
        }

        MatchInfoBuilder addExtraField(int fieldNumber, Object value, int wireType) {
            this.extraFields.add(new ExtraField(fieldNumber, value, wireType));
            return this;
        }

        byte[] build() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                if (durationS != null) {
                    cos.writeUInt32(1, durationS);
                }
                for (byte[] p : players) {
                    cos.writeByteArray(4, p);
                }
                if (matchId != null) {
                    cos.writeUInt64(6, matchId);
                }
                writeExtraFields(cos, extraFields);
                cos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class PlayerBuilder {
        private Long accountId;
        private Integer team;
        private Integer heroId;
        private final List<byte[]> items = new ArrayList<>();
        private final List<byte[]> stats = new ArrayList<>();
        private final List<byte[]> buffs = new ArrayList<>();
        private final List<ExtraField> extraFields = new ArrayList<>();

        PlayerBuilder accountId(Long accountId) {
            this.accountId = accountId;
            return this;
        }

        PlayerBuilder team(Integer team) {
            this.team = team;
            return this;
        }

        PlayerBuilder heroId(Integer heroId) {
            this.heroId = heroId;
            return this;
        }

        PlayerBuilder addItem(byte[] item) {
            this.items.add(item);
            return this;
        }

        PlayerBuilder addStat(byte[] stat) {
            this.stats.add(stat);
            return this;
        }

        PlayerBuilder addBuff(byte[] buff) {
            this.buffs.add(buff);
            return this;
        }

        PlayerBuilder addExtraField(int fieldNumber, Object value, int wireType) {
            this.extraFields.add(new ExtraField(fieldNumber, value, wireType));
            return this;
        }

        byte[] build() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                if (accountId != null) {
                    cos.writeUInt32(1, (int) accountId.longValue());
                }
                for (byte[] it : items) {
                    cos.writeByteArray(4, it);
                }
                for (byte[] st : stats) {
                    cos.writeByteArray(5, st);
                }
                if (team != null) {
                    cos.writeEnum(6, team);
                }
                if (heroId != null) {
                    cos.writeUInt32(12, heroId);
                }
                for (byte[] b : buffs) {
                    cos.writeByteArray(30, b);
                }
                writeExtraFields(cos, extraFields);
                cos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class ItemBuilder {
        private Integer gameTimeS;
        private Long itemId;
        private Integer soldTimeS;
        private final List<ExtraField> extraFields = new ArrayList<>();

        ItemBuilder gameTimeS(Integer gameTimeS) {
            this.gameTimeS = gameTimeS;
            return this;
        }

        ItemBuilder itemId(Long itemId) {
            this.itemId = itemId;
            return this;
        }

        ItemBuilder soldTimeS(Integer soldTimeS) {
            this.soldTimeS = soldTimeS;
            return this;
        }

        ItemBuilder addExtraField(int fieldNumber, Object value, int wireType) {
            this.extraFields.add(new ExtraField(fieldNumber, value, wireType));
            return this;
        }

        byte[] build() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                if (gameTimeS != null) {
                    cos.writeUInt32(1, gameTimeS);
                }
                if (itemId != null) {
                    cos.writeUInt32(2, (int) itemId.longValue());
                }
                if (soldTimeS != null) {
                    cos.writeUInt32(4, soldTimeS);
                }
                writeExtraFields(cos, extraFields);
                cos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class StatBuilder {
        private Integer timeStampS;
        private Long netWorth;
        private Long playerDamage;
        private Long playerHealing;
        private final List<ExtraField> extraFields = new ArrayList<>();

        StatBuilder timeStampS(Integer timeStampS) {
            this.timeStampS = timeStampS;
            return this;
        }

        StatBuilder netWorth(Long netWorth) {
            this.netWorth = netWorth;
            return this;
        }

        StatBuilder playerDamage(Long playerDamage) {
            this.playerDamage = playerDamage;
            return this;
        }

        StatBuilder playerHealing(Long playerHealing) {
            this.playerHealing = playerHealing;
            return this;
        }

        StatBuilder addExtraField(int fieldNumber, Object value, int wireType) {
            this.extraFields.add(new ExtraField(fieldNumber, value, wireType));
            return this;
        }

        byte[] build() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                if (timeStampS != null) {
                    cos.writeUInt32(1, timeStampS);
                }
                if (netWorth != null) {
                    cos.writeUInt32(2, (int) netWorth.longValue());
                }
                if (playerDamage != null) {
                    cos.writeUInt32(21, (int) playerDamage.longValue());
                }
                if (playerHealing != null) {
                    cos.writeUInt32(25, (int) playerHealing.longValue());
                }
                writeExtraFields(cos, extraFields);
                cos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class BuffBuilder {
        private String type;
        private Boolean isPermanent;
        private List<Integer> pickupTimes = new ArrayList<>();
        private boolean packed = true;
        private final List<ExtraField> extraFields = new ArrayList<>();

        BuffBuilder type(String type) {
            this.type = type;
            return this;
        }

        BuffBuilder isPermanent(Boolean isPermanent) {
            this.isPermanent = isPermanent;
            return this;
        }

        BuffBuilder pickupTimes(List<Integer> pickupTimes) {
            this.pickupTimes = pickupTimes;
            return this;
        }

        BuffBuilder packed(boolean packed) {
            this.packed = packed;
            return this;
        }

        BuffBuilder addExtraField(int fieldNumber, Object value, int wireType) {
            this.extraFields.add(new ExtraField(fieldNumber, value, wireType));
            return this;
        }

        byte[] build() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                CodedOutputStream cos = CodedOutputStream.newInstance(baos);
                if (type != null) {
                    cos.writeString(1, type);
                }
                if (isPermanent != null) {
                    cos.writeBool(3, isPermanent);
                }
                if (pickupTimes != null && !pickupTimes.isEmpty()) {
                    if (packed) {
                        ByteArrayOutputStream packedBaos = new ByteArrayOutputStream();
                        CodedOutputStream packedCos = CodedOutputStream.newInstance(packedBaos);
                        for (int t : pickupTimes) {
                            packedCos.writeUInt32NoTag(t);
                        }
                        packedCos.flush();
                        cos.writeByteArray(4, packedBaos.toByteArray());
                    } else {
                        for (int t : pickupTimes) {
                            cos.writeUInt32(4, t);
                        }
                    }
                }
                writeExtraFields(cos, extraFields);
                cos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private record ExtraField(int fieldNumber, Object value, int wireType) { }

    private static void writeExtraFields(CodedOutputStream cos, List<ExtraField> fields) throws IOException {
        for (ExtraField f : fields) {
            switch (f.wireType()) {
                case WireFormat.WIRETYPE_VARINT -> cos.writeInt64(f.fieldNumber(), ((Number) f.value()).longValue());
                case WireFormat.WIRETYPE_FIXED32 -> cos.writeFixed32(f.fieldNumber(), ((Number) f.value()).intValue());
                case WireFormat.WIRETYPE_FIXED64 -> cos.writeFixed64(f.fieldNumber(), ((Number) f.value()).longValue());
                case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    if (f.value() instanceof byte[] b) {
                        cos.writeByteArray(f.fieldNumber(), b);
                    } else if (f.value() instanceof String s) {
                        cos.writeString(f.fieldNumber(), s);
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported wire type: " + f.wireType());
            }
        }
    }
}
