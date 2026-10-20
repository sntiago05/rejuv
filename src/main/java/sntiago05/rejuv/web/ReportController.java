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
import sntiago05.rejuv.services.ReportIconService;
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
    private static final String VALUE_ICON_CACHE_CONTROL = "private, max-age=86400";

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
        return png.map(bytes -> ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.IMAGE_PNG)
                .body(bytes)).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build());
    }

    @GetMapping("/r/{publicId}/img/hero/{heroId}.png")
    public ResponseEntity<byte[]> heroIcon(@PathVariable("publicId") String publicIdRaw,
                                           @PathVariable("heroId") int heroId) {
        return serveIcon(publicIdRaw, uuid -> reportService.heroIcon(uuid, heroId));
    }

    @GetMapping("/r/{publicId}/img/item/{itemId}.png")
    public ResponseEntity<byte[]> itemIcon(@PathVariable("publicId") String publicIdRaw,
                                           @PathVariable("itemId") long itemId) {
        return serveIcon(publicIdRaw, uuid -> reportService.itemIcon(uuid, itemId));
    }

    @GetMapping("/r/{publicId}/soul-curve.svg")
    public ResponseEntity<String> soulCurveSvg(@PathVariable("publicId") String publicIdRaw) {
        return serveSvg(publicIdRaw, reportService::soulCurveSvg);
    }

    @GetMapping({"/r/{publicId}/soul-lead.svg", "/r/{publicId}/lead.svg"})
    public ResponseEntity<String> soulLeadSvg(@PathVariable("publicId") String publicIdRaw) {
        return serveSvg(publicIdRaw, reportService::soulLeadSvg);
    }

    @GetMapping("/r/{publicId}/phases.svg")
    public ResponseEntity<String> phasesSvg(@PathVariable("publicId") String publicIdRaw) {
        return serveSvg(publicIdRaw, reportService::phasesSvg);
    }

    @GetMapping("/r/{publicId}/deaths.svg")
    public ResponseEntity<String> deathsSvg(@PathVariable("publicId") String publicIdRaw) {
        return serveSvg(publicIdRaw, reportService::deathsSvg);
    }

    @GetMapping("/r/{publicId}/metrics.svg")
    public ResponseEntity<String> metricsSvg(@PathVariable("publicId") String publicIdRaw) {
        return serveSvg(publicIdRaw, reportService::metricsSvg);
    }

    @GetMapping("/r/{publicId}/build.svg")
    public ResponseEntity<String> buildSvg(@PathVariable("publicId") String publicIdRaw) {
        return serveSvg(publicIdRaw, reportService::buildSvg);
    }

    private ResponseEntity<String> serveSvg(String publicIdRaw, java.util.function.Function<UUID, Optional<String>> loader) {
        HttpHeaders headers = createSecurityHeaders();
        Optional<UUID> uuid = parseUuid(publicIdRaw);
        if (uuid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build();
        }

        Optional<String> svg = loader.apply(uuid.get());
        return svg.map(s -> ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"))
                .body(s)).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build());
    }

    /** Icons keep every security header; only the cache rule differs because the bytes never change per id. */
    private ResponseEntity<byte[]> serveIcon(String publicIdRaw,
                                             java.util.function.Function<UUID, Optional<ReportIconService.Icon>> loader) {
        HttpHeaders headers = createSecurityHeaders();
        Optional<UUID> uuid = parseUuid(publicIdRaw);
        if (uuid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build();
        }
        return loader.apply(uuid.get()).map(icon -> {
            headers.set(HEADER_CACHE_CONTROL, VALUE_ICON_CACHE_CONTROL);
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(MediaType.parseMediaType(icon.contentType()))
                    .body(icon.bytes());
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build());
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
