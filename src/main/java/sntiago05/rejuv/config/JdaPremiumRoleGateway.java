package sntiago05.rejuv.config;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.services.PremiumLookupException;
import sntiago05.rejuv.services.PremiumRoleGateway;

@Component
@ConditionalOnProperty(prefix = "rejuv.premium", name = "enabled", havingValue = "true")
public class JdaPremiumRoleGateway implements PremiumRoleGateway {

    private final ObjectProvider<JDA> jdaProvider;
    private final JDA directJda;

    @Autowired
    public JdaPremiumRoleGateway(ObjectProvider<JDA> jdaProvider) {
        this.jdaProvider = jdaProvider;
        this.directJda = null;
    }

    JdaPremiumRoleGateway(JDA jda) {
        this.jdaProvider = null;
        this.directJda = jda;
    }

    private JDA getJda() {
        return directJda != null ? directJda : (jdaProvider != null ? jdaProvider.getIfAvailable() : null);
    }

    @Override
    public boolean hasRole(long guildId, long roleId, long userId) {
        JDA jda = getJda();
        if (jda == null) {
            throw new PremiumLookupException("JDA is not available");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new PremiumLookupException("Configured premium guild is not available");
        }

        Member member;
        try {
            member = guild.retrieveMemberById(userId).submit().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PremiumLookupException("Interrupted while retrieving member", e);
        } catch (TimeoutException e) {
            throw new PremiumLookupException("Timeout while retrieving member", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ErrorResponseException ere) {
                if (isUnknownMemberOrUser(ere)) {
                    return false;
                }
                throw new PremiumLookupException("Discord error while retrieving member: " + ere.getErrorResponse(), ere);
            }
            throw new PremiumLookupException("Failed to retrieve member", cause);
        } catch (ErrorResponseException ere) {
            if (isUnknownMemberOrUser(ere)) {
                return false;
            }
            throw new PremiumLookupException("Discord error while retrieving member: " + ere.getErrorResponse(), ere);
        } catch (RuntimeException e) {
            throw new PremiumLookupException("Unexpected error while retrieving member", e);
        }

        if (member == null) {
            return false;
        }

        for (Role role : member.getRoles()) {
            if (role.getIdLong() == roleId) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnknownMemberOrUser(ErrorResponseException ere) {
        ErrorResponse response = ere.getErrorResponse();
        return response == ErrorResponse.UNKNOWN_MEMBER || response == ErrorResponse.UNKNOWN_USER;
    }
}
