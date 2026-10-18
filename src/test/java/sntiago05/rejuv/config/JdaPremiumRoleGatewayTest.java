package sntiago05.rejuv.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import sntiago05.rejuv.services.PremiumLookupException;

class JdaPremiumRoleGatewayTest {

    private static final long GUILD_ID = 1000L;
    private static final long ROLE_ID = 2000L;
    private static final long USER_ID = 3000L;

    private JDA jda;
    private Guild guild;
    private CacheRestAction<Member> restAction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        restAction = mock(CacheRestAction.class);

        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
        when(guild.retrieveMemberById(USER_ID)).thenReturn(restAction);
    }

    @Test
    void memberWithRoleReturnsTrue() {
        Member member = mock(Member.class);
        Role role1 = mock(Role.class);
        when(role1.getIdLong()).thenReturn(9999L);
        Role role2 = mock(Role.class);
        when(role2.getIdLong()).thenReturn(ROLE_ID);
        when(member.getRoles()).thenReturn(List.of(role1, role2));

        when(restAction.submit()).thenReturn(CompletableFuture.completedFuture(member));

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThat(gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID)).isTrue();
    }

    @Test
    void memberWithoutRoleReturnsFalse() {
        Member member = mock(Member.class);
        Role role1 = mock(Role.class);
        when(role1.getIdLong()).thenReturn(9999L);
        when(member.getRoles()).thenReturn(List.of(role1));

        when(restAction.submit()).thenReturn(CompletableFuture.completedFuture(member));

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThat(gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID)).isFalse();
    }

    @Test
    void memberWithEmptyRolesReturnsFalse() {
        Member member = mock(Member.class);
        when(member.getRoles()).thenReturn(List.of());

        when(restAction.submit()).thenReturn(CompletableFuture.completedFuture(member));

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThat(gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID)).isFalse();
    }

    @Test
    void unknownMemberErrorReturnsFalse() {
        ErrorResponseException ere = mock(ErrorResponseException.class);
        when(ere.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_MEMBER);
        when(restAction.submit()).thenReturn(CompletableFuture.failedFuture(ere));

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThat(gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID)).isFalse();
    }

    @Test
    void unknownUserErrorReturnsFalse() {
        ErrorResponseException ere = mock(ErrorResponseException.class);
        when(ere.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_USER);
        when(restAction.submit()).thenReturn(CompletableFuture.failedFuture(ere));

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThat(gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID)).isFalse();
    }

    @Test
    void otherDiscordErrorThrowsPremiumLookupException() {
        ErrorResponseException ere = mock(ErrorResponseException.class);
        when(ere.getErrorResponse()).thenReturn(ErrorResponse.SERVER_ERROR);
        when(restAction.submit()).thenReturn(CompletableFuture.failedFuture(ere));

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThatThrownBy(() -> gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID))
                .isInstanceOf(PremiumLookupException.class)
                .hasMessageContaining("Discord error");
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutThrowsPremiumLookupException() throws Exception {
        CompletableFuture<Member> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new TimeoutException("Timed out"));
        when(restAction.submit()).thenReturn(future);

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThatThrownBy(() -> gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID))
                .isInstanceOf(PremiumLookupException.class)
                .hasMessageContaining("Timeout");
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptedThrowsPremiumLookupExceptionAndRestoresFlag() throws Exception {
        CompletableFuture<Member> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new InterruptedException("Interrupted"));
        when(restAction.submit()).thenReturn(future);

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        try {
            assertThatThrownBy(() -> gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID))
                    .isInstanceOf(PremiumLookupException.class)
                    .hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void guildNullThrowsPremiumLookupException() {
        when(jda.getGuildById(GUILD_ID)).thenReturn(null);

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(jda);
        assertThatThrownBy(() -> gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID))
                .isInstanceOf(PremiumLookupException.class)
                .hasMessageContaining("guild is not available");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noJdaBeanThrowsPremiumLookupException() {
        ObjectProvider<JDA> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        JdaPremiumRoleGateway gateway = new JdaPremiumRoleGateway(provider);
        assertThatThrownBy(() -> gateway.hasRole(GUILD_ID, ROLE_ID, USER_ID))
                .isInstanceOf(PremiumLookupException.class)
                .hasMessageContaining("JDA is not available");
    }
}
