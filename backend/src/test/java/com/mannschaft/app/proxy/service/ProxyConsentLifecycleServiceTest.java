package com.mannschaft.app.proxy.service;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity.RevokeMethod;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProxyConsentLifecycleServiceTest {

    @Mock
    private ProxyInputConsentRepository consentRepository;

    @InjectMocks
    private ProxyConsentLifecycleService sut;

    @Nested
    @DisplayName("expireOutdatedConsents: 有効期限切れ同意書の自動失効")
    class ExpireOutdatedConsents {

        @Test
        @DisplayName("有効期限切れの同意書を AUTO_BY_TENURE_END で失効させる")
        void expireExpiredConsents() {
            // GIVEN
            ProxyInputConsentEntity c1 = buildConsent(1L);
            ProxyInputConsentEntity c2 = buildConsent(2L);
            given(consentRepository.findExpired()).willReturn(List.of(c1, c2));

            // WHEN
            int count = sut.expireOutdatedConsents();

            // THEN
            assertThat(count).isEqualTo(2);
            assertThat(c1.getRevokedAt()).isNotNull();
            assertThat(c1.getRevokeMethod()).isEqualTo(RevokeMethod.AUTO_BY_TENURE_END);
            assertThat(c2.getRevokedAt()).isNotNull();
            verify(consentRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("期限切れ同意書が0件の場合は0を返す")
        void noExpiredConsents() {
            given(consentRepository.findExpired()).willReturn(List.of());
            assertThat(sut.expireOutdatedConsents()).isZero();
        }
    }

    @Nested
    @DisplayName("revokeAllForUser: ライフイベントによる全同意書失効")
    class RevokeAllForUser {

        @Test
        @DisplayName("指定ユーザーの全同意書を AUTO_BY_LIFE_EVENT で失効させる")
        void revokeAllConsentsForDeceased() {
            // GIVEN
            Long userId = 10L;
            ProxyInputConsentEntity c1 = buildConsent(1L);
            ProxyInputConsentEntity c2 = buildConsent(2L);
            given(consentRepository.findActiveBySubjectOrProxyUserId(userId))
                    .willReturn(List.of(c1, c2));

            // WHEN
            int count = sut.revokeAllForUser(userId, "ユーザーステータス変更: DECEASED");

            // THEN
            assertThat(count).isEqualTo(2);
            assertThat(c1.getRevokeMethod()).isEqualTo(RevokeMethod.AUTO_BY_LIFE_EVENT);
            assertThat(c2.getRevokeMethod()).isEqualTo(RevokeMethod.AUTO_BY_LIFE_EVENT);
            verify(consentRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("対象同意書が0件の場合は0を返す")
        void noConsentsForUser() {
            Long userId = 99L;
            given(consentRepository.findActiveBySubjectOrProxyUserId(userId))
                    .willReturn(List.of());
            assertThat(sut.revokeAllForUser(userId, "DECEASED")).isZero();
        }
    }

    private ProxyInputConsentEntity buildConsent(Long id) {
        // ProxyInputConsentEntity は @Builder を持つため、builder で構築
        return ProxyInputConsentEntity.builder()
                .subjectUserId(100L)
                .proxyUserId(200L)
                .organizationId(1L)
                .consentMethod(ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED)
                .effectiveFrom(java.time.LocalDate.now().minusDays(30))
                .effectiveUntil(java.time.LocalDate.now().minusDays(1))
                .build();
    }
}
