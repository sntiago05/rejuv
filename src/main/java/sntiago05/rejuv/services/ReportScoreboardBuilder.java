package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.ReportView;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;

/**
 * Builds the two-team match scoreboard of the web report from match telemetry. Pure: no I/O, one pass over the
 * players. Names, hero names and item names come in as maps so the caller decides how to look them up.
 */
public final class ReportScoreboardBuilder {

    /** A build holds at most 12 items. */
    static final int MAX_ITEMS = 12;
    static final int MAX_NAME_CODE_POINTS = 24;

    private static final Comparator<RowDraft> BY_DAMAGE_THEN_KILLS =
            Comparator.comparingLong(RowDraft::damage).reversed()
                    .thenComparing(Comparator.comparingInt(RowDraft::kills).reversed());

    private ReportScoreboardBuilder() {
    }

    /**
     * @param viewerAccountId account of the analyzed player; highlighted when present in the match
     * @param result          {@code win}, {@code loss} or null when unknown (then no team gets an outcome)
     * @return empty when the telemetry has no players
     */
    public static Optional<ReportView.Scoreboard> build(MatchTelemetry telemetry, long viewerAccountId, String result,
                                                        Map<Integer, String> heroNames, Map<Long, String> playerNames,
                                                        Map<Long, ItemAsset> itemsById) {
        if (telemetry == null || telemetry.players().isEmpty()) {
            return Optional.empty();
        }
        Integer viewerTeam = telemetry.player(viewerAccountId).map(PlayerTelemetry::team).orElse(null);
        long maxDamage = 0;
        Map<Integer, List<RowDraft>> byTeam = new TreeMap<>();
        for (PlayerTelemetry player : telemetry.players()) {
            RowDraft draft = draft(player, viewerAccountId, heroNames, playerNames, itemsById);
            maxDamage = Math.max(maxDamage, draft.damage());
            byTeam.computeIfAbsent(player.team(), key -> new ArrayList<>()).add(draft);
        }

        List<Integer> teamOrder = new ArrayList<>(byTeam.keySet());
        if (viewerTeam != null) {
            teamOrder.remove(viewerTeam);
            teamOrder.add(0, viewerTeam);
        }
        String viewerOutcome = normalizeOutcome(result);
        List<ReportView.Team> teams = new ArrayList<>(teamOrder.size());
        int position = 0;
        for (int team : teamOrder) {
            List<RowDraft> drafts = byTeam.get(team);
            drafts.sort(BY_DAMAGE_THEN_KILLS);
            long kills = 0;
            long netWorth = 0;
            List<ReportView.Row> rows = new ArrayList<>(drafts.size());
            for (RowDraft d : drafts) {
                kills += d.kills();
                netWorth += d.netWorth();
                rows.add(d.toRow(maxDamage));
            }
            boolean isViewerTeam = viewerTeam != null && viewerTeam == team;
            teams.add(new ReportView.Team(labelKey(viewerTeam != null, isViewerTeam, position),
                    isViewerTeam, outcomeFor(viewerOutcome, viewerTeam != null, isViewerTeam),
                    Long.toString(kills), formatNetWorth(netWorth), rows));
            position++;
        }
        return Optional.of(new ReportView.Scoreboard(teams));
    }

    private static RowDraft draft(PlayerTelemetry player, long viewerAccountId, Map<Integer, String> heroNames,
                                  Map<Long, String> playerNames, Map<Long, ItemAsset> itemsById) {
        StatSnapshot last = player.snapshots().isEmpty() ? null : player.snapshots().get(player.snapshots().size() - 1);
        SnapshotDetail detail = last == null ? null : last.detail();
        int kills = detail == null ? 0 : detail.kills();
        int deaths = detail == null ? 0 : detail.deaths();
        int assists = detail == null ? 0 : detail.assists();
        int creeps = detail == null ? 0 : detail.creepKills();
        long damage = last == null ? 0 : last.playerDamage();
        long netWorth = last == null ? 0 : last.netWorth();
        String heroName = heroNames.getOrDefault(player.heroId(), "hero_" + player.heroId());
        return new RowDraft(player.accountId() == viewerAccountId, player.heroId(), heroName,
                cleanName(playerNames.get(player.accountId())), kills, deaths, assists, creeps, damage, netWorth,
                finalItems(player.items(), itemsById));
    }

    /** Items still owned at the end, oldest first, at most {@link #MAX_ITEMS} (the latest ones when more). */
    private static List<ReportView.ScoreItem> finalItems(List<ItemPurchase> purchases, Map<Long, ItemAsset> itemsById) {
        List<ItemPurchase> kept = purchases.stream().filter(p -> p.soldAtS() == null).toList();
        List<ItemPurchase> tail = kept.size() > MAX_ITEMS ? kept.subList(kept.size() - MAX_ITEMS, kept.size()) : kept;
        List<ReportView.ScoreItem> items = new ArrayList<>(tail.size());
        for (ItemPurchase purchase : tail) {
            ItemAsset asset = itemsById.get(purchase.itemId());
            String name = asset == null || asset.name() == null || asset.name().isBlank()
                    ? "Item " + purchase.itemId() : asset.name();
            items.add(new ReportView.ScoreItem(purchase.itemId(), name));
        }
        return items;
    }

    /** Single line, at most {@link #MAX_NAME_CODE_POINTS} code points; HTML escaping is left to {@code th:text}. */
    static String cleanName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String oneLine = rawName.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (oneLine.codePointCount(0, oneLine.length()) > MAX_NAME_CODE_POINTS) {
            oneLine = oneLine.substring(0, oneLine.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)).trim();
        }
        return oneLine;
    }

    private static String normalizeOutcome(String result) {
        if (result == null) {
            return null;
        }
        String lower = result.toLowerCase(Locale.ROOT);
        return lower.equals("win") || lower.equals("loss") ? lower : null;
    }

    /** Needs both a known result and a known viewer team; otherwise no team gets an outcome. */
    private static String outcomeFor(String viewerOutcome, boolean viewerKnown, boolean isViewerTeam) {
        if (viewerOutcome == null || !viewerKnown) {
            return null;
        }
        if (isViewerTeam) {
            return viewerOutcome;
        }
        return viewerOutcome.equals("win") ? "loss" : "win";
    }

    private static String labelKey(boolean viewerKnown, boolean isViewerTeam, int position) {
        if (viewerKnown) {
            return isViewerTeam ? "report.scoreboard.team.you" : "report.scoreboard.team.enemy";
        }
        return position == 0 ? "report.scoreboard.team.a" : "report.scoreboard.team.b";
    }

    private static String formatNetWorth(long netWorth) {
        return String.format(Locale.ROOT, "%.1fk", netWorth / 1000.0);
    }

    private record RowDraft(boolean viewer, int heroId, String heroName, String playerName, int kills, int deaths,
                            int assists, int creeps, long damage, long netWorth, List<ReportView.ScoreItem> items) {

        ReportView.Row toRow(long maxDamage) {
            double kda = (kills + assists) / (double) Math.max(1, deaths);
            double pct = maxDamage <= 0 ? 0.0 : damage * 100.0 / maxDamage;
            return new ReportView.Row(viewer, heroId, heroName, playerName, kills, deaths, assists,
                    String.format(Locale.ROOT, "%.1f", kda), creeps,
                    String.format(Locale.ROOT, "%,d", damage), ReportService.mapWidthClass(pct), items);
        }
    }
}
