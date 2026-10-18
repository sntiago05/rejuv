package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import sntiago05.rejuv.config.UsageProperties;

class PremiumFeatureGateTest {

    private static final long USER_ID = 123456789L;

    @Test
    void limitsOffAllowsAnyoneWithoutCallingChecker() {
        UsageProperties properties = new UsageProperties(false, 3);
        PremiumChecker checker = mock(PremiumChecker.class);
        PremiumFeatureGate gate = new PremiumFeatureGate(properties, checker);

        assertThat(gate.allowed(USER_ID)).isTrue();
        verify(checker, never()).isPremium(USER_ID);
    }

    @Test
    void limitsOnAndUserIsPremiumAllowsAccess() {
        UsageProperties properties = new UsageProperties(true, 3);
        PremiumChecker checker = mock(PremiumChecker.class);
        when(checker.isPremium(USER_ID)).thenReturn(true);
        PremiumFeatureGate gate = new PremiumFeatureGate(properties, checker);

        assertThat(gate.allowed(USER_ID)).isTrue();
        verify(checker).isPremium(USER_ID);
    }

    @Test
    void limitsOnAndUserIsNotPremiumDeniesAccess() {
        UsageProperties properties = new UsageProperties(true, 3);
        PremiumChecker checker = mock(PremiumChecker.class);
        when(checker.isPremium(USER_ID)).thenReturn(false);
        PremiumFeatureGate gate = new PremiumFeatureGate(properties, checker);

        assertThat(gate.allowed(USER_ID)).isFalse();
        verify(checker).isPremium(USER_ID);
    }

    @Test
    void throwingPremiumCheckerDeniesAccessAndNeverThrows() {
        UsageProperties properties = new UsageProperties(true, 3);
        PremiumChecker checker = mock(PremiumChecker.class);
        when(checker.isPremium(USER_ID)).thenThrow(new RuntimeException("Patreon API down"));
        PremiumFeatureGate gate = new PremiumFeatureGate(properties, checker);

        assertThat(gate.allowed(USER_ID)).isFalse();
        verify(checker).isPremium(USER_ID);
    }
}
