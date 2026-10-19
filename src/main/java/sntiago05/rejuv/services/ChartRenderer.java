package sntiago05.rejuv.services;

import java.awt.AWTError;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.domain.MatchVisuals;

/** Draws the match chart as a PNG with Java2D only (no dependency, data never leaves the bot). */
@Component
public class ChartRenderer {

    static final int WIDTH = 900;
    static final int HEIGHT = 720;

    // Phase boundaries matching DeathsCoachProvider (laning < 12 min, mid-game < 20 min, late game thereafter).
    static final int LANING_PHASE_END_S = 720;
    static final int MID_GAME_END_S = 1200;

    private static final Logger log = LoggerFactory.getLogger(ChartRenderer.class);

    // Must run before the AWT constants below are created (static initializers run in source order).
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final Color BACKGROUND = new Color(0x1E1F22);
    private static final Color GRID = new Color(255, 255, 255, 36);
    private static final Color AXIS = new Color(0x8E9297);
    private static final Color TEXT = new Color(0xE3E5E8);
    private static final Color HINT = new Color(0xB5BAC1);
    private static final Color POSITIVE = new Color(0x4C9BE8);
    private static final Color NEGATIVE = new Color(0xF28C28);
    private static final Color PLAYER = new Color(0xFFC83D);
    private static final Color TEAM = new Color(0x9AA0A6);
    static final Color DEATH_MARKER = new Color(0xED4245);
    private static final Color POSITIVE_FILL = withAlpha(POSITIVE, 90);
    private static final Color NEGATIVE_FILL = withAlpha(NEGATIVE, 90);
    private static final Color PHASE_BAND_1 = new Color(255, 255, 255, 6);
    private static final Color PHASE_BAND_2 = new Color(255, 255, 255, 14);
    private static final Color PHASE_TEXT = new Color(0x72767D);

    // Fonts and strokes are immutable, so one instance serves every render (and every thread).
    private static final Font BASE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font TITLE_FONT = BASE_FONT.deriveFont(Font.BOLD, 15f);
    private static final Font HINT_FONT = BASE_FONT.deriveFont(Font.PLAIN, 12f);
    private static final Font LABEL_FONT = BASE_FONT.deriveFont(Font.BOLD, 12f);
    private static final Font PHASE_FONT = BASE_FONT.deriveFont(Font.BOLD, 12f);
    private static final BasicStroke GRID_STROKE = new BasicStroke(1f);
    private static final BasicStroke ZERO_LINE_STROKE = new BasicStroke(1.4f);
    private static final BasicStroke LEAD_STROKE = new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke SERIES_STROKE = new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    static final int LEFT = 70;
    static final int RIGHT = 110;
    private static final int PANEL_TOP_1 = 58;
    static final int PANEL_TOP_2 = 414;
    private static final int PANEL_H = 224;

    private final Messages messages;

    public ChartRenderer(Messages messages) {
        this.messages = messages;
    }

    /** PNG bytes (900 x 720); empty when the chart is unusable or rendering fails. */
    public Optional<byte[]> render(MatchVisuals.ChartData chart) {
        if (chart == null || chart.timesS() == null || chart.timesS().size() < 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(draw(chart));
        } catch (RuntimeException | AWTError | LinkageError | IOException ex) {
            log.warn("Chart rendering failed: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    private byte[] draw(MatchVisuals.ChartData chart) throws IOException {
        int n = chart.timesS().size();
        if (chart.soulLead().size() != n || chart.playerNetWorth().size() != n
                || (!chart.teammatesAvgNetWorth().isEmpty() && chart.teammatesAvgNetWorth().size() != n)) {
            throw new IllegalArgumentException("Chart series have different lengths");
        }
        // 3-byte BGR skips per-row conversion on write; PNG bytes are identical in structure to TYPE_INT_RGB.
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setFont(BASE_FONT);

            double t0 = chart.timesS().get(0);
            double t1 = chart.timesS().get(n - 1);

            drawTitleAndHint(g, messages.get("analyze.chart.lead_title"), messages.get("analyze.chart.lead_hint"), 28, 46);
            drawLead(g, chart.timesS(), toDoubles(chart.soulLead()), t0, t1);

            drawTitleAndHint(g, messages.get("analyze.chart.networth_title"), messages.get("analyze.chart.networth_hint"), 384, 402);
            drawNetWorth(g, chart, t0, t1);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", stream)) {
                throw new IOException("No PNG writer available");
            }
        }
        return out.toByteArray();
    }

    private void drawTitleAndHint(Graphics2D g, String title, String hint, int titleY, int hintY) {
        g.setColor(TEXT);
        g.setFont(TITLE_FONT);
        g.drawString(title, LEFT, titleY);
        g.setColor(HINT);
        g.setFont(HINT_FONT);
        g.drawString(hint, LEFT, hintY);
        g.setFont(BASE_FONT);
    }

    private void drawPhaseBands(Graphics2D g, int top, double t0, double t1) {
        int right = WIDTH - RIGHT;
        record Phase(double start, double end, String key, Color color) {}
        List<Phase> phases = List.of(
                new Phase(0, LANING_PHASE_END_S, "analyze.chart.phase.laning", PHASE_BAND_1),
                new Phase(LANING_PHASE_END_S, MID_GAME_END_S, "analyze.chart.phase.mid", PHASE_BAND_2),
                new Phase(MID_GAME_END_S, Math.max(t1, MID_GAME_END_S + 1), "analyze.chart.phase.late", PHASE_BAND_1)
        );

        FontMetrics fm = g.getFontMetrics(PHASE_FONT);
        for (Phase phase : phases) {
            double pStart = Math.max(t0, phase.start);
            double pEnd = Math.min(t1, phase.end);
            if (pEnd > pStart) {
                int x1 = (int) Math.round(x(pStart, t0, t1, LEFT, right));
                int x2 = (int) Math.round(x(pEnd, t0, t1, LEFT, right));
                g.setColor(phase.color);
                g.fillRect(x1, top, x2 - x1, PANEL_H);

                String phaseName = messages.get(phase.key);
                int strW = fm.stringWidth(phaseName);
                int bandW = x2 - x1;
                if (bandW >= strW + 8) {
                    g.setFont(PHASE_FONT);
                    g.setColor(PHASE_TEXT);
                    int tx = x1 + (bandW - strW) / 2;
                    g.drawString(phaseName, tx, top + 16);
                }
            }
        }
        g.setFont(BASE_FONT);
    }

    private void drawLead(Graphics2D g, List<Integer> times, double[] lead, double t0, double t1) {
        double max = 0;
        double min = 0;
        for (double v : lead) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        double[] range = padRange(min, max);
        drawPhaseBands(g, PANEL_TOP_1, t0, t1);
        drawFrame(g, PANEL_TOP_1, range[0], range[1], t0, t1);
        int right = WIDTH - RIGHT;
        int zeroY = y(0, range[0], range[1], PANEL_TOP_1);

        // Split segments at zero crossings so each part is filled in its own colour.
        for (int i = 0; i < lead.length - 1; i++) {
            double xa = x(times.get(i), t0, t1, LEFT, right);
            double xb = x(times.get(i + 1), t0, t1, LEFT, right);
            double va = lead[i];
            double vb = lead[i + 1];
            if ((va >= 0) == (vb >= 0)) {
                fillSegment(g, xa, va, xb, vb, range, zeroY);
            } else {
                double xc = xa + (xb - xa) * (va / (va - vb));
                fillSegment(g, xa, va, xc, 0, range, zeroY);
                fillSegment(g, xc, 0, xb, vb, range, zeroY);
            }
        }
        g.setStroke(LEAD_STROKE);
        for (int i = 0; i < lead.length - 1; i++) {
            g.setColor((lead[i] + lead[i + 1]) / 2 >= 0 ? POSITIVE : NEGATIVE);
            g.drawLine((int) Math.round(x(times.get(i), t0, t1, LEFT, right)),
                    y(lead[i], range[0], range[1], PANEL_TOP_1),
                    (int) Math.round(x(times.get(i + 1), t0, t1, LEFT, right)),
                    y(lead[i + 1], range[0], range[1], PANEL_TOP_1));
        }
        g.setStroke(ZERO_LINE_STROKE);
        g.setColor(AXIS);
        g.drawLine(LEFT, zeroY, right, zeroY);

        // Direct labels at zero line edge
        String sideYou = messages.get("analyze.chart.side_you");
        String sideEnemy = messages.get("analyze.chart.side_enemy");
        g.setFont(LABEL_FONT);
        FontMetrics fm = g.getFontMetrics();
        int sideX = right - 6 - Math.max(fm.stringWidth(sideYou), fm.stringWidth(sideEnemy));
        int yAbove = Math.max(PANEL_TOP_1 + 14, zeroY - 6);
        int yBelow = Math.min(PANEL_TOP_1 + PANEL_H - 6, zeroY + 16);
        g.setColor(POSITIVE);
        g.drawString(sideYou, sideX, yAbove);
        g.setColor(NEGATIVE);
        g.drawString(sideEnemy, sideX, yBelow);

        // Final value annotation next to last point
        double finalLead = lead[lead.length - 1];
        String signedK = formatSignedSouls(finalLead);
        String finalMin = formatMinutes(t1 / 60.0);
        String finalLeadStr = messages.get("analyze.chart.final_lead", signedK, finalMin);
        int lastY = y(finalLead, range[0], range[1], PANEL_TOP_1);
        g.setColor(finalLead >= 0 ? POSITIVE : NEGATIVE);
        int clampedLastY = Math.max(PANEL_TOP_1 + 12, Math.min(PANEL_TOP_1 + PANEL_H, lastY + 4));
        g.drawString(finalLeadStr, right + 6, clampedLastY);
        g.setFont(BASE_FONT);
    }

    private void fillSegment(Graphics2D g, double xa, double va, double xb, double vb, double[] range, int zeroY) {
        g.setColor((va + vb) / 2 >= 0 ? POSITIVE_FILL : NEGATIVE_FILL);
        Polygon p = new Polygon();
        p.addPoint((int) Math.round(xa), zeroY);
        p.addPoint((int) Math.round(xa), y(va, range[0], range[1], PANEL_TOP_1));
        p.addPoint((int) Math.round(xb), y(vb, range[0], range[1], PANEL_TOP_1));
        p.addPoint((int) Math.round(xb), zeroY);
        g.fillPolygon(p);
    }

    private void drawNetWorth(Graphics2D g, MatchVisuals.ChartData chart, double t0, double t1) {
        double[] you = toDoubles(chart.playerNetWorth());
        double[] team = toDoubles(chart.teammatesAvgNetWorth());
        double max = 0;
        double min = 0;
        for (double v : you) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        for (double v : team) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        double[] range = padRange(min, max);
        drawPhaseBands(g, PANEL_TOP_2, t0, t1);
        drawFrame(g, PANEL_TOP_2, range[0], range[1], t0, t1);
        if (team.length > 0) {
            drawSeries(g, chart.timesS(), team, TEAM, range, t0, t1);
        }
        drawSeries(g, chart.timesS(), you, PLAYER, range, t0, t1);

        int right = WIDTH - RIGHT;

        // Direct labels at the end of each series line
        String youLabel = messages.get("analyze.chart.legend_you");
        String teamLabel = messages.get("analyze.chart.legend_team");
        int yYou = y(you[you.length - 1], range[0], range[1], PANEL_TOP_2);
        int yTeam = team.length > 0 ? y(team[team.length - 1], range[0], range[1], PANEL_TOP_2) : yYou;
        if (team.length > 0 && Math.abs(yYou - yTeam) < 14) {
            if (yYou <= yTeam) {
                yYou -= 7;
                yTeam += 7;
            } else {
                yYou += 7;
                yTeam -= 7;
            }
        }
        g.setFont(LABEL_FONT);
        g.setColor(PLAYER);
        g.drawString(youLabel, right + 6, Math.max(PANEL_TOP_2 + 12, Math.min(PANEL_TOP_2 + PANEL_H, yYou + 4)));
        if (team.length > 0) {
            g.setColor(TEAM);
            g.drawString(teamLabel, right + 6, Math.max(PANEL_TOP_2 + 12, Math.min(PANEL_TOP_2 + PANEL_H, yTeam + 4)));
        }
        g.setFont(BASE_FONT);

        // Death markers and legend entry
        List<Integer> deaths = chart.playerDeathTimesS();
        if (deaths != null && !deaths.isEmpty()) {
            int lx = LEFT + 12;
            int ly = PANEL_TOP_2 + 20;
            g.setColor(DEATH_MARKER);
            g.fillOval(lx, ly - 7, 9, 9);
            g.setColor(BACKGROUND);
            g.drawOval(lx, ly - 7, 9, 9);
            g.setFont(BASE_FONT);
            g.setColor(TEXT);
            g.drawString(messages.get("analyze.chart.deaths_label"), lx + 15, ly);

            List<Integer> times = chart.timesS();
            for (int deathTimeS : deaths) {
                if (deathTimeS < t0 || deathTimeS > t1) {
                    continue;
                }
                double worth = interpolate(times, you, deathTimeS);
                int dx = (int) Math.round(x(deathTimeS, t0, t1, LEFT, right));
                int dy = y(worth, range[0], range[1], PANEL_TOP_2);

                g.setColor(DEATH_MARKER);
                g.fillOval(dx - 4, dy - 4, 9, 9);
                g.setColor(BACKGROUND);
                g.drawOval(dx - 4, dy - 4, 9, 9);
            }
        }
    }

    static double interpolate(List<Integer> times, double[] values, double targetTime) {
        if (times == null || times.isEmpty() || values == null || values.length == 0) {
            return 0;
        }
        if (targetTime <= times.get(0)) {
            return values[0];
        }
        if (targetTime >= times.get(times.size() - 1)) {
            return values[values.length - 1];
        }
        for (int i = 0; i < times.size() - 1; i++) {
            int tA = times.get(i);
            int tB = times.get(i + 1);
            if (targetTime >= tA && targetTime <= tB) {
                double frac = tB == tA ? 0 : (targetTime - tA) / (double) (tB - tA);
                return values[i] + frac * (values[i + 1] - values[i]);
            }
        }
        return values[values.length - 1];
    }

    private void drawSeries(Graphics2D g, List<Integer> times, double[] values, Color color,
                            double[] range, double t0, double t1) {
        g.setColor(color);
        g.setStroke(SERIES_STROKE);
        int right = WIDTH - RIGHT;
        for (int i = 0; i < values.length - 1; i++) {
            g.drawLine((int) Math.round(x(times.get(i), t0, t1, LEFT, right)),
                    y(values[i], range[0], range[1], PANEL_TOP_2),
                    (int) Math.round(x(times.get(i + 1), t0, t1, LEFT, right)),
                    y(values[i + 1], range[0], range[1], PANEL_TOP_2));
        }
    }

    /** Grid, Y labels in thousands of souls, X labels in minutes. */
    private void drawFrame(Graphics2D g, int top, double minV, double maxV, double t0, double t1) {
        int right = WIDTH - RIGHT;
        FontMetrics fm = g.getFontMetrics();
        g.setStroke(GRID_STROKE);
        for (int i = 0; i <= 4; i++) {
            double v = minV + (maxV - minV) * i / 4.0;
            int yy = y(v, minV, maxV, top);
            g.setColor(GRID);
            g.drawLine(LEFT, yy, right, yy);
            String label = formatSouls(v);
            g.setColor(AXIS);
            g.drawString(label, LEFT - 8 - fm.stringWidth(label), yy + 4);
        }
        double step = niceStep((t1 - t0) / 60.0 / 6.0);
        double startMin = Math.ceil(t0 / 60.0 / step) * step;
        for (double m = startMin; m * 60.0 <= t1 + 0.001; m += step) {
            int xx = (int) Math.round(x(m * 60.0, t0, t1, LEFT, right));
            g.setColor(GRID);
            g.drawLine(xx, top, xx, top + PANEL_H);
            String label = formatMinutes(m);
            g.setColor(AXIS);
            g.drawString(label, xx - fm.stringWidth(label) / 2, top + PANEL_H + 16);
        }
    }

    /** Pads the value range; a flat series gets a symmetric window so the axis is never degenerate or inverted. */
    private static double[] padRange(double min, double max) {
        if (max - min < 1e-9) {
            double pad = Math.max(1000, Math.abs(max) * 0.1);
            return new double[] {min - pad, max + pad};
        }
        double pad = (max - min) * 0.08;
        return new double[] {min - pad, max + pad};
    }

    static double x(double t, double t0, double t1, int left, int right) {
        double span = t1 - t0;
        return span <= 0 ? left : left + (t - t0) / span * (right - left);
    }

    static int y(double v, double minV, double maxV, int top) {
        double span = maxV - minV;
        double frac = span <= 0 ? 0.5 : (v - minV) / span;
        return (int) Math.round(top + PANEL_H - frac * PANEL_H);
    }

    private static double niceStep(double raw) {
        for (double s : new double[] {1, 2, 5, 10, 15, 20, 30}) {
            if (raw <= s) {
                return s;
            }
        }
        return 60;
    }

    static String formatSouls(double v) {
        String s = String.format(Locale.ROOT, "%.1fk", v / 1000.0);
        return "-0.0k".equals(s) ? "0.0k" : s;
    }

    static String formatSignedSouls(double v) {
        String s = formatSouls(v);
        if (v > 0 && !s.startsWith("+") && !"0.0k".equals(s)) {
            return "+" + s;
        }
        return s;
    }

    static String formatMinutes(double m) {
        return m == Math.rint(m) ? String.valueOf((long) m) : String.format(Locale.ROOT, "%.1f", m);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static double[] toDoubles(List<Long> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
