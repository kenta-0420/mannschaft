package com.mannschaft.app.provisioning.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.provisioning.ProvisioningErrorCode;
import com.mannschaft.app.provisioning.entity.ProvisioningInvitationEntity;
import com.mannschaft.app.provisioning.repository.ProvisioningInvitationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * 柱②-2 試練（AC4・AC7〜AC10・AC12）: 販促プロビジョニング承諾サービスの UT。
 *
 * <p>正本: .claude/campaigns/2026-09-01-org-governance.md 柱②。骨格は
 * {@link UnsupportedOperationException} を投げるため、以下は red が正しい。
 * 実装は後続 PR（出陣）で行う。
 *
 * <p>並行実行が本質の AC6（悲観ロックによる二重承諾防止）は Testcontainers の
 * {@code ProvisioningAcceptanceIT} 側で検証する（モックの悲観ロックはロック競合を再現できないため）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningAcceptanceServiceTest {

    @Mock
    private ProvisioningInvitationRepository invitationRepository;

    @Mock
    private SecretTokenVault secretTokenVault;

    @Mock
    private ProvisioningEmailNormalizer emailNormalizer;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ProvisioningAcceptanceService acceptanceService;

    private static final String TOKEN_PLAINTEXT = "plaintext-token";
    private static final String TOKEN_HASH = "hash-value";
    private static final Long ACTOR_USER_ID = 42L;

    private ProvisioningInvitationEntity pendingInvitation(Instant expiresAt) {
        return ProvisioningInvitationEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(1L)
                .inviteEmail("invited@example.com")
                .tokenHash(TOKEN_HASH)
                .status("PENDING")
                .expiresAt(expiresAt)
                .issuedBy(99L)
                .build();
    }

    @Test
    @DisplayName("AC7: TTLが経過した招待の承諾は409（PROV_002）")
    void acceptRejectsExpiredInvitation() {
        lenient().when(secretTokenVault.hash(TOKEN_PLAINTEXT)).thenReturn(TOKEN_HASH);
        lenient().when(invitationRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(pendingInvitation(Instant.now().minus(1, ChronoUnit.SECONDS))));

        assertThatThrownBy(() -> acceptanceService.accept(TOKEN_PLAINTEXT, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ProvisioningErrorCode.PROV_002);
    }

    @Test
    @DisplayName("AC8: 取消(CANCELLED)済み招待への承諾は404/409系（PROV_003）")
    void acceptRejectsCancelledInvitation() {
        ProvisioningInvitationEntity cancelled = pendingInvitation(Instant.now().plus(1, ChronoUnit.DAYS))
                .toBuilder().status("CANCELLED").build();
        lenient().when(secretTokenVault.hash(TOKEN_PLAINTEXT)).thenReturn(TOKEN_HASH);
        lenient().when(invitationRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> acceptanceService.accept(TOKEN_PLAINTEXT, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ProvisioningErrorCode.PROV_003);
    }

    @Test
    @DisplayName("AC1/AC9前提: 存在しないトークンの承諾は404（PROV_001）")
    void acceptRejectsUnknownToken() {
        lenient().when(secretTokenVault.hash(TOKEN_PLAINTEXT)).thenReturn(TOKEN_HASH);
        lenient().when(invitationRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> acceptanceService.accept(TOKEN_PLAINTEXT, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ProvisioningErrorCode.PROV_001);
    }

    @Test
    @DisplayName("AC9: ACCEPTED済みトークンへの別ユーザーからの再承諾は404（PROV_010・本人以外に畳む）")
    void acceptByOtherUserAfterAcceptedReturnsNotFound() {
        Long originalAcceptor = 7L;
        ProvisioningInvitationEntity accepted = pendingInvitation(Instant.now().plus(1, ChronoUnit.DAYS))
                .toBuilder().status("ACCEPTED").acceptedBy(originalAcceptor).build();
        lenient().when(secretTokenVault.hash(TOKEN_PLAINTEXT)).thenReturn(TOKEN_HASH);
        lenient().when(invitationRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(accepted));

        // ACTOR_USER_ID(42) != originalAcceptor(7) のため本人以外 -> 404
        assertThatThrownBy(() -> acceptanceService.accept(TOKEN_PLAINTEXT, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ProvisioningErrorCode.PROV_010);
    }

    @Test
    @DisplayName("AC4: 招待先メールとログインユーザーの検証済みメールが不一致なら403（PROV_006）")
    void acceptRejectsEmailMismatch() {
        lenient().when(secretTokenVault.hash(TOKEN_PLAINTEXT)).thenReturn(TOKEN_HASH);
        lenient().when(invitationRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(pendingInvitation(Instant.now().plus(1, ChronoUnit.DAYS))));
        lenient().when(emailNormalizer.normalize("invited@example.com")).thenReturn("invited@example.com");
        lenient().when(emailNormalizer.normalize("someone-else@example.com")).thenReturn("someone-else@example.com");

        assertThatThrownBy(() -> acceptanceService.accept(TOKEN_PLAINTEXT, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ProvisioningErrorCode.PROV_006);
    }
}
