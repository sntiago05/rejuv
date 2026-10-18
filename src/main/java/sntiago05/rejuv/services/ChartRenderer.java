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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.domain.MatchVisuals;

/** Draws the match chart as a PNG with Java2D only (no dependency, data never leaves the bot). */
@Component
public class ChartRenderer {

    static final int WIDTH = 900;
    static final int HEIGHT = 640;

    private static final Logger log = LoggerFactory.getLogger(ChartRenderer.class);

    private static final Color BACKGROUND = new Color(0x1E1F22);
    private static final Color GRID = new Color(255, 255, 255, 36);
    private static final Color AXIS = new Color(0x8E9297);
    private static final Color TEXT = new Color(0xE3E5E8);
    private static final Color POSITIVE = new Color(0x4C9BE8);
    private static final Color NEGATIVE = new Color(0xF28C28);
    private static final Color PLAYER = new Color(0xFFC83D);
    private static final Color TEAM = new Color(0x9AA0A6);

    private static final int LEFT = 70;
    private static final int RIGHT = 24;
    private static final int PANEL_TOP_1 = 44;
    private static final int PANEL_TOP_2 = 340;
    private static final int PANEL_H = 218;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final Messages messages;

    public ChartRenderer(Messages messages) {
        this.messages = messages;
    }

    /** PNG bytes (900 x 640); empty when the chart is unusable or rendering fails. */
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
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

            double t0 = chart.timesS().get(0);
            double t1 = chart.timesS().get(n - 1);

            drawTitle(g, messages.get("analyze.chart.lead_title"), PANEL_TOP_1);
            drawLead(g, chart.timesS(), toDoubles(chart.soulLead()), t0, t1);

            drawTitle(g, messages.get("analyze.chart.networth_title"), PANEL_TOP_2);
            drawNetWorth(g, chart, t0, t1);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG writer available");
        }
        return out.toByteArray();
    }

    private void drawTitle(Graphics2D g, String title, int panelTop) {
        Font previous = g.getFont();
        g.setColor(TEXT);
        g.setFont(previous.deriveFont(Font.BOLD, 15f));
        g.drawString(title, LEFT, panelTop - 14);
        g.setFont(previous);
    }

    private void drawLead(Graphics2D g, List<Integer> times, double[] lead, double t0, double t1) {
        double max = 0;
        double min = 0;
        for (double v : lead) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        double[] range = padRange(min, max);
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
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < lead.length - 1; i++) {
            g.setColor((lead[i] + lead[i + 1]) / 2 >= 0 ? POSITIVE : NEGATIVE);
            g.drawLine((int) Math.round(x(times.get(i), t0, t1, LEFT, right)),
                    y(lead[i], range[0], range[1], PANEL_TOP_1),
                    (int) Math.round(x(times.get(i + 1), t0, t1, LEFT, right)),
                    y(lead[i + 1], range[0], range[1], PANEL_TOP_1));
        }
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(AXIS);
        g.drawLine(LEFT, zeroY, right, zeroY);
    }

    private void fillSegment(Graphics2D g, double xa, double va, double xb, double vb, double[] range, int zeroY) {
        Color base = (va + vb) / 2 >= 0 ? POSITIVE : NEGATIVE;
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 90));
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
        drawFrame(g, PANEL_TOP_2, range[0], range[1], t0, t1);
        if (team.length > 0) {
            drawSeries(g, chart.timesS(), team, TEAM, range, t0, t1);
        }
        drawSeries(g, chart.timesS(), you, PLAYER, range, t0, t1);

        FontMetrics fm = g.getFontMetrics();
        String youLabel = messages.get("analyze.chart.legend_you");
        int lx = LEFT + 12;
        int ly = PANEL_TOP_2 + 18;
        g.setStroke(new BasicStroke(3f));
        g.setColor(PLAYER);
        g.drawLine(lx, ly - 4, lx + 22, ly - 4);
        g.setColor(TEXT);
        g.drawString(youLabel, lx + 28, ly);
        if (team.length > 0) {
            int tx = lx + 28 + fm.stringWidth(youLabel) + 24;
            g.setColor(TEAM);
            g.drawLine(tx, ly - 4, tx + 22, ly - 4);
            g.setColor(TEXT);
            g.drawString(messages.get("analyze.chart.legend_team"), tx + 28, ly);
        }
    }

    private void drawSeries(Graphics2D g, List<Integer> times, double[] values, Color color,
                            double[] range, double t0, double t1) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < values.length - 1; i++) {
            g.drawLine((int) Math.round(x(times.get(i), t0, t1, LEFT, WIDTH - RIGHT)),
                    y(values[i], range[0], range[1], PANEL_TOP_2),
                    (int) Math.round(x(times.get(i + 1), t0, t1, LEFT, WIDTH - RIGHT)),
                    y(values[i + 1], range[0], range[1], PANEL_TOP_2));
        }
    }

    /** Grid, Y labels in thousands of souls, X labels in minutes. */
    private void drawFrame(Graphics2D g, int top, double minV, double maxV, double t0, double t1) {
        int right = WIDTH - RIGHT;
        FontMetrics fm = g.getFontMetrics();
        g.setStroke(new BasicStroke(1f));
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

    private static double x(double t, double t0, double t1, int left, int right) {
        double span = t1 - t0;
        return span <= 0 ? left : left + (t - t0) / span * (right - left);
    }

    private static int y(double v, double minV, double maxV, int top) {
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

    private static String formatMinutes(double m) {
        return m == Math.rint(m) ? String.valueOf((long) m) : String.format(Locale.ROOT, "%.1f", m);
    }

    private static double[] toDoubles(List<Long> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
