package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.LeaderboardEntry;
import sntiago05.rejuv.domain.LinkedMember;
import sntiago05.rejuv.domain.ServerLeaderboard;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.LeaderboardService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class LeaderboardCommandTest {

    private static final long CALLER_ID = 111L;
    private static final String GUILD_NAME = "Test Guild";

    private final Messages messages = MessagesFixture.english();
    private LeaderboardService leaderboardService;
    private AssetImages assetImages;
    private LeaderboardCommand command;
    private Guild guild;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        leaderboardService = mock(LeaderboardService.class);
        assetImages = mock(AssetImages.class);
        command = new LeaderboardCommand(leaderboardService, messages, assetImages);

        guild = mock(Guild.class);
        when(guild.getName()).thenReturn(GUILD_NAME);

        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
    }

    @Test
    void dataHasLeaderboardNameGuildOnlyAndServerSubcommand() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("leaderboard");
        assertThat(data.getName()).isEqualTo("leaderboard");
        assertThat(data.isGuildOnly()).isTrue();
        assertThat(data.getDescription()).isEqualTo(messages.get("leaderboard.description"));
        assertThat(data.getSubcommands()).hasSize(1);

        SubcommandData subcommand = data.getSubcommands().get(0);
        assertThat(subcommand.getName()).isEqualTo("server");
        assertThat(subcommand.getDescription()).isEqualTo(messages.get("leaderboard.server.description"));
    }

    @Test
    void missingGuildRepliesEphemeralGuildOnlyWithoutDeferring() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getGuild()).thenReturn(null);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply(messages.get("leaderboard.guild_only"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
    }

    @Test
    void unknownSubcommandRepliesEphemeralUnknownCommandWithoutDeferring() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getSubcommandName()).thenReturn("unknown");
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        verify(event).reply(messages.get("error.unknown_command"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
    }

    @Test
    void noCandidatesRepliesEmptyAfterPublicDeferral() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(caller);
        when(event.getSubcommandName()).thenReturn("server");
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);

        InteractionHook hook = hookFor(event);
        when(leaderboardService.findCandidates()).thenReturn(List.of());

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();
        verify(hook).editOriginal(messages.get("leaderboard.empty"));
        verify(edit).queue();
    }

    @Test
    void candidatesDatabaseFailureHandlesUnexpectedError() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(caller);
        when(event.getSubcommandName()).thenReturn("server");
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);

        InteractionHook hook = hookFor(event);
        when(leaderboardService.findCandidates()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
        verify(edit).queue();
    }

    @Test
    void respondWithRankedMembersBuildsEmbedWithSanitizedNamesAndCounts() {
        InteractionHook hook = mockHook();
        Map<Long, String> namesByUserId = new LinkedHashMap<>();
        namesByUserId.put(100L, "**bold** @everyone");
        namesByUserId.put(200L, "PlayerTwo");
        namesByUserId.put(300L, "PlayerThree");

        Map<Long, Long> accountIdByUserId = Map.of(100L, 1001L, 200L, 1002L, 300L, 1003L);

        ServerLeaderboard leaderboard = new ServerLeaderboard(
                List.of(
                        new LeaderboardEntry(100L, 1001L, 116, 11, 6, "Eternus"),
                        new LeaderboardEntry(200L, 1002L, 80, 8, 0, "Phantom"),
                        new LeaderboardEntry(300L, 1003L, 50, 5, 0, null)),
                3,
                1);

        when(leaderboardService.buildLeaderboard(any())).thenReturn(leaderboard);
        when(assetImages.rankBadgeUrl(11, 6)).thenReturn(Optional.of("https://assets-bucket.deadlock-api.com/badge.png"));

        command.respond(hook, CALLER_ID, GUILD_NAME, namesByUserId, accountIdByUserId);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        verify(edit).queue();

        MessageEmbed embed = embedCaptor.getValue();
        assertThat(embed.getColor().getRGB() & 0xFFFFFF).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail()).isNotNull();
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets-bucket.deadlock-api.com/badge.png");
        assertThat(embed.getTitle()).isEqualTo("Deadlock leaderboard: Test Guild");
        assertThat(embed.getDescription()).isEqualTo(
                "1. \\*\\*bold\\*\\* @everyone - Eternus 6\n"
                        + "2. PlayerTwo - Phantom\n"
                        + "3. PlayerThree - Rank tier 5");
        assertThat(embed.getFooter()).isNotNull();
        assertThat(embed.getFooter().getText()).isEqualTo("3 ranked, 1 unranked. Source: deadlock-api (cached 10 min)");
    }

    @Test
    void respondWithZeroRankedMembersRepliesEmpty() {
        InteractionHook hook = mockHook();
        Map<Long, String> namesByUserId = Map.of(100L, "PlayerOne");
        Map<Long, Long> accountIdByUserId = Map.of(100L, 1001L);

        ServerLeaderboard leaderboard = new ServerLeaderboard(List.of(), 0, 1);
        when(leaderboardService.buildLeaderboard(any())).thenReturn(leaderboard);

        command.respond(hook, CALLER_ID, GUILD_NAME, namesByUserId, accountIdByUserId);

        verify(hook).editOriginal(messages.get("leaderboard.empty"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void respondWhenServiceThrowsUnavailableRepliesDataSourceBusy() {
        InteractionHook hook = mockHook();
        Map<Long, String> namesByUserId = Map.of(100L, "PlayerOne");
        Map<Long, Long> accountIdByUserId = Map.of(100L, 1001L);

        when(leaderboardService.buildLeaderboard(any()))
                .thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.respond(hook, CALLER_ID, GUILD_NAME, namesByUserId, accountIdByUserId);

        verify(hook).editOriginal(messages.get("error.data_source_busy"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void respondWhenServiceThrowsUnexpectedHandlesErrorWithoutRethrow() {
        InteractionHook hook = mockHook();
        Map<Long, String> namesByUserId = Map.of(100L, "PlayerOne");
        Map<Long, Long> accountIdByUserId = Map.of(100L, 1001L);

        when(leaderboardService.buildLeaderboard(any()))
                .thenThrow(new RuntimeException("unexpected API parse error"));

        assertThatCode(() -> command.respond(hook, CALLER_ID, GUILD_NAME, namesByUserId, accountIdByUserId))
                .doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
        verify(edit).queue();
    }

    @Test
    void descriptionWithTenLongNamesIsWellWithinDiscordLimit() {
        InteractionHook hook = mockHook();
        String longName = "A".repeat(32);
        Map<Long, String> namesByUserId = new LinkedHashMap<>();
        Map<Long, Long> accountIdByUserId = new LinkedHashMap<>();

        List<LeaderboardEntry> entries = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> {
                    long id = (long) i;
                    namesByUserId.put(id, longName);
                    accountIdByUserId.put(id, id * 1000L);
                    return new LeaderboardEntry(id, id * 1000L, 116 - i, 11, 6, "Eternus");
                })
                .toList();

        ServerLeaderboard leaderboard = new ServerLeaderboard(entries, 10, 0);
        when(leaderboardService.buildLeaderboard(any())).thenReturn(leaderboard);

        command.respond(hook, CALLER_ID, GUILD_NAME, namesByUserId, accountIdByUserId);

        ArgumentCaptor<MessageEmbed> embedCaptor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embedCaptor.capture());
        MessageEmbed embed = embedCaptor.getValue();

        assertThat(embed.getDescription()).isNotNull();
        assertThat(embed.getDescription().length()).isLessThanOrEqualTo(4096);
    }

    @Test
    void allLeaderboardMessagesResolveToNonBlankText() {
        List<String> keys = List.of(
                "leaderboard.description",
                "leaderboard.server.description",
                "leaderboard.guild_only",
                "leaderboard.empty",
                "leaderboard.title",
                "leaderboard.entry",
                "leaderboard.rank_value",
                "leaderboard.rank_unknown_tier",
                "leaderboard.footer"
        );

        for (String key : keys) {
            assertThat(messages.get(key)).as(key).isNotBlank();
        }
    }

    @SuppressWarnings("unchecked")
    private InteractionHook mockHook() {
        InteractionHook hook = mock(InteractionHook.class);
        edit = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(edit);
        return hook;
    }

    private InteractionHook hookFor(SlashCommandInteractionEvent event) {
        InteractionHook hook = mockHook();
        when(event.getHook()).thenReturn(hook);
        return hook;
    }
}
