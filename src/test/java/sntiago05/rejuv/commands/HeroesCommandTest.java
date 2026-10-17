package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
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
import sntiago05.rejuv.domain.HeroPerformance;
import sntiago05.rejuv.domain.HeroPoolSummary;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.HeroPoolService;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.ProfileService;

class HeroesCommandTest {

    private static final long CALLER_ID = 222L;
    private static final long OTHER_ID = 333L;
    private static final long ACCOUNT_ID = 1000001L;

    private final Messages messages = MessagesFixture.english();
    private ProfileService profileService;
    private HeroPoolService heroPoolService;
    private AssetImages assetImages;
    private IconCatalog iconCatalog;
    private HeroesCommand command;
    private User caller;
    private User other;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        heroPoolService = mock(HeroPoolService.class);
        assetImages = mock(AssetImages.class);
        iconCatalog = new IconCatalog();
        command = new HeroesCommand(profileService, heroPoolService, messages, assetImages, iconCatalog);
        caller = user(CALLER_ID, "Caller");
        other = user(OTHER_ID, "Other");
    }

    @Test
    void nameIsHeroesWithOptionalUserOption() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("heroes");
        assertThat(data.getName()).isEqualTo("heroes");
        assertThat(data.getDescription()).isEqualTo(messages.get("heroes.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).hasSize(1);
        OptionData option = data.getOptions().get(0);
        assertThat(option.getName()).isEqualTo("user");
        assertThat(option.getType()).isEqualTo(OptionType.USER);
        assertThat(option.isRequired()).isFalse();
        assertThat(option.getDescription()).isEqualTo(messages.get("heroes.option.user"));
        assertThat(option.getDescription().length()).isBetween(1, 100);
    }

    @Test
    void callerNotLinkedRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        assertImmediateReply(event, messages.get("heroes.not_linked_self"));
    }

    @Test
    void otherUserNotLinkedRepliesWithMention() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.empty());

        String text = assertImmediateReply(event, messages.get("heroes.not_linked_other", other.getAsMention()));
        assertThat(text).contains("<@333>");
    }

    @Test
    void optionUserIsUsedAsTarget() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(heroPoolService.getHeroPool(ACCOUNT_ID)).thenReturn(sampleSummary(7));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(profileService).findLinkedAccountId(OTHER_ID);
        verify(profileService, never()).findLinkedAccountId(CALLER_ID);
        assertThat(capturedEmbed(hook).getTitle()).isEqualTo(messages.get("heroes.title", "Other"));
    }

    @Test
    void successDefersPubliclyAndEditsWithEmbed() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(heroPoolService.getHeroPool(ACCOUNT_ID)).thenReturn(sampleSummary(7));
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo(messages.get("heroes.title", "Caller"));
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(2);
        assertThat(fields).noneMatch(MessageEmbed.Field::isInline);

        assertThat(fields.get(0).getName()).isEqualTo(messages.get("heroes.field.top"));
        assertThat(fields.get(0).getValue()).contains("Infernus - 50 matches, 60% win (+3.2% vs avg) - KDA 3.5 (avg 2.5)");

        assertThat(fields.get(1).getName()).isEqualTo(messages.get("heroes.field.tips"));
        assertThat(fields.get(1).getValue()).contains("Strongest pick: Infernus (+3.2% vs avg)", "Weakest pick: Seven (-2.5% vs avg)");

        assertThat(embed.getFooter().getText()).isEqualTo(
                messages.get("heroes.footer", messages.get("meta.rank.7"), "100"));
    }

    @Test
    void unrankedPlayerShowsAllRanksInFooter() {
        MessageEmbed embed = embedFor(sampleSummary(null));

        assertThat(embed.getFooter().getText()).isEqualTo(
                messages.get("heroes.footer", messages.get("meta.all_ranks"), "100"));
    }

    @Test
    void embedUsesBrandColorTopHeroCardAndHeroIcons() {
        when(assetImages.heroCardUrl(1)).thenReturn(Optional.of("https://assets-bucket.deadlock-api.com/hero1.png"));
        iconCatalog.replace(Map.of(IconCatalog.heroEmojiName(1), "<:h_1:111>"));

        MessageEmbed embed = embedFor(sampleSummary(null));

        assertThat(embed.getColor().getRGB() & 0xFFFFFF).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail()).isNotNull();
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets-bucket.deadlock-api.com/hero1.png");
        String top = embed.getFields().get(0).getValue();
        assertThat(top).startsWith("<:h_1:111> Infernus");
        assertThat(top).contains("\nSeven");
    }

    @Test
    void embedWithoutIconsOrCardStaysTextOnly() {
        MessageEmbed embed = embedFor(sampleSummary(null));

        assertThat(embed.getThumbnail()).isNull();
        assertThat(embed.getFields().get(0).getValue()).startsWith("Infernus");
    }

    @Test
    void nullDeltaLineUsesVsAverageUnknown() {
        HeroPerformance heroWithoutBenchmark = new HeroPerformance(
                1, "Infernus", 20, 10, 50.0, 2.8, null, null, null);
        HeroPoolSummary summary = new HeroPoolSummary(
                ACCOUNT_ID, null, List.of(heroWithoutBenchmark), null, null);

        MessageEmbed embed = embedFor(summary);

        assertThat(embed.getFields().get(0).getValue())
                .contains(messages.get("heroes.vs_average_unknown"))
                .contains("Infernus - 20 matches, 50% win (no benchmark) - KDA 2.8");
    }

    @Test
    void nullBenchmarkKdaShowsKdaWithoutAverage() {
        HeroPerformance heroWithoutAvgKda = new HeroPerformance(
                1, "Infernus", 20, 12, 60.0, 3.2, 50.0, 10.0, null);
        HeroPoolSummary summary = new HeroPoolSummary(
                ACCOUNT_ID, null, List.of(heroWithoutAvgKda), null, null);

        MessageEmbed embed = embedFor(summary);

        assertThat(embed.getFields().get(0).getValue())
                .contains("Infernus - 20 matches, 60% win (+10.0% vs avg) - KDA 3.2")
                .doesNotContain("(avg");
    }

    @Test
    void unknownHeroNameUsesHeroUnknownFallback() {
        HeroPerformance unknownHero = new HeroPerformance(
                99, null, 10, 5, 50.0, 2.0, 50.0, 0.0, 2.0);
        HeroPoolSummary summary = new HeroPoolSummary(
                ACCOUNT_ID, null, List.of(unknownHero), unknownHero, unknownHero);

        MessageEmbed embed = embedFor(summary);

        assertThat(embed.getFields().get(0).getValue()).contains("Hero #99");
        assertThat(embed.getFields().get(1).getValue()).contains("Strongest pick: Hero #99 (+0.0% vs avg)");
    }

    @Test
    void tipsVariants() {
        HeroPerformance best = new HeroPerformance(1, "Infernus", 20, 16, 80.0, 4.0, 50.0, 30.0, 2.5);
        HeroPerformance weakest = new HeroPerformance(2, "Seven", 20, 4, 20.0, 1.2, 50.0, -30.0, 2.5);

        // Both present
        HeroPoolSummary both = new HeroPoolSummary(ACCOUNT_ID, 1, List.of(best), best, weakest);
        assertThat(embedFor(both).getFields().get(1).getValue())
                .isEqualTo(messages.get("heroes.tip_best", "Infernus", "+30.0") + "\n"
                        + messages.get("heroes.tip_weakest", "Seven", "-30.0"));

        // Only best present
        HeroPoolSummary onlyBest = new HeroPoolSummary(ACCOUNT_ID, 1, List.of(best), best, null);
        assertThat(embedFor(onlyBest).getFields().get(1).getValue())
                .isEqualTo(messages.get("heroes.tip_best", "Infernus", "+30.0"));

        // Only weakest present
        HeroPoolSummary onlyWeakest = new HeroPoolSummary(ACCOUNT_ID, 1, List.of(weakest), null, weakest);
        assertThat(embedFor(onlyWeakest).getFields().get(1).getValue())
                .isEqualTo(messages.get("heroes.tip_weakest", "Seven", "-30.0"));

        // Neither present
        HeroPoolSummary neither = new HeroPoolSummary(ACCOUNT_ID, 1, List.of(best), null, null);
        assertThat(embedFor(neither).getFields().get(1).getValue())
                .isEqualTo(messages.get("heroes.tip_none"));
    }

    @Test
    void emptyTopHeroesEditsWithNoMatchesMessage() {
        when(heroPoolService.getHeroPool(ACCOUNT_ID)).thenReturn(
                new HeroPoolSummary(ACCOUNT_ID, null, List.of(), null, null));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("heroes.no_matches"));
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        when(heroPoolService.getHeroPool(ACCOUNT_ID))
                .thenThrow(new DeadlockApiUnavailableException("busy", Duration.ofSeconds(5), null));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        when(heroPoolService.getHeroPool(ACCOUNT_ID)).thenThrow(new RuntimeException("boom"));

        assertThat(editedTextForLinkedCaller()).startsWith("Something went wrong. Error id: ");
    }

    @Test
    void allHeroesCatalogKeysResolve() {
        List<String> requiredKeys = List.of(
                "heroes.description",
                "heroes.option.user",
                "heroes.not_linked_self",
                "heroes.not_linked_other",
                "heroes.no_matches",
                "heroes.title",
                "heroes.field.top",
                "heroes.field.tips",
                "heroes.entry",
                "heroes.vs_average",
                "heroes.vs_average_unknown",
                "heroes.kda",
                "heroes.kda_vs_average",
                "heroes.hero_unknown",
                "heroes.tip_best",
                "heroes.tip_weakest",
                "heroes.tip_none",
                "heroes.footer"
        );

        for (String key : requiredKeys) {
            assertThat(messages.get(key)).isNotBlank();
        }
    }

    @Test
    void embedFieldValuesDoNotExceed1024CharactersEvenWithLongHeroNames() {
        String longName = "A".repeat(80);
        List<HeroPerformance> top = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            top.add(new HeroPerformance(i, longName, 100, 60, 60.0, 3.5, 50.0, 10.0, 2.5));
        }
        HeroPerformance best = new HeroPerformance(1, longName, 100, 60, 60.0, 3.5, 50.0, 10.0, 2.5);
        HeroPerformance weakest = new HeroPerformance(2, longName, 100, 40, 40.0, 1.5, 50.0, -10.0, 2.5);
        HeroPoolSummary summary = new HeroPoolSummary(ACCOUNT_ID, 7, top, best, weakest);

        MessageEmbed embed = embedFor(summary);

        for (MessageEmbed.Field field : embed.getFields()) {
            assertThat(field.getValue().length()).isLessThanOrEqualTo(1024);
        }
    }

    private String assertImmediateReply(SlashCommandInteractionEvent event, String expected) {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(event).reply(text.capture());
        assertThat(text.getValue()).isEqualTo(expected);
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(heroPoolService, never()).getHeroPool(anyLong());
        return text.getValue();
    }

    private String editedTextForLinkedCaller() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        deferFor(event);
        InteractionHook hook = hookFor(event);

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
        return text.getValue();
    }

    private MessageEmbed embedFor(HeroPoolSummary summary) {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(heroPoolService.getHeroPool(ACCOUNT_ID)).thenReturn(summary);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        return capturedEmbed(hook);
    }

    private static HeroPoolSummary sampleSummary(Integer rankTier) {
        HeroPerformance hero1 = new HeroPerformance(
                1, "Infernus", 50, 30, 60.0, 3.48, 56.8, 3.2, 2.45);
        HeroPerformance hero2 = new HeroPerformance(
                2, "Seven", 30, 15, 50.0, 2.12, 52.5, -2.5, 2.30);
        return new HeroPoolSummary(ACCOUNT_ID, rankTier, List.of(hero1, hero2), hero1, hero2);
    }

    private MessageEmbed capturedEmbed(InteractionHook hook) {
        ArgumentCaptor<MessageEmbed> embed = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embed.capture());
        verify(edit).queue();
        return embed.getValue();
    }

    private SlashCommandInteractionEvent event(User optionUser) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        if (optionUser != null) {
            OptionMapping option = mock(OptionMapping.class);
            when(option.getAsUser()).thenReturn(optionUser);
            when(event.getOption("user")).thenReturn(option);
        }
        return event;
    }

    private static User user(long id, String name) {
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(id);
        when(user.getEffectiveName()).thenReturn(name);
        when(user.getAsMention()).thenReturn("<@" + id + ">");
        return user;
    }

    private static ReplyCallbackAction deferFor(SlashCommandInteractionEvent event) {
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);
        return defer;
    }

    @SuppressWarnings("unchecked")
    private InteractionHook hookFor(SlashCommandInteractionEvent event) {
        InteractionHook hook = mock(InteractionHook.class);
        edit = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(hook.editOriginalEmbeds(any(MessageEmbed.class))).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        return hook;
    }
}
