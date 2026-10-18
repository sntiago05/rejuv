package sntiago05.rejuv.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.Test;

class CommandSupportTest {

    @Test
    void minutesSecondsPadsSeconds() {
        assertThat(CommandSupport.minutesSeconds(0)).isEqualTo("0:00");
        assertThat(CommandSupport.minutesSeconds(65)).isEqualTo("1:05");
        assertThat(CommandSupport.minutesSeconds(2104)).isEqualTo("35:04");
    }

    @Test
    void winFilterReadsWinAndLossIgnoringCase() {
        assertThat(CommandSupport.winFilter(option("win"))).isTrue();
        assertThat(CommandSupport.winFilter(option("WIN"))).isTrue();
        assertThat(CommandSupport.winFilter(option("loss"))).isFalse();
        assertThat(CommandSupport.winFilter(option("Loss"))).isFalse();
    }

    @Test
    void winFilterIsNullWhenAbsentOrUnknown() {
        assertThat(CommandSupport.winFilter(null)).isNull();
        assertThat(CommandSupport.winFilter(option("draw"))).isNull();
    }

    @Test
    void heroChoicesFilterByContainsIgnoringCaseSortAndDeduplicate() {
        List<Command.Choice> choices = CommandSupport.heroChoices(
                List.of("Seven", "Lady Geist", "Haze", "Infernus", "Lady Geist", "viscous"), "S");

        assertThat(choices).extracting(Command.Choice::getName)
                .containsExactly("Infernus", "Lady Geist", "Seven", "viscous");
        assertThat(choices).extracting(Command.Choice::getAsString)
                .containsExactly("Infernus", "Lady Geist", "Seven", "viscous");
    }

    @Test
    void heroChoicesAreCappedAtTwentyFive() {
        List<String> names = IntStream.range(0, 40).mapToObj(i -> "Hero " + (100 + i)).toList();

        assertThat(CommandSupport.heroChoices(names, "")).hasSize(25);
    }

    private static OptionMapping option(String value) {
        OptionMapping mapping = mock(OptionMapping.class);
        when(mapping.getAsString()).thenReturn(value);
        return mapping;
    }
}
