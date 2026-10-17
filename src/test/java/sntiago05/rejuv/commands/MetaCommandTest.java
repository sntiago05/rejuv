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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import sntiago05.rejuv.domain.HeroTier;
import sntiago05.rejuv.domain.MetaHero;
import sntiago05.rejuv.domain.MetaTierList;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.MetaService;

class MetaCommandTest {

    private static final long CALLER_ID = 222L;

    private final Messages messages = MessagesFixture.english();
    private MetaService metaService;
    private IconCatalog iconCatalog;
    private MetaCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        metaService = mock(MetaService.class);
        iconCatalog = new IconCatalog();
        command = new MetaCommand(metaService, messages, iconCatalog);
        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
    }

    @Test
    void nameIsMetaWithOptionalRankChoices() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("meta");
        assertThat(data.getName()).isEqualTo("meta");
        assertThat(data.getDescription()).isEqualTo(messages.get("meta.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);

        assertThat(data.getOptions()).hasSize(1);
        OptionData option = data.getOptions().get(0);
        assertThat(option.getName()).isEqualTo("rank");
        assertThat(option.getType()).isEqualTo(OptionType.INTEGER);
        assertThat(option.isRequired()).isFalse();
        assertThat(option.getDescription()).isEqualTo(messages.get("meta.option.rank"));
        assertThat(option.getDescription().length()).isBetween(1, 100);

        List<Command.Choice> choices = option.getChoices();
        assertThat(choices).hasSize(11);
        for (int tier = 1; tier <= 11; tier++) {
            Command.Choice choice = choices.get(tier - 1);
            assertThat(choice.getAsLong()).isEqualTo(tier);
            assertThat(choice.getName()).isEqualTo(messages.get("meta.rank." + tier));
            assertThat(choice.getName().length()).isBetween(1, 100);
        }
    }

    @Test
    void defersPubliclyAndEditsWithEmbedWithIcons() {
        SlashCommandInteractionEvent event = event(null);
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        iconCatalog.replace(Map.of(
                "h_1", "<:h_1:1001>",
                "h_2", "<:h_2:1002>"
        ));

        MetaTierList tierList = new MetaTierList(null, 100, List.of(
                new MetaHero(1, "Infernus", 500, 55.4, HeroTier.S),
                new MetaHero(2, "Seven", 300, 49.5, HeroTier.B),
                new MetaHero(3, "Vindicta", 200, 45.0, HeroTier.D)
        ));
        when(metaService.getTierList(null)).thenReturn(tierList);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getTitle()).isEqualTo(messages.get("meta.title", messages.get("meta.all_ranks")));

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(3);
        assertThat(fields).noneMatch(MessageEmbed.Field::isInline);
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("meta.tier.s"));
        assertThat(fields.get(0).getValue()).isEqualTo("<:h_1:1001> " + messages.get("meta.hero_entry", "Infernus", "55.4"));
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("meta.tier.b"));
        assertThat(fields.get(1).getValue()).isEqualTo("<:h_2:1002> " + messages.get("meta.hero_entry", "Seven", "49.5"));
        assertThat(fields.get(2).getName()).isEqualTo(messages.get("meta.tier.d"));
        assertThat(fields.get(2).getValue()).isEqualTo(messages.get("meta.hero_entry", "Vindicta", "45.0"));

        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("meta.footer", "100"));
    }

    @Test
    void defersPubliclyAndEditsWithEmbedWithoutIcons() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        MetaTierList tierList = new MetaTierList(null, 100, List.of(
                new MetaHero(1, "Infernus", 500, 55.4, HeroTier.S)
        ));
        when(metaService.getTierList(null)).thenReturn(tierList);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail()).isNull();
        assertThat(embed.getFields().get(0).getValue()).isEqualTo(messages.get("meta.hero_entry", "Infernus", "55.4"));
    }

    @Test
    void veryLongTierNeverExceeds1024CharsPerFieldAndEmbedUnder6000() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        List<MetaHero> manyHeroes = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            manyHeroes.add(new MetaHero(i, "HeroLongName" + i, 100 + i, 55.0, HeroTier.S));
        }
        when(metaService.getTierList(null)).thenReturn(new MetaTierList(null, 100, manyHeroes));

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        for (MessageEmbed.Field field : embed.getFields()) {
            assertThat(field.getValue().length()).isLessThanOrEqualTo(1024);
        }
        assertThat(embed.getLength()).isLessThanOrEqualTo(6000);
    }

    @Test
    void titleUsesAllRanksWithoutOption() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        MetaTierList tierList = new MetaTierList(null, 100, List.of(
                new MetaHero(1, "Infernus", 200, 54.0, HeroTier.S)
        ));
        when(metaService.getTierList(null)).thenReturn(tierList);

        command.handle(event);

        verify(metaService).getTierList(null);
        assertThat(capturedEmbed(hook).getTitle()).isEqualTo(messages.get("meta.title", messages.get("meta.all_ranks")));
    }

    @Test
    void rankOptionIsPassedToService() {
        SlashCommandInteractionEvent event = event(7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        MetaTierList tierList = new MetaTierList(7, 100, List.of(
                new MetaHero(1, "Infernus", 200, 54.0, HeroTier.S)
        ));
        when(metaService.getTierList(7)).thenReturn(tierList);

        command.handle(event);

        verify(metaService).getTierList(7);
        assertThat(capturedEmbed(hook).getTitle()).isEqualTo(messages.get("meta.title", messages.get("meta.rank.7")));
    }

    @Test
    void unknownHeroUsesHeroUnknown() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        MetaTierList tierList = new MetaTierList(null, 100, List.of(
                new MetaHero(99, null, 150, 52.0, HeroTier.A)
        ));
        when(metaService.getTierList(null)).thenReturn(tierList);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields().get(0).getValue())
                .isEqualTo(messages.get("meta.hero_entry", messages.get("meta.hero_unknown", "99"), "52.0"));
    }

    @Test
    void emptyListEditsWithNoData() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(metaService.getTierList(null)).thenReturn(new MetaTierList(null, 100, List.of()));

        command.handle(event);

        verify(hook).editOriginal(messages.get("meta.no_data"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(metaService.getTierList(null)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        verify(hook).editOriginal(messages.get("error.data_source_busy"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        SlashCommandInteractionEvent event = event(null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(metaService.getTierList(null)).thenThrow(new RuntimeException("unexpected crash"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    private SlashCommandInteractionEvent event(Integer rankOptionValue) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        if (rankOptionValue != null) {
            OptionMapping option = mock(OptionMapping.class);
            when(option.getAsInt()).thenReturn(rankOptionValue);
            when(event.getOption("rank")).thenReturn(option);
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
