package sntiago05.rejuv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import sntiago05.rejuv.TestcontainersConfiguration;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.ReportView;
import sntiago05.rejuv.services.ReportService;

@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})
@Import(TestcontainersConfiguration.class)
class ReportControllerTest {

    private static final UUID KNOWN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID UNKNOWN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void viewReportReturns200WithStrengthTextAndSecurityHeaders() throws Exception {
        AnalysisResult analysis = new AnalysisResult(
                "Superb farming in lane", "Too aggressive without vision", "Group for objectives", List.of("15 kills"));
        ReportView report = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, null, null,
                30.5, analysis, List.of(), false, Instant.parse("2026-10-18T12:00:00Z"));
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(report));

        mockMvc.perform(get("/r/" + KNOWN_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("report"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Superb farming in lane")))
                .andExpect(content().string(containsString("Infernus")))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));
    }

    @Test
    void viewReportEscapesHtmlSpecialCharactersInAnalysisText() throws Exception {
        AnalysisResult analysis = new AnalysisResult(
                "<script>alert('xss')</script>", "<b>leak</b>", "a & b", List.of("<script>danger</script>"));
        ReportView report = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, null, null,
                30.0, analysis, List.of(), false, Instant.now());
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(report));

        MvcResult result = mockMvc.perform(get("/r/" + KNOWN_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script"))))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("<script");
    }

    @Test
    void viewReportMalformedIdReturns404NotFoundWithoutEcho() throws Exception {
        String malformed = "not-a-valid-uuid-and-definitely-bad";

        MvcResult result = mockMvc.perform(get("/r/" + malformed))
                .andExpect(status().isNotFound())
                .andExpect(view().name("report-not-found"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(malformed);
    }

    @Test
    void viewReportUnknownIdReturns404NotFoundWithoutEcho() throws Exception {
        when(reportService.find(UNKNOWN_ID)).thenReturn(Optional.empty());

        MvcResult result = mockMvc.perform(get("/r/" + UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(view().name("report-not-found"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(UNKNOWN_ID.toString());
    }

    @Test
    void chartPngReturnsBytesWhenPresent() throws Exception {
        byte[] pngData = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        when(reportService.chartPng(KNOWN_ID)).thenReturn(Optional.of(pngData));

        mockMvc.perform(get("/r/" + KNOWN_ID + "/chart.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(pngData))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));
    }

    @Test
    void chartPngReturns404WithEmptyBodyWhenEmptyOrMalformed() throws Exception {
        when(reportService.chartPng(UNKNOWN_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/r/" + UNKNOWN_ID + "/chart.png"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));

        mockMvc.perform(get("/r/not-a-uuid/chart.png"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));
    }

    @Test
    void soulCurveSvgReturns200WithSvgAndSecurityHeaders() throws Exception {
        String svgContent = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 640 360\" role=\"img\"><title>Soul Curve</title></svg>";
        when(reportService.soulCurveSvg(KNOWN_ID)).thenReturn(Optional.of(svgContent));

        mockMvc.perform(get("/r/" + KNOWN_ID + "/soul-curve.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml;charset=UTF-8"))
                .andExpect(content().string(svgContent))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));
    }

    @Test
    void soulCurveSvgReturns404WithEmptyBodyWhenEmptyOrMalformed() throws Exception {
        when(reportService.soulCurveSvg(UNKNOWN_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/r/" + UNKNOWN_ID + "/soul-curve.svg"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));

        mockMvc.perform(get("/r/not-a-uuid/soul-curve.svg"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));
    }

    @Test
    void viewReportIncludesSoulCurveImgOnlyWhenHasSoulCurve() throws Exception {
        AnalysisResult analysis = new AnalysisResult("Strength", "Leak", "Action", List.of());

        // Report with hasSoulCurve = true
        ReportView withCurve = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, null, null,
                30.0, analysis, List.of(), false, Instant.now(), true);
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(withCurve));

        mockMvc.perform(get("/r/" + KNOWN_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/r/" + KNOWN_ID + "/soul-curve.svg")))
                .andExpect(content().string(containsString("soul-curve-img")));

        // Report with hasSoulCurve = false
        ReportView withoutCurve = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, null, null,
                30.0, analysis, List.of(), false, Instant.now(), false);
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(withoutCurve));

        mockMvc.perform(get("/r/" + KNOWN_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("soul-curve.svg"))));
    }

    @Test
    void viewReportPageStillHasNoScript() throws Exception {
        AnalysisResult analysis = new AnalysisResult("Strength", "Leak", "Action", List.of());
        ReportView report = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, null, null,
                30.0, analysis, List.of(), true, Instant.now(), true);
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(report));

        MvcResult result = mockMvc.perform(get("/r/" + KNOWN_ID))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("<script");
    }

    @Test
    void viewReportWithDetailedSectionsRendersGlanceMetricsCoachAndOtherSignals() throws Exception {
        AnalysisResult analysis = new AnalysisResult("Strength", "Leak", "Action", List.of());
        List<ReportView.MetricRow> metrics = List.of(
                new ReportView.MetricRow("kills", "analyze.metric.kills", "10", "5", "8", "above p50"),
                new ReportView.MetricRow("deaths", "analyze.metric.deaths", "7", "4", "6", "below p25")
        );
        List<ReportView.CoachSection> coach = List.of(
                new ReportView.CoachSection("phases", "report.coach.phases", "major", List.of(
                        new ReportView.CoachField("report.coach.phases.weakest_phase", "Laning")
                )),
                new ReportView.CoachSection("deaths", "report.coach.deaths", "notable", List.of(
                        new ReportView.CoachField("report.coach.deaths.total", "7")
                ))
        );
        List<ReportView.OtherSignal> signals = List.of(
                new ReportView.OtherSignal("custom_signal", "true")
        );

        ReportView report = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, "Archon 3", "win",
                30.5, analysis, List.of(), false, Instant.now(), false,
                true, metrics, coach, signals);
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(report));

        mockMvc.perform(get("/r/" + KNOWN_ID))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Match at a Glance")))
            .andExpect(content().string(containsString("Archon 3")))
            .andExpect(content().string(containsString("Victory")))
            .andExpect(content().string(containsString("Your Numbers vs Peers")))
            .andExpect(content().string(containsString("above p50")))
            .andExpect(content().string(containsString("below p25")))
            .andExpect(content().string(containsString("Phases &amp; Momentum")))
            .andExpect(content().string(containsString("Major")))
            .andExpect(content().string(containsString("Deaths &amp; Survival")))
            .andExpect(content().string(containsString("Notable")))
            .andExpect(content().string(containsString("Other Signals")))
            .andExpect(content().string(containsString("custom_signal")))
            .andExpect(content().string(not(containsString("Detailed match metrics and coach sections are only available for newer analyses."))));
    }

    @Test
    void viewReportWithoutDetailedSectionsRendersOlderAnalysisNotice() throws Exception {
        AnalysisResult analysis = new AnalysisResult("Strength", "Leak", "Action", List.of());
        ReportView report = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, null, null,
                30.0, analysis, List.of(), false, Instant.now(), false,
                false, List.of(), List.of(), List.of());
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(report));

        mockMvc.perform(get("/r/" + KNOWN_ID))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Detailed match metrics and coach sections are only available for newer analyses.")))
            .andExpect(content().string(not(containsString("Match at a Glance"))))
            .andExpect(content().string(not(containsString("Your Numbers vs Peers"))));
    }

    @Test
    void viewReportEscapesHtmlSpecialCharactersInCoachAndSignals() throws Exception {
        AnalysisResult analysis = new AnalysisResult("Strength", "Leak", "Action", List.of());
        List<ReportView.CoachSection> coach = List.of(
                new ReportView.CoachSection("phases", "report.coach.phases", "major", List.of(
                        new ReportView.CoachField("report.coach.phases.weakest_phase", "<script>alert('coach')</script><b>bold</b>")
                ))
        );
        List<ReportView.OtherSignal> signals = List.of(
                new ReportView.OtherSignal("exploit", "<script>alert('signal')</script>")
        );

        ReportView report = new ReportView(KNOWN_ID, 987654321L, "Infernus", 1, "Archon 3", "win",
                30.5, analysis, List.of(), false, Instant.now(), false,
                true, List.of(), coach, signals);
        when(reportService.find(KNOWN_ID)).thenReturn(Optional.of(report));

        MvcResult result = mockMvc.perform(get("/r/" + KNOWN_ID))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("&lt;script&gt;")))
            .andExpect(content().string(containsString("&lt;b&gt;bold&lt;/b&gt;")))
            .andExpect(content().string(not(containsString("<script"))))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("<script");
    }
}
