package sntiago05.rejuv.services;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sntiago05.rejuv.services.InvalidAccountInputException.Reason;

/** Parses a SteamID64, a steamcommunity.com profile URL or a Deadlock account ID into a Deadlock account ID. */
public final class AccountIdParser {

    public static final long STEAM_ID64_BASE = 76561197960265728L;

    private static final long MAX_ACCOUNT_ID = 4294967295L;
    private static final String STEAM_ID64_PREFIX = "7656119";
    private static final Pattern STEAM_ID64 = Pattern.compile("^\\d{17}$");
    private static final Pattern PROFILE_URL = Pattern.compile(
            "^https?://(www\\.)?steamcommunity\\.com/profiles/(\\d{17})/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_ID = Pattern.compile("^\\d{1,10}$");
    private static final Pattern VANITY_URL = Pattern.compile(
            "^https?://(www\\.)?steamcommunity\\.com/id/[^/\\s]+/?$", Pattern.CASE_INSENSITIVE);

    private AccountIdParser() {
    }

    /** Returns the Deadlock account ID (1..4294967295). */
    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            throw new InvalidAccountInputException(Reason.UNRECOGNIZED);
        }
        String value = input.trim();

        if (STEAM_ID64.matcher(value).matches()) {
            return fromSteamId64(value);
        }
        Matcher profile = PROFILE_URL.matcher(value);
        if (profile.matches()) {
            return fromSteamId64(profile.group(2));
        }
        if (ACCOUNT_ID.matcher(value).matches()) {
            long accountId = Long.parseLong(value);
            if (accountId >= 1 && accountId <= MAX_ACCOUNT_ID) {
                return accountId;
            }
            throw new InvalidAccountInputException(Reason.UNRECOGNIZED);
        }
        if (VANITY_URL.matcher(value).matches()) {
            throw new InvalidAccountInputException(Reason.VANITY_URL);
        }
        throw new InvalidAccountInputException(Reason.UNRECOGNIZED);
    }

    private static long fromSteamId64(String digits) {
        if (!digits.startsWith(STEAM_ID64_PREFIX)) {
            throw new InvalidAccountInputException(Reason.UNRECOGNIZED);
        }
        long accountId = Long.parseLong(digits) - STEAM_ID64_BASE;
        if (accountId < 1) {
            throw new InvalidAccountInputException(Reason.UNRECOGNIZED);
        }
        return accountId;
    }
}
