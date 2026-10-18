package sntiago05.rejuv;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.DeathEvent;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.PowerUpBuff;
import sntiago05.rejuv.domain.SnapshotDetail;
import sntiago05.rejuv.domain.StatSnapshot;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.services.ChartRenderer;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.SoulCurveSvg;

/**
 * Task 039 CPU and memory measurements. Disabled by default; run with
 * {@code ./mvnw test -Dtest=CpuHotPathBenchmarkTest -Dbenchmark=true}. Prints medians; asserts only correctness.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class CpuHotPathBenchmarkTest {

    private static final int WARMUP = 30;
    private static final int RUNS = 60;
    // A full match has snapshots at 180..2100 s every 180-300 s plus the final one: 10 to 13 points.
    private static final int CHART_POINTS = 12;

    @Test
    void chartRenderFullPath() {
        ChartRenderer renderer = new ChartRenderer(MessagesFixture.english());
        MatchVisuals.ChartData chart = chartSample(CHART_POINTS);
        byte[] png = renderer.render(chart).orElseThrow();
        System.out.printf("[benchmark] chart render (%d points): median %.3f ms, %d bytes, sha256 %s%n",
                CHART_POINTS, medianMillis(() -> renderer.render(chart)), png.length, sha256(png));
    }

    @Test
    void pngEncodingDiskCacheVersusMemoryStream() {
        BufferedImage image = new BufferedImage(900, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x1E1F22));
        g.fillRect(0, 0, 900, 640);
        g.setColor(new Color(0x4C9BE8));
        for (int i = 0; i < 40; i++) {
            g.drawLine(0, i * 16, 900, 640 - i * 16);
        }
        g.dispose();

        // Previous encoding: ImageIO picks a FileCacheImageOutputStream (temp file) when its disk cache is enabled.
        Supplier<byte[]> before = () -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        Supplier<byte[]> after = () -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
                try (ImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                    ImageIO.write(image, "png", stream);
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        assertThat(after.get()).isEqualTo(before.get());
        System.out.printf("[benchmark] ImageIO.useCache=%s cacheDirectory=%s%n", ImageIO.getUseCache(),
                ImageIO.getCacheDirectory() != null ? ImageIO.getCacheDirectory() : System.getProperty("java.io.tmpdir"));
        report("PNG encoding 900x640", medianMillis(before), medianMillis(after));
    }

    @Test
    void heroInputWhitespaceRegexCompiledPerCallVersusPrecompiled() {
        Pattern whitespace = Pattern.compile("\\s+");
        String input = "  Lady    Geist ";
        Supplier<String> before = () -> input.trim().replaceAll("\\s+", " ");
        Supplier<String> after = () -> whitespace.matcher(input.trim()).replaceAll(" ");

        assertThat(after.get()).isEqualTo(before.get());
        report("hero input whitespace normalisation (x1000)", medianMillis(() -> repeat(before, 1000)),
                medianMillis(() -> repeat(after, 1000)));
    }

    /** Every hero option (and every /analyze match autocomplete keystroke with a hero) resolves the typed name. */
    @Test
    void heroResolve() {
        List<HeroAsset> heroes = IntStream.rangeClosed(1, 40)
                .mapToObj(i -> new HeroAsset(i, "hero_" + i, i == 7 ? "Lady Geist" : "Hero Number " + i))
                .toList();
        DeadlockApiClient client = new DeadlockApiClient(RestClient.create(), new DeadlockApiProperties(
                "https://deadlock-api.test", Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(2),
                Duration.ofSeconds(10)), wait -> { }) {
            @Override
            public List<HeroAsset> getHeroes() {
                return heroes;
            }
        };
        HeroResolver resolver = new HeroResolver(new GameAssets(client));
        assertThat(resolver.resolve("  lady   geist ")).map(HeroAsset::id).contains(7);
        assertThat(resolver.resolve("lady")).map(HeroAsset::id).contains(7);
        System.out.printf("[benchmark] hero resolve x1000 (exact + unique prefix, 40 heroes): median %.3f ms%n",
                medianMillis(() -> repeat(() -> resolver.resolve("  lady   geist ").isPresent()
                        && resolver.resolve("lady").isPresent(), 500)));
    }

    @Test
    void soulCurveSvgRender() {
        SoulCurveSvg svg = new SoulCurveSvg(MessagesFixture.english());
        List<Integer> times = IntStream.rangeClosed(1, 12).mapToObj(i -> i * 180).toList();
        List<Long> player = times.stream().map(t -> t * 14L).toList();
        List<Double> avg = times.stream().map(t -> t * 13.0).toList();
        List<Double> std = times.stream().map(t -> t * 2.0).toList();
        String body = svg.render("Infernus", "Oracle", false, times, player, avg, std).orElseThrow();
        System.out.printf("[benchmark] soul curve SVG (12 points): median %.3f ms, %d chars%n",
                medianMillis(() -> svg.render("Infernus", "Oracle", false, times, player, avg, std)), body.length());
    }

    /** Approximate retained heap per cached value (heap delta over many copies, after GC). Reported, not optimized. */
    @Test
    void cacheEntryFootprint() {
        System.out.printf("[benchmark] heap per match history entry list (200 entries): %.1f KiB%n",
                retainedKib(50, CpuHotPathBenchmarkTest::history200));
        System.out.printf("[benchmark] heap per match telemetry (12 players, 13 snapshots, 30 items): %.1f KiB%n",
                retainedKib(50, CpuHotPathBenchmarkTest::telemetry));
        System.out.printf("[benchmark] heap per hero counter list (1,800 rows): %.1f KiB%n",
                retainedKib(20, CpuHotPathBenchmarkTest::counters1800));
    }

    private static List<MatchHistoryEntry> history200() {
        List<MatchHistoryEntry> list = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            list.add(new MatchHistoryEntry(900_000_000L + i, i % 40, 30, 1_789_000_000L - i * 2_000L, 1_900 + i,
                    1, i % 2, 0, 1, 5 + i % 7, 5, 9, 30_000L + i, 150 + i, 10, i % 5 == 0 ? null : 70 + i % 9));
        }
        return List.copyOf(list);
    }

    private static MatchTelemetry telemetry() {
        List<PlayerTelemetry> players = new ArrayList<>(12);
        for (int p = 0; p < 12; p++) {
            List<StatSnapshot> snapshots = new ArrayList<>();
            for (int s = 1; s <= 13; s++) {
                snapshots.add(new StatSnapshot(s * 180, s * 2_000L + p, s * 1_500L, s * 200L,
                        new SnapshotDetail(s, s / 2, s, s * 10, s * 12, s * 50, s * 60, s, s * 900L,
                                s * 700L, s * 300L, s * 20L, s * 40L)));
            }
            List<ItemPurchase> items = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                items.add(new ItemPurchase(1_000L + i, i * 60, null));
            }
            List<DeathEvent> deaths = List.of(new DeathEvent(400, 3, 2.0, 20), new DeathEvent(900, 4, 3.0, 25));
            players.add(new PlayerTelemetry(2_000_000L + p, p % 2, p + 1, items, snapshots,
                    List.of(new PowerUpBuff("weapon_power", true, List.of(600, 1200))), p, 1 + p % 3, deaths));
        }
        return new MatchTelemetry(900_000_003L, 2_100, players, 74);
    }

    private static List<HeroCounterStat> counters1800() {
        List<HeroCounterStat> list = new ArrayList<>(1_800);
        for (int i = 0; i < 1_800; i++) {
            list.add(new HeroCounterStat(i / 42, i % 42, 500L + i, 1_000L + i));
        }
        return List.copyOf(list);
    }

    private static double retainedKib(int copies, Supplier<?> factory) {
        long baseline = usedHeap();
        Object[] keep = new Object[copies];
        for (int i = 0; i < copies; i++) {
            keep[i] = factory.get();
        }
        long used = usedHeap();
        assertThat(keep).doesNotContainNull();
        return (used - baseline) / 1024.0 / copies;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    static MatchVisuals.ChartData chartSample(int points) {
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

    private static Object repeat(Supplier<?> task, int times) {
        Object last = null;
        for (int i = 0; i < times; i++) {
            last = task.get();
        }
        return last;
    }

    private static double medianMillis(Supplier<?> task) {
        for (int i = 0; i < WARMUP; i++) {
            task.get();
        }
        double[] millis = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            task.get();
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(millis);
        return millis[RUNS / 2];
    }

    private static void report(String name, double beforeMs, double afterMs) {
        System.out.printf("[benchmark] %s: before %.3f ms, after %.3f ms, %.1f%% less%n",
                name, beforeMs, afterMs, (1 - afterMs / beforeMs) * 100);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
