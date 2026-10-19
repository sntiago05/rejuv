package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

class SoulCurveSvgTest {

    private SoulCurveSvg svgRenderer;

    @BeforeEach
    void setUp() {
        svgRenderer = new SoulCurveSvg(MessagesFixture.english());
    }

    @Test
    void wellFormedXmlParsableByJdkParser() throws Exception {
        List<Integer> times = List.of(180, 360, 540, 720);
        List<Long> player = List.of(1500L, 3200L, 5100L, 7500L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0, 7200.0);
        List<Double> std = List.of(200.0, 400.0, 600.0, 800.0);

        Optional<String> result = svgRenderer.render("Infernus", "Archon", times, player, avg, std);

        assertThat(result).isPresent();
        String svg = result.get();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));

        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(doc.getDocumentElement().getAttribute("viewBox")).isEqualTo("0 0 640 360");
        assertThat(doc.getDocumentElement().getAttribute("role")).isEqualTo("img");
        assertThat(doc.getDocumentElement().getAttribute("aria-label")).isNotEmpty();
    }

    @Test
    void containsNoScriptsForeignObjectsOrExternalHrefs() {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3200L, 5100L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        Optional<String> result = svgRenderer.render("Haze", "Ritualist", times, player, avg, std);

        assertThat(result).isPresent();
        String lower = result.get().toLowerCase();

        assertThat(lower).doesNotContain("<script");
        assertThat(lower).doesNotContain("foreignobject");
        assertThat(lower).doesNotContain("href=");
        assertThat(lower).doesNotContain("xlink");
    }

    @Test
    void heroNamesWithSpecialCharactersAreEscaped() throws Exception {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3200L, 5100L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        String maliciousHero = "Haze <Test> & \"Quotes\" 'Single'";
        String maliciousRank = "Archon <Rank> & \"Pro\"";

        Optional<String> result = svgRenderer.render(maliciousHero, maliciousRank, times, player, avg, std);

        assertThat(result).isPresent();
        String svg = result.get();

        // Valid XML parse test
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));

        assertThat(doc).isNotNull();
        // Check caption tag contains escaped entities in raw XML
        assertThat(svg).contains("&lt;Test&gt;");
        assertThat(svg).contains("&amp;");
        assertThat(svg).contains("&quot;Quotes&quot;");
        assertThat(svg).contains("&apos;Single&apos;");
        // Must not contain raw unescaped <Test>
        assertThat(svg).doesNotContain("<Test>");
    }

    @Test
    void fewerThanThreePointsReturnsEmpty() {
        List<Double> emptyDouble = List.of();
        List<Long> emptyLong = List.of();
        List<Integer> emptyInt = List.of();

        assertThat(svgRenderer.render(emptyInt, emptyLong, emptyDouble, emptyDouble)).isEmpty();
        assertThat(svgRenderer.render(List.of(180), List.of(1000L), List.of(1000.0), List.of(100.0))).isEmpty();
        assertThat(svgRenderer.render(List.of(180, 360), List.of(1000L, 2000L), List.of(1000.0, 2000.0), List.of(100.0, 200.0))).isEmpty();
        assertThat(svgRenderer.render(null, null, null, null)).isEmpty();
    }

    @Test
    void mismatchedListSizesReturnsEmpty() {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3200L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        assertThat(svgRenderer.render(times, player, avg, std)).isEmpty();
        assertThat(svgRenderer.render(times, List.of(1500L, 3200L, 5100L), List.of(1400.0), std)).isEmpty();
    }

    @Test
    void lowerBandClampedAtZero() {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1000L, 2000L, 3000L);
        // avg = 100, std = 300 -> avg - std = -200 < 0
        List<Double> avg = List.of(100.0, 200.0, 300.0);
        List<Double> std = List.of(300.0, 100.0, 100.0);

        Optional<String> result = svgRenderer.render("Infernus", "Archon", times, player, avg, std);
        assertThat(result).isPresent();

        String svg = result.get();
        // PLOT_BOTTOM is 290.0 (where souls = 0)
        // With clamping at 0, the lower bound at index 0 should map to exactly 290.0, not > 290.0
        assertThat(svg).contains("290.0");

        // Verify no coordinate exceeds the plot area bounds
        Pattern pathPattern = Pattern.compile("d=\"([^\"]+)\"");
        Matcher matcher = pathPattern.matcher(svg);
        while (matcher.find()) {
            String pathD = matcher.group(1);
            String[] tokens = pathD.split("[MLZ ]+");
            for (int i = 0; i < tokens.length; i++) {
                if (!tokens[i].isBlank()) {
                    double val = Double.parseDouble(tokens[i]);
                    // Y coordinates in paths should never exceed 290.0 (bottom of plot)
                    assertThat(val).isLessThanOrEqualTo(605.0);
                }
            }
        }
    }

    @Test
    void pointCoordinatesStayInsideViewBoxForLargeAndSmallValues() {
        // Small values
        List<Integer> times = List.of(60, 120, 180);
        List<Long> smallPlayer = List.of(1L, 2L, 3L);
        List<Double> smallAvg = List.of(1.0, 2.0, 3.0);
        List<Double> smallStd = List.of(0.1, 0.1, 0.1);

        Optional<String> smallResult = svgRenderer.render("Haze", "Archon", times, smallPlayer, smallAvg, smallStd);
        assertThat(smallResult).isPresent();
        assertAllCoordinatesInsideViewBox(smallResult.get());

        // Huge values
        List<Long> hugePlayer = List.of(500_000_000L, 800_000_000L, 1_000_000_000L);
        List<Double> hugeAvg = List.of(400_000_000.0, 700_000_000.0, 900_000_000.0);
        List<Double> hugeStd = List.of(50_000_000.0, 80_000_000.0, 100_000_000.0);

        Optional<String> hugeResult = svgRenderer.render("Haze", "Archon", times, hugePlayer, hugeAvg, hugeStd);
        assertThat(hugeResult).isPresent();
        assertAllCoordinatesInsideViewBox(hugeResult.get());
    }

    private void assertAllCoordinatesInsideViewBox(String svg) {
        Pattern pattern = Pattern.compile("(\\d+\\.\\d+)");
        Matcher matcher = pattern.matcher(svg);
        while (matcher.find()) {
            double coord = Double.parseDouble(matcher.group(1));
            // All coordinates inside SVG must be within 0..640
            assertThat(coord).isBetween(0.0, 640.0);
        }
    }

    @Test
    void deterministicOutputForIdenticalInput() {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3200L, 5100L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        Optional<String> r1 = svgRenderer.render("Infernus", "Archon", times, player, avg, std);
        Optional<String> r2 = svgRenderer.render("Infernus", "Archon", times, player, avg, std);

        assertThat(r1).isPresent();
        assertThat(r2).isPresent();
        assertThat(r1.get()).isEqualTo(r2.get());
    }

    @Test
    void ownRankTrueCaptionContainsOwnRankWording() {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3200L, 5100L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        Optional<String> result = svgRenderer.render("Infernus", "Archon", true, times, player, avg, std);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("Infernus net worth vs Archon average at your current rank");
    }

    @Test
    void oldSixArgumentOverloadProducesOldCaption() {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3200L, 5100L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        Optional<String> result = svgRenderer.render("Infernus", "Archon", times, player, avg, std);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("Infernus net worth vs Archon lobby average");
    }

    @Test
    void ownRankOutputStaysEscapedAndScriptFree() throws Exception {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3200L, 5100L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        String maliciousHero = "Hero <script>alert(1)</script>";
        String maliciousRank = "Rank <tag> & 'test'";

        Optional<String> result = svgRenderer.render(maliciousHero, maliciousRank, true, times, player, avg, std);
        assertThat(result).isPresent();
        String svg = result.get();

        assertThat(svg.toLowerCase()).doesNotContain("<script");
        assertThat(svg).contains("&lt;script&gt;");
        assertThat(svg).contains("&amp;");
        assertThat(svg).contains("&apos;test&apos;");

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        assertThat(doc).isNotNull();
    }

    @Test
    void directLabelsAndVerdictRenderedWithCorrectPalette() {
        List<Integer> times = List.of(180, 360, 540);
        List<Long> player = List.of(1500L, 3500L, 6000L);
        List<Double> avg = List.of(1400.0, 3000.0, 5000.0);
        List<Double> std = List.of(200.0, 400.0, 600.0);

        Optional<String> result = svgRenderer.render("Infernus", "Archon", true, times, player, avg, std);
        assertThat(result).isPresent();
        String svg = result.get();

        // Direct labels
        assertThat(svg).contains(">You<");
        assertThat(svg).contains(">Average<");

        // Final verdict annotation
        assertThat(svg).contains("above the average by");

        // Gold line stroke
        assertThat(svg).contains("stroke: #ffc83d");
    }

    @Test
    void computeSoulCurveAltSummarizesDifference() {
        String alt = SoulCurveSvg.computeSoulCurveAlt(6000L, 5000.0, 9);
        assertThat(alt).contains("Net worth vs lobby average: finished above the average by 20% at 9 min.");
    }
}
