package sntiago05.rejuv.services;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Pure server-side SVG renderer for the soul curve (net worth over match time).
 * Produces valid, script-free, deterministic XML with dark and light theme support.
 */
@Component
public class SoulCurveSvg {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;

    private static final double PLOT_LEFT = 65.0;
    private static final double PLOT_RIGHT = 605.0;
    private static final double PLOT_TOP = 45.0;
    private static final double PLOT_BOTTOM = 290.0;
    private static final double PLOT_WIDTH = PLOT_RIGHT - PLOT_LEFT;
    private static final double PLOT_HEIGHT = PLOT_BOTTOM - PLOT_TOP;

    private final Messages messages;

    public SoulCurveSvg(Messages messages) {
        this.messages = messages;
    }

    /**
     * Convenience render without explicit hero/rank labels (uses defaults).
     */
    public Optional<String> render(List<Integer> timesS, List<Long> playerNetWorth,
                                   List<Double> avgNetWorth, List<Double> stdNetWorth) {
        return render(null, null, timesS, playerNetWorth, avgNetWorth, stdNetWorth);
    }

    /**
     * Renders the soul curve SVG. Empty when fewer than 3 points or lists differ in size.
     */
    public Optional<String> render(String heroName, String rankLabel,
                                   List<Integer> timesS, List<Long> playerNetWorth,
                                   List<Double> avgNetWorth, List<Double> stdNetWorth) {
        return render(heroName, rankLabel, false, timesS, playerNetWorth, avgNetWorth, stdNetWorth);
    }

    /**
     * Renders the soul curve SVG with ownRank flag selecting caption wording.
     */
    public Optional<String> render(String heroName, String rankLabel, boolean ownRank,
                                   List<Integer> timesS, List<Long> playerNetWorth,
                                   List<Double> avgNetWorth, List<Double> stdNetWorth) {
        if (timesS == null || playerNetWorth == null || avgNetWorth == null || stdNetWorth == null) {
            return Optional.empty();
        }
        int n = timesS.size();
        if (n < 3 || playerNetWorth.size() != n || avgNetWorth.size() != n || stdNetWorth.size() != n) {
            return Optional.empty();
        }

        double maxTimeMin = timesS.get(n - 1) / 60.0;
        double tickStepX = (maxTimeMin <= 12.0) ? 2.0 : 5.0;
        double maxXMinutes = Math.max(tickStepX, Math.ceil(maxTimeMin / tickStepX) * tickStepX);

        double maxDataY = 0.0;
        for (int i = 0; i < n; i++) {
            double p = playerNetWorth.get(i);
            double upper = avgNetWorth.get(i) + stdNetWorth.get(i);
            if (p > maxDataY) {
                maxDataY = p;
            }
            if (upper > maxDataY) {
                maxDataY = upper;
            }
        }
        if (maxDataY <= 0.0) {
            maxDataY = 1000.0;
        }

        double maxK = maxDataY / 1000.0;
        double tickStepY;
        if (maxK <= 5.0) {
            tickStepY = 1.0;
        } else if (maxK <= 15.0) {
            tickStepY = 2.5;
        } else if (maxK <= 30.0) {
            tickStepY = 5.0;
        } else if (maxK <= 60.0) {
            tickStepY = 10.0;
        } else if (maxK <= 150.0) {
            tickStepY = 20.0;
        } else {
            tickStepY = Math.ceil(maxK / 5.0 / 10.0) * 10.0;
        }
        double maxYK = Math.ceil(maxK / tickStepY) * tickStepY;
        if (maxYK < maxK) {
            maxYK += tickStepY;
        }
        double yMaxSouls = maxYK * 1000.0;

        StringBuilder sb = new StringBuilder(4096);

        String title = escapeXml(messages.get("report.curve.title"));
        String ariaLabel = escapeXml(messages.get("report.curve.aria"));

        sb.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" role=\"img\" aria-label=\"%s\">\n",
                WIDTH, HEIGHT, ariaLabel));
        sb.append("<title>").append(title).append("</title>\n");

        sb.append("<style>\n");
        sb.append(".bg { fill: #1e1f22; }\n");
        sb.append(".grid { stroke: #2b2d31; stroke-width: 1; }\n");
        sb.append(".axis-text { fill: #949ba4; font-size: 11px; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; }\n");
        sb.append(".axis-title { fill: #949ba4; font-size: 11px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; }\n");
        sb.append(".caption { fill: #dbdee1; font-size: 13px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; }\n");
        sb.append(".band { fill: #4c9be8; fill-opacity: 0.18; stroke: none; }\n");
        sb.append(".avg-line { stroke: #4c9be8; stroke-width: 2; stroke-dasharray: 6 4; fill: none; }\n");
        sb.append(".player-line { stroke: #ffc83d; stroke-width: 3; fill: none; stroke-linecap: round; stroke-linejoin: round; }\n");
        sb.append(".direct-label-player { fill: #ffc83d; font-size: 11px; font-weight: 700; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; }\n");
        sb.append(".direct-label-avg { fill: #4c9be8; font-size: 11px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; }\n");
        sb.append(".verdict-text { fill: #dbdee1; font-size: 11px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; }\n");
        sb.append(".legend-text { fill: #dbdee1; font-size: 11px; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; }\n");
        sb.append("@media (prefers-color-scheme: light) {\n");
        sb.append("  .bg { fill: #ffffff; }\n");
        sb.append("  .grid { stroke: #e5e7eb; }\n");
        sb.append("  .axis-text { fill: #6b7280; }\n");
        sb.append("  .axis-title { fill: #6b7280; }\n");
        sb.append("  .caption { fill: #111827; }\n");
        sb.append("  .band { fill: #2563eb; fill-opacity: 0.15; }\n");
        sb.append("  .avg-line { stroke: #2563eb; }\n");
        sb.append("  .player-line { stroke: #d97706; }\n");
        sb.append("  .direct-label-player { fill: #d97706; }\n");
        sb.append("  .direct-label-avg { fill: #2563eb; }\n");
        sb.append("  .verdict-text { fill: #111827; }\n");
        sb.append("  .legend-text { fill: #374151; }\n");
        sb.append("}\n");
        sb.append("</style>\n");

        sb.append("<rect width=\"100%\" height=\"100%\" class=\"bg\" />\n");

        // Y-axis grid and ticks
        for (double k = 0.0; k <= maxYK + 0.001; k += tickStepY) {
            double y = mapY(k * 1000.0, yMaxSouls);
            sb.append(String.format(Locale.ROOT,
                    "<line class=\"grid\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                    PLOT_LEFT, y, PLOT_RIGHT, y));
            String label = (k == (long) k)
                    ? String.format(Locale.ROOT, "%dk", (long) k)
                    : String.format(Locale.ROOT, "%.1fk", k);
            sb.append(String.format(Locale.ROOT,
                    "<text class=\"axis-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n",
                    PLOT_LEFT - 8.0, y + 4.0, label));
        }

        // X-axis grid and ticks
        for (double m = 0.0; m <= maxXMinutes + 0.001; m += tickStepX) {
            double x = mapX(m, maxXMinutes);
            sb.append(String.format(Locale.ROOT,
                    "<line class=\"grid\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                    x, PLOT_TOP, x, PLOT_BOTTOM));
            sb.append(String.format(Locale.ROOT,
                    "<text class=\"axis-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%d</text>\n",
                    x, PLOT_BOTTOM + 16.0, (long) m));
        }

        // Axis titles
        String axisSouls = escapeXml(messages.get("report.curve.axis.souls"));
        sb.append(String.format(Locale.ROOT,
                "<text class=\"axis-title\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"start\">%s</text>\n",
                PLOT_LEFT, PLOT_TOP - 12.0, axisSouls));

        String axisMinutes = escapeXml(messages.get("report.curve.axis.minutes"));
        sb.append(String.format(Locale.ROOT,
                "<text class=\"axis-title\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>\n",
                PLOT_LEFT + PLOT_WIDTH / 2.0, PLOT_BOTTOM + 32.0, axisMinutes));

        // Shaded band (avg +/- std, lower bound clamped at 0)
        StringBuilder bandD = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double tMin = timesS.get(i) / 60.0;
            double upper = avgNetWorth.get(i) + stdNetWorth.get(i);
            double x = mapX(tMin, maxXMinutes);
            double y = mapY(upper, yMaxSouls);
            if (i == 0) {
                bandD.append(String.format(Locale.ROOT, "M %.1f %.1f", x, y));
            } else {
                bandD.append(String.format(Locale.ROOT, " L %.1f %.1f", x, y));
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            double tMin = timesS.get(i) / 60.0;
            double lower = Math.max(0.0, avgNetWorth.get(i) - stdNetWorth.get(i));
            double x = mapX(tMin, maxXMinutes);
            double y = mapY(lower, yMaxSouls);
            bandD.append(String.format(Locale.ROOT, " L %.1f %.1f", x, y));
        }
        bandD.append(" Z");
        sb.append(String.format(Locale.ROOT, "<path class=\"band\" d=\"%s\" />\n", bandD));

        // Average dashed line
        StringBuilder avgD = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double tMin = timesS.get(i) / 60.0;
            double x = mapX(tMin, maxXMinutes);
            double y = mapY(avgNetWorth.get(i), yMaxSouls);
            if (i == 0) {
                avgD.append(String.format(Locale.ROOT, "M %.1f %.1f", x, y));
            } else {
                avgD.append(String.format(Locale.ROOT, " L %.1f %.1f", x, y));
            }
        }
        sb.append(String.format(Locale.ROOT, "<path class=\"avg-line\" d=\"%s\" />\n", avgD));

        // Player solid thicker line
        StringBuilder playerD = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double tMin = timesS.get(i) / 60.0;
            double x = mapX(tMin, maxXMinutes);
            double y = mapY((double) playerNetWorth.get(i), yMaxSouls);
            if (i == 0) {
                playerD.append(String.format(Locale.ROOT, "M %.1f %.1f", x, y));
            } else {
                playerD.append(String.format(Locale.ROOT, " L %.1f %.1f", x, y));
            }
        }
        sb.append(String.format(Locale.ROOT, "<path class=\"player-line\" d=\"%s\" />\n", playerD));

        // Direct end labels
        double lastTimeMin = timesS.get(n - 1) / 60.0;
        double endX = mapX(lastTimeMin, maxXMinutes);
        double endYPlayer = mapY((double) playerNetWorth.get(n - 1), yMaxSouls);
        double endYAvg = mapY(avgNetWorth.get(n - 1), yMaxSouls);

        double labelYPlayer = endYPlayer;
        double labelYAvg = endYAvg;
        if (Math.abs(labelYPlayer - labelYAvg) < 14.0) {
            if (labelYPlayer <= labelYAvg) {
                labelYPlayer -= 7.0;
                labelYAvg += 7.0;
            } else {
                labelYPlayer += 7.0;
                labelYAvg -= 7.0;
            }
        }

        String labelXAnchor = (endX >= PLOT_RIGHT - 40.0) ? "end" : "start";
        double labelX = (endX >= PLOT_RIGHT - 40.0) ? (endX - 8.0) : (endX + 8.0);

        sb.append(String.format(Locale.ROOT,
                "<text class=\"direct-label-player\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\">%s</text>\n",
                labelX, labelYPlayer + 4.0, labelXAnchor, "You"));
        sb.append(String.format(Locale.ROOT,
                "<text class=\"direct-label-avg\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\">%s</text>\n",
                labelX, labelYAvg + 4.0, labelXAnchor, "Average"));

        // Verdict from last point
        long lastPlayer = playerNetWorth.get(n - 1);
        double lastAvg = avgNetWorth.get(n - 1);
        String verdictText;
        if (lastAvg > 0) {
            double diff = lastPlayer - lastAvg;
            long pct = Math.round((Math.abs(diff) / lastAvg) * 100.0);
            if (diff > 0) {
                verdictText = "above the average by " + pct + "%";
            } else if (diff < 0) {
                verdictText = "below the average by " + pct + "%";
            } else {
                verdictText = "tied with the average";
            }
        } else {
            verdictText = "";
        }
        if (!verdictText.isEmpty()) {
            sb.append(String.format(Locale.ROOT,
                    "<text class=\"verdict-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n",
                    PLOT_RIGHT, PLOT_TOP - 12.0, escapeXml(verdictText)));
        }

        // Caption
        String hero = (heroName != null && !heroName.isBlank()) ? heroName : "Hero";
        String rank = (rankLabel != null && !rankLabel.isBlank()) ? rankLabel : "lobby";
        String captionKey = ownRank ? "report.curve.caption_own_rank" : "report.curve.caption";
        String captionText = escapeXml(messages.get(captionKey, hero, rank));
        sb.append(String.format(Locale.ROOT,
                "<text class=\"caption\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>\n",
                WIDTH / 2.0, 24.0, captionText));

        // Legend
        String legendYou = escapeXml(messages.get("report.curve.legend.you"));
        String legendAvg = escapeXml(messages.get("report.curve.legend.average"));
        String legendBand = escapeXml(messages.get("report.curve.legend.band"));

        sb.append("<g>\n");
        sb.append(String.format(Locale.ROOT,
                "<line class=\"player-line\" x1=\"150.0\" y1=\"341.0\" x2=\"175.0\" y2=\"341.0\" />\n"
                        + "<text class=\"legend-text\" x=\"182.0\" y=\"344.0\">%s</text>\n"
                        + "<line class=\"avg-line\" x1=\"250.0\" y1=\"341.0\" x2=\"275.0\" y2=\"341.0\" />\n"
                        + "<text class=\"legend-text\" x=\"282.0\" y=\"344.0\">%s</text>\n"
                        + "<rect class=\"band\" x=\"370.0\" y=\"336.0\" width=\"20.0\" height=\"10.0\" />\n"
                        + "<text class=\"legend-text\" x=\"397.0\" y=\"344.0\">%s</text>\n",
                legendYou, legendAvg, legendBand));
        sb.append("</g>\n");

        sb.append("</svg>\n");
        return Optional.of(sb.toString());
    }

    private static double mapX(double timeMinutes, double maxXMinutes) {
        double ratio = timeMinutes / maxXMinutes;
        ratio = Math.clamp(ratio, 0.0, 1.0);
        return PLOT_LEFT + ratio * PLOT_WIDTH;
    }

    private static double mapY(double souls, double yMaxSouls) {
        double clamped = Math.max(0.0, souls);
        double ratio = clamped / yMaxSouls;
        ratio = Math.clamp(ratio, 0.0, 1.0);
        return PLOT_BOTTOM - ratio * PLOT_HEIGHT;
    }

    public static String computeSoulCurveAlt(long finalPlayer, double finalAvg, int durationMin) {
        if (finalAvg <= 0) {
            return "Net worth vs lobby average chart";
        }
        double diff = finalPlayer - finalAvg;
        long pct = Math.round((Math.abs(diff) / finalAvg) * 100.0);
        String standing = (diff >= 0) ? ("above the average by " + pct + "%") : ("below the average by " + pct + "%");
        return String.format(Locale.ROOT, "Net worth vs lobby average: finished %s at %d min.", standing, durationMin);
    }

    static String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
