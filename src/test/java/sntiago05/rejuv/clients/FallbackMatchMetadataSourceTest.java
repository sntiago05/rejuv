package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import sntiago05.rejuv.domain.MatchTelemetry;

class FallbackMatchMetadataSourceTest {

    private static final long MATCH_ID = 900000003L;

    private JsonMatchMetadataSource json;
    private RawMatchMetadataSource raw;
    private FallbackMatchMetadataSource source;
    private MatchTelemetry telemetry;

    @BeforeEach
    void setUp() {
        json = mock(JsonMatchMetadataSource.class);
        raw = mock(RawMatchMetadataSource.class);
        source = new FallbackMatchMetadataSource(json, raw);
        telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of());
    }

    @Test
    void jsonSuccessRawNeverCalled() {
        when(json.getMatch(MATCH_ID)).thenReturn(telemetry);

        MatchTelemetry result = source.getMatch(MATCH_ID);

        assertThat(result).isSameAs(telemetry);
        verify(json).getMatch(MATCH_ID);
        verifyNoInteractions(raw);
    }

    @Test
    void jsonUnavailableReturnsRawResult() {
        when(json.getMatch(MATCH_ID)).thenThrow(new DeadlockApiUnavailableException("503", null, null));
        when(raw.getMatch(MATCH_ID)).thenReturn(telemetry);

        MatchTelemetry result = source.getMatch(MATCH_ID);

        assertThat(result).isSameAs(telemetry);
        InOrder inOrder = inOrder(json, raw);
        inOrder.verify(json).getMatch(MATCH_ID);
        inOrder.verify(raw).getMatch(MATCH_ID);
    }

    @Test
    void jsonApiExceptionReturnsRawResult() {
        when(json.getMatch(MATCH_ID)).thenThrow(new DeadlockApiException("invalid body"));
        when(raw.getMatch(MATCH_ID)).thenReturn(telemetry);

        MatchTelemetry result = source.getMatch(MATCH_ID);

        assertThat(result).isSameAs(telemetry);
        InOrder inOrder = inOrder(json, raw);
        inOrder.verify(json).getMatch(MATCH_ID);
        inOrder.verify(raw).getMatch(MATCH_ID);
    }

    @Test
    void jsonNotFoundRethrownWithoutRawCall() {
        when(json.getMatch(MATCH_ID)).thenThrow(new MatchMetadataNotFoundException("not found"));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(MatchMetadataNotFoundException.class);

        verify(json).getMatch(MATCH_ID);
        verifyNoInteractions(raw);
    }

    @Test
    void bothFailThrowsRawExceptionWithJsonSuppressed() {
        DeadlockApiUnavailableException jsonEx = new DeadlockApiUnavailableException("503", null, null);
        DeadlockApiException rawEx = new DeadlockApiException("corrupt raw");
        when(json.getMatch(MATCH_ID)).thenThrow(jsonEx);
        when(raw.getMatch(MATCH_ID)).thenThrow(rawEx);

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isSameAs(rawEx);

        assertThat(rawEx.getSuppressed()).containsExactly(jsonEx);
    }

    @Test
    void jsonUnavailableAndRawNotFoundThrowsJsonExceptionWithRawSuppressed() {
        DeadlockApiUnavailableException jsonEx = new DeadlockApiUnavailableException("503", null, null);
        MatchMetadataNotFoundException rawEx = new MatchMetadataNotFoundException("not found");
        when(json.getMatch(MATCH_ID)).thenThrow(jsonEx);
        when(raw.getMatch(MATCH_ID)).thenThrow(rawEx);

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isSameAs(jsonEx);

        assertThat(jsonEx.getSuppressed()).containsExactly(rawEx);
    }

    @Test
    void callsAreSequential() {
        when(json.getMatch(MATCH_ID)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));
        when(raw.getMatch(MATCH_ID)).thenReturn(telemetry);

        source.getMatch(MATCH_ID);

        InOrder inOrder = inOrder(json, raw);
        inOrder.verify(json).getMatch(MATCH_ID);
        inOrder.verify(raw).getMatch(MATCH_ID);
        inOrder.verifyNoMoreInteractions();
    }
}
