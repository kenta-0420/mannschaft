package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.dto.BetaRestrictionConfigResponse;
import com.mannschaft.app.admin.entity.BetaRestrictionConfigEntity;
import com.mannschaft.app.admin.repository.BetaRestrictionConfigRepository;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link BetaRestrictionService} の単体テスト。
 * ベータ招待トークン検証・設定取得ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BetaRestrictionService 単体テスト")
class BetaRestrictionServiceTest {

    @Mock
    private BetaRestrictionConfigRepository repo;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @InjectMocks
    private BetaRestrictionService betaRestrictionService;

    // ========================================
    // isBetaTokenValid
    // ========================================

    @Nested
    @DisplayName("isBetaTokenValid")
    class IsBetaTokenValid {

        @Test
        @DisplayName("トークンが存在しない場合は false")
        void isBetaTokenValid_トークン不在_false() {
            // Given
            given(inviteTokenRepository.findByToken("nonexistent")).willReturn(Optional.empty());

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("nonexistent");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("トークンが存在するが isValid()=false の場合は false")
        void isBetaTokenValid_isValidFalse_false() {
            // Given: revoke されたトークン（isValid() = false）
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("revoked-token")
                    .teamId(1L)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .build();
            token.revoke(); // revokedAt を設定して isValid()=false にする
            given(inviteTokenRepository.findByToken("revoked-token")).willReturn(Optional.of(token));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("revoked-token");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("teamId・orgId が両方 null のトークンは false")
        void isBetaTokenValid_teamIdOrgId両方null_false() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("no-target-token")
                    .teamId(null)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(inviteTokenRepository.findByToken("no-target-token")).willReturn(Optional.of(token));
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.empty());

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("no-target-token");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("teamId=5, maxTeamId=10 の場合は true")
        void isBetaTokenValid_teamId5_maxTeamId10_true() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("team-token")
                    .teamId(5L)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(10L)
                    .maxOrgId(null)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(inviteTokenRepository.findByToken("team-token")).willReturn(Optional.of(token));
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("team-token");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("teamId=15, maxTeamId=10 の場合は false")
        void isBetaTokenValid_teamId15_maxTeamId10_false() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("large-team-token")
                    .teamId(15L)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(10L)
                    .maxOrgId(null)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(inviteTokenRepository.findByToken("large-team-token")).willReturn(Optional.of(token));
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("large-team-token");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("orgId=3, maxOrgId=10 の場合は true")
        void isBetaTokenValid_orgId3_maxOrgId10_true() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("org-token")
                    .teamId(null)
                    .organizationId(3L)
                    .roleId(1L)
                    .usedCount(0)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(null)
                    .maxOrgId(10L)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(inviteTokenRepository.findByToken("org-token")).willReturn(Optional.of(token));
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("org-token");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("orgId=15, maxOrgId=10 の場合は false")
        void isBetaTokenValid_orgId15_maxOrgId10_false() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("large-org-token")
                    .teamId(null)
                    .organizationId(15L)
                    .roleId(1L)
                    .usedCount(0)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(null)
                    .maxOrgId(10L)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(inviteTokenRepository.findByToken("large-org-token")).willReturn(Optional.of(token));
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("large-org-token");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // getConfig
    // ========================================

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("レコードなしの場合は isEnabled=false のデフォルト値が返る")
        void getConfig_レコードなし_デフォルト値() {
            // Given
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.empty());

            // When
            BetaRestrictionConfigResponse response = betaRestrictionService.getConfig();

            // Then
            assertThat(response.getIsEnabled()).isFalse();
            assertThat(response.getMaxTeamId()).isNull();
            assertThat(response.getMaxOrgId()).isNull();
        }
    }

    // ========================================
    // isEnabled
    // ========================================

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("レコードありで isEnabled=true の場合は true が返る")
        void isEnabled_レコードあり_isEnabledTrue_true() {
            // Given
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(null)
                    .maxOrgId(null)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isEnabled();

            // Then
            assertThat(result).isTrue();
        }
    }
}
