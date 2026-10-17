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
import sntiago05.rejuv.domain.CounterGuide;
import sntiago05.rejuv.domain.CounterItem;
import sntiago05.rejuv.domain.CounterMatchup;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.CounterService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class CounterCommandTest {

    private static final long CALLER_ID = 222L;

    private final Messages messages = MessagesFixture.english();
    private CounterService counterService;
    private AssetImages assetImages;
    private IconCatalog iconCatalog;
    private CounterCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        counterService = mock(CounterService.class);
        assetImages = mock(AssetImages.class);
        iconCatalog = new IconCatalog();
        command = new CounterCommand(counterService, messages, assetImages, iconCatalog);
        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
    }

    @Test
    void nameIsCounterWithRequiredHeroAndOptionalRankChoices() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("counter");
        assertThat(data.getName()).isEqualTo("counter");
        assertThat(data.getDescription()).isEqualTo(messages.get("counter.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);

        assertThat(data.getOptions()).hasSize(2);

        OptionData heroOption = data.getOptions().get(0);
        assertThat(heroOption.getName()).isEqualTo("hero");
        assertThat(heroOption.getType()).isEqualTo(OptionType.STRING);
        assertThat(heroOption.isRequired()).isTrue();
        assertThat(heroOption.getDescription()).isEqualTo(messages.get("counter.option.hero"));
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
                "h_2", "<:h_2:222>",
                "h_3", "<:h_3:333>",
                "i_101", "<:i_101:444>"
        ));

        CounterGuide guide = new CounterGuide(1, "Infernus", 7,
                List.of(new CounterMatchup(2, "Seven", 100, 60.0)),
                List.of(new CounterMatchup(3, "Vindicta", 100, 70.0)),
                List.of(new CounterItem(101L, "Item A", 100, 55.0))
        );
        when(counterService.getCounterGuide("Infernus", 7)).thenReturn(guide);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets-bucket.deadlock-api.com/inferno_card.png");
        assertThat(embed.getTitle()).isEqualTo(messages.get("counter.title", "Infernus", messages.get("meta.rank.7")));

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(3);
        assertThat(fields).noneMatch(MessageEmbed.Field::isInline);

        // In order: counters, items, favorable
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("counter.field.counters"));
        assertThat(fields.get(0).getValue()).isEqualTo("<:h_2:222> " + messages.get("counter.matchup_entry", "Seven", "60.0"));

        assertThat(fields.get(1).getName()).isEqualTo(messages.get("counter.field.items"));
        assertThat(fields.get(1).getValue()).isEqualTo("<:i_101:444> " + messages.get("counter.item_entry", "Item A", "55.0"));

        assertThat(fields.get(2).getName()).isEqualTo(messages.get("counter.field.favorable"));
        assertThat(fields.get(2).getValue()).isEqualTo("<:h_3:333> " + messages.get("counter.matchup_entry", "Vindicta", "70.0"));

        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("counter.footer", "50", "100"));
    }

    @Test
    void defersPubliclyAndEditsWithEmbedWithoutThumbnailOrIcons() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        CounterGuide guide = new CounterGuide(1, "Infernus", 7,
                List.of(new CounterMatchup(2, "Seven", 100, 60.0)),
                List.of(),
                List.of(new CounterItem(101L, "Item A", 100, 55.0))
        );
        when(counterService.getCounterGuide("Infernus", 7)).thenReturn(guide);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getThumbnail()).isNull();
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getFields().get(0).getValue()).isEqualTo(messages.get("counter.matchup_entry", "Seven", "60.0"));
        assertThat(embed.getFields().get(1).getValue()).isEqualTo(messages.get("counter.item_entry", "Item A", "55.0"));
    }

    @Test
    void titleUsesAllRanksWithoutOption() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        CounterGuide guide = new CounterGuide(1, "Infernus", null,
                List.of(new CounterMatchup(2, "Seven", 100, 60.0)),
                List.of(),
                List.of()
        );
        when(counterService.getCounterGuide("Infernus", null)).thenReturn(guide);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo(messages.get("counter.title", "Infernus", messages.get("meta.all_ranks")));
    }

    @Test
    void heroAndRankAreSentToService() {
        SlashCommandInteractionEvent event = event("Seven", 8);
        deferFor(event);
        hookFor(event);

        CounterGuide guide = new CounterGuide(2, "Seven", 8, List.of(), List.of(), List.of());
        when(counterService.getCounterGuide("Seven", 8)).thenReturn(guide);

        command.handle(event);

        verify(counterService).getCounterGuide("Seven", 8);
    }

    @Test
    void omitsSectionsWhenEmpty() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        CounterGuide guide = new CounterGuide(1, "Infernus", null,
                List.of(new CounterMatchup(2, "Seven", 100, 60.0)),
                List.of(),
                List.of()
        );
        when(counterService.getCounterGuide("Infernus", null)).thenReturn(guide);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields()).hasSize(1);
        assertThat(embed.getFields().get(0).getName()).isEqualTo(messages.get("counter.field.counters"));
    }

    @Test
    void unknownHeroUsesHeroUnknown() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        CounterGuide guide = new CounterGuide(1, "Infernus", null,
                List.of(new CounterMatchup(999, null, 100, 55.0)),
                List.of(),
                List.of()
        );
        when(counterService.getCounterGuide("Infernus", null)).thenReturn(guide);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getFields().get(0).getValue()).isEqualTo(
                messages.get("counter.matchup_entry", messages.get("counter.hero_unknown", "999"), "55.0")
        );
    }

    @Test
    void emptyGuideEditsWithNoData() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(counterService.getCounterGuide("Infernus", null))
                .thenReturn(new CounterGuide(1, "Infernus", null, List.of(), List.of(), List.of()));

        command.handle(event);

        verify(hook).editOriginal(messages.get("counter.no_data"));
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void heroNotFoundEditsWithoutEchoingInput() {
        SlashCommandInteractionEvent event = event("typoHero", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(counterService.getCounterGuide("typoHero", null))
                .thenThrow(new HeroNotFoundException("typoHero"));

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("counter.hero_not_found"));
        assertThat(text.getValue()).doesNotContain("typoHero");
        verify(hook, never()).editOriginalEmbeds(any(MessageEmbed.class));
        verify(edit).queue();
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(counterService.getCounterGuide("Infernus", null))
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

        when(counterService.getCounterGuide("Infernus", null)).thenThrow(new RuntimeException("unexpected failure"));

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
