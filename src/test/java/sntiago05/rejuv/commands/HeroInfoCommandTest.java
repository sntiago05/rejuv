package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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
import sntiago05.rejuv.domain.HeroInfo;
import sntiago05.rejuv.services.HeroInfoService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;

class HeroInfoCommandTest {

    private static final long CALLER_ID = 333L;

    private final Messages messages = MessagesFixture.english();
    private HeroInfoService service;
    private HeroInfoCommand command;
    private WebhookMessageEditAction<Message> edit;
    private ReplyCallbackAction defer;

    @BeforeEach
    void setUp() {
        service = mock(HeroInfoService.class);
        command = new HeroInfoCommand(service, messages);
    }

    private static HeroInfo info(String role, String playstyle, String lore, String hex, boolean playable,
                                 List<HeroInfo.Stat> stats, String heroType, Integer complexity, String gun,
                                 List<String> tags) {
        return new HeroInfo(1, "Infernus", role, playstyle, lore, heroType, complexity, gun, tags, hex,
                "https://assets-bucket.deadlock-api.com/inferno_card.png", playable, stats);
    }

    private static HeroInfo fullInfo() {
        return info("Lights up enemies", "Burns foes over time.", "Some lore.", "#C93C26", true,
                List.of(new HeroInfo.Stat("max_health", 830.0), new HeroInfo.Stat("max_move_speed", 6.7),
                        new HeroInfo.Stat("base_health_regen", 2.0)),
                "marksman", 1, "Rapid Fire", List.of("Arsonist", "Explosive"));
    }

    private SlashCommandInteractionEvent event(String hero) {
        User caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        OptionMapping heroOption = mock(OptionMapping.class);
        when(heroOption.getAsString()).thenReturn(hero);
        when(event.getOption("hero")).thenReturn(heroOption);
        defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);
        return event;
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

    private MessageEmbed run(HeroInfo result) {
        SlashCommandInteractionEvent event = event("Infernus");
        InteractionHook hook = hookFor(event);
        when(service.getInfo("Infernus")).thenReturn(result);
        command.handle(event);
        ArgumentCaptor<MessageEmbed> captor = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(hook).editOriginalEmbeds(captor.capture());
        verify(edit).queue();
        return captor.getValue();
    }

    private static String field(MessageEmbed embed, String name) {
        return embed.getFields().stream().filter(f -> name.equals(f.getName())).findFirst()
                .map(MessageEmbed.Field::getValue).orElse(null);
    }

    @Test
    void definesAHeroCommandWithOneRequiredHeroOption() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("heroinfo");
        assertThat(data.getDescription()).isEqualTo(messages.get("heroinfo.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).hasSize(1);
        OptionData option = data.getOptions().get(0);
        assertThat(option.getName()).isEqualTo("hero");
        assertThat(option.getType()).isEqualTo(OptionType.STRING);
        assertThat(option.isRequired()).isTrue();
        assertThat(option.getDescription().length()).isBetween(1, 100);
    }

    @Test
    void embedShowsRoleTypePlaystyleTagsStatsAndLore() {
        MessageEmbed embed = run(fullInfo());

        assertThat(embed.getTitle()).isEqualTo("Infernus");
        assertThat(embed.getDescription()).isEqualTo("Lights up enemies");
        assertThat(embed.getColor().getRGB() & 0xFFFFFF).isEqualTo(0xC93C26);
        assertThat(embed.getThumbnail().getUrl()).endsWith("inferno_card.png");
        assertThat(field(embed, messages.get("heroinfo.field.type"))).isEqualTo("Marksman, Complexity 1, Gun: Rapid Fire");
        assertThat(field(embed, messages.get("heroinfo.field.playstyle"))).isEqualTo("Burns foes over time.");
        assertThat(field(embed, messages.get("heroinfo.field.tags"))).isEqualTo("Arsonist, Explosive");
        assertThat(field(embed, messages.get("heroinfo.field.stats")))
                .isEqualTo("• Health: 830\n• Move speed: 6.7\n• Health regen: 2");
        assertThat(field(embed, messages.get("heroinfo.field.lore"))).isEqualTo("Some lore.");
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("heroinfo.footer"));
        assertThat(embed.getLength()).isLessThanOrEqualTo(MessageEmbed.EMBED_MAX_LENGTH_BOT);
    }

    @Test
    void emptyPartsAreSkippedAndABadColorFallsBackToTheBrandColor() {
        MessageEmbed embed = run(info(null, null, null, "red", true, List.of(), null, null, null, List.of()));

        assertThat(embed.getDescription()).isNull();
        assertThat(embed.getFields()).isEmpty();
        assertThat(embed.getColor().getRGB() & 0xFFFFFF).isEqualTo(EmbedStyle.COLOR);
        assertThat(HeroInfoCommand.embedColor(null)).isEqualTo(EmbedStyle.COLOR);
        assertThat(HeroInfoCommand.embedColor("#12345")).isEqualTo(EmbedStyle.COLOR);
        assertThat(HeroInfoCommand.embedColor("#00ff7f")).isEqualTo(0x00FF7F);
    }

    @Test
    void notPlayableHeroGetsAnExtraDescriptionLine() {
        MessageEmbed embed = run(info("Role", "Play", "Lore", "#C93C26", false, List.of(), "brawler", 2, null, List.of()));

        assertThat(embed.getDescription()).isEqualTo("Role\n" + messages.get("heroinfo.not_playable"));
        assertThat(field(embed, messages.get("heroinfo.field.type"))).isEqualTo("Brawler, Complexity 2");

        MessageEmbed noRole = run(info(null, null, null, null, false, List.of(), null, null, null, List.of()));
        assertThat(noRole.getDescription()).isEqualTo(messages.get("heroinfo.not_playable"));
    }

    @Test
    void veryLongTextStaysInsideDiscordLimits() {
        String huge = "word ".repeat(2000);
        List<HeroInfo.Stat> stats = List.of(new HeroInfo.Stat("max_health", 830.0));
        MessageEmbed embed = run(info("r".repeat(5000), huge, huge, "#C93C26", true, stats, "mystic", 3, "x".repeat(300),
                List.of("t".repeat(900), "u".repeat(900))));

        assertThat(embed.getDescription().length()).isLessThanOrEqualTo(4096);
        embed.getFields().forEach(f -> assertThat(f.getValue().length()).isLessThanOrEqualTo(1024));
        assertThat(embed.getLength()).isLessThanOrEqualTo(MessageEmbed.EMBED_MAX_LENGTH_BOT);
    }

    @Test
    void numbersShowAtMostOneDecimalAndNoTrailingZero() {
        assertThat(HeroInfoCommand.formatNumber(830.0)).isEqualTo("830");
        assertThat(HeroInfoCommand.formatNumber(6.7)).isEqualTo("6.7");
        assertThat(HeroInfoCommand.formatNumber(2.0)).isEqualTo("2");
        assertThat(HeroInfoCommand.formatNumber(0.222222)).isEqualTo("0.2");
        assertThat(HeroInfoCommand.formatNumber(1.04)).isEqualTo("1");
    }

    @Test
    void unknownHeroAnswersWithTheNotFoundMessage() {
        SlashCommandInteractionEvent event = event("zzz");
        InteractionHook hook = hookFor(event);
        when(service.getInfo("zzz")).thenThrow(new HeroNotFoundException("zzz"));

        command.handle(event);

        verify(defer).queue();
        verify(hook).editOriginal(messages.get("heroinfo.hero_not_found"));
        verify(edit).queue();
    }

    @Test
    void busyApiAnswersWithTheDataSourceBusyMessage() {
        SlashCommandInteractionEvent event = event("Haze");
        InteractionHook hook = hookFor(event);
        when(service.getInfo("Haze")).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        command.handle(event);

        verify(hook).editOriginal(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedFailureAnswersWithAGenericErrorWithoutRethrowing() {
        SlashCommandInteractionEvent event = event("Haze");
        InteractionHook hook = hookFor(event);
        when(service.getInfo("Haze")).thenThrow(new IllegalStateException("boom"));

        command.handle(event);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(text.capture());
        String sample = messages.get("error.generic", "x");
        assertThat(text.getValue()).startsWith(sample.substring(0, sample.length() - 1));
    }
}
