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

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import sntiago05.rejuv.domain.BuildItem;
import sntiago05.rejuv.domain.BuildPhase;
import sntiago05.rejuv.domain.HeroBuild;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.BuildService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class BuildCommandTest {

    private static final long CALLER_ID = 222L;

    private final Messages messages = MessagesFixture.english();
    private BuildService buildService;
    private AssetImages assetImages;
    private IconCatalog iconCatalog;
    private BuildCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        buildService = mock(BuildService.class);
        assetImages = mock(AssetImages.class);
        iconCatalog = new IconCatalog();
        command = new BuildCommand(buildService, messages, assetImages, iconCatalog);
        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
    }

    @Test
    void nameIsBuildWithRequiredHeroAndOptionalRankChoices() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("build");
        assertThat(data.getName()).isEqualTo("build");
        assertThat(data.getDescription()).isEqualTo(messages.get("build.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);

        assertThat(data.getOptions()).hasSize(2);

        OptionData heroOption = data.getOptions().get(0);
        assertThat(heroOption.getName()).isEqualTo("hero");
        assertThat(heroOption.getType()).isEqualTo(OptionType.STRING);
        assertThat(heroOption.isRequired()).isTrue();
        assertThat(heroOption.getDescription()).isEqualTo(messages.get("build.option.hero"));
        assertThat(heroOption.getDescription().length()).isBetween(1, 100);
        assertThat(heroOption.getMaxLength()).isEqualTo(50);

        OptionData rankOption = data.getOptions().get(1);
        assertThat(rankOption.getName()).isEqualTo("rank");
        assertThat(rankOption.getType()).isEqualTo(OptionType.INTEGER);
        assertThat(rankOption.isRequired()).isFalse();
        assertThat(rankOption.getDescription()).isEqualTo(messages.get("meta.option.rank"));
        assertThat(rankOption.getDescription().length()).isBetween(1, 100);

        List<Command.Choice> choices = rankOption.getChoices();
        assertThat(choices).hasSize(11);
        for (int tier = 1; tier <= 11; tier++) {
            Command.Choice choice = choices.get(tier - 1);
            assertThat(choice.getAsLong()).isEqualTo(tier);
            assertThat(choice.getName()).isEqualTo(messages.get("meta.rank." + tier));
            assertThat(choice.getName().length()).isBetween(1, 100);
        }
    }

    @Test
    void defersPubliclyAndEditsWithEmbedWithThumbnailAndIcons() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        when(assetImages.heroCardUrl(1)).thenReturn(Optional.of("https://assets-bucket.deadlock-api.com/inferno_card.png"));
        iconCatalog.replace(Map.of(
                "i_1001", "<:i_1001:1111>",
                "i_2001", "<:i_2001:2222>"
        ));

        HeroBuild build = new HeroBuild(1, "Infernus", 7, List.of(
                new BuildPhase(0, List.of(
                        new BuildItem(1001, "Close Range", 500, 50.0, 55.4)
                )),
                new BuildPhase(1, List.of(
                        new BuildItem(2001, "Endurance", 300, 30.0, 52.0)
                ))
        ), List.of("Flame Dash", "Afterburn"), 1200);
        when(buildService.getBuild("Infernus", 7)).thenReturn(build);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets-bucket.deadlock-api.com/inferno_card.png");
        assertThat(embed.getTitle()).isEqualTo(messages.get("build.title", "Infernus", messages.get("meta.rank.7")));

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(3);
        assertThat(fields).noneMatch(MessageEmbed.Field::isInline);

        assertThat(fields.get(0).getName()).isEqualTo(messages.get("build.phase", "1"));
        assertThat(fields.get(0).getValue()).isEqualTo("<:i_1001:1111> " + messages.get("build.item_entry", "Close Range", "50.0", "55.4"));

        assertThat(fields.get(1).getName()).isEqualTo(messages.get("build.phase", "2"));
        assertThat(fields.get(1).getValue()).isEqualTo("<:i_2001:2222> " + messages.get("build.item_entry", "Endurance", "30.0", "52.0"));

        String separator = messages.get("build.ability_separator");
        assertThat(fields.get(2).getName()).isEqualTo(messages.get("build.abilities"));
        assertThat(fields.get(2).getValue()).isEqualTo("1️⃣ Flame Dash" + separator + "2️⃣ Afterburn\n"
                + messages.get("build.ability_matches", "1200"));

        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("build.footer", String.valueOf(BuildService.MIN_ITEM_MATCHES)));
    }

    @Test
    void defersPubliclyAndEditsWithEmbedWithoutThumbnailOrIcons() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        HeroBuild build = new HeroBuild(1, "Infernus", 7, List.of(
                new BuildPhase(0, List.of(new BuildItem(1001, "Close Range", 500, 50.0, 55.4)))
        ), List.of(), 0);
        when(buildService.getBuild("Infernus", 7)).thenReturn(build);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getThumbnail()).isNull();
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getFields().get(0).getValue()).isEqualTo(messages.get("build.item_entry", "Close Range", "50.0", "55.4"));
    }

    @Test
    void titleUsesAllRanksWithoutOption() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        HeroBuild build = new HeroBuild(1, "Infernus", null, List.of(
                new BuildPhase(0, List.of(new BuildItem(1001, "Close Range", 500, 50.0, 55.4)))
        ), List.of(), 0);
        when(buildService.getBuild("Infernus", null)).thenReturn(build);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo(messages.get("build.title", "Infernus", messages.get("meta.all_ranks")));
    }

    @Test
    void heroAndRankAreSentToService() {
        SlashCommandInteractionEvent event = event("Seven", 8);
        deferFor(event);
        hookFor(event);

        HeroBuild build = new HeroBuild(2, "Seven", 8, List.of(
                new BuildPhase(0, List.of(new BuildItem(1001, "Close Range", 100, 20.0, 50.0)))
        ), List.of(), 0);
        when(buildService.getBuild("Seven", 8)).thenReturn(build);

        command.handle(event);

        verify(buildService).getBuild("Seven", 8);
    }

    @Test
    void omitsAbilityFieldWhenEmpty() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        HeroBuild build = new HeroBuild(1, "Infernus", null, List.of(
                new BuildPhase(0, List.of(new BuildItem(1001, "Close Range", 500, 50.0, 55.4)))
        ), List.of(), 0);
        when(buildService.getBuild("Infernus", null)).thenReturn(build);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields()).hasSize(1);
        assertThat(embed.getFields().get(0).getName()).isEqualTo(messages.get("build.phase", "1"));
    }

    @Test
    void formatsAbilityOrderWithNumberedBadgesAndBracketsBeyondTen() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        List<String> order = List.of(
                "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11"
        );
        HeroBuild build = new HeroBuild(1, "Infernus", null, List.of(
                new BuildPhase(0, List.of(new BuildItem(1001, "Close Range", 500, 50.0, 55.4)))
        ), order, 500);
        when(buildService.getBuild("Infernus", null)).thenReturn(build);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        String separator = messages.get("build.ability_separator");
        MessageEmbed.Field field = embed.getFields().get(1);
        assertThat(field.getName()).isEqualTo(messages.get("build.abilities"));
        assertThat(field.getValue()).contains("1️⃣ S1" + separator + "2️⃣ S2");
        assertThat(field.getValue()).contains("🔟 S10" + separator + "[11] S11");
        assertThat(field.getValue()).endsWith(messages.get("build.ability_matches", "500"));
    }

    @Test
    void unknownItemUsesItemUnknown() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        HeroBuild build = new HeroBuild(1, "Infernus", null, List.of(
                new BuildPhase(0, List.of(new BuildItem(9999, null, 100, 10.0, 45.0)))
        ), List.of(), 0);
        when(buildService.getBuild("Infernus", null)).thenReturn(build);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields().get(0).getValue()).isEqualTo(
                messages.get("build.item_entry", messages.get("build.item_unknown", "9999"), "10.0", "45.0")
        );
    }

    @Test
    void emptyBuildEditsWithNoData() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(buildService.getBuild("Infernus", null))
                .thenReturn(new HeroBuild(1, "Infernus", null, List.of(), List.of(), 0));

        command.handle(event);

        verify(hook).editOriginal(messages.get("build.no_data"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void heroNotFoundEditsWithoutEchoingInput() {
        SlashCommandInteractionEvent event = event("typoHero", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(buildService.getBuild("typoHero", null))
                .thenThrow(new HeroNotFoundException("typoHero"));

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("build.hero_not_found"));
        assertThat(text.getValue()).doesNotContain("typoHero");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(buildService.getBuild("Infernus", null))
                .thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        verify(hook).editOriginal(messages.get("error.data_source_busy"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(buildService.getBuild("Infernus", null)).thenThrow(new RuntimeException("unexpected failure"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    private SlashCommandInteractionEvent event(String hero, Integer rank) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        if (hero != null) {
            OptionMapping heroOption = mock(OptionMapping.class);
            when(heroOption.getAsString()).thenReturn(hero);
            when(event.getOption("hero")).thenReturn(heroOption);
        }
        if (rank != null) {
            OptionMapping rankOption = mock(OptionMapping.class);
            when(rankOption.getAsInt()).thenReturn(rank);
            when(event.getOption("rank")).thenReturn(rankOption);
        }
        return event;
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

    private MessageEmbed capturedEmbed(InteractionHook hook) {
        ArgumentCaptor<MessageEmbed> embed = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(embed.capture());
        verify(edit).queue();
        return embed.getValue();
    }
}
