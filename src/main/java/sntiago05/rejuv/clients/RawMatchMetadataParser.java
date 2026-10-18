package sntiago05.rejuv.clients;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.PowerUpBuff;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Hand-written protobuf decoder for {@code CMsgMatchMetaData} using {@link CodedInputStream}.
 */
final class RawMatchMetadataParser {

    private static final int DEFAULT_RECURSION_LIMIT = 100;

    private RawMatchMetadataParser() { }

    static MatchTelemetry parse(long requestedMatchId, byte[] decompressedPayload) {
        if (decompressedPayload == null || decompressedPayload.length == 0) {
            throw new DeadlockApiException("Invalid body: payload is empty");
        }
        try {
            return doParse(requestedMatchId, decompressedPayload);
        } catch (DeadlockApiException e) {
            throw e;
        } catch (InvalidProtocolBufferException e) {
            throw new DeadlockApiException("Invalid body: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new DeadlockApiException("Invalid body: " + e.getMessage(), e);
        }
    }

    private static MatchTelemetry doParse(long requestedMatchId, byte[] decompressedPayload) throws IOException {
        CodedInputStream outer = CodedInputStream.newInstance(decompressedPayload);
        outer.setSizeLimit(decompressedPayload.length + 1024);
        outer.setRecursionLimit(DEFAULT_RECURSION_LIMIT);

        byte[] matchDetails = null;
        Long outerMatchId = null;

        while (!outer.isAtEnd()) {
            int tag = outer.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (fieldNumber == 2 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                matchDetails = outer.readByteArray();
            } else if (fieldNumber == 3 && wireType == WireFormat.WIRETYPE_VARINT) {
                outerMatchId = outer.readUInt64();
            } else {
                outer.skipField(tag);
            }
        }

        if (matchDetails == null) {
            throw new DeadlockApiException("Invalid body: match_details is missing");
        }
        if (outerMatchId != null && outerMatchId != requestedMatchId) {
            throw new DeadlockApiException("Match id mismatch: requested " + requestedMatchId + " but outer had " + outerMatchId);
        }

        CodedInputStream contents = CodedInputStream.newInstance(matchDetails);
        contents.setSizeLimit(matchDetails.length + 1024);
        contents.setRecursionLimit(DEFAULT_RECURSION_LIMIT);

        byte[] matchInfoBytes = null;
        while (!contents.isAtEnd()) {
            int tag = contents.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (fieldNumber == 2 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                matchInfoBytes = contents.readByteArray();
            } else {
                contents.skipField(tag);
            }
        }

        if (matchInfoBytes == null) {
            throw new DeadlockApiException("Invalid body: match_info is missing");
        }

        CodedInputStream matchInfo = CodedInputStream.newInstance(matchInfoBytes);
        matchInfo.setSizeLimit(matchInfoBytes.length + 1024);
        matchInfo.setRecursionLimit(DEFAULT_RECURSION_LIMIT);

        Integer durationS = null;
        Long matchId = null;
        List<PlayerTelemetry> players = new ArrayList<>();

        while (!matchInfo.isAtEnd()) {
            int tag = matchInfo.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (fieldNumber == 1 && wireType == WireFormat.WIRETYPE_VARINT) {
                durationS = matchInfo.readUInt32();
            } else if (fieldNumber == 6 && wireType == WireFormat.WIRETYPE_VARINT) {
                matchId = matchInfo.readUInt64();
            } else if (fieldNumber == 4 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                int length = matchInfo.readRawVarint32();
                int limit = matchInfo.pushLimit(length);
                players.add(parsePlayer(matchInfo));
                matchInfo.popLimit(limit);
            } else {
                matchInfo.skipField(tag);
            }
        }

        if (durationS == null) {
            throw new DeadlockApiException("Invalid body: duration_s is missing");
        }
        if (matchId == null) {
            throw new DeadlockApiException("Invalid body: match_id is missing");
        }
        if (matchId != requestedMatchId) {
            throw new DeadlockApiException("Match id mismatch: requested " + requestedMatchId + " but body had " + matchId);
        }
        if (players.isEmpty()) {
            throw new DeadlockApiException("Invalid body: players is missing or empty");
        }

        return new MatchTelemetry(matchId, durationS, List.copyOf(players));
    }

    private static PlayerTelemetry parsePlayer(CodedInputStream input) throws IOException {
        Long accountId = null;
        Integer team = null;
        Integer heroId = null;
        List<ItemPurchase> items = new ArrayList<>();
        List<StatSnapshot> stats = new ArrayList<>();
        List<PowerUpBuff> buffs = new ArrayList<>();

        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (fieldNumber == 1 && wireType == WireFormat.WIRETYPE_VARINT) {
                accountId = Integer.toUnsignedLong(input.readUInt32());
            } else if (fieldNumber == 6 && wireType == WireFormat.WIRETYPE_VARINT) {
                team = input.readEnum();
            } else if (fieldNumber == 12 && wireType == WireFormat.WIRETYPE_VARINT) {
                heroId = input.readUInt32();
            } else if (fieldNumber == 4 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                int length = input.readRawVarint32();
                int limit = input.pushLimit(length);
                items.add(parseItem(input));
                input.popLimit(limit);
            } else if (fieldNumber == 5 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                int length = input.readRawVarint32();
                int limit = input.pushLimit(length);
                stats.add(parseStat(input));
                input.popLimit(limit);
            } else if (fieldNumber == 30 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                int length = input.readRawVarint32();
                int limit = input.pushLimit(length);
                PowerUpBuff buff = parseBuff(input);
                if (buff != null) {
                    buffs.add(buff);
                }
                input.popLimit(limit);
            } else {
                input.skipField(tag);
            }
        }

        if (accountId == null) {
            throw new DeadlockApiException("Invalid body: account_id is missing");
        }
        if (team == null) {
            throw new DeadlockApiException("Invalid body: team is missing");
        }
        if (heroId == null) {
            throw new DeadlockApiException("Invalid body: hero_id is missing");
        }

        List<ItemPurchase> sortedItems = items.stream()
                .sorted(Comparator.comparingInt(ItemPurchase::boughtAtS))
                .toList();

        List<StatSnapshot> sortedStats = stats.stream()
                .sorted(Comparator.comparingInt(StatSnapshot::timeS))
                .toList();

        return new PlayerTelemetry(accountId, team, heroId, sortedItems, sortedStats, List.copyOf(buffs));
    }

    private static ItemPurchase parseItem(CodedInputStream input) throws IOException {
        Integer gameTimeS = null;
        Long itemId = null;
        Integer soldTimeS = null;

        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (fieldNumber == 1 && wireType == WireFormat.WIRETYPE_VARINT) {
                gameTimeS = input.readUInt32();
            } else if (fieldNumber == 2 && wireType == WireFormat.WIRETYPE_VARINT) {
                itemId = Integer.toUnsignedLong(input.readUInt32());
            } else if (fieldNumber == 4 && wireType == WireFormat.WIRETYPE_VARINT) {
                soldTimeS = input.readUInt32();
            } else {
                input.skipField(tag);
            }
        }

        if (gameTimeS == null) {
            throw new DeadlockApiException("Invalid body: game_time_s is missing");
        }
        if (itemId == null) {
            throw new DeadlockApiException("Invalid body: item_id is missing");
        }

        Integer soldAtS = (soldTimeS != null && soldTimeS > 0) ? soldTimeS : null;
        return new ItemPurchase(itemId, gameTimeS, soldAtS);
    }

    private static StatSnapshot parseStat(CodedInputStream input) throws IOException {
        Integer timeStampS = null;
        Long netWorth = null;
        Long playerDamage = null;
        Long playerHealing = null;

        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (fieldNumber == 1 && wireType == WireFormat.WIRETYPE_VARINT) {
                timeStampS = input.readUInt32();
            } else if (fieldNumber == 2 && wireType == WireFormat.WIRETYPE_VARINT) {
                netWorth = Integer.toUnsignedLong(input.readUInt32());
            } else if (fieldNumber == 21 && wireType == WireFormat.WIRETYPE_VARINT) {
                playerDamage = Integer.toUnsignedLong(input.readUInt32());
            } else if (fieldNumber == 25 && wireType == WireFormat.WIRETYPE_VARINT) {
                playerHealing = Integer.toUnsignedLong(input.readUInt32());
            } else {
                input.skipField(tag);
            }
        }

        if (timeStampS == null) {
            throw new DeadlockApiException("Invalid body: time_stamp_s is missing");
        }
        if (netWorth == null) {
            throw new DeadlockApiException("Invalid body: net_worth is missing");
        }
        if (playerDamage == null) {
            throw new DeadlockApiException("Invalid body: player_damage is missing");
        }
        if (playerHealing == null) {
            throw new DeadlockApiException("Invalid body: player_healing is missing");
        }

        return new StatSnapshot(timeStampS, netWorth, playerDamage, playerHealing);
    }

    private static PowerUpBuff parseBuff(CodedInputStream input) throws IOException {
        String type = null;
        Boolean isPermanent = null;
        List<Integer> pickupTimes = new ArrayList<>();

        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 0) {
                break;
            }
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (fieldNumber == 1 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                type = input.readString();
            } else if (fieldNumber == 3 && wireType == WireFormat.WIRETYPE_VARINT) {
                isPermanent = input.readBool();
            } else if (fieldNumber == 4) {
                if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    int length = input.readRawVarint32();
                    int limit = input.pushLimit(length);
                    while (input.getBytesUntilLimit() > 0) {
                        pickupTimes.add(input.readUInt32());
                    }
                    input.popLimit(limit);
                } else if (wireType == WireFormat.WIRETYPE_VARINT) {
                    pickupTimes.add(input.readUInt32());
                } else {
                    input.skipField(tag);
                }
            } else {
                input.skipField(tag);
            }
        }

        if (type == null) {
            return null;
        }

        boolean permanent = Boolean.TRUE.equals(isPermanent);
        List<Integer> sortedPickupTimes = pickupTimes.stream().sorted().toList();
        return new PowerUpBuff(type, permanent, sortedPickupTimes);
    }
}
