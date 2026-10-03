package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * F01.9 保護者同意ゲート: {@link StatusClaimResolver} の ppc 判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatusClaimResolver (F01.9)")
class StatusClaimResolverTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private StatusClaimResolver resolver;

    @Test
    @DisplayName("PENDING_PARENTAL_CONSENT なら true")
    void pending_returns_true() {
        when(userRepository.findStatusById(100L))
                .thenReturn(Optional.of(UserStatus.PENDING_PARENTAL_CONSENT));
        assertThat(resolver.isPendingParentalConsent(100L)).isTrue();
    }

    @Test
    @DisplayName("ACTIVE なら false")
    void active_returns_false() {
        when(userRepository.findStatusById(100L))
                .thenReturn(Optional.of(UserStatus.ACTIVE));
        assertThat(resolver.isPendingParentalConsent(100L)).isFalse();
    }

    @Test
    @DisplayName("ユーザー不在なら false")
    void missing_returns_false() {
        when(userRepository.findStatusById(100L)).thenReturn(Optional.empty());
        assertThat(resolver.isPendingParentalConsent(100L)).isFalse();
    }
}
