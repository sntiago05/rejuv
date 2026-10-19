package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import sntiago05.rejuv.domain.ReportView;

class ReportChartsSvgTest {

    private ReportChartsSvg svgRenderer;

    @BeforeEach
    void setUp() {
        svgRenderer = new ReportChartsSvg(MessagesFixture.english());
    }

    private Document parseXml(String svg) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }

    @Test
    void soulLeadReturnsWellFormedSvg() throws Exception {
        List<Integer> times = List.of(0, 180, 360, 540, 720, 1200, 1500);
        List<Long> lead = List.of(0L, 500L, -1200L, 2500L, 4100L, 3200L, -1500L);
        List<Integer> deaths = List.of(360, 1250);

        Optional<String> result = svgRenderer.renderSoulLead(times, lead, deaths);

        assertThat(result).isPresent();
        String svg = result.get();
        Document doc = parseXml(svg);

        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(doc.getDocumentElement().getAttribute("role")).isEqualTo("img");
        assertThat(svg).contains("Biggest swing:");
        assertThat(svg).contains("death-marker");
        assertThat(svg).contains("zero-line");
    }

    @Test
    void soulLeadHandlesEmptyOrInsufficientData() {
        assertThat(svgRenderer.renderSoulLead(null, null, null)).isEmpty();
        assertThat(svgRenderer.renderSoulLead(List.of(180), List.of(500L), null)).isEmpty();
        assertThat(svgRenderer.renderSoulLead(List.of(180, 360), List.of(500L), null)).isEmpty();
    }

    @Test
    void phasesReturnsWellFormedSvg() throws Exception {
        List<ReportChartsSvg.PhaseScore> phases = List.of(
                new ReportChartsSvg.PhaseScore("laning", -0.8),
                new ReportChartsSvg.PhaseScore("mid", 1.4),
                new ReportChartsSvg.PhaseScore("late", 0.0)
        );

        Optional<String> result = svgRenderer.renderPhases(phases);

        assertThat(result).isPresent();
        String svg = result.get();
        Document doc = parseXml(svg);

        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(svg).contains("Better than average");
        assertThat(svg).contains("Worse than average");
        assertThat(svg).contains("Average");
    }

    @Test
    void phasesHandlesEmptyReturnsEmpty() {
        assertThat(svgRenderer.renderPhases(null)).isEmpty();
        assertThat(svgRenderer.renderPhases(List.of())).isEmpty();
    }

    @Test
    void deathsReturnsWellFormedSvg() throws Exception {
        Optional<String> result = svgRenderer.renderDeaths(1, 3, 2, 6, 48);

        assertThat(result).isPresent();
        String svg = result.get();
        Document doc = parseXml(svg);

        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(svg).contains("Total: 6 deaths");
        assertThat(svg).contains("Longest respawn: 48s");
        assertThat(svg).contains("1 death");
        assertThat(svg).contains("3 deaths");
        assertThat(svg).contains("2 deaths");
    }

    @Test
    void metricsReturnsWellFormedSvg() throws Exception {
        List<ReportView.MetricRow> metrics = List.of(
                new ReportView.MetricRow("kills", "analyze.metric.kills", "12", "5", "8", "above p50", "w70"),
                new ReportView.MetricRow("deaths", "analyze.metric.deaths", "7", "3", "5", "below p25", "w80"),
                new ReportView.MetricRow("net_worth_per_min", "analyze.metric.net_worth_per_min", "620", "500", "600", "between p25 and p50", "w50")
        );

        Optional<String> result = svgRenderer.renderMetrics(metrics);

        assertThat(result).isPresent();
        String svg = result.get();
        Document doc = parseXml(svg);

        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(svg).contains("p25");
        assertThat(svg).contains("p50 (Median)");
        assertThat(svg).contains("above p50");
        assertThat(svg).contains("below p25");
    }

    @Test
    void metricsHandlesEmptyReturnsEmpty() {
        assertThat(svgRenderer.renderMetrics(null)).isEmpty();
        assertThat(svgRenderer.renderMetrics(List.of())).isEmpty();
    }

    @Test
    void allChartsContainNoScriptsAndHaveLightDarkThemeRules() {
        List<Integer> times = List.of(0, 180, 360, 540);
        List<Long> lead = List.of(0L, 500L, 1000L, 800L);
        List<ReportChartsSvg.PhaseScore> phases = List.of(new ReportChartsSvg.PhaseScore("laning", 1.0));
        List<ReportView.MetricRow> metrics = List.of(
                new ReportView.MetricRow("kills", "analyze.metric.kills", "10", "5", "8", "above p50", "w60"));

        List<String> renderedSvgs = List.of(
                svgRenderer.renderSoulLead(times, lead, List.of()).orElseThrow(),
                svgRenderer.renderPhases(phases).orElseThrow(),
                svgRenderer.renderDeaths(1, 2, 1, 4, 30).orElseThrow(),
                svgRenderer.renderMetrics(metrics).orElseThrow()
        );

        for (String svg : renderedSvgs) {
            String lower = svg.toLowerCase();
            assertThat(lower).doesNotContain("<script");
            assertThat(lower).doesNotContain("foreignobject");
            assertThat(lower).doesNotContain("javascript:");
            assertThat(svg).contains("@media (prefers-color-scheme: light)");
            assertThat(svg).contains(".bg { fill: #1e1f22; }");
            assertThat(svg).contains(".bg { fill: #ffffff; }");
        }
    }

    @Test
    void specialCharactersInMetricLabelsAreEscaped() throws Exception {
        List<ReportView.MetricRow> metrics = List.of(
                new ReportView.MetricRow("exploit", "key <tag> & 'quote' \"test\"", "10", "5", "8", "above p50", "w50"));

        String svg = svgRenderer.renderMetrics(metrics).orElseThrow();
        parseXml(svg);

        assertThat(svg).contains("&lt;tag&gt;");
        assertThat(svg).contains("&amp;");
        assertThat(svg).contains("&apos;quote&apos;");
        assertThat(svg).contains("&quot;test&quot;");
        assertThat(svg).doesNotContain("<tag>");
    }

    @Test
    void altTextHelpersComputeClearTakeaways() {
        List<Integer> times = List.of(0, 600, 1200, 1800);
        List<Long> lead = List.of(0L, 2000L, -3000L, 4000L);

        String leadAlt = ReportChartsSvg.computeSoulLeadAlt(times, lead);
        assertThat(leadAlt).contains("peak +4.0k at 30 min");
        assertThat(leadAlt).contains("biggest swing");

        List<ReportChartsSvg.PhaseScore> phases = List.of(
                new ReportChartsSvg.PhaseScore("laning", 1.2),
                new ReportChartsSvg.PhaseScore("mid", -0.5)
        );
        String phasesAlt = ReportChartsSvg.computePhasesAlt(phases);
        assertThat(phasesAlt).contains("laning +1.2 z");
        assertThat(phasesAlt).contains("mid -0.5 z");

        String deathsAlt = ReportChartsSvg.computeDeathsAlt(2, 3, 1, 6, 45);
        assertThat(deathsAlt).isEqualTo("Deaths breakdown: 2 in laning, 3 in mid game, 1 in late game (total 6). Longest respawn 45s.");

        List<ReportView.MetricRow> metrics = List.of(
                new ReportView.MetricRow("m1", "m1", "10", "5", "8", "above p50", "w50"),
                new ReportView.MetricRow("m2", "m2", "4", "5", "8", "below p25", "w30"),
                new ReportView.MetricRow("m3", "m3", "6", "5", "8", "between p25 and p50", "w40")
        );
        String metricsAlt = ReportChartsSvg.computeMetricsAlt(metrics);
        assertThat(metricsAlt).contains("1 above average, 1 average, 1 below average across 3 metrics.");
    }

    @Test
    void cpuGenerationGenerousBoundUnder200Ms() {
        int points = 40;
        List<Integer> times = new ArrayList<>(points);
        List<Long> lead = new ArrayList<>(points);
        List<Integer> deaths = List.of(360, 720, 1200, 1600);
        for (int i = 0; i < points; i++) {
            times.add(i * 60);
            lead.add((long) ((i % 3 == 0 ? 1 : -1) * (i * 150)));
        }
        List<ReportChartsSvg.PhaseScore> phases = List.of(
                new ReportChartsSvg.PhaseScore("laning", -0.5),
                new ReportChartsSvg.PhaseScore("mid", 1.5),
                new ReportChartsSvg.PhaseScore("late", 0.2));
        List<ReportView.MetricRow> metrics = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            metrics.add(new ReportView.MetricRow("m" + i, "label" + i, "100", "50", "80", "above p50", "w60"));
        }

        long start = System.nanoTime();
        for (int iteration = 0; iteration < 5; iteration++) {
            assertThat(svgRenderer.renderSoulLead(times, lead, deaths)).isPresent();
            assertThat(svgRenderer.renderPhases(phases)).isPresent();
            assertThat(svgRenderer.renderDeaths(2, 4, 3, 9, 60)).isPresent();
            assertThat(svgRenderer.renderMetrics(metrics)).isPresent();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Generous bound: 5 iterations of all 4 charts must comfortably finish under 200 ms
        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void saveSampleSvgsToTargetForReviewer() throws Exception {
        java.nio.file.Path targetDir = java.nio.file.Path.of("target/samples");
        java.nio.file.Files.createDirectories(targetDir);

        List<Integer> times = List.of(0, 180, 360, 540, 720, 1200, 1500);
        List<Long> lead = List.of(0L, 500L, -1200L, 2500L, 4100L, 3200L, -1500L);
        List<Integer> deaths = List.of(360, 1250);
        String soulLead = svgRenderer.renderSoulLead(times, lead, deaths).orElseThrow();
        java.nio.file.Files.writeString(targetDir.resolve("soul-lead.svg"), soulLead);

        List<ReportChartsSvg.PhaseScore> phases = List.of(
                new ReportChartsSvg.PhaseScore("laning", -0.8),
                new ReportChartsSvg.PhaseScore("mid", 1.4),
                new ReportChartsSvg.PhaseScore("late", 0.0)
        );
        String phasesSvg = svgRenderer.renderPhases(phases).orElseThrow();
        java.nio.file.Files.writeString(targetDir.resolve("phases.svg"), phasesSvg);

        String deathsSvg = svgRenderer.renderDeaths(1, 3, 2, 6, 48).orElseThrow();
        java.nio.file.Files.writeString(targetDir.resolve("deaths.svg"), deathsSvg);

        List<ReportView.MetricRow> metrics = List.of(
                new ReportView.MetricRow("kills", "analyze.metric.kills", "12", "5", "8", "above p50", "w70"),
                new ReportView.MetricRow("deaths", "analyze.metric.deaths", "7", "3", "5", "below p25", "w80"),
                new ReportView.MetricRow("net_worth_per_min", "analyze.metric.net_worth_per_min", "620", "500", "600", "between p25 and p50", "w50")
        );
        String metricsSvg = svgRenderer.renderMetrics(metrics).orElseThrow();
        java.nio.file.Files.writeString(targetDir.resolve("metrics.svg"), metricsSvg);

        SoulCurveSvg soulCurveRenderer = new SoulCurveSvg(MessagesFixture.english());
        String soulCurve = soulCurveRenderer.render("Infernus", "Archon", true,
                List.of(180, 360, 540, 720),
                List.of(1500L, 3200L, 5100L, 7500L),
                List.of(1400.0, 3000.0, 5000.0, 7200.0),
                List.of(200.0, 400.0, 600.0, 800.0)).orElseThrow();
        java.nio.file.Files.writeString(targetDir.resolve("soul-curve.svg"), soulCurve);

        assertThat(java.nio.file.Files.exists(targetDir.resolve("soul-lead.svg"))).isTrue();
        assertThat(java.nio.file.Files.exists(targetDir.resolve("phases.svg"))).isTrue();
        assertThat(java.nio.file.Files.exists(targetDir.resolve("deaths.svg"))).isTrue();
        assertThat(java.nio.file.Files.exists(targetDir.resolve("metrics.svg"))).isTrue();
        assertThat(java.nio.file.Files.exists(targetDir.resolve("soul-curve.svg"))).isTrue();
    }
}
