package sntiago05.rejuv.services;

import java.util.Map;

/**
 * Gateway to Discord application emojis. Free of JDA types.
 */
public interface EmojiGateway {

    /**
     * Lists existing application emojis.
     *
     * @return map of emoji name to mention markup (e.g. "<:name:id>")
     */
    Map<String, String> listExisting();

    /**
     * Creates an application emoji and returns its mention markup.
     *
     * @param name  emoji name (2-32 alphanumeric/underscore characters)
     * @param image image bytes
     * @return emoji mention markup (e.g. "<:name:id>")
     */
    String create(String name, byte[] image);
}
