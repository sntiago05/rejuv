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
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.GameAssets;
import sntiago05.rejuv.services.HeroResolver;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.SynergyService;
import sntiago05.rejuv.services.SynergyService.Partner;
import sntiago05.rejuv.services.SynergyService.SynergyResult;

class SynergyCommandTest {

    private static final long CALLER_ID = 222L;

    private final Messages messages = MessagesFixture.english();
    private SynergyService synergyService;
    private HeroResolver heroResolver;
    private GameAssets gameAssets;
    private AssetImages assetImages;
    private IconCatalog iconCatalog;
    private SynergyCommand command;
    private User caller;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        synergyService = mock(SynergyService.class);
        heroResolver = mock(HeroResolver.class);
        gameAssets = mock(GameAssets.class);
        assetImages = mock(AssetImages.class);
        iconCatalog = new IconCatalog();
        command = new SynergyCommand(synergyService, heroResolver, gameAssets, messages, assetImages, iconCatalog);
        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
    }

    @Test
    void nameIsSynergyWithRequiredHeroAndOptionalRankChoices() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("synergy");
        assertThat(data.getName()).isEqualTo("synergy");
        assertThat(data.getDescription()).isEqualTo(messages.get("synergy.description"));
        assertThat(data.getOptions()).hasSize(2);

        OptionData heroOption = data.getOptions().get(0);
        assertThat(heroOption.getName()).isEqualTo("hero");
        assertThat(heroOption.getType()).isEqualTo(OptionType.STRING);
        assertThat(heroOption.isRequired()).isTrue();
        assertThat(heroOption.getDescription()).isEqualTo(messages.get("synergy.option.hero"));

        OptionData rankOption = data.getOptions().get(1);
        assertThat(rankOption.getName()).isEqualTo("rank");
        assertThat(rankOption.getType()).isEqualTo(OptionType.INTEGER);
        assertThat(rankOption.isRequired()).isFalse();
        assertThat(rankOption.getChoices()).hasSize(11);
    }

    @Test
    void defersPubliclyAndBuildsSynergyEmbed() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        HeroAsset infernus = new HeroAsset(1, "infernus", "Infernus", null);
        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(infernus));
        when(assetImages.heroCardUrl(1)).thenReturn(Optional.of("https://assets.deadlock.com/infernus.png"));
        when(gameAssets.heroNames()).thenReturn(Map.of(1, "Infernus", 2, "Seven", 3, "Vindicta"));

        SynergyResult result = new SynergyResult(1,
                List.of(new Partner(2, 0.58, 200)),
                List.of(new Partner(3, 0.42, 150))
        );
        when(synergyService.forHero(1, 7)).thenReturn(result);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        assertThat(embed.getTitle()).isEqualTo(messages.get("synergy.title", "Infernus", messages.get("meta.rank.7")));
        assertThat(embed.getThumbnail().getUrl()).isEqualTo("https://assets.deadlock.com/infernus.png");

        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("synergy.field.best"));
        assertThat(fields.get(0).getValue()).contains("Seven", "58.0%", "200 matches");
        assertThat(fields.get(1).getName()).isEqualTo(messages.get("synergy.field.worst"));
        assertThat(fields.get(1).getValue()).contains("Vindicta", "42.0%", "150 matches");
    }

    @Test
    void heroNotFoundRepliesWithStandardMessage() {
        SlashCommandInteractionEvent event = event("UnknownHero", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("UnknownHero")).thenReturn(Optional.empty());

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("counter.hero_not_found"));
    }

    @Test
    void noDataRepliesWithNoDataMessage() {
        SlashCommandInteractionEvent event = event("Infernus", 7);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        HeroAsset infernus = new HeroAsset(1, "infernus", "Infernus", null);
        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(infernus));
        when(synergyService.forHero(1, 7)).thenReturn(new SynergyResult(1, List.of(), List.of()));

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("synergy.no_data"));
    }

    @Test
    void apiUnavailableRepliesWithBusyMessage() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        HeroAsset infernus = new HeroAsset(1, "infernus", "Infernus", null);
        when(heroResolver.resolve("Infernus")).thenReturn(Optional.of(infernus));
        when(synergyService.forHero(1, null)).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorRepliesWithGenericError() {
        SlashCommandInteractionEvent event = event("Infernus", null);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        when(heroResolver.resolve("Infernus")).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> command.handle(event)).doesNotThrowAnyException();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        assertThat(text.getValue()).startsWith("Something went wrong. Error id: ");
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
