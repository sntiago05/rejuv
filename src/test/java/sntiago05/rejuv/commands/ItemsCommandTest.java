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
import net.dv8tion.jda.api.interactions.commands.Command;
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
import sntiago05.rejuv.domain.ItemComparison;
import sntiago05.rejuv.domain.ItemsReport;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.ItemsService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.NoRankException;
import sntiago05.rejuv.services.PremiumFeatureGate;
import sntiago05.rejuv.services.ProfileService;

class ItemsCommandTest {

    private static final long CALLER_ID = 222L;
    private static final long ACCOUNT_ID = 10001L;

    private final Messages messages = MessagesFixture.english();
    private ItemsService itemsService;
    private ProfileService profileService;
    private PremiumFeatureGate premiumFeatureGate;
    private AssetImages assetImages;
    private IconCatalog iconCatalog;
    private ItemsCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        itemsService = mock(ItemsService.class);
        profileService = mock(ProfileService.class);
        premiumFeatureGate = mock(PremiumFeatureGate.class);
        assetImages = mock(AssetImages.class);
        iconCatalog = new IconCatalog();
        command = new ItemsCommand(itemsService, profileService, premiumFeatureGate, messages, assetImages, iconCatalog);
        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);

        edit = (WebhookMessageEditAction<Message>) mock(WebhookMessageEditAction.class);
    }

    private SlashCommandInteractionEvent event(String hero, Integer rank) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);

        if (hero != null) {
            OptionMapping heroOpt = mock(OptionMapping.class);
            when(heroOpt.getAsString()).thenReturn(hero);
            when(event.getOption(CommandSupport.HERO_OPTION)).thenReturn(heroOpt);
        }

        if (rank != null) {
            OptionMapping rankOpt = mock(OptionMapping.class);
            when(rankOpt.getAsInt()).thenReturn(rank);
            when(event.getOption(CommandSupport.RANK_OPTION)).thenReturn(rankOpt);
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
    void nameIsItemsWithRequiredHeroAndOptionalRankChoices() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("items");
        assertThat(data.getName()).isEqualTo("items");
        assertThat(data.getDescription()).isEqualTo(messages.get("items.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);

        List<OptionData> options = data.getOptions();
        assertThat(options).hasSize(2);

        OptionData heroOption = options.get(0);
        assertThat(heroOption.getName()).isEqualTo("hero");
        assertThat(heroOption.getType()).isEqualTo(OptionType.STRING);
        assertThat(heroOption.isRequired()).isTrue();
        assertThat(heroOption.getDescription()).isEqualTo(messages.get("items.option.hero"));
        assertThat(heroOption.getMaxLength()).isEqualTo(50);

        OptionData rankOption = options.get(1);
        assertThat(rankOption.getName()).isEqualTo("rank");
        assertThat(rankOption.getType()).isEqualTo(OptionType.INTEGER);
        assertThat(rankOption.isRequired()).isFalse();
        assertThat(rankOption.getDescription()).isEqualTo(messages.get("meta.option.rank"));

        List<Command.Choice> choices = rankOption.getChoices();
        assertThat(choices).hasSize(11);
        for (int tier = 1; tier <= 11; tier++) {
            Command.Choice choice = choices.get(tier - 1);
            assertThat(choice.getAsLong()).isEqualTo(tier);
            assertThat(choice.getName()).isEqualTo(messages.get("meta.rank." + tier));
        }
    }

    @Test
    void notLinkedUserGetsEphemeralRefusalWithoutDeferralOrApiCall() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        ReplyCallbackAction reply = replyFor(event);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        command.handle(event);

        verify(event).reply(messages.get("items.not_linked"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(premiumFeatureGate, never()).allowed(anyLong());
        verify(itemsService, never()).compare(anyLong(), anyString(), any());
    }

    @Test
    void notPremiumUserGetsEphemeralRefusalWithoutDeferralOrApiCall() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        ReplyCallbackAction reply = replyFor(event);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(false);

        command.handle(event);

        verify(event).reply(messages.get("items.premium_required"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(itemsService, never()).compare(anyLong(), anyString(), any());
    }

    @Test
    void defersPubliclyAndEditsWithEmbedWithThumbnailAndIcons() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(true);
        when(assetImages.heroCardUrl(1)).thenReturn(Optional.of("https://assets-bucket.deadlock-api.com/inferno_card.png"));
        iconCatalog.replace(Map.of(
                "i_101", "<:i_101:111>",
                "i_102", "<:i_102:222>"
        ));

        ItemComparison under = new ItemComparison(101L, "Warp Stone", 10, 40.0, 100, 50.0, -10.0, 10.5, 12.0);
        ItemComparison out = new ItemComparison(102L, "Leech", 20, 65.0, 200, 50.0, 15.0, null, null);
        ItemsReport report = new ItemsReport(1, "Infernus", 7, List.of(under), List.of(out), 5);
        when(itemsService.compare(ACCOUNT_ID, "Infernus", 7)).thenReturn(report);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets-bucket.deadlock-api.com/inferno_card.png");
        assertThat(embed.getTitle()).isEqualTo(messages.get("items.title", "Infernus", messages.get("meta.rank.7")));

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(2);
        assertThat(fields).noneMatch(MessageEmbed.Field::isInline);

        // Underperforming field
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("items.field.underperforming"));
        String expectedUnder = "<:i_101:111> " + messages.get("items.entry", "Warp Stone", "40.0", 10, "50.0", "-10.0")
                + messages.get("items.entry_buy_time", "10.5", "12.0");
        assertThat(fields.get(0).getValue()).isEqualTo(expectedUnder);

        // Outperforming field
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("items.field.outperforming"));
        String expectedOut = "<:i_102:222> " + messages.get("items.entry", "Leech", "65.0", 20, "50.0", "+15.0");
        assertThat(fields.get(1).getValue()).isEqualTo(expectedOut);

        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("items.footer", ItemsService.MIN_PLAYER_MATCHES, ItemsService.MIN_META_MATCHES));
    }

    @Test
    void defersPubliclyAndEditsWithTextOnlyWhenIconCatalogIsEmpty() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(true);
        when(assetImages.heroCardUrl(1)).thenReturn(Optional.empty());
        iconCatalog.replace(Map.of());

        ItemComparison under = new ItemComparison(101L, "Warp Stone", 10, 40.0, 100, 50.0, -10.0, null, null);
        ItemsReport report = new ItemsReport(1, "Infernus", 7, List.of(under), List.of(), 3);
        when(itemsService.compare(ACCOUNT_ID, "Infernus", 7)).thenReturn(report);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getThumbnail()).isNull();
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getFields()).hasSize(1);
        String text = embed.getFields().getFirst().getValue();
        assertThat(text).doesNotContain("<:");
        assertThat(text).isEqualTo(messages.get("items.entry", "Warp Stone", "40.0", 10, "50.0", "-10.0"));
    }

    @Test
    void emptyResultsRepliesWithNoDataMessage() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(true);

        ItemsReport report = new ItemsReport(1, "Infernus", 7, List.of(), List.of(), 5);
        when(itemsService.compare(ACCOUNT_ID, "Infernus", 7)).thenReturn(report);

        command.handle(event);

        verify(hook).editOriginal(messages.get("items.no_data"));
    }

    @Test
    void heroNotFoundEditsWithHeroNotFoundMessage() {
        SlashCommandInteractionEvent event = event("UnknownHero", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(true);
        when(itemsService.compare(ACCOUNT_ID, "UnknownHero", null)).thenThrow(new HeroNotFoundException("UnknownHero"));

        command.handle(event);

        verify(hook).editOriginal(messages.get("items.hero_not_found"));
    }

    @Test
    void noRankEditsWithNoRankMessage() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(true);
        when(itemsService.compare(ACCOUNT_ID, "Infernus", null)).thenThrow(new NoRankException(ACCOUNT_ID));

        command.handle(event);

        verify(hook).editOriginal(messages.get("items.no_rank"));
    }

    @Test
    void dataSourceBusyEditsWithBusyMessage() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(true);
        when(itemsService.compare(ACCOUNT_ID, "Infernus", 7)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        verify(hook).editOriginal(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedExceptionCallsHandleUnexpectedError() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(premiumFeatureGate.allowed(CALLER_ID)).thenReturn(true);
        when(itemsService.compare(ACCOUNT_ID, "Infernus", 7)).thenThrow(new RuntimeException("database failed"));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(captor.capture());
        assertThat(captor.getValue()).startsWith("Something went wrong. Error id:");
    }

    @Test
    void everyItemsKeyResolvesInCatalog() {
        List<String> keys = List.of(
                "items.description",
                "items.option.hero",
                "items.option.rank",
                "items.not_linked",
                "items.premium_required",
                "items.no_rank",
                "items.hero_not_found",
                "items.no_data",
                "items.title",
                "items.field.underperforming",
                "items.field.outperforming",
                "items.entry",
                "items.entry_buy_time",
                "items.footer"
        );

        for (String key : keys) {
            assertThat(messages.get(key)).isNotBlank();
        }
    }
}
