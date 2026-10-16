package sntiago05.rejuv.config;

import java.util.Collections;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sntiago05.rejuv.commands.CommandRouter;

@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class JdaConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression("'${bot.token:}'.length() > 0 and '${bot.dev-guild-id:}'.length() > 0")
    JDA jda(BotProperties botProperties, CommandRouter commandRouter) {
        return JDABuilder.createLight(botProperties.token(), Collections.emptyList())
                .addEventListeners(commandRouter)
                .build();
    }
}
