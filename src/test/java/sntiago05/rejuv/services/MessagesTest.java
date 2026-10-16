package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.StaticMessageSource;

class MessagesTest {

    @Test
    void returnsMessageForKey() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("greeting", Locale.ENGLISH, "Hello world");
        Messages messages = new Messages(messageSource);

        assertThat(messages.get("greeting")).isEqualTo("Hello world");
    }

    @Test
    void formatsPositionalArguments() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("summary", Locale.ENGLISH, "Hello {0}, you have {1} items");
        Messages messages = new Messages(messageSource);

        assertThat(messages.get("summary", "Ana", "3")).isEqualTo("Hello Ana, you have 3 items");
    }

    @Test
    void keepsApostrophesAndBracesWhenNoArguments() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("raw.text", Locale.ENGLISH, "don't use {0}");
        Messages messages = new Messages(messageSource);

        assertThat(messages.get("raw.text")).isEqualTo("don't use {0}");
    }

    @Test
    void throwsNoSuchMessageExceptionForMissingKey() {
        StaticMessageSource messageSource = new StaticMessageSource();
        Messages messages = new Messages(messageSource);

        assertThatThrownBy(() -> messages.get("missing.key"))
                .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void alwaysResolvesInEnglishRegardlessOfDefaultLocale() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("greeting", Locale.ENGLISH, "Hello");
        messageSource.addMessage("greeting", Locale.forLanguageTag("es"), "Hola");
        Messages messages = new Messages(messageSource);

        Locale previousDefault = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es-CO"));
            assertThat(messages.get("greeting")).isEqualTo("Hello");
        } finally {
            Locale.setDefault(previousDefault);
        }
    }
}
