package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import sntiago05.rejuv.domain.MatchVisuals;

class ChartRendererTest {

    private final ChartRenderer renderer = new ChartRenderer(MessagesFixture.english());

    private static MatchVisuals.ChartData sample(int points) {
        List<Integer> t = new ArrayList<>();
        List<Long> lead = new ArrayList<>();
        List<Long> me = new ArrayList<>();
        List<Long> team = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            t.add(i * 180);
            lead.add((long) (Math.sin(i / 2.0) * 9000 + i * 150));
            me.add(i * 1800L);
            team.add(i * 1500L);
        }
        return new MatchVisuals.ChartData(t, lead, me, team);
    }

    @Test
    void validDataReturnsPngOfExpectedSize() throws IOException {
        byte[] png = renderer.render(sample(11)).orElseThrow();

        assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img.getWidth()).isEqualTo(900);
        assertThat(img.getHeight()).isEqualTo(720);
        String samplePath = System.getProperty("rejuv.chart.sample");
        if (samplePath != null) {
            Files.write(Path.of(samplePath), png);
        }
    }

    @Test
    void rendersWithAndWithoutDeaths() {
        MatchVisuals.ChartData c = sample(11);
        MatchVisuals.ChartData withDeaths = new MatchVisuals.ChartData(
                c.timesS(), c.soulLead(), c.playerNetWorth(), c.teammatesAvgNetWorth(), List.of(300, 600));
        MatchVisuals.ChartData withoutDeaths = new MatchVisuals.ChartData(
                c.timesS(), c.soulLead(), c.playerNetWorth(), c.teammatesAvgNetWorth(), List.of());

        assertThat(renderer.render(withDeaths)).isPresent();
        assertThat(renderer.render(withoutDeaths)).isPresent();
    }

    @Test
    void deathMarkersDoNotThrowWithEmptyListOrTimesOutsideSeries() {
        MatchVisuals.ChartData c = sample(11);
        MatchVisuals.ChartData outside = new MatchVisuals.ChartData(
                c.timesS(), c.soulLead(), c.playerNetWorth(), c.teammatesAvgNetWorth(), List.of(-500, 999_999));
        MatchVisuals.ChartData empty = new MatchVisuals.ChartData(
                c.timesS(), c.soulLead(), c.playerNetWorth(), c.teammatesAvgNetWorth(), List.of());

        assertThatCode(() -> renderer.render(outside)).doesNotThrowAnyException();
        assertThat(renderer.render(outside)).isPresent();
        assertThatCode(() -> renderer.render(empty)).doesNotThrowAnyException();
        assertThat(renderer.render(empty)).isPresent();
    }

    @Test
    void phaseBandsRenderForMatchShorterThan720sAndLongerThan1200s() {
        // Shorter than 720 s (e.g. 600 s)
        MatchVisuals.ChartData shortMatch = new MatchVisuals.ChartData(
                List.of(0, 180, 360, 540, 600),
                List.of(0L, 500L, -200L, 1000L, 1200L),
                List.of(0L, 1000L, 2500L, 4000L, 4800L),
                List.of(0L, 900L, 2200L, 3800L, 4500L),
                List.of(360));
        // Longer than 1200 s (e.g. 1800 s)
        MatchVisuals.ChartData longMatch = sample(11); // 10 * 180 = 1800 s

        assertThat(renderer.render(shortMatch)).isPresent();
        assertThat(renderer.render(longMatch)).isPresent();
    }

    @Test
    void deathMarkerColorAppearsAtExpectedXForKnownDeathTime() throws IOException {
        MatchVisuals.ChartData base = sample(11); // times: 0, 180, 360, 540, 720, 900, 1080, 1260, 1440, 1620, 1800
        int knownDeathTime = 540;
        MatchVisuals.ChartData withDeath = new MatchVisuals.ChartData(
                base.timesS(), base.soulLead(), base.playerNetWorth(), base.teammatesAvgNetWorth(), List.of(knownDeathTime));
        MatchVisuals.ChartData withoutDeath = new MatchVisuals.ChartData(
                base.timesS(), base.soulLead(), base.playerNetWorth(), base.teammatesAvgNetWorth(), List.of());

        byte[] pngWith = renderer.render(withDeath).orElseThrow();
        byte[] pngWithout = renderer.render(withoutDeath).orElseThrow();

        BufferedImage imgWith = ImageIO.read(new ByteArrayInputStream(pngWith));
        BufferedImage imgWithout = ImageIO.read(new ByteArrayInputStream(pngWithout));

        int expectedX = (int) Math.round(ChartRenderer.x(
                knownDeathTime, 0, 1800, ChartRenderer.LEFT, ChartRenderer.WIDTH - ChartRenderer.RIGHT));
        int markerRgb = ChartRenderer.DEATH_MARKER.getRGB() & 0xFFFFFF;

        boolean foundInWith = false;
        for (int y = ChartRenderer.PANEL_TOP_2; y <= ChartRenderer.PANEL_TOP_2 + 224; y++) {
            if ((imgWith.getRGB(expectedX, y) & 0xFFFFFF) == markerRgb) {
                foundInWith = true;
                break;
            }
        }

        boolean foundInWithout = false;
        for (int y = ChartRenderer.PANEL_TOP_2; y <= ChartRenderer.PANEL_TOP_2 + 224; y++) {
            if ((imgWithout.getRGB(expectedX, y) & 0xFFFFFF) == markerRgb) {
                foundInWithout = true;
                break;
            }
        }

        assertThat(foundInWith).as("Death marker color 0xED4245 should appear at x=%d", expectedX).isTrue();
        assertThat(foundInWithout).as("Death marker color should not appear without deaths").isFalse();
    }

    @Test
    void noTeammatesStillRenders() {
        MatchVisuals.ChartData c = sample(5);
        MatchVisuals.ChartData noTeam = new MatchVisuals.ChartData(c.timesS(), c.soulLead(), c.playerNetWorth(), List.of());

        assertThat(renderer.render(noTeam)).isPresent();
    }

    @Test
    void nullChartAndSinglePointReturnEmpty() {
        assertThatCode(() -> renderer.render(null)).doesNotThrowAnyException();
        assertThat(renderer.render(null)).isEmpty();
        assertThat(renderer.render(sample(1))).isEmpty();
    }

    @Test
    void allZeroSeriesStillRendersAnImage() {
        MatchVisuals.ChartData zero = new MatchVisuals.ChartData(
                List.of(0, 180, 360), List.of(0L, 0L, 0L), List.of(0L, 0L, 0L), List.of(0L, 0L, 0L));

        assertThat(renderer.render(zero)).isPresent();
    }

    @Test
    void largeValuesDoNotBreakRendering() {
        MatchVisuals.ChartData big = new MatchVisuals.ChartData(
                List.of(0, 180), List.of(-900_000_000L, 900_000_000L), List.of(0L, 1_000_000_000L), List.of(0L, 5L));

        assertThat(renderer.render(big)).isPresent();
    }

    @Test
    void mismatchedLengthsReturnEmptyWithoutThrowing() {
        MatchVisuals.ChartData bad = new MatchVisuals.ChartData(
                List.of(0, 180, 360), List.of(0L, 1L), List.of(0L, 1L, 2L), List.of(0L, 1L, 2L));

        assertThat(renderer.render(bad)).isEmpty();
    }
}
