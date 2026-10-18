package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.StatSnapshot;

/** Builds the final build and the chart series from telemetry already fetched. No I/O, no AI. */
@Service
public class MatchVisualsService {

    static final int MAX_ITEMS = 12;
    private static final String BUYABLE_TYPE = "upgrade";

    private final GameAssets gameAssets;

    public MatchVisualsService(GameAssets gameAssets) {
        this.gameAssets = gameAssets;
    }

    /** Empty when telemetry is null or the player is not in it. Never throws for missing data. */
    public Optional<MatchVisuals> build(long accountId, MatchTelemetry telemetry) {
        if (telemetry == null) {
            return Optional.empty();
        }
        Optional<PlayerTelemetry> playerOpt = telemetry.player(accountId);
        if (playerOpt.isEmpty()) {
            return Optional.empty();
        }
        PlayerTelemetry player = playerOpt.get();
        List<MatchVisuals.PurchasedItem> items = buildItems(player);
        MatchVisuals.ChartData chart = buildChart(player, telemetry.players());
        if (items.isEmpty() && chart == null) {
            return Optional.empty();
        }
        return Optional.of(new MatchVisuals(items, chart));
    }

    private List<MatchVisuals.PurchasedItem> buildItems(PlayerTelemetry player) {
        Map<Long, ItemAsset> assets = gameAssets.itemsById();
        List<MatchVisuals.PurchasedItem> items = new ArrayList<>();
        for (ItemPurchase purchase : player.items()) {
            if (purchase.soldAtS() != null) {
                continue;
            }
            ItemAsset asset = assets.get(purchase.itemId());
            if (asset == null || !BUYABLE_TYPE.equals(asset.type()) || asset.name() == null || asset.name().isBlank()) {
                continue;
            }
            items.add(new MatchVisuals.PurchasedItem(purchase.itemId(), asset.name(), purchase.boughtAtS()));
        }
        return items.size() > MAX_ITEMS ? List.copyOf(items.subList(items.size() - MAX_ITEMS, items.size())) : items;
    }

    private MatchVisuals.ChartData buildChart(PlayerTelemetry self, List<PlayerTelemetry> players) {
        boolean hasTeammate = players.stream().anyMatch(p -> p != self && p.team() == self.team());
        if (!hasTeammate) {
            return null;
        }
        // One timeS -> netWorth map per player, built in a single pass.
        List<Map<Integer, Long>> perPlayer = new ArrayList<>(players.size());
        TreeSet<Integer> common = null;
        for (PlayerTelemetry p : players) {
            Map<Integer, Long> byTime = new HashMap<>();
            for (StatSnapshot s : p.snapshots()) {
                byTime.put(s.timeS(), s.netWorth());
            }
            perPlayer.add(byTime);
            if (common == null) {
                common = new TreeSet<>(byTime.keySet());
            } else {
                common.retainAll(byTime.keySet());
            }
        }
        if (common == null || common.size() < 2) {
            return null;
        }

        List<Integer> times = new ArrayList<>(common);
        List<Long> lead = new ArrayList<>(times.size());
        List<Long> own = new ArrayList<>(times.size());
        List<Long> avg = new ArrayList<>(times.size());
        int teammateCount = 0;
        for (PlayerTelemetry p : players) {
            if (p != self && p.team() == self.team()) {
                teammateCount++;
            }
        }
        for (int t : times) {
            long ownTeam = 0;
            long others = 0;
            long teammates = 0;
            long selfWorth = 0;
            for (int i = 0; i < players.size(); i++) {
                PlayerTelemetry p = players.get(i);
                long worth = perPlayer.get(i).get(t);
                if (p.team() == self.team()) {
                    ownTeam += worth;
                    if (p == self) {
                        selfWorth = worth;
                    } else {
                        teammates += worth;
                    }
                } else {
                    others += worth;
                }
            }
            lead.add(ownTeam - others);
            own.add(selfWorth);
            if (teammateCount > 0) {
                avg.add(teammates / teammateCount);
            }
        }
        return new MatchVisuals.ChartData(times, lead, own, avg);
    }
}
