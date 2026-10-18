package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sntiago05.rejuv.domain.UsageStatus;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.MessagesFixture;
import sntiago05.rejuv.services.UsageService;

class UsageCommandTest {

    private static final long CALLER_ID = 123456789L;
    private static final Instant RESETS_AT = Instant.parse("2026-10-26T00:00:00Z");

    private final Messages messages = MessagesFixture.english();
    private UsageService usageService;
    private UsageCommand command;
    private User caller;

    @BeforeEach
    void setUp() {
        usageService = mock(UsageService.class);
        command = new UsageCommand(usageService, messages);
        caller = mock(User.class);
        when(caller.getIdLong()).thenReturn(CALLER_ID);
    }

    @Test
    void nameIsUsage() {
        assertThat(command.name()).isEqualTo("usage");
    }

    @Test
    void dataMatchesSpecWithNoOptions() {
        SlashCommandData data = command.data();

        assertThat(data.getName()).isEqualTo("usage");
        assertThat(data.getDescription()).isEqualTo(messages.get("usage.description"));
        assertThat(data.getDescription().length()).isBetween(1, 100);
        assertThat(data.getOptions()).isEmpty();
    }

    @Test
    void flagOffRepliesWithDisabledMessageEphemerally() {
        SlashCommandInteractionEvent event = mockEvent();
        ReplyCallbackAction reply = mockReply(event);
        when(usageService.status(CALLER_ID)).thenReturn(new UsageStatus(false, false, 0, 3, RESETS_AT));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).reply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("usage.disabled"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verify(event, never()).deferReply(anyBoolean());
    }

    @Test
    void premiumRepliesWithPremiumMessageEphemerally() {
        SlashCommandInteractionEvent event = mockEvent();
        ReplyCallbackAction reply = mockReply(event);
        when(usageService.status(CALLER_ID)).thenReturn(new UsageStatus(true, true, 5, 3, RESETS_AT));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).reply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(messages.get("usage.premium"));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
    }

    @Test
    void limitedUserRepliesWithStatusMessageAndResetTimestampEphemerally() {
        SlashCommandInteractionEvent event = mockEvent();
        ReplyCallbackAction reply = mockReply(event);
        when(usageService.status(CALLER_ID)).thenReturn(new UsageStatus(true, false, 2, 3, RESETS_AT));

        command.handle(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(event).reply(captor.capture());
        String expectedReset = "<t:" + RESETS_AT.getEpochSecond() + ":R>";
        assertThat(captor.getValue()).isEqualTo(messages.get("usage.status", 2, 3, expectedReset));
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
    }

    @Test
    void resolvesAllUsageKeysAndAnalyzeLimitReached() {
        List<String> requiredKeys = List.of(
                "usage.description",
                "usage.disabled",
                "usage.premium",
                "usage.status",
                "analyze.limit_reached"
        );

        for (String key : requiredKeys) {
            assertThat(messages.get(key)).as("key %s should resolve", key).isNotBlank();
        }
    }

    private SlashCommandInteractionEvent mockEvent() {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getUser()).thenReturn(caller);
        return event;
    }

    private ReplyCallbackAction mockReply(SlashCommandInteractionEvent event) {
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);
        return reply;
    }
}
