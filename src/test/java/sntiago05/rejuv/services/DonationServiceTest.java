package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sntiago05.rejuv.config.DonationProperties;

class DonationServiceTest {

    private Messages messages;

    @BeforeEach
    void setUp() {
        messages = MessagesFixture.english();
    }

    @Test
    void validHttpsUrlReturnsDonation() {
        DonationProperties properties = new DonationProperties("https://example.com/donate", "Donate Now");
        DonationService service = new DonationService(properties, messages);

        Optional<DonationService.Donation> donation = service.get();
        assertThat(donation).isPresent();
        assertThat(donation.get().url()).isEqualTo("https://example.com/donate");
        assertThat(donation.get().label()).isEqualTo("Donate Now");
    }

    @Test
    void validHttpUrlReturnsDonation() {
        DonationProperties properties = new DonationProperties("http://example.com/donate", "Support");
        DonationService service = new DonationService(properties, messages);

        Optional<DonationService.Donation> donation = service.get();
        assertThat(donation).isPresent();
        assertThat(donation.get().url()).isEqualTo("http://example.com/donate");
        assertThat(donation.get().label()).isEqualTo("Support");
    }

    @Test
    void nullUrlReturnsEmpty() {
        DonationProperties properties = new DonationProperties(null, "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void blankUrlReturnsEmpty(String blankUrl) {
        DonationProperties properties = new DonationProperties(blankUrl, "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @Test
    void noSchemeReturnsEmpty() {
        DonationProperties properties = new DonationProperties("example.com/donate", "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @Test
    void ftpSchemeReturnsEmpty() {
        DonationProperties properties = new DonationProperties("ftp://example.com/donate", "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @Test
    void javascriptSchemeReturnsEmpty() {
        DonationProperties properties = new DonationProperties("javascript:alert(1)", "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/donate here",
            " https://example.com/donate",
            "https://example.com/donate ",
            "https://example.com/\tpath"
    })
    void whitespaceInUrlReturnsEmpty(String urlWithWhitespace) {
        DonationProperties properties = new DonationProperties(urlWithWhitespace, "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @Test
    void urlOver512CharactersReturnsEmpty() {
        String longPath = "a".repeat(500);
        String url = "https://example.com/" + longPath;
        assertThat(url.length()).isGreaterThan(512);

        DonationProperties properties = new DonationProperties(url, "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://",
            "https://",
            "https:///path",
            "https://:8080/path"
    })
    void noHostReturnsEmpty(String noHostUrl) {
        DonationProperties properties = new DonationProperties(noHostUrl, "Donate");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isEmpty();
    }

    @Test
    void blankLabelFallsBackToDefault() {
        DonationProperties withBlank = new DonationProperties("https://example.com/donate", "   ");
        DonationService serviceBlank = new DonationService(withBlank, messages);

        assertThat(serviceBlank.get()).isPresent();
        assertThat(serviceBlank.get().get().label()).isEqualTo(messages.get("donation.label.default"));

        DonationProperties withNull = new DonationProperties("https://example.com/donate", null);
        DonationService serviceNull = new DonationService(withNull, messages);

        assertThat(serviceNull.get()).isPresent();
        assertThat(serviceNull.get().get().label()).isEqualTo(messages.get("donation.label.default"));
    }

    @Test
    void labelIsTrimmed() {
        DonationProperties properties = new DonationProperties("https://example.com/donate", "  Patreon  ");
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isPresent();
        assertThat(service.get().get().label()).isEqualTo("Patreon");
    }

    @Test
    void labelOver80CharactersIsCut() {
        String longLabel = "A".repeat(100);
        DonationProperties properties = new DonationProperties("https://example.com/donate", longLabel);
        DonationService service = new DonationService(properties, messages);

        assertThat(service.get()).isPresent();
        assertThat(service.get().get().label()).hasSize(80).isEqualTo("A".repeat(80));
    }

    @Test
    void invalidUrlDoesNotThrow() {
        assertThatCode(() -> {
            DonationService service = new DonationService(
                    new DonationProperties("http://[invalid-ipv6-reference", "Label"), messages);
            assertThat(service.get()).isEmpty();
        }).doesNotThrowAnyException();
    }
}
