package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.CompareReport;
import sntiago05.rejuv.domain.MetricGap;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.CompareService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.NoMatchHistoryException;
import sntiago05.rejuv.services.NotEnoughGamesException;
import sntiago05.rejuv.services.ProfileService;

class CompareCommandTest {

    private static final long CALLER_ID = 42L;
    private static final long ACCOUNT_ID = 10001L;

    private CompareService compareService;
    private ProfileService profileService;
    private Messages messages;
    private AssetImages assetImages;
    private IconCatalog iconCatalog;
    private CompareCommand command;

    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        compareService = mock(CompareService.class);
        profileService = mock(ProfileService.class);
        messages = MessagesFixture.english();
        assetImages = mock(AssetImages.class);
        iconCatalog = new IconCatalog();

        command = new CompareCommand(compareService, profileService, messages, assetImages, iconCatalog);

        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);

        edit = (WebhookMessageEditAction<Message>) mock(WebhookMessageEditAction.class);
    }

    private SlashCommandInteractionEvent event(String hero) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);

        if (hero != null) {
            OptionMapping heroOpt = mock(OptionMapping.class);
            when(heroOpt.getAsString()).thenReturn(hero);
            when(event.getOption(CommandSupport.HERO_OPTION)).thenReturn(heroOpt);
        }

        return event;
    }

    private ReplyCallbackAction replyFor(SlashCommandInteractionEvent event) {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(anyBoolean())).thenReturn(reply);
        return reply;
    }

    private ReplyCallbackAction deferFor(SlashCommandInteractionEvent event) {
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);
        return defer;
    }

    private InteractionHook hookFor(SlashCommandInteractionEvent event) {
        InteractionHook hook = mock(InteractionHook.class);
        when(event.getHook()).thenReturn(hook);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(edit);
        return hook;
    }

    private MessageEmbed capturedEmbed(InteractionHook hook) {
        ArgumentCaptor<MessageEmbed> captor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(captor.capture());
        return captor.getValue();
    }

    @Test
    void nameIsCompareWithOptionalHeroOption() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("compare");
        assertThat(data.getName()).isEqualTo("compare");
        assertThat(data.getDescription()).isEqualTo(messages.get("compare.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);

        List<OptionData> options = data.getOptions();
        assertThat(options).hasSize(1);

        OptionData heroOption = options.getFirst();
        assertThat(heroOption.getName()).isEqualTo("hero");
        assertThat(heroOption.getType()).isEqualTo(OptionType.STRING);
        assertThat(heroOption.isRequired()).isFalse();
        assertThat(heroOption.getDescription()).isEqualTo(messages.get("compare.option.hero"));
        assertThat(heroOption.getMaxLength()).isEqualTo(50);
    }

    @Test
    void notLinkedRepliesEphemerallyWithoutDeferralOrServiceCall() {
        SlashCommandInteractionEvent event = event("Infernus");
        ReplyCallbackAction reply = replyFor(event);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        command.handle(event);

        verify(event).reply(messages.get("compare.not_linked"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(compareService, never()).compare(anyLong(), anyString());
    }

    @Test
    void happyPathEmbedWithFieldsThumbnailAndIcons() {
        SlashCommandInteractionEvent event = event("Infernus");
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(assetImages.heroCardUrl(1)).thenReturn(Optional.of("https://assets-bucket.deadlock-api.com/inferno_card.png"));
        iconCatalog.replace(Map.of(IconCatalog.heroEmojiName(1), "<:h_1:111>"));

        MetricGap gap1 = new MetricGap("net_worth_per_min", 1500.0, 1200.0, 1000.0, 25.0, true);
        MetricGap gap2 = new MetricGap("deaths", 4.0, 5.0, 6.0, -20.0, true);
        CompareReport report = new CompareReport(1, "Infernus", 20, 11, 7, List.of(gap1, gap2));
        when(compareService.compare(ACCOUNT_ID, "Infernus")).thenReturn(report);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets-bucket.deadlock-api.com/inferno_card.png");
        assertThat(embed.getTitle()).isEqualTo(messages.get("compare.title", "Infernus", 20));

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(1);
        MessageEmbed.Field field = fields.getFirst();
        assertThat(field.isInline()).isFalse();
        assertThat(field.getName()).isEqualTo("<:h_1:111> " + messages.get("compare.field.metrics"));

        String line1 = messages.get("compare.line", messages.get("compare.metric.net_worth_per_min"), "1500.0", "1200.0", "+25.0%")
                + messages.get("compare.line_rank", "1000.0");
        String line2 = messages.get("compare.line", messages.get("compare.metric.deaths"), "4.0", "5.0", "-20.0%")
                + messages.get("compare.line_rank", "6.0");

        assertThat(field.getValue()).isEqualTo(line1 + "\n" + line2);
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("compare.footer", messages.get("meta.rank.7")));
    }

    @Test
    void textOnlyEmbedWithAnEmptyIconCatalog() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(assetImages.heroCardUrl(1)).thenReturn(Optional.empty());
        // iconCatalog is empty
        iconCatalog.replace(Map.of());

        MetricGap gap1 = new MetricGap("kills", 8.0, 10.0, null, -20.0, false);
        CompareReport report = new CompareReport(1, "Infernus", 5, 11, null, List.of(gap1));
        when(compareService.compare(ACCOUNT_ID, "")).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getThumbnail()).isNull();
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getFields()).hasSize(1);

        MessageEmbed.Field field = embed.getFields().getFirst();
        assertThat(field.getName()).isEqualTo(messages.get("compare.field.metrics"));
        assertThat(field.getName()).doesNotContain("<:");
        assertThat(field.getValue()).doesNotContain("<:");

        String expectedLine = messages.get("compare.line", messages.get("compare.metric.kills"), "8.0", "10.0", "-20.0%");
        assertThat(field.getValue()).isEqualTo(expectedLine);
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("compare.footer", messages.get("meta.all_ranks")));
    }

    @Test
    void emptyGapsRepliesWithNoDataMessage() {
        SlashCommandInteractionEvent event = event("Infernus");
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));

        CompareReport report = new CompareReport(1, "Infernus", 5, 11, null, List.of());
        when(compareService.compare(ACCOUNT_ID, "Infernus")).thenReturn(report);

        command.handle(event);

        verify(hook).editOriginal(messages.get("compare.no_data"));
    }

    @Test
    void heroNotFoundEditsWithHeroNotFoundMessage() {
        SlashCommandInteractionEvent event = event("UnknownHero");
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(compareService.compare(ACCOUNT_ID, "UnknownHero")).thenThrow(new HeroNotFoundException("UnknownHero"));

        command.handle(event);

        verify(hook).editOriginal(messages.get("compare.hero_not_found"));
    }

    @Test
    void noMatchHistoryEditsWithNoMatchesMessage() {
        SlashCommandInteractionEvent event = event("Infernus");
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(compareService.compare(ACCOUNT_ID, "Infernus")).thenThrow(new NoMatchHistoryException(ACCOUNT_ID));

        command.handle(event);

        verify(hook).editOriginal(messages.get("compare.no_matches"));
    }

    @Test
    void notEnoughGamesEditsWithNotEnoughGamesMessage() {
        SlashCommandInteractionEvent event = event("Infernus");
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(compareService.compare(ACCOUNT_ID, "Infernus")).thenThrow(new NotEnoughGamesException(3, 5));

        command.handle(event);

        verify(hook).editOriginal(messages.get("compare.not_enough_games", 5));
    }

    @Test
    void deadlockApiUnavailableEditsWithDataSourceBusyMessage() {
        SlashCommandInteractionEvent event = event("Infernus");
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(compareService.compare(ACCOUNT_ID, "Infernus")).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        verify(hook).editOriginal(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedExceptionCallsHandleUnexpectedError() {
        SlashCommandInteractionEvent event = event("Infernus");
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(compareService.compare(ACCOUNT_ID, "Infernus")).thenThrow(new RuntimeException("failed"));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).startsWith("Something went wrong. Error id:");
    }

    @Test
    void everyCompareKeyResolvesInCatalog() {
        List<String> keys = List.of(
                "compare.description",
                "compare.option.hero",
                "compare.not_linked",
                "compare.hero_not_found",
                "compare.no_matches",
                "compare.not_enough_games",
                "compare.no_data",
                "compare.title",
                "compare.field.metrics",
                "compare.metric.net_worth_per_min",
                "compare.metric.kills",
                "compare.metric.deaths",
                "compare.metric.assists",
                "compare.metric.last_hits",
                "compare.metric.denies",
                "compare.line",
                "compare.line_rank",
                "compare.footer"
        );

        for (String key : keys) {
            assertThat(messages.get(key)).isNotBlank();
        }
    }
}
