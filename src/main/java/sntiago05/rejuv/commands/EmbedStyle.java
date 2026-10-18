package sntiago05.rejuv.commands;

import java.util.List;
import java.util.Optional;

import net.dv8tion.jda.api.EmbedBuilder;

/**
 * Styling constants and helpers for Discord embeds.
 */
final class EmbedStyle {

    /** Brand accent color for all embed commands (warm Deadlock amber). */
    static final int COLOR = 0xE5A93C;

    private static final int MAX_FIELD_LENGTH = 1024;

    private EmbedStyle() {
    }

    /**
     * Prepends an emoji icon to text if present.
     *
     * @param icon emoji mention markup or empty
     * @param text text to display
     * @return {@code "<icon> <text>"} when icon is present, else {@code text}
     */
    static String withIcon(Optional<String> icon, String text) {
        if (icon != null && icon.isPresent() && !icon.get().isBlank()) {
            return icon.get() + " " + text;
        }
        return text;
    }

    /**
     * Joins lines with {@code "\n"}, dropping whole trailing lines until the total
     * length is at most 1024 characters.
     *
     * @param lines lines to join
     * @return joined string (<= 1024 chars), or empty string if lines is empty or no line fits
     */
    static String fitField(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int totalLength = 0;
        int count = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineLen = line != null ? line.length() : 0;
            int added = (i == 0) ? lineLen : 1 + lineLen;
            if (totalLength + added > MAX_FIELD_LENGTH) {
                break;
            }
            totalLength += added;
            count++;
        }
        if (count == 0) {
            return "";
        }
        return String.join("\n", lines.subList(0, count));
    }

    /**
     * Adds a non-inline field whose value is {@link #fitField(List)} of the lines; adds nothing when no line fits
     * (including an empty list).
     */
    static void addFittedField(EmbedBuilder builder, String name, List<String> lines) {
        String value = fitField(lines);
        if (!value.isEmpty()) {
            builder.addField(name, value, false);
        }
    }
}
