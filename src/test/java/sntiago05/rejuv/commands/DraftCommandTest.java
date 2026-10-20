package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.services.DraftService;
import sntiago05.rejuv.services.DraftService.DraftRequest;
import sntiago05.rejuv.services.DraftService.DraftResult;
import sntiago05.rejuv.services.DraftService.PickSuggestion;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class DraftCommandTest {

    private static final long CALLER_ID = 333L;

    private final Messages messages = MessagesFixture.english();
    private DraftService draftService;
    private HeroResolver heroResolver;
    private GameAssets gameAssets;
    private IconCatalog iconCatalog;
    private DraftCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        draftService = mock(DraftService.class);
        heroResolver = mock(HeroResolver.class);
        gameAssets = mock(GameAssets.class);
        iconCatalog = new IconCatalog();
        command = new DraftCommand(draftService, heroResolver, gameAssets, messages, iconCatalog);
        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
    }

    @Test
    void nameIsDraftWithOptions() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("draft");
        assertThat(data.getName()).isEqualTo("draft");
        assertThat(data.getDescription()).isEqualTo(messages.get("draft.description"));
        assertThat(data.getOptions()).hasSize(3);

        OptionData alliesOption = data.getOptions().get(0);
        assertThat(alliesOption.getName()).isEqualTo("allies");
        assertThat(alliesOption.getType()).isEqualTo(OptionType.STRING);
        assertThat(alliesOption.isRequired()).isTrue();

        OptionData enemiesOption = data.getOptions().get(1);
        assertThat(enemiesOption.getName()).isEqualTo("enemies");
        assertThat(enemiesOption.getType()).isEqualTo(OptionType.STRING);
        assertThat(enemiesOption.isRequired()).isTrue();

        OptionData rankOption = data.getOptions().get(2);
        assertThat(rankOption.getName()).isEqualTo("rank");
        assertThat(rankOption.getType()).isEqualTo(OptionType.INTEGER);
        assertThat(rankOption.isRequired()).isFalse();
        assertThat(rankOption.getChoices()).hasSize(11);
    }

    @Test
    void rejectsTooManyAllies() {
        SlashCommandInteractionEvent event = event("h1, h2, h3, h4, h5, h6", "e1", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("draft.error.too_many_allies"));
    }

    @Test
    void rejectsTooManyEnemies() {
        SlashCommandInteractionEvent event = event("h1", "e1, e2, e3, e4, e5, e6, e7", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("draft.error.too_many_enemies"));
    }

    @Test
    void rejectsUnknownHeroInAlliesOrEnemies() {
        SlashCommandInteractionEvent event = event("Infernus, InvalidHero", "Seven", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(new HeroAsset(1, "infernus", "Infernus", null)));
        when(heroResolver.resolve("InvalidHero")).thenReturn(Optional.empty());

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("counter.hero_not_found"));
    }

    @Test
    void rejectsDuplicateHeroInSameTeam() {
        SlashCommandInteractionEvent event = event("Infernus, Infernus", "Seven", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(new HeroAsset(1, "infernus", "Infernus", null)));
        when(heroResolver.resolve("Seven")).thenReturn(Optional.of(new HeroAsset(2, "Seven", "seven", null)));

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("draft.error.duplicate_hero"));
    }

    @Test
    void draftWithThreeAlliesAndThreeEnemiesGivesTop3Suggestions() {
        SlashCommandInteractionEvent event = event("Infernus, Ivy, Seven", "Haze, Wraith, Abrams", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(new HeroAsset(1, "infernus", "Infernus", null)));
        when(heroResolver.resolve("Ivy")).thenReturn(Optional.of(new HeroAsset(2, "Ivy", "ivy", null)));
        when(heroResolver.resolve("Seven")).thenReturn(Optional.of(new HeroAsset(3, "Seven", "seven", null)));
        when(heroResolver.resolve("Haze")).thenReturn(Optional.of(new HeroAsset(4, "Haze", "haze", null)));
        when(heroResolver.resolve("Wraith")).thenReturn(Optional.of(new HeroAsset(5, "Wraith", "wraith", null)));
        when(heroResolver.resolve("Abrams")).thenReturn(Optional.of(new HeroAsset(6, "Abrams", "abrams", null)));

        when(gameAssets.heroNames()).thenReturn(Map.of(
                1, "Infernus", 2, "Ivy", 3, "Seven",
                4, "Haze", 5, "Wraith", 6, "Abrams",
                7, "Vindicta", 8, "Bebop", 9, "Dynamo"
        ));

        DraftResult result = new DraftResult(0.55, 3, 3, List.of(
                new PickSuggestion(7, 0.58, 500),
                new PickSuggestion(8, 0.56, 400),
                new PickSuggestion(9, 0.54, 300)
        ), false);
        when(draftService.estimate(new DraftRequest(List.of(1, 2, 3), List.of(4, 5, 6), 7))).thenReturn(result);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getTitle()).isEqualTo(messages.get("draft.title", messages.get("meta.rank.7")));
        assertThat(embed.getDescription()).contains("55.0%");
        assertThat(embed.getDescription()).contains("█");

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(3); // Allies, Enemies, Suggestions
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("draft.field.allies", "3"));
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("draft.field.enemies", "3"));
        assertThat(fields.get(2).getName()).isEqualTo(messages.get("draft.field.suggestions"));
        assertThat(fields.get(2).getValue()).contains("Vindicta", "58.0%", "500 matches");
    }

    @Test
    void draftWithFiveAlliesAndSixEnemiesGivesProbabilityAndNoSuggestions() {
        SlashCommandInteractionEvent event = event("h1, h2, h3, h4, h5", "e1, e2, e3, e4, e5, e6", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        for (int i = 1; i <= 5; i++) {
            when(heroResolver.resolve("h" + i)).thenReturn(Optional.of(new HeroAsset(i, "Hero" + i, "h" + i, null)));
        }
        for (int i = 1; i <= 6; i++) {
            when(heroResolver.resolve("e" + i)).thenReturn(Optional.of(new HeroAsset(10 + i, "Enemy" + i, "e" + i, null)));
        }

        when(gameAssets.heroNames()).thenReturn(Map.of());

        DraftResult result = new DraftResult(0.52, 5, 6, List.of(), false);
        when(draftService.estimate(new DraftRequest(List.of(1, 2, 3, 4, 5), List.of(11, 12, 13, 14, 15, 16), null)))
                .thenReturn(result);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getDescription()).contains("52.0%");
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(2); // Allies and Enemies only, no suggestions field
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("draft.field.allies", "5"));
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("draft.field.enemies", "6"));
    }

    @Test
    void lowConfidenceIncludesWarningInDescription() {
        SlashCommandInteractionEvent event = event("h1", "e1", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("h1")).thenReturn(Optional.of(new HeroAsset(1, "Hero1", "h1", null)));
        when(heroResolver.resolve("e1")).thenReturn(Optional.of(new HeroAsset(2, "Enemy1", "e1", null)));
        when(gameAssets.heroNames()).thenReturn(Map.of());

        DraftResult result = new DraftResult(0.50, 1, 1, List.of(), true);
        when(draftService.estimate(new DraftRequest(List.of(1), List.of(2), null))).thenReturn(result);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getDescription()).contains(messages.get("draft.low_confidence"));
    }

    @Test
    void apiUnavailableRepliesWithBusyMessage() {
        SlashCommandInteractionEvent event = event("h1", "e1", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("h1")).thenReturn(Optional.of(new HeroAsset(1, "Hero1", "h1", null)));
        when(heroResolver.resolve("e1")).thenReturn(Optional.of(new HeroAsset(2, "Enemy1", "e1", null)));
        when(draftService.estimate(any())).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorRepliesWithGenericError() {
        SlashCommandInteractionEvent event = event("h1", "e1", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("h1")).thenThrow(new RuntimeException("crash"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
    }

    private SlashCommandInteractionEvent event(String allies, String enemies, Integer rank) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        if (allies != null) {
            OptionMapping opt = mock(OptionMapping.class);
            when(opt.getAsString()).thenReturn(allies);
            when(event.getOption("allies")).thenReturn(opt);
        }
        if (enemies != null) {
            OptionMapping opt = mock(OptionMapping.class);
            when(opt.getAsString()).thenReturn(enemies);
            when(event.getOption("enemies")).thenReturn(opt);
        }
        if (rank != null) {
            OptionMapping opt = mock(OptionMapping.class);
            when(opt.getAsInt()).thenReturn(rank);
            when(event.getOption("rank")).thenReturn(opt);
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
