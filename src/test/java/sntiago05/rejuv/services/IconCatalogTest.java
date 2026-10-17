package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IconCatalogTest {

    @Test
    void emojiNamesFormatCorrectly() {
        assertThat(IconCatalog.heroEmojiName(1)).isEqualTo("h_1");
        assertThat(IconCatalog.heroEmojiName(65)).isEqualTo("h_65");
        assertThat(IconCatalog.itemEmojiName(1548066885L)).isEqualTo("i_1548066885");
    }

    @Test
    void startsEmpty() {
        IconCatalog catalog = new IconCatalog();

        assertThat(catalog.hero(1)).isEmpty();
        assertThat(catalog.item(1548066885L)).isEmpty();
    }

    @Test
    void replaceSwapsMapAndReadsSeeNewValues() {
        IconCatalog catalog = new IconCatalog();

        catalog.replace(Map.of(
                "h_1", "<:h_1:1001>",
                "i_1548066885", "<:i_1548066885:2002>"
        ));

        assertThat(catalog.hero(1)).contains("<:h_1:1001>");
        assertThat(catalog.item(1548066885L)).contains("<:i_1548066885:2002>");
        assertThat(catalog.hero(2)).isEmpty();

        // Swap to another map
        catalog.replace(Map.of(
                "h_2", "<:h_2:3003>"
        ));

        assertThat(catalog.hero(1)).isEmpty();
        assertThat(catalog.hero(2)).contains("<:h_2:3003>");
    }

    @Test
    void storedMapIsACopyDefendingAgainstMutation() {
        IconCatalog catalog = new IconCatalog();
        Map<String, String> mutable = new HashMap<>();
        mutable.put("h_1", "<:h_1:1001>");

        catalog.replace(mutable);
        assertThat(catalog.hero(1)).contains("<:h_1:1001>");

        // Mutating the original map has no effect on the catalog
        mutable.put("h_1", "<:h_1:MUTATED>");
        mutable.put("h_2", "<:h_2:NEW>");

        assertThat(catalog.hero(1)).contains("<:h_1:1001>");
        assertThat(catalog.hero(2)).isEmpty();
    }

    @Test
    void replaceWithNullResetsToEmpty() {
        IconCatalog catalog = new IconCatalog();
        catalog.replace(Map.of("h_1", "<:h_1:1001>"));

        catalog.replace(null);

        assertThat(catalog.hero(1)).isEmpty();
    }
}
