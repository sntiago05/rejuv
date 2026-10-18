package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Supplier;

import com.google.protobuf.WireFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.domain.MatchTelemetry;

/**
 * Task 039: cost of the raw metadata fallback (Zstandard + hand-written protobuf reader) for a payload padded to
 * a realistic size. Measured and reported only. Disabled by default; run with
 * {@code ./mvnw test -Dtest=RawMetadataBenchmarkTest -Dbenchmark=true}.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class RawMetadataBenchmarkTest {

    private static final String BASE_URL = "https://deadlock-api.test";
    private static final long MATCH_ID = RawMetadataTestData.DEFAULT_MATCH_ID;
    private static final DeadlockApiProperties PROPERTIES = new DeadlockApiProperties(BASE_URL, Duration.ofSeconds(3),
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));
    // Unknown match_details data (damage matrix, objectives, ...) that the parser must skip.
    private static final int PADDING_BYTES = 3 * 1024 * 1024;

    @Test
    void decompressAndParse() {
        byte[] raw = payload();
        byte[] compressed = RawMetadataTestData.compress(raw);

        RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(PROPERTIES);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), requestTo(BASE_URL + "/v1/matches/" + MATCH_ID + "/metadata/raw"))
                .andRespond(withSuccess(compressed, MediaType.APPLICATION_OCTET_STREAM));
        RawMatchMetadataSource source = new RawMatchMetadataSource(builder.build(), PROPERTIES, wait -> { });

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);
        assertThat(telemetry.players()).hasSize(12);

        double full = medianMillis(() -> source.getMatch(MATCH_ID));
        double parse = medianMillis(() -> RawMatchMetadataParser.parse(MATCH_ID, raw));
        System.out.printf("[benchmark] raw metadata (%d KiB raw, %d KiB zstd): full getMatch %.3f ms, parse only %.3f ms%n",
                raw.length / 1024, compressed.length / 1024, full, parse);
    }

    private static byte[] payload() {
        RawMetadataTestData.MatchInfoBuilder matchInfo = new RawMetadataTestData.MatchInfoBuilder()
                .durationS(2_100).matchId(MATCH_ID);
        for (int p = 0; p < 12; p++) {
            RawMetadataTestData.PlayerBuilder player = new RawMetadataTestData.PlayerBuilder()
                    .accountId(1_000_001L + p).team(p % 2).heroId(p + 1);
            for (int i = 0; i < 30; i++) {
                player.addItem(new RawMetadataTestData.ItemBuilder().gameTimeS(i * 60).itemId(1_000L + i).soldTimeS(0).build());
            }
            for (int s = 1; s <= 13; s++) {
                player.addStat(new RawMetadataTestData.StatBuilder().timeStampS(s * 180).netWorth(s * 2_000L)
                        .playerDamage(s * 1_500L).playerHealing(s * 200L).build());
            }
            matchInfo.addPlayer(player.build());
        }
        byte[] padding = new byte[PADDING_BYTES];
        new Random(39).nextBytes(padding);
        byte[] contents = new RawMetadataTestData.ContentsBuilder()
                .matchInfo(matchInfo.build())
                .addExtraField(9, padding, WireFormat.WIRETYPE_LENGTH_DELIMITED)
                .build();
        return new RawMetadataTestData.OuterBuilder().matchId(MATCH_ID).matchDetails(contents).build();
    }

    private static double medianMillis(Supplier<?> task) {
        for (int i = 0; i < 10; i++) {
            task.get();
        }
        double[] millis = new double[25];
        for (int i = 0; i < millis.length; i++) {
            long start = System.nanoTime();
            task.get();
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(millis);
        return millis[millis.length / 2];
    }
}
