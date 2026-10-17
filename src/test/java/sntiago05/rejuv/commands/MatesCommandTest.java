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
import sntiago05.rejuv.domain.MatesSummary;
import sntiago05.rejuv.domain.Teammate;
import sntiago05.rejuv.services.MatesService;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.ProfileService;

class MatesCommandTest {

    private static final long CALLER_ID = 222L;
    private static final long OTHER_ID = 333L;
    private static final long ACCOUNT_ID = 1000001L;

    private final Messages messages = MessagesFixture.english();
    private ProfileService profileService;
    private MatesService matesService;
    private MatesCommand command;
    private User caller;
    private User other;
    private WebhookMessageEditAction<Message> edit;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        matesService = mock(MatesService.class);
        command = new MatesCommand(profileService, matesService, messages);
        caller = user(CALLER_ID, "Caller");
        other = user(OTHER_ID, "Other");
    }

    @Test
    void nameIsMatesWithOptionalUserOption() {
        SlashCommandData data = command.data();

        assertThat(command.name()).isEqualTo("mates");
        assertThat(data.getName()).isEqualTo("mates");
        assertThat(data.getDescription()).isEqualTo(messages.get("mates.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).hasSize(1);
        OptionData option = data.getOptions().get(0);
        assertThat(option.getName()).isEqualTo("user");
        assertThat(option.getType()).isEqualTo(OptionType.USER);
        assertThat(option.isRequired()).isFalse();
        assertThat(option.getDescription()).isEqualTo(messages.get("mates.option.user"));
        assertThat(option.getDescription().length()).isBetween(1, 100);
    }

    @Test
    void callerNotLinkedRepliesEphemerallyWithoutDeferring() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.empty());

        assertImmediateReply(event, messages.get("mates.not_linked_self"));
    }

    @Test
    void otherUserNotLinkedRepliesWithMention() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.empty());

        String text = assertImmediateReply(event, messages.get("mates.not_linked_other", other.getAsMention()));
        assertThat(text).contains("<@333>");
    }

    @Test
    void optionUserIsUsedAsTarget() {
        SlashCommandInteractionEvent event = event(other);
        when(profileService.findLinkedAccountId(OTHER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(matesService.getMates(ACCOUNT_ID)).thenReturn(sampleSummary());
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(profileService).findLinkedAccountId(OTHER_ID);
        verify(profileService, never()).findLinkedAccountId(CALLER_ID);
        assertThat(capturedEmbed(hook).getTitle()).isEqualTo(messages.get("mates.title", "Other"));
    }

    @Test
    void successDefersPubliclyAndEditsWithEmbed() {
        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(matesService.getMates(ACCOUNT_ID)).thenReturn(sampleSummary());
        ReplyCallbackAction defer = deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        verify(event).deferReply();
        verify(event, never()).deferReply(anyBoolean());
        verify(defer).queue();

        MessageEmbed embed = capturedEmbed(hook);
        assertThat(embed.getTitle()).isEqualTo(messages.get("mates.title", "Caller"));
        assertThat(embed.getColorRaw()).isEqualTo(EmbedStyle.COLOR);
        List<MessageEmbed.Field> fields = embed.getFields();
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getName()).isEqualTo(messages.get("mates.field.top"));
        assertThat(fields.get(0).getValue()).contains("1. Player #2000001 - 20 matches, 75% win");
        assertThat(fields.get(0).getValue()).contains("2. Player #2000002 - 10 matches, 60% win");
        assertThat(embed.getFooter().getText()).isEqualTo(messages.get("mates.footer"));
    }

    @Test
    void emptyTeammatesEditsWithNoMatesMessage() {
        when(matesService.getMates(ACCOUNT_ID)).thenReturn(new MatesSummary(ACCOUNT_ID, List.of()));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("mates.no_mates", MatesService.MIN_MATCHES_TOGETHER));
    }

    @Test
    void unavailableEditsWithBusyMessage() {
        when(matesService.getMates(ACCOUNT_ID))
                .thenThrow(new DeadlockApiUnavailableException("busy", Duration.ofSeconds(5), null));

        assertThat(editedTextForLinkedCaller()).isEqualTo(messages.get("error.data_source_busy"));
    }

    @Test
    void unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow() {
        when(matesService.getMates(ACCOUNT_ID)).thenThrow(new RuntimeException("boom"));

        assertThat(editedTextForLinkedCaller()).startsWith("Something went wrong. Error id: ");
    }

    @Test
    void allMatesCatalogKeysResolve() {
        List<String> requiredKeys = List.of(
                "mates.description",
                "mates.option.user",
                "mates.not_linked_self",
                "mates.not_linked_other",
                "mates.no_mates",
                "mates.title",
                "mates.field.top",
                "mates.entry",
                "mates.teammate_label",
                "mates.footer"
        );

        for (String key : requiredKeys) {
            assertThat(messages.get(key)).isNotBlank();
        }
    }

    @Test
    void embedFieldValuesDoNotExceed1024CharactersEvenWithManyMates() {
        List<Teammate> mates = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            mates.add(new Teammate(2000000L + i, 100, 60, 60.0));
        }
        MatesSummary summary = new MatesSummary(ACCOUNT_ID, mates);

        SlashCommandInteractionEvent event = event(null);
        when(profileService.findLinkedAccountId(CALLER_ID)).thenReturn(OptionalLong.of(ACCOUNT_ID));
        when(matesService.getMates(ACCOUNT_ID)).thenReturn(summary);
        deferFor(event);
        InteractionHook hook = hookFor(event);

        command.handle(event);

        MessageEmbed embed = capturedEmbed(hook);
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
        verify(matesService, never()).getMates(anyLong());
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

    private static MatesSummary sampleSummary() {
        Teammate m1 = new Teammate(2000001L, 20, 15, 75.0);
        Teammate m2 = new Teammate(2000002L, 10, 6, 60.0);
        return new MatesSummary(ACCOUNT_ID, List.of(m1, m2));
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
