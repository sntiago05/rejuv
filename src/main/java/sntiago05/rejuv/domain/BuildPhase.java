package sntiago05.rejuv.domain;

import java.util.List;
import java.util.Objects;

/**
 * column is 0-based. items immutable, sorted by matches desc, then itemId asc; never empty.
 */
public record BuildPhase(int column, List<BuildItem> items) {

    public BuildPhase {
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        items = List.copyOf(items);
    }
}
