package sntiago05.rejuv.clients;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.github.luben.zstd.ZstdInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.domain.MatchTelemetry;

/**
 * Reads match telemetry from the deadlock-api raw metadata endpoint ({@code GET /v1/matches/{match_id}/metadata/raw})
 * using Zstandard decompression and protobuf decoding.
 */
@Component
public class RawMatchMetadataSource {

    private static final String METADATA_RAW_PATH = "/v1/matches/{match_id}/metadata/raw";

    static final int DEFAULT_MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
    static final int DEFAULT_MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;

    private final DeadlockApiRequests requests;
    private final int maxCompressedBytes;
    private final int maxDecompressedBytes;

    @Autowired
    public RawMatchMetadataSource(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper) {
        this(deadlockApiRestClient, properties, sleeper, DEFAULT_MAX_COMPRESSED_BYTES, DEFAULT_MAX_DECOMPRESSED_BYTES);
    }

    RawMatchMetadataSource(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper,
                           int maxCompressedBytes, int maxDecompressedBytes) {
        this.requests = new DeadlockApiRequests(deadlockApiRestClient, properties, sleeper);
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    @Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA_RAW, sync = true)
    public MatchTelemetry getMatch(long matchId) {
        byte[] compressed = requests.get(METADATA_RAW_PATH,
                uri -> uri.path(METADATA_RAW_PATH).build(matchId),
                DeadlockApiRequests.bytes(maxCompressedBytes),
                false,
                () -> new MatchMetadataNotFoundException("GET " + METADATA_RAW_PATH + " returned HTTP 404"));

        byte[] decompressed = decompress(compressed);
        return RawMatchMetadataParser.parse(matchId, decompressed);
    }

    private byte[] decompress(byte[] compressedBytes) {
        if (!isZstd(compressedBytes)) {
            throw new DeadlockApiException("Unrecognized metadata compression format");
        }
        try (InputStream in = new ByteArrayInputStream(compressedBytes);
             ZstdInputStream zis = new ZstdInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = zis.read(buffer)) != -1) {
                total += n;
                if (total > maxDecompressedBytes) {
                    throw new DeadlockApiException("Decompressed metadata exceeded limit of " + maxDecompressedBytes + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DeadlockApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DeadlockApiException("Failed to decompress metadata", e);
        }
    }

    private static boolean isZstd(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 0x28
                && bytes[1] == (byte) 0xB5
                && bytes[2] == 0x2F
                && bytes[3] == (byte) 0xFD;
    }
}
