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

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    @Autowired
    private Messages messages;

    @Autowired
    private List<SlashCommand> commands;

    @Test
    void resolvesEveryRequiredKey() {
        List<String> requiredKeys = List.of(
                "help.description",
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
                "profile.footer"
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
        assertThat(commandNames).contains("help", "ping", "link", "unlink", "profile");
    }
}
