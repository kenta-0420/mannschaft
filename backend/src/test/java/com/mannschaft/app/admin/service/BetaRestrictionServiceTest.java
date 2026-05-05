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
 * ベータ登録制限の有効判定・トークン検証ロジックを検証する。
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
        @DisplayName("トークンが存在しない場合 false を返す")
        void isBetaTokenValid_トークン不存在_false() {
            // Given
            given(inviteTokenRepository.findByToken("nonexistent")).willReturn(Optional.empty());

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("nonexistent");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("トークンが存在するが isValid()=false の場合 false を返す")
        void isBetaTokenValid_トークン無効_false() {
            // Given: 期限切れトークン（revokedAtあり）
            InviteTokenEntity revokedToken = InviteTokenEntity.builder()
                    .token("revoked-token")
                    .teamId(1L)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .revokedAt(LocalDateTime.now().minusDays(1))
                    .build();
            given(inviteTokenRepository.findByToken("revoked-token")).willReturn(Optional.of(revokedToken));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("revoked-token");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("teamId・orgId が両方 null の場合 false を返す")
        void isBetaTokenValid_teamIdOrgIdともnull_false() {
            // Given: teamId/orgId 両方 null の有効トークン
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("no-target-token")
                    .teamId(null)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .build();
            given(inviteTokenRepository.findByToken("no-target-token")).willReturn(Optional.of(token));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("no-target-token");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("teamId=5・maxTeamId=10・制限ON の場合 true を返す")
        void isBetaTokenValid_teamId5_maxTeamId10_true() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("team-token")
                    .teamId(5L)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .build();
            given(inviteTokenRepository.findByToken("team-token")).willReturn(Optional.of(token));
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(10L)
                    .maxOrgId(null)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("team-token");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("teamId=15・maxTeamId=10・制限ON の場合 false を返す")
        void isBetaTokenValid_teamId15_maxTeamId10_false() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("team-over-token")
                    .teamId(15L)
                    .organizationId(null)
                    .roleId(1L)
                    .usedCount(0)
                    .build();
            given(inviteTokenRepository.findByToken("team-over-token")).willReturn(Optional.of(token));
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(10L)
                    .maxOrgId(null)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("team-over-token");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("orgId=3・maxOrgId=10・制限ON の場合 true を返す")
        void isBetaTokenValid_orgId3_maxOrgId10_true() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("org-token")
                    .teamId(null)
                    .organizationId(3L)
                    .roleId(1L)
                    .usedCount(0)
                    .build();
            given(inviteTokenRepository.findByToken("org-token")).willReturn(Optional.of(token));
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(null)
                    .maxOrgId(10L)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("org-token");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("orgId=15・maxOrgId=10・制限ON の場合 false を返す")
        void isBetaTokenValid_orgId15_maxOrgId10_false() {
            // Given
            InviteTokenEntity token = InviteTokenEntity.builder()
                    .token("org-over-token")
                    .teamId(null)
                    .organizationId(15L)
                    .roleId(1L)
                    .usedCount(0)
                    .build();
            given(inviteTokenRepository.findByToken("org-over-token")).willReturn(Optional.of(token));
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(null)
                    .maxOrgId(10L)
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.of(config));

            // When
            boolean result = betaRestrictionService.isBetaTokenValid("org-over-token");

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
        @DisplayName("レコードなし → デフォルト(isEnabled=false) で返す")
        void getConfig_レコードなし_デフォルト() {
            // Given
            given(repo.findTopByOrderByIdAsc()).willReturn(Optional.empty());

            // When
            BetaRestrictionConfigResponse config = betaRestrictionService.getConfig();

            // Then
            assertThat(config.getIsEnabled()).isFalse();
            assertThat(config.getMaxTeamId()).isNull();
            assertThat(config.getMaxOrgId()).isNull();
        }
    }

    // ========================================
    // isEnabled
    // ========================================

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("レコードあり・isEnabled=true → true を返す")
        void isEnabled_レコードあり_isEnabledTrue_true() {
            // Given
            BetaRestrictionConfigEntity config = BetaRestrictionConfigEntity.builder()
                    .isEnabled(true)
                    .maxTeamId(10L)
                    .maxOrgId(10L)
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
