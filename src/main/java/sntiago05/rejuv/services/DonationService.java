package sntiago05.rejuv.services;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.config.DonationProperties;

@Service
@EnableConfigurationProperties(DonationProperties.class)
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);
    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_LABEL_LENGTH = 80;

    private final Optional<Donation> donation;

    public DonationService(DonationProperties properties, Messages messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        DonationProperties props = properties != null ? properties : new DonationProperties(null, null);

        String rawUrl = props.url();
        String validUrl = validateUrl(rawUrl);

        if (validUrl != null) {
            String rawLabel = props.label();
            String label = resolveLabel(rawLabel, messages);
            this.donation = Optional.of(new Donation(validUrl, label));
        } else {
            this.donation = Optional.empty();
        }
    }

    public Optional<Donation> get() {
        return donation;
    }

    public record Donation(String url, String label) {
    }

    private static String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        if (url.length() > MAX_URL_LENGTH || containsWhitespace(url)) {
            log.warn("Invalid donation URL configured: '{}'", url);
            return null;
        }

        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                log.warn("Invalid donation URL configured (scheme must be http or https): '{}'", url);
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                log.warn("Invalid donation URL configured (missing host): '{}'", url);
                return null;
            }
            return url;
        } catch (URISyntaxException ex) {
            log.warn("Invalid donation URL configured (syntax error): '{}'", url);
            return null;
        }
    }

    private static boolean containsWhitespace(String s) {
        return s.chars().anyMatch(Character::isWhitespace);
    }

    private static String resolveLabel(String rawLabel, Messages messages) {
        String label = (rawLabel != null && !rawLabel.isBlank())
                ? rawLabel.trim()
                : messages.get("donation.label.default");
        if (label.length() > MAX_LABEL_LENGTH) {
            label = label.substring(0, MAX_LABEL_LENGTH);
        }
        return label;
    }
}
