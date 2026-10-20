package sntiago05.rejuv.services;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.ReportView;

/**
 * Pure server-side SVG renderer for descriptive web report charts.
 * Produces valid, script-free, deterministic XML with dark and light theme support
 * without any external library or JavaScript.
 */
@Component
public class ReportChartsSvg {

    // Shared theme tokens (matching src/main/tailwind/report.css @theme)
    public static final String COLOR_YOU_GOLD = "#e5a93c";
    public static final String COLOR_YOU_GOLD_LIGHT = "#d97706";
    public static final String COLOR_REF_GREY = "#9aa0a6";
    public static final String COLOR_REF_GREY_LIGHT = "#6b7280";
    public static final String COLOR_SIDE_BLUE = "#4c9be8";
    public static final String COLOR_SIDE_BLUE_LIGHT = "#2563eb";
    public static final String COLOR_SIDE_ORANGE = "#f28c28";
    public static final String COLOR_SIDE_ORANGE_LIGHT = "#ea580c";
    public static final String COLOR_BETTER_GREEN = "#57f287";
    public static final String COLOR_BETTER_GREEN_LIGHT = "#16a34a";
    public static final String COLOR_WORSE_RED = "#ed4245";
    public static final String COLOR_WORSE_RED_LIGHT = "#dc2626";

    public static final String COLOR_BG_DARK = "#1e1f22";
    public static final String COLOR_BG_LIGHT = "#ffffff";
    public static final String COLOR_GRID_DARK = "#2b2d31";
    public static final String COLOR_GRID_LIGHT = "#e5e7eb";
    public static final String COLOR_TEXT_DARK = "#dbdee1";
    public static final String COLOR_TEXT_LIGHT = "#111827";
    public static final String COLOR_MUTED_DARK = "#949ba4";
    public static final String COLOR_MUTED_LIGHT = "#6b7280";

    // Phase boundary constants matching DeathsCoachProvider / ChartRenderer
    public static final int LANING_PHASE_END_S = 720;
    public static final int MID_GAME_END_S = 1200;

    private static final int DEFAULT_WIDTH = 480;
    private static final int DEFAULT_HEIGHT = 320;
    /** Text size in viewBox units: at 480 wide the chart is drawn at 1x to 1.4x on screens, so this is 12 to 17 px. */
    private static final int TEXT_PX = 17;
    private static final int SOUL_LEAD_HEIGHT = 348;
    /** A phase name is only drawn when its band is at least this wide (units), so it never runs past the plot. */
    private static final double MIN_PHASE_LABEL_BAND = 92.0;

    private final Messages messages;

    public ReportChartsSvg(Messages messages) {
        this.messages = messages;
    }

    public record PhaseScore(String name, double change) {}

    // -------------------------------------------------------------------------
    // 1. Soul lead over time
    // -------------------------------------------------------------------------
    public Optional<String> renderSoulLead(List<Integer> timesS, List<Long> soulLead, List<Integer> deathTimesS) {
        if (timesS == null || soulLead == null) {
            return Optional.empty();
        }
        int n = timesS.size();
        if (n < 2 || soulLead.size() != n) {
            return Optional.empty();
        }

        double maxTimeMin = timesS.get(n - 1) / 60.0;
        if (maxTimeMin <= 0) {
            return Optional.empty();
        }
        double tickStepX = timeTickStepMinutes(maxTimeMin);
        double maxXMinutes = Math.max(tickStepX, Math.ceil(maxTimeMin / tickStepX) * tickStepX);

        long maxAbsLead = 0;
        for (long val : soulLead) {
            long abs = Math.abs(val);
            if (abs > maxAbsLead) {
                maxAbsLead = abs;
            }
        }
        if (maxAbsLead <= 0) {
            maxAbsLead = 1000;
        }

        double maxK = maxAbsLead / 1000.0;
        double tickStepY;
        if (maxK <= 4.0) {
            tickStepY = 1.0;
        } else if (maxK <= 10.0) {
            tickStepY = 2.5;
        } else if (maxK <= 25.0) {
            tickStepY = 5.0;
        } else {
            tickStepY = 10.0;
        }
        double maxYK = Math.ceil(maxK / tickStepY) * tickStepY;
        if (maxYK < maxK) {
            maxYK += tickStepY;
        }
        double yMaxSouls = maxYK * 1000.0;

        double plotLeft = 58.0;
        double plotRight = 450.0;
        double plotTop = 62.0;
        double plotBottom = 268.0;
        double plotW = plotRight - plotLeft;
        double plotH = plotBottom - plotTop;
        double centerY = plotTop + plotH / 2.0;

        SvgBuilder builder = new SvgBuilder(DEFAULT_WIDTH, SOUL_LEAD_HEIGHT,
                messages.get("report.chart.soul_lead.title"),
                messages.get("report.chart.soul_lead.title"));

        // Phase background bands (laning < 12m, mid < 20m, late >= 20m)
        double x12 = plotLeft + Math.clamp(12.0 / maxXMinutes, 0.0, 1.0) * plotW;
        double x20 = plotLeft + Math.clamp(20.0 / maxXMinutes, 0.0, 1.0) * plotW;

        builder.append(String.format(Locale.ROOT,
                "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" class=\"phase-band-alt\" />\n",
                plotLeft, plotTop, x12 - plotLeft, plotH));
        if (x20 > x12) {
            builder.append(String.format(Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" class=\"phase-band\" />\n",
                    x12, plotTop, x20 - x12, plotH));
        }
        if (plotRight > x20) {
            builder.append(String.format(Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" class=\"phase-band-alt\" />\n",
                    x20, plotTop, plotRight - x20, plotH));
        }

        // Phase labels (only where the band is wide enough for the text)
        double laningEnd = Math.min(x12, plotRight);
        if (laningEnd - plotLeft >= MIN_PHASE_LABEL_BAND) {
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"phase-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>\n",
                    (plotLeft + laningEnd) / 2.0, plotTop + 20.0, messages.get("analyze.chart.phase.laning")));
        }
        double midEnd = Math.min(x20, plotRight);
        if (x12 < plotRight && midEnd - x12 >= MIN_PHASE_LABEL_BAND) {
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"phase-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>\n",
                    (x12 + midEnd) / 2.0, plotTop + 20.0, messages.get("analyze.chart.phase.mid")));
        }
        if (x20 < plotRight && plotRight - x20 >= MIN_PHASE_LABEL_BAND) {
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"phase-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>\n",
                    (x20 + plotRight) / 2.0, plotTop + 20.0, messages.get("analyze.chart.phase.late")));
        }

        // Y grid lines and ticks
        for (double k = -maxYK; k <= maxYK + 0.001; k += tickStepY) {
            double ratio = (k + maxYK) / (2.0 * maxYK);
            double y = plotBottom - ratio * plotH;
            builder.append(String.format(Locale.ROOT,
                    "<line class=\"grid\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                    plotLeft, y, plotRight, y));
            String label = (k == (long) k)
                    ? String.format(Locale.ROOT, "%+dk", (long) k)
                    : String.format(Locale.ROOT, "%+.1fk", k);
            if (k == 0.0) {
                label = "0";
            }
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"axis-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n",
                    plotLeft - 8.0, y + 6.0, label));
        }

        // X grid lines and ticks
        for (double m = 0.0; m <= maxXMinutes + 0.001; m += tickStepX) {
            double x = plotLeft + (m / maxXMinutes) * plotW;
            builder.append(String.format(Locale.ROOT,
                    "<line class=\"grid\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                    x, plotTop, x, plotBottom));
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"axis-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%dm</text>\n",
                    x, plotBottom + 22.0, (long) m));
        }

        // Zero line with side indicator texts
        builder.append(String.format(Locale.ROOT,
                "<line class=\"zero-line\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                plotLeft, centerY, plotRight, centerY));
        // Which side is which: a legend row under the axis, so the labels never sit on the line
        builder.append(String.format(Locale.ROOT,
                "<text class=\"side-you\" x=\"12.0\" y=\"%.1f\">&#9650; %s</text>\n",
                SOUL_LEAD_HEIGHT - 10.0, messages.get("analyze.chart.side_you")));
        builder.append(String.format(Locale.ROOT,
                "<text class=\"side-enemy\" x=\"250.0\" y=\"%.1f\">&#9660; %s</text>\n",
                SOUL_LEAD_HEIGHT - 10.0, messages.get("analyze.chart.side_enemy")));

        // Map data points
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            double tMin = timesS.get(i) / 60.0;
            xs[i] = plotLeft + Math.clamp(tMin / maxXMinutes, 0.0, 1.0) * plotW;
            double souls = soulLead.get(i);
            double ratio = (souls + yMaxSouls) / (2.0 * yMaxSouls);
            ys[i] = plotBottom - Math.clamp(ratio, 0.0, 1.0) * plotH;
        }

        // Shaded areas above / below zero
        for (int i = 0; i < n - 1; i++) {
            double xA = xs[i];
            double yA = ys[i];
            double xB = xs[i + 1];
            double yB = ys[i + 1];
            long leadA = soulLead.get(i);
            long leadB = soulLead.get(i + 1);

            if ((leadA >= 0 && leadB >= 0) || (leadA <= 0 && leadB <= 0)) {
                String fillClass = (leadA + leadB >= 0) ? "lead-fill-you" : "lead-fill-enemy";
                builder.append(String.format(Locale.ROOT,
                        "<polygon class=\"%s\" points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f\" />\n",
                        fillClass, xA, centerY, xA, yA, xB, yB, xB, centerY));
            } else {
                // Segment crosses zero line: split at intersection
                double frac = (double) (-leadA) / (double) (leadB - leadA);
                double xMid = xA + frac * (xB - xA);
                String fillA = (leadA >= 0) ? "lead-fill-you" : "lead-fill-enemy";
                String fillB = (leadB >= 0) ? "lead-fill-you" : "lead-fill-enemy";
                builder.append(String.format(Locale.ROOT,
                        "<polygon class=\"%s\" points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f\" />\n",
                        fillA, xA, centerY, xA, yA, xMid, centerY));
                builder.append(String.format(Locale.ROOT,
                        "<polygon class=\"%s\" points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f\" />\n",
                        fillB, xMid, centerY, xB, yB, xB, centerY));
            }
        }

        // Soul lead line
        StringBuilder leadD = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                leadD.append(String.format(Locale.ROOT, "M %.1f %.1f", xs[i], ys[i]));
            } else {
                leadD.append(String.format(Locale.ROOT, " L %.1f %.1f", xs[i], ys[i]));
            }
        }
        builder.append(String.format(Locale.ROOT, "<path class=\"lead-line\" d=\"%s\" />\n", leadD));

        // Find largest swing
        long biggestSwing = 0;
        int swingStartMin = 0;
        int swingEndMin = 0;
        for (int i = 0; i < n - 1; i++) {
            long delta = soulLead.get(i + 1) - soulLead.get(i);
            if (Math.abs(delta) > Math.abs(biggestSwing)) {
                biggestSwing = delta;
                swingStartMin = timesS.get(i) / 60;
                swingEndMin = timesS.get(i + 1) / 60;
            }
        }

        // Annotate largest swing
        String swingAmount = String.format(Locale.ROOT, "%+.1f", biggestSwing / 1000.0);
        String swingLabel = messages.get("report.chart.soul_lead.swing",
                swingAmount, String.valueOf(swingStartMin), String.valueOf(swingEndMin));
        builder.append(String.format(Locale.ROOT,
                "<text class=\"annotation-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"start\">%s</text>\n",
                12.0, 44.0, escapeXml(swingLabel)));

        // Death markers if provided
        if (deathTimesS != null && !deathTimesS.isEmpty()) {
            for (int deathTime : deathTimesS) {
                if (deathTime < timesS.get(0) || deathTime > timesS.get(n - 1)) {
                    continue;
                }
                // Linear interpolate position
                double tMin = deathTime / 60.0;
                double xDeath = plotLeft + Math.clamp(tMin / maxXMinutes, 0.0, 1.0) * plotW;
                // Find enclosing interval
                double yDeath = centerY;
                for (int i = 0; i < n - 1; i++) {
                    int t0 = timesS.get(i);
                    int t1 = timesS.get(i + 1);
                    if (deathTime >= t0 && deathTime <= t1 && t1 > t0) {
                        double frac = (double) (deathTime - t0) / (double) (t1 - t0);
                        yDeath = ys[i] + frac * (ys[i + 1] - ys[i]);
                        break;
                    }
                }
                builder.append(String.format(Locale.ROOT,
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"6\" class=\"death-marker\" />\n",
                        xDeath, yDeath));
            }
        }

        // Axis title
        builder.append(String.format(Locale.ROOT,
                "<text class=\"axis-title\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"start\">%s</text>\n",
                12.0, 22.0, escapeXml(messages.get("report.curve.axis.souls"))));

        builder.append(String.format(Locale.ROOT,
                "<text class=\"axis-title\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>\n",
                plotLeft + plotW / 2.0, plotBottom + 46.0, escapeXml(messages.get("report.curve.axis.minutes"))));

        return Optional.of(builder.build());
    }

    /** Minutes between time-axis ticks: never more than 6 ticks, so the labels do not touch at 17 units wide. */
    static double timeTickStepMinutes(double maxMinutes) {
        if (maxMinutes <= 12.0) {
            return 2.0;
        }
        if (maxMinutes <= 30.0) {
            return 5.0;
        }
        return maxMinutes <= 60.0 ? 10.0 : 15.0;
    }

    public String computeSoulLeadAlt(List<Integer> timesS, List<Long> soulLead) {
        if (timesS == null || soulLead == null || timesS.size() < 2 || soulLead.size() != timesS.size()) {
            return messages.get("report.chart.soul_lead.alt_empty");
        }
        int n = timesS.size();
        long peakLead = soulLead.get(0);
        int peakMin = timesS.get(0) / 60;
        for (int i = 1; i < n; i++) {
            if (soulLead.get(i) > peakLead) {
                peakLead = soulLead.get(i);
                peakMin = timesS.get(i) / 60;
            }
        }

        long biggestSwing = 0;
        int swingStart = 0;
        int swingEnd = 0;
        for (int i = 0; i < n - 1; i++) {
            long delta = soulLead.get(i + 1) - soulLead.get(i);
            if (Math.abs(delta) > Math.abs(biggestSwing)) {
                biggestSwing = delta;
                swingStart = timesS.get(i) / 60;
                swingEnd = timesS.get(i + 1) / 60;
            }
        }
        String peakAmount = String.format(Locale.ROOT, "%+.1f", peakLead / 1000.0);
        String swingAmount = String.format(Locale.ROOT, "%+.1f", biggestSwing / 1000.0);
        return messages.get("report.chart.soul_lead.alt",
                peakAmount, String.valueOf(peakMin), swingAmount, String.valueOf(swingStart), String.valueOf(swingEnd));
    }

    // -------------------------------------------------------------------------
    // 2. Phase scorecard
    // -------------------------------------------------------------------------
    public Optional<String> renderPhases(List<PhaseScore> phases) {
        if (phases == null || phases.isEmpty()) {
            return Optional.empty();
        }

        int width = DEFAULT_WIDTH;
        double left = 16.0;
        double plotW = width - 2 * left;
        double centerX = left + plotW / 2.0;
        double headY = 26.0;
        double plotTop = 50.0;
        double rowHeight = 66.0;
        int height = (int) (plotTop + phases.size() * rowHeight + 10.0);

        double maxAbsZ = 1.0;
        for (PhaseScore p : phases) {
            double abs = Math.abs(p.change());
            if (abs > maxAbsZ) {
                maxAbsZ = abs;
            }
        }
        double scaleZ = Math.max(2.0, Math.ceil(maxAbsZ * 1.2));

        SvgBuilder builder = new SvgBuilder(width, height,
                messages.get("report.chart.phases.title"),
                messages.get("report.chart.phases.title"));

        builder.append(String.format(Locale.ROOT,
                "<line class=\"zero-line\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                centerX, plotTop - 6.0, centerX, plotTop + phases.size() * rowHeight));
        builder.append(String.format(Locale.ROOT,
                "<text class=\"phase-head-worse\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"end\">&#8592; %s</text>\n",
                centerX - 8.0, headY, escapeXml(messages.get("report.chart.phases.worse"))));
        builder.append(String.format(Locale.ROOT,
                "<text class=\"phase-head-better\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"start\">%s &#8594;</text>\n",
                centerX + 8.0, headY, escapeXml(messages.get("report.chart.phases.better"))));

        for (int i = 0; i < phases.size(); i++) {
            PhaseScore phase = phases.get(i);
            double y = plotTop + i * rowHeight;
            String phaseLabel = switch (phase.name().toLowerCase(Locale.ROOT)) {
                case "laning" -> messages.get("analyze.chart.phase.laning");
                case "mid" -> messages.get("analyze.chart.phase.mid");
                case "late" -> messages.get("analyze.chart.phase.late");
                default -> capitalize(phase.name());
            };
            double z = phase.change();
            String verdictWord = (z >= 0.1)
                    ? messages.get("report.chart.phases.better")
                    : (z <= -0.1)
                    ? messages.get("report.chart.phases.worse")
                    : messages.get("report.chart.phases.average");
            String verdictText = messages.get("report.chart.phases.verdict",
                    String.format(Locale.ROOT, "%+.1f", z), verdictWord);

            // Name and plain-words verdict on one line above the bar, so nothing competes for space beside it
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"bar-row-label\" x=\"%.1f\" y=\"%.1f\">%s<tspan class=\"bar-value-text\" dx=\"10\">%s</tspan></text>\n",
                    left, y + 18.0, escapeXml(phaseLabel), escapeXml(verdictText)));

            double barW = Math.abs(z) / scaleZ * (plotW / 2.0);
            barW = Math.max(3.0, Math.min(barW, plotW / 2.0));
            double barX = (z >= 0) ? centerX : (centerX - barW);
            String barClass = (z >= 0) ? "bar-better" : "bar-worse";
            builder.append(String.format(Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"3\" class=\"%s\" />\n",
                    barX, y + 28.0, barW, 24.0, barClass));
        }

        return Optional.of(builder.build());
    }

    public String computePhasesAlt(List<PhaseScore> phases) {
        if (phases == null || phases.isEmpty()) {
            return messages.get("report.chart.phases.alt_empty");
        }
        StringBuilder sb = new StringBuilder(messages.get("report.chart.phases.alt_prefix"));
        for (int i = 0; i < phases.size(); i++) {
            PhaseScore p = phases.get(i);
            if (i > 0) sb.append(", ");
            String phaseLabel = switch (p.name().toLowerCase(Locale.ROOT)) {
                case "laning" -> messages.get("analyze.chart.phase.laning");
                case "mid" -> messages.get("analyze.chart.phase.mid");
                case "late" -> messages.get("analyze.chart.phase.late");
                default -> capitalize(p.name());
            };
            String verdictWord = (p.change() >= 0.1)
                    ? messages.get("report.chart.phases.better")
                    : (p.change() <= -0.1)
                    ? messages.get("report.chart.phases.worse")
                    : messages.get("report.chart.phases.average");
            String formattedZ = String.format(Locale.ROOT, "%+.1f", p.change());
            sb.append(phaseLabel).append(" ").append(messages.get("report.chart.phases.verdict", formattedZ, verdictWord));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // 3. Deaths timeline / breakdown
    // -------------------------------------------------------------------------
    public Optional<String> renderDeaths(int laning, int mid, int late, int total, int longestRespawnS) {
        int width = DEFAULT_WIDTH;
        double left = 16.0;
        double plotW = width - 2 * left;
        double headY = 26.0;
        double plotTop = 50.0;
        double rowHeight = 62.0;
        int height = (int) (plotTop + 3 * rowHeight + 6.0);

        int maxDeaths = Math.max(1, Math.max(laning, Math.max(mid, late)));

        SvgBuilder builder = new SvgBuilder(width, height,
                messages.get("report.chart.deaths.title"),
                messages.get("report.chart.deaths.title"));

        String summary = messages.get("report.chart.deaths.summary",
                String.valueOf(total), String.valueOf(longestRespawnS));
        builder.append(String.format(Locale.ROOT,
                "<text class=\"annotation-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"start\">%s</text>\n",
                left, headY, escapeXml(summary)));

        String[] phaseNames = {
                messages.get("analyze.chart.phase.laning"),
                messages.get("analyze.chart.phase.mid"),
                messages.get("analyze.chart.phase.late")
        };
        int[] counts = {laning, mid, late};

        for (int i = 0; i < 3; i++) {
            double y = plotTop + i * rowHeight;
            String countText = (counts[i] == 1)
                    ? messages.get("report.chart.deaths.count_one")
                    : messages.get("report.chart.deaths.count_many", String.valueOf(counts[i]));
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"bar-row-label\" x=\"%.1f\" y=\"%.1f\">%s<tspan class=\"bar-value-text\" dx=\"10\">%s</tspan></text>\n",
                    left, y + 18.0, escapeXml(phaseNames[i]), escapeXml(countText)));

            double barW = (counts[i] == 0) ? 0.0 : Math.max(8.0, ((double) counts[i] / (double) maxDeaths) * plotW);
            if (barW > 0) {
                builder.append(String.format(Locale.ROOT,
                        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"3\" class=\"bar-death\" />\n",
                        left, y + 28.0, barW, 22.0));
            }
        }

        return Optional.of(builder.build());
    }

    public String computeDeathsAlt(int laning, int mid, int late, int total, int longestRespawnS) {
        return messages.get("report.chart.deaths.alt",
                String.valueOf(laning), String.valueOf(mid), String.valueOf(late),
                String.valueOf(total), String.valueOf(longestRespawnS));
    }

    // -------------------------------------------------------------------------
    // 4. Metrics vs peers
    // -------------------------------------------------------------------------
    public Optional<String> renderMetrics(List<ReportView.MetricRow> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return Optional.empty();
        }

        int n = metrics.size();
        double rowH = 58.0;
        int width = DEFAULT_WIDTH;
        double plotLeft = 16.0;
        double plotRight = width - 16.0;
        double plotW = plotRight - plotLeft;
        double headY = 26.0;
        double plotTop = 44.0;
        int height = (int) (plotTop + n * rowH + 8.0);

        SvgBuilder builder = new SvgBuilder(width, height,
                messages.get("report.chart.metrics.title"),
                messages.get("report.chart.metrics.title"));

        builder.append(String.format(Locale.ROOT,
                "<text class=\"annotation-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">p25</text>\n",
                plotLeft + 0.25 * plotW, headY));
        builder.append(String.format(Locale.ROOT,
                "<text class=\"annotation-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">p50 (Median)</text>\n",
                plotLeft + 0.50 * plotW, headY));
        builder.append(String.format(Locale.ROOT,
                "<line class=\"guide-dashed\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                plotLeft + 0.25 * plotW, headY + 6.0, plotLeft + 0.25 * plotW, plotTop + n * rowH));
        builder.append(String.format(Locale.ROOT,
                "<line class=\"guide-dashed\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                plotLeft + 0.50 * plotW, headY + 6.0, plotLeft + 0.50 * plotW, plotTop + n * rowH));

        for (int i = 0; i < n; i++) {
            ReportView.MetricRow m = metrics.get(i);
            double y = plotTop + i * rowH;
            String label = resolveLabel(m.labelKey(), m.key());
            String verdictText = messages.get("report.chart.metrics.value_with_verdict",
                    m.yourValue(), m.verdict());

            builder.append(String.format(Locale.ROOT,
                    "<text class=\"metric-row-label\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"start\">%s</text>\n",
                    plotLeft, y + 18.0, escapeXml(label)));
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"metric-val-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n",
                    plotRight, y + 18.0, escapeXml(verdictText)));

            builder.append(String.format(Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"4\" class=\"metric-track\" />\n",
                    plotLeft, y + 28.0, plotW, 10.0));
            builder.append(String.format(Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"2\" class=\"metric-range-band\" />\n",
                    plotLeft + 0.25 * plotW, y + 28.0, 0.25 * plotW, 10.0));

            double dotRatio = computeDotRatio(m);
            double dotX = plotLeft + dotRatio * plotW;
            String dotClass = switch (m.verdict()) {
                case "above p50" -> "dot-better";
                case "below p25" -> "dot-worse";
                default -> "dot-neutral";
            };
            builder.append(String.format(Locale.ROOT,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"8\" class=\"%s\" />\n",
                    dotX, y + 33.0, dotClass));
        }

        return Optional.of(builder.build());
    }

    private static double computeDotRatio(ReportView.MetricRow m) {
        try {
            double v = Double.parseDouble(m.yourValue());
            double p25 = Double.parseDouble(m.p25());
            double p50 = Double.parseDouble(m.p50());
            if (p50 <= 0) return 0.5;

            boolean lowerBetter = ReportService.isLowerBetterMetric(m.key());
            if (lowerBetter) {
                // Inverted standing: lower values are better
                if (v <= p25) {
                    double frac = (p25 <= 0) ? 1.0 : (1.0 - (v / p25));
                    return Math.clamp(0.50 + 0.35 * Math.clamp(frac, 0.0, 1.0), 0.05, 0.95);
                } else if (v <= p50) {
                    double frac = (p50 == p25) ? 0.5 : (v - p25) / (p50 - p25);
                    return Math.clamp(0.50 - 0.25 * frac, 0.25, 0.50);
                } else {
                    double frac = (p50 == 0) ? 1.0 : (v - p50) / p50;
                    return Math.clamp(0.25 - 0.20 * Math.clamp(frac, 0.0, 1.0), 0.05, 0.25);
                }
            } else {
                if (v <= p25) {
                    double frac = (p25 == 0) ? 0.0 : v / p25;
                    return Math.clamp(0.25 * Math.clamp(frac, 0.0, 1.0), 0.05, 0.25);
                } else if (v <= p50) {
                    double frac = (p50 == p25) ? 0.5 : (v - p25) / (p50 - p25);
                    return Math.clamp(0.25 + 0.25 * frac, 0.25, 0.50);
                } else {
                    double frac = (p50 == 0) ? 1.0 : (v - p50) / p50;
                    return Math.clamp(0.50 + 0.35 * Math.clamp(frac, 0.0, 1.0), 0.50, 0.95);
                }
            }
        } catch (Exception ex) {
            return 0.50;
        }
    }

    public String computeMetricsAlt(List<ReportView.MetricRow> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return messages.get("report.chart.metrics.alt_empty");
        }
        int above = 0;
        int below = 0;
        int between = 0;
        for (ReportView.MetricRow m : metrics) {
            switch (m.verdict()) {
                case "above p50" -> above++;
                case "below p25" -> below++;
                default -> between++;
            }
        }
        return messages.get("report.chart.metrics.alt",
                String.valueOf(above), String.valueOf(between), String.valueOf(below), String.valueOf(metrics.size()));
    }

    // -------------------------------------------------------------------------
    // 5. Build timeline
    // -------------------------------------------------------------------------
    public Optional<String> renderBuildTimeline(List<MatchVisuals.PurchasedItem> items) {
        if (items == null || items.size() < 3) {
            return Optional.empty();
        }

        // Cap at 12 items, earliest first
        List<MatchVisuals.PurchasedItem> timeline = items.stream()
                .filter(i -> i.boughtAtS() >= 0)
                .sorted(Comparator.comparingInt(MatchVisuals.PurchasedItem::boughtAtS))
                .limit(12)
                .toList();

        if (timeline.size() < 3) {
            return Optional.empty();
        }

        // Vertical list: item names read left to right at any width and never overlap
        int width = DEFAULT_WIDTH;
        double rowH = 32.0;
        double top = 24.0;
        double axisX = 78.0;
        int height = (int) (top + timeline.size() * rowH + 16.0);

        SvgBuilder builder = new SvgBuilder(width, height,
                messages.get("report.chart.build.title"),
                messages.get("report.chart.build.title"));

        double firstY = top + rowH / 2.0;
        double lastY = top + (timeline.size() - 1) * rowH + rowH / 2.0;
        builder.append(String.format(Locale.ROOT,
                "<line class=\"zero-line\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                axisX, firstY, axisX, lastY));

        for (int i = 0; i < timeline.size(); i++) {
            MatchVisuals.PurchasedItem item = timeline.get(i);
            double y = top + i * rowH + rowH / 2.0;
            int minute = item.boughtAtS() / 60;

            builder.append(String.format(Locale.ROOT,
                    "<text class=\"axis-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"end\">%dm</text>\n",
                    axisX - 14.0, y + 6.0, minute));
            builder.append(String.format(Locale.ROOT,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"6\" class=\"dot-neutral\" />\n", axisX, y));
            builder.append(String.format(Locale.ROOT,
                    "<text class=\"bar-value-text\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"start\">%s</text>\n",
                    axisX + 16.0, y + 6.0, escapeXml(item.name())));
        }

        return Optional.of(builder.build());
    }

    public String computeBuildAlt(List<MatchVisuals.PurchasedItem> items) {
        if (items == null || items.size() < 3) {
            return messages.get("report.chart.build.alt_empty");
        }
        List<MatchVisuals.PurchasedItem> timeline = items.stream()
                .filter(i -> i.boughtAtS() >= 0)
                .sorted(Comparator.comparingInt(MatchVisuals.PurchasedItem::boughtAtS))
                .limit(12)
                .toList();
        if (timeline.size() < 3) {
            return messages.get("report.chart.build.alt_empty");
        }
        int firstMin = timeline.get(0).boughtAtS() / 60;
        int lastMin = timeline.get(timeline.size() - 1).boughtAtS() / 60;
        return messages.get("report.chart.build.alt",
                String.valueOf(timeline.size()), String.valueOf(firstMin), String.valueOf(lastMin));
    }

    // -------------------------------------------------------------------------
    // Shared SVG builder (package-private for reuse across SVG chart generators)
    // -------------------------------------------------------------------------
    static class SvgBuilder {
        private final StringBuilder sb = new StringBuilder(4096);

        SvgBuilder(int width, int height, String title, String ariaLabel) {
            sb.append(String.format(Locale.ROOT,
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" role=\"img\" aria-label=\"%s\">\n",
                    width, height, escapeXml(ariaLabel)));
            sb.append("<title>").append(escapeXml(title)).append("</title>\n");

            sb.append("<style>\n");
            sb.append(".bg { fill: ").append(COLOR_BG_DARK).append("; }\n");
            sb.append(".grid { stroke: ").append(COLOR_GRID_DARK).append("; stroke-width: 1.5; }\n");
            sb.append(".zero-line { stroke: #6b6f78; stroke-width: 2; }\n");
            sb.append(".guide-dashed { stroke: #4e5058; stroke-width: 1; stroke-dasharray: 4 4; }\n");
            sb.append(".axis-text { fill: ").append(COLOR_MUTED_DARK).append("; font-size: 17px; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".axis-title { fill: ").append(COLOR_MUTED_DARK).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".annotation-text { fill: ").append(COLOR_TEXT_DARK).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".phase-band { fill: rgba(255, 255, 255, 0.02); stroke: none; }\n");
            sb.append(".phase-band-alt { fill: rgba(255, 255, 255, 0.05); stroke: none; }\n");
            sb.append(".phase-text { fill: #72767d; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".side-you { fill: ").append(COLOR_SIDE_BLUE).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".side-enemy { fill: ").append(COLOR_SIDE_ORANGE).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".lead-line { stroke: ").append(COLOR_YOU_GOLD).append("; stroke-width: 3; fill: none; stroke-linecap: round; stroke-linejoin: round; }\n");
            sb.append(".lead-fill-you { fill: ").append(COLOR_SIDE_BLUE).append("; fill-opacity: 0.18; stroke: none; }\n");
            sb.append(".lead-fill-enemy { fill: ").append(COLOR_SIDE_ORANGE).append("; fill-opacity: 0.18; stroke: none; }\n");
            sb.append(".death-marker { fill: ").append(COLOR_WORSE_RED).append("; stroke: #1e1f22; stroke-width: 1.5; }\n");
            sb.append(".bar-better { fill: ").append(COLOR_BETTER_GREEN).append("; }\n");
            sb.append(".bar-worse { fill: ").append(COLOR_WORSE_RED).append("; }\n");
            sb.append(".bar-death { fill: ").append(COLOR_WORSE_RED).append("; fill-opacity: 0.85; }\n");
            sb.append(".phase-head-worse { fill: ").append(COLOR_WORSE_RED).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".phase-head-better { fill: ").append(COLOR_BETTER_GREEN).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".bar-row-label { fill: ").append(COLOR_TEXT_DARK).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".bar-value-text { fill: ").append(COLOR_TEXT_DARK).append("; font-size: 17px; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".metric-row-label { fill: ").append(COLOR_TEXT_DARK).append("; font-size: 17px; font-weight: 500; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".metric-track { fill: #2b2d31; }\n");
            sb.append(".metric-range-band { fill: #4e5058; }\n");
            sb.append(".dot-better { fill: ").append(COLOR_BETTER_GREEN).append("; stroke: #1e1f22; stroke-width: 1.5; }\n");
            sb.append(".dot-neutral { fill: ").append(COLOR_YOU_GOLD).append("; stroke: #1e1f22; stroke-width: 1.5; }\n");
            sb.append(".dot-worse { fill: ").append(COLOR_WORSE_RED).append("; stroke: #1e1f22; stroke-width: 1.5; }\n");
            sb.append(".metric-val-text { fill: ").append(COLOR_MUTED_DARK).append("; font-size: 17px; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".band { fill: ").append(COLOR_SIDE_BLUE).append("; fill-opacity: 0.18; stroke: none; }\n");
            sb.append(".avg-line { stroke: ").append(COLOR_SIDE_BLUE).append("; stroke-width: 2.5; stroke-dasharray: 6 4; fill: none; }\n");
            sb.append(".player-line { stroke: ").append(COLOR_YOU_GOLD).append("; stroke-width: 3.5; fill: none; stroke-linecap: round; stroke-linejoin: round; }\n");
            sb.append(".direct-label-player { fill: ").append(COLOR_YOU_GOLD).append("; font-size: 17px; font-weight: 700; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".direct-label-avg { fill: ").append(COLOR_SIDE_BLUE).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".verdict-text { fill: ").append(COLOR_TEXT_DARK).append("; font-size: 17px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".legend-text { fill: ").append(COLOR_TEXT_DARK).append("; font-size: 17px; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");
            sb.append(".caption { fill: ").append(COLOR_TEXT_DARK).append("; font-size: 18px; font-weight: 600; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n");

            sb.append("@media (prefers-color-scheme: light) {\n");
            sb.append("  .bg { fill: ").append(COLOR_BG_LIGHT).append("; }\n");
            sb.append("  .grid { stroke: ").append(COLOR_GRID_LIGHT).append("; }\n");
            sb.append("  .zero-line { stroke: #9ca3af; }\n");
            sb.append("  .guide-dashed { stroke: #9ca3af; }\n");
            sb.append("  .axis-text { fill: ").append(COLOR_MUTED_LIGHT).append("; }\n");
            sb.append("  .axis-title { fill: ").append(COLOR_MUTED_LIGHT).append("; }\n");
            sb.append("  .annotation-text { fill: ").append(COLOR_TEXT_LIGHT).append("; }\n");
            sb.append("  .phase-band { fill: rgba(0, 0, 0, 0.02); }\n");
            sb.append("  .phase-band-alt { fill: rgba(0, 0, 0, 0.04); }\n");
            sb.append("  .phase-text { fill: #6b7280; }\n");
            sb.append("  .side-you { fill: ").append(COLOR_SIDE_BLUE_LIGHT).append("; }\n");
            sb.append("  .side-enemy { fill: ").append(COLOR_SIDE_ORANGE_LIGHT).append("; }\n");
            sb.append("  .lead-line { stroke: ").append(COLOR_YOU_GOLD_LIGHT).append("; }\n");
            sb.append("  .lead-fill-you { fill: ").append(COLOR_SIDE_BLUE_LIGHT).append("; fill-opacity: 0.15; }\n");
            sb.append("  .lead-fill-enemy { fill: ").append(COLOR_SIDE_ORANGE_LIGHT).append("; fill-opacity: 0.15; }\n");
            sb.append("  .death-marker { fill: ").append(COLOR_WORSE_RED_LIGHT).append("; stroke: #ffffff; }\n");
            sb.append("  .bar-better { fill: ").append(COLOR_BETTER_GREEN_LIGHT).append("; }\n");
            sb.append("  .bar-worse { fill: ").append(COLOR_WORSE_RED_LIGHT).append("; }\n");
            sb.append("  .bar-death { fill: ").append(COLOR_WORSE_RED_LIGHT).append("; }\n");
            sb.append("  .phase-head-worse { fill: ").append(COLOR_WORSE_RED_LIGHT).append("; }\n");
            sb.append("  .phase-head-better { fill: ").append(COLOR_BETTER_GREEN_LIGHT).append("; }\n");
            sb.append("  .bar-row-label { fill: ").append(COLOR_TEXT_LIGHT).append("; }\n");
            sb.append("  .bar-value-text { fill: ").append(COLOR_TEXT_LIGHT).append("; }\n");
            sb.append("  .metric-row-label { fill: ").append(COLOR_TEXT_LIGHT).append("; }\n");
            sb.append("  .metric-track { fill: #e5e7eb; }\n");
            sb.append("  .metric-range-band { fill: #9ca3af; }\n");
            sb.append("  .dot-better { fill: ").append(COLOR_BETTER_GREEN_LIGHT).append("; stroke: #ffffff; }\n");
            sb.append("  .dot-neutral { fill: ").append(COLOR_YOU_GOLD_LIGHT).append("; stroke: #ffffff; }\n");
            sb.append("  .dot-worse { fill: ").append(COLOR_WORSE_RED_LIGHT).append("; stroke: #ffffff; }\n");
            sb.append("  .metric-val-text { fill: ").append(COLOR_MUTED_LIGHT).append("; }\n");
            sb.append("  .band { fill: ").append(COLOR_SIDE_BLUE_LIGHT).append("; fill-opacity: 0.15; }\n");
            sb.append("  .avg-line { stroke: ").append(COLOR_SIDE_BLUE_LIGHT).append("; }\n");
            sb.append("  .player-line { stroke: ").append(COLOR_YOU_GOLD_LIGHT).append("; }\n");
            sb.append("  .direct-label-player { fill: ").append(COLOR_YOU_GOLD_LIGHT).append("; }\n");
            sb.append("  .direct-label-avg { fill: ").append(COLOR_SIDE_BLUE_LIGHT).append("; }\n");
            sb.append("  .verdict-text { fill: ").append(COLOR_TEXT_LIGHT).append("; }\n");
            sb.append("  .legend-text { fill: #374151; }\n");
            sb.append("  .caption { fill: ").append(COLOR_TEXT_LIGHT).append("; }\n");
            sb.append("}\n");
            sb.append("</style>\n");

            sb.append("<rect width=\"100%\" height=\"100%\" class=\"bg\" />\n");
        }

        void append(String svgFragment) {
            sb.append(svgFragment);
        }

        String build() {
            sb.append("</svg>\n");
            return sb.toString();
        }
    }

    public static String escapeXml(String text) {
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

    private String resolveLabel(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback != null ? fallback : "";
        }
        try {
            return messages.get(key);
        } catch (Exception e) {
            return key;
        }
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
