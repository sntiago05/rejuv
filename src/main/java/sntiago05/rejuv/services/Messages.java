package sntiago05.rejuv.services;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class Messages {

    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Resolves a catalog message in English.
     *
     * @param key  catalog key, for example "help.body"
     * @param args positional MessageFormat arguments ({0}, {1}); may be empty
     * @return the resolved text
     * @throws org.springframework.context.NoSuchMessageException if the key is not defined
     */
    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, DEFAULT_LOCALE);
    }
}
