package sntiago05.rejuv.web;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import sntiago05.rejuv.domain.ReportView;
import sntiago05.rejuv.services.ReportService;

@Controller
public class ReportController {

    private static final String HEADER_ROBOTS = "X-Robots-Tag";
    private static final String VALUE_ROBOTS = "noindex, nofollow";

    private static final String HEADER_CSP = "Content-Security-Policy";
    private static final String VALUE_CSP = "default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'";

    private static final String HEADER_REFERRER = "Referrer-Policy";
    private static final String VALUE_REFERRER = "no-referrer";

    private static final String HEADER_NOSNIFF = "X-Content-Type-Options";
    private static final String VALUE_NOSNIFF = "nosniff";

    private static final String HEADER_CACHE_CONTROL = "Cache-Control";
    private static final String VALUE_CACHE_CONTROL = "private, max-age=300";

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/r/{publicId}")
    public String viewReport(@PathVariable("publicId") String publicIdRaw, Model model, HttpServletResponse response) {
        applySecurityHeaders(response);
        Optional<UUID> uuid = parseUuid(publicIdRaw);
        if (uuid.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "report-not-found";
        }

        Optional<ReportView> report = reportService.find(uuid.get());
        if (report.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "report-not-found";
        }

        model.addAttribute("report", report.get());
        return "report";
    }

    @GetMapping("/r/{publicId}/chart.png")
    public ResponseEntity<byte[]> chartPng(@PathVariable("publicId") String publicIdRaw) {
        HttpHeaders headers = createSecurityHeaders();
        Optional<UUID> uuid = parseUuid(publicIdRaw);
        if (uuid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build();
        }

        Optional<byte[]> png = reportService.chartPng(uuid.get());
        if (png.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build();
        }

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.IMAGE_PNG)
                .body(png.get());
    }

    @GetMapping("/r/{publicId}/soul-curve.svg")
    public ResponseEntity<String> soulCurveSvg(@PathVariable("publicId") String publicIdRaw) {
        HttpHeaders headers = createSecurityHeaders();
        Optional<UUID> uuid = parseUuid(publicIdRaw);
        if (uuid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build();
        }

        Optional<String> svg = reportService.soulCurveSvg(uuid.get());
        if (svg.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build();
        }

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"))
                .body(svg.get());
    }

    private void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader(HEADER_ROBOTS, VALUE_ROBOTS);
        response.setHeader(HEADER_CSP, VALUE_CSP);
        response.setHeader(HEADER_REFERRER, VALUE_REFERRER);
        response.setHeader(HEADER_NOSNIFF, VALUE_NOSNIFF);
        response.setHeader(HEADER_CACHE_CONTROL, VALUE_CACHE_CONTROL);
    }

    private HttpHeaders createSecurityHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_ROBOTS, VALUE_ROBOTS);
        headers.set(HEADER_CSP, VALUE_CSP);
        headers.set(HEADER_REFERRER, VALUE_REFERRER);
        headers.set(HEADER_NOSNIFF, VALUE_NOSNIFF);
        headers.set(HEADER_CACHE_CONTROL, VALUE_CACHE_CONTROL);
        return headers;
    }

    private static Optional<UUID> parseUuid(String raw) {
        if (raw == null || raw.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
