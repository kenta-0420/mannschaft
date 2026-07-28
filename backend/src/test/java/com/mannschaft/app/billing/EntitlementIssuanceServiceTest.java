package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EntitlementIssuanceService} 単体テスト（F20.3 発行基盤・試練先行）。
 *
 * <p>元 {@code BillingContractService.issueEntitlements} から抽出した発行ロジックを検証する。
 * BETA_GRANT 発行元・有期限（valid_until）・二重発行の {@code uk_ent_grant} 変換を対象にする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntitlementIssuanceService 単体テスト（entitlements 発行の共有基盤）")
class EntitlementIssuanceServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_CLOCK.instant(), ZoneOffset.UTC);

    @Mock private EntitlementRepository entitlementRepository;

    private EntitlementIssuanceService service;

    @BeforeEach
    void setUp() {
        service = new EntitlementIssuanceService(entitlementRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("BETA_GRANT の発行: 各行に scope/feature/source/validFrom(now) が正しく載る（無期限）")
    void issue_betaGrant_infinite() {
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        UUID grantId = UUID.randomUUID();

        List<String> issued = service.issue(
                EntitlementScopeKind.USER, 42L, null,
                List.of("ads.hide", "template.premium_modules"),
                EntitlementSourceKind.BETA_GRANT, grantId, null);

        assertThat(issued).containsExactly("ads.hide", "template.premium_modules");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EntitlementEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(entitlementRepository).saveAll(captor.capture());
        verify(entitlementRepository).flush();

        assertThat(captor.getValue()).hasSize(2).allSatisfy(e -> {
            assertThat(e.getScopeKind()).isEqualTo(EntitlementScopeKind.USER);
            assertThat(e.getScopeId()).isEqualTo(42L);
            assertThat(e.getSourceKind()).isEqualTo(EntitlementSourceKind.BETA_GRANT);
            assertThat(e.getSourceRefId()).isEqualTo(grantId);
            assertThat(e.getValidFrom()).isEqualTo(NOW);
            assertThat(e.getValidUntil()).isNull();
            assertThat(e.getOrganizationId()).isNull();
        });
        assertThat(captor.getValue()).extracting(EntitlementEntity::getFeatureKey)
                .containsExactly("ads.hide", "template.premium_modules");
    }

    @Test
    @DisplayName("有期限の発行: valid_until がそのまま反映される（TEAM_ORG 2 年など）")
    void issue_withValidUntil() {
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        UUID grantId = UUID.randomUUID();
        LocalDateTime until = NOW.plusYears(2);

        service.issue(EntitlementScopeKind.TEAM, 7L, 99L, List.of("ads.hide"),
                EntitlementSourceKind.BETA_GRANT, grantId, until);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EntitlementEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(entitlementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(e -> {
            assertThat(e.getValidFrom()).isEqualTo(NOW);
            assertThat(e.getValidUntil()).isEqualTo(until);
            assertThat(e.getOrganizationId()).isEqualTo(99L);
        });
    }

    @Test
    @DisplayName("feature_key 集合が空なら no-op（saveAll/flush を呼ばず空リストを返す）")
    void issue_emptyFeatureKeys_noop() {
        List<String> issued = service.issue(
                EntitlementScopeKind.USER, 1L, null, List.of(),
                EntitlementSourceKind.BETA_GRANT, UUID.randomUUID(), null);

        assertThat(issued).isEmpty();
        verify(entitlementRepository, never()).saveAll(anyList());
        verify(entitlementRepository, never()).flush();
    }

    @Test
    @DisplayName("uk_ent_grant 違反（flush で DataIntegrityViolation）は DUPLICATE_ENTITLEMENT に変換")
    void issue_duplicate_throwsDuplicate() {
        given(entitlementRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        org.mockito.BDDMockito.willThrow(new DataIntegrityViolationException("uk_ent_grant"))
                .given(entitlementRepository).flush();

        assertThatThrownBy(() -> service.issue(
                EntitlementScopeKind.USER, 1L, null, List.of("ads.hide"),
                EntitlementSourceKind.BETA_GRANT, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntitlementErrorCode.DUPLICATE_ENTITLEMENT);
    }
}
