package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import sntiago05.rejuv.services.InvalidAccountInputException.Reason;

class AccountIdParserTest {

    @Test
    void parsesSteamId64() {
        assertThat(AccountIdParser.parse("76561197961265729")).isEqualTo(1000001L);
    }

    @Test
    void parsesProfileUrl() {
        assertThat(AccountIdParser.parse("https://steamcommunity.com/profiles/76561197961265729")).isEqualTo(1000001L);
        assertThat(AccountIdParser.parse("https://steamcommunity.com/profiles/76561197961265729/")).isEqualTo(1000001L);
        assertThat(AccountIdParser.parse("http://steamcommunity.com/profiles/76561197961265729")).isEqualTo(1000001L);
        assertThat(AccountIdParser.parse("https://www.steamcommunity.com/profiles/76561197961265729")).isEqualTo(1000001L);
        assertThat(AccountIdParser.parse("https://STEAMCOMMUNITY.COM/profiles/76561197961265729")).isEqualTo(1000001L);
    }

    @Test
    void parsesDeadlockAccountId() {
        assertThat(AccountIdParser.parse("1000001")).isEqualTo(1000001L);
        assertThat(AccountIdParser.parse("1")).isEqualTo(1L);
        assertThat(AccountIdParser.parse("4294967295")).isEqualTo(4294967295L);
    }

    @Test
    void trimsWhitespace() {
        assertThat(AccountIdParser.parse("  1000001  ")).isEqualTo(1000001L);
    }

    @Test
    void rejectsVanityUrl() {
        assertThat(reasonFor("https://steamcommunity.com/id/somename")).isEqualTo(Reason.VANITY_URL);
        assertThat(reasonFor("https://steamcommunity.com/id/somename/")).isEqualTo(Reason.VANITY_URL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "abc",
            "0",
            "-5",
            "4294967296",
            "12345678901",
            "1234567890123456",
            "12345678901234567",
            "76561197960265728",
            "76561190000000000",
            "https://example.com/profiles/76561197961265729",
            "https://steamcommunity.com/profiles/123",
            "1000001 2"
    })
    void rejectsUnrecognizedInput(String input) {
        assertThat(reasonFor(input)).isEqualTo(Reason.UNRECOGNIZED);
    }

    private static Reason reasonFor(String input) {
        InvalidAccountInputException ex = catchThrowableOfType(InvalidAccountInputException.class,
                () -> AccountIdParser.parse(input));
        assertThat(ex).isNotNull();
        return ex.reason();
    }
}
