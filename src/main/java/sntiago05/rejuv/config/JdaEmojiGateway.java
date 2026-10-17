package sntiago05.rejuv.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.EmojiGateway;

/**
 * JDA-based implementation of {@link EmojiGateway} operating on Discord application emojis.
 */
@Component
public class JdaEmojiGateway implements EmojiGateway {

    private final ObjectProvider<JDA> jdaProvider;
    private final JDA directJda;

    @org.springframework.beans.factory.annotation.Autowired
    public JdaEmojiGateway(ObjectProvider<JDA> jdaProvider) {
        this.jdaProvider = jdaProvider;
        this.directJda = null;
    }

    JdaEmojiGateway(JDA jda) {
        this.jdaProvider = null;
        this.directJda = jda;
    }

    private JDA getJda() {
        return directJda != null ? directJda : (jdaProvider != null ? jdaProvider.getIfAvailable() : null);
    }

    @Override
    public Map<String, String> listExisting() {
        JDA jda = getJda();
        if (jda == null) {
            return Map.of();
        }

        List<ApplicationEmoji> emojis = jda.retrieveApplicationEmojis().complete();
        Map<String, String> result = new HashMap<>(emojis.size());
        for (ApplicationEmoji emoji : emojis) {
            result.put(emoji.getName(), emoji.getAsMention());
        }
        return result;
    }

    @Override
    public String create(String name, byte[] image) {
        JDA jda = getJda();
        if (jda == null) {
            throw new IllegalStateException("JDA is not available");
        }

        ApplicationEmoji emoji = jda.createApplicationEmoji(name, Icon.from(image)).complete();
        return emoji.getAsMention();
    }
}
