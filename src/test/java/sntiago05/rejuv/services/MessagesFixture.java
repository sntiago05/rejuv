package sntiago05.rejuv.services;

import org.springframework.context.support.ResourceBundleMessageSource;

public final class MessagesFixture {
    private MessagesFixture() { }

    /** Messages backed by the real classpath "messages" bundle, no Spring context. */
    public static Messages english() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new Messages(source);
    }
}
