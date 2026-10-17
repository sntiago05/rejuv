package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import sntiago05.rejuv.TestcontainersConfiguration;
import sntiago05.rejuv.commands.SlashCommand;

@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})
@Import(TestcontainersConfiguration.class)
class MessagesIntegrationTest {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$");

    @Autowired
    private Messages messages;

    @Autowired
    private List<SlashCommand> commands;

    @Test
    void resolvesEveryRequiredKey() {
        List<String> requiredKeys = List.of(
                "help.description",
                "help.title",
                "help.body",
                "ping.description",
                "ping.reply",
                "error.unknown_command",
                "error.generic",
                "error.data_source_busy",
                "link.description",
                "link.option.account",
                "link.success",
                "link.invalid_input",
                "link.vanity_unsupported",
                "link.no_matches",
                "unlink.description",
                "unlink.success",
                "unlink.not_linked",
                "profile.description",
                "profile.option.user",
                "profile.not_linked_self",
                "profile.not_linked_other",
                "profile.no_matches",
                "profile.title",
                "profile.field.rank",
                "profile.rank_value",
                "profile.rank_unranked",
                "profile.rank_unknown_tier",
                "profile.field.recent",
                "profile.recent_value",
                "profile.field.top_heroes",
                "profile.hero_line",
                "profile.hero_unknown",
                "profile.no_recent_heroes",
                "profile.footer",
                "meta.description",
                "meta.option.rank",
                "meta.rank.1",
                "meta.rank.2",
                "meta.rank.3",
                "meta.rank.4",
                "meta.rank.5",
                "meta.rank.6",
                "meta.rank.7",
                "meta.rank.8",
                "meta.rank.9",
                "meta.rank.10",
                "meta.rank.11",
                "meta.all_ranks",
                "meta.title",
                "meta.tier.s",
                "meta.tier.a",
                "meta.tier.b",
                "meta.tier.c",
                "meta.tier.d",
                "meta.hero_entry",
                "meta.hero_unknown",
                "meta.footer",
                "meta.no_data",
                "build.description",
                "build.option.hero",
                "build.title",
                "build.phase",
                "build.item_entry",
                "build.item_unknown",
                "build.abilities",
                "build.ability_separator",
                "build.ability_matches",
                "build.footer",
                "build.hero_not_found",
                "build.no_data",
                "counter.description",
                "counter.option.hero",
                "counter.title",
                "counter.field.counters",
                "counter.field.items",
                "counter.field.favorable",
                "counter.matchup_entry",
                "counter.item_entry",
                "counter.hero_unknown",
                "counter.footer",
                "counter.hero_not_found",
                "counter.no_data"
        );

        for (String key : requiredKeys) {
            assertThat(messages.get(key)).isNotBlank();
        }
    }

    @Test
    void formatsParameterizedMessages() {
        assertThat(messages.get("ping.reply", "42")).isEqualTo("Pong! 42 ms");
        assertThat(messages.get("error.generic", "abc-123")).isEqualTo("Something went wrong. Error id: abc-123");
    }

    @Test
    void failsFastOnMissingKey() {
        assertThatThrownBy(() -> messages.get("does.not.exist"))
                .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void catalogFileIsWellFormed() throws IOException {
        ClassPathResource resource = new ClassPathResource("messages.properties");
        Properties properties = PropertiesLoaderUtils.loadProperties(new EncodedResource(resource, StandardCharsets.UTF_8));

        assertThat(properties).isNotEmpty();
        for (String key : properties.stringPropertyNames()) {
            assertThat(key).matches(KEY_PATTERN);
            assertThat(properties.getProperty(key)).isNotBlank();
        }
    }

    @Test
    void registersHelpAndPingAsSlashCommands() {
        List<String> commandNames = commands.stream().map(SlashCommand::name).toList();
        assertThat(commandNames).contains("help", "ping", "link", "unlink", "profile", "meta", "build", "counter");
    }
}
