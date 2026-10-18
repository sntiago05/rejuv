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
        assertThat(img.getHeight()).isEqualTo(640);
        String samplePath = System.getProperty("rejuv.chart.sample");
        if (samplePath != null) {
            Files.write(Path.of(samplePath), png);
        }
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
