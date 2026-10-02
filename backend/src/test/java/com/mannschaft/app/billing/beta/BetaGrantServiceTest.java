package com.mannschaft.app.billing.beta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementCacheEvictor;
import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementIssuanceService;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gamification.service.BetaTesterBadgeAwardService;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BetaGrantService} 単体テスト（F20.3 付与本体・試練先行・Clock 固定）。
 *
 * <p>受け入れ条件: 付与成功(issue 呼出)・GRANT_SCOPE_MISMATCH・BETA_PHASE_INVALID・GRANT_ALREADY_EXISTS・
 * ACTIVITY_CRITERIA_NOT_MET・AC-I1(issue 例外→後続不実行=全ロールバック)・AC-I3(バッジ失敗でも付与成立)・
 * AC-I4(二重取消→GRANT_ALREADY_REVOKED)・EXTEND_NOT_APPLICABLE・AC-P4(延長は append-only)。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BetaGrantService 単体テスト（付与/取消/延長の原子性・不変条件）")
class BetaGrantServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_CLOCK.instant(), ZoneOffset.UTC);
    private static final List<String> FULL_KEYS = List.of("ads.hide", "template.premium_modules");

    @Mock private BetaGrantRepository betaGrantRepository;
    @Mock private EntitlementRepository entitlementRepository;
    @Mock private PlanFeatureRepository planFeatureRepository;
    @Mock private EntitlementIssuanceService entitlementIssuanceService;
    @Mock private EntitlementCacheEvictor cacheEvictor;
    @Mock private ScopeMemberCountService scopeMemberCountService;
    @Mock private BetaPerkEligibilityService eligibilityService;
    @Mock private BetaTesterBadgeAwardService betaTesterBadgeAwardService;
    @Mock private NotificationHelper notificationHelper;
    @Mock private org.springframework.context.MessageSource messageSource;

    private BetaGrantService service;

    @BeforeEach
    void setUp() {
        service = new BetaGrantService(
                betaGrantRepository, entitlementRepository, planFeatureRepository,
                entitlementIssuanceService, cacheEvictor, scopeMemberCountService,
                eligibilityService, betaTesterBadgeAwardService, notificationHelper,
                messageSource, new ObjectMapper(), FIXED_CLOCK);
        // 通知本文の解決（notify する経路のみ使用・未使用テストで落とさないよう lenient）。
        lenient().when(messageSource.getMessage(any(), any(), any())).thenReturn("msg");
        // save は付与/取消/延長で使用（検証系 throw テストでは未使用のため lenient）。id を採番して返す。
        lenient().when(betaGrantRepository.save(any())).thenAnswer(inv -> {
            BetaGrantEntity g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId(UUID.randomUUID());
            }
            return g;
        });
    }

    private void givenFullPlan() {
        given(planFeatureRepository.findByPlanKey("FULL")).willReturn(List.of(
                PlanFeatureEntity.builder().planKey("FULL").featureKey("ads.hide").build(),
                PlanFeatureEntity.builder().planKey("FULL").featureKey("template.premium_modules").build()));
    }

    private BetaGrantEntity grantEntity(
            GrantKind kind, EntitlementScopeKind scopeKind, Long scopeId, Long orgId) {
        BetaGrantEntity g = BetaGrantEntity.builder()
                .grantKind(kind).betaPhase(2).scopeKind(scopeKind).scopeId(scopeId).organizationId(orgId)
                .criteriaSnapshot("{}").grantedFeatureKeys("[]").transferable(false).reviewFlag(false)
                .grantedAt(NOW).build();
        g.setId(UUID.randomUUID());
        return g;
    }

    // ------------------------------------------------------------------ 付与

    @Test
    @DisplayName("付与成功(INDIVIDUAL): issue が USER/BETA_GRANT/無期限で呼ばれ、バッジ授与＋通知される")
    void grant_individual_success() {
        given(betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, 42L, 1)).willReturn(Optional.empty());
        given(eligibilityService.evaluate(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, 1))
                .willReturn(new EligibilityResult(true, List.of(new MetricProgress("membershipTenureDays", 40, 30)), 1, 60));
        givenFullPlan();

        service.grantBetaPerk(GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, 42L, null, false, 9L);

        verify(entitlementIssuanceService).issue(eq(EntitlementScopeKind.USER), eq(42L), isNull(),
                eq(FULL_KEYS), eq(EntitlementSourceKind.BETA_GRANT), any(UUID.class), isNull());
        verify(betaTesterBadgeAwardService).awardBetaTesterBadge(42L, 1);
        verify(notificationHelper).notify(eq(42L), eq("BETA_PERK_GRANTED"), any(), any(), any(),
                any(), any(), any(), any(), any(), any());

        ArgumentCaptor<BetaGrantEntity> captor = ArgumentCaptor.forClass(BetaGrantEntity.class);
        verify(betaGrantRepository).save(captor.capture());
        assertThat(captor.getValue().isTransferable()).isFalse();
        assertThat(captor.getValue().getActiveMemberCountSnapshot()).isNull();
    }

    @Test
    @DisplayName("付与成功(TEAM_ORG): valid_until=now+2年、人数スナップショットが焼き付く（バッジは授与しない）")
    void grant_teamOrg_success() {
        given(betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.TEAM, 7L, 2)).willReturn(Optional.empty());
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 7L)).willReturn(6);
        given(eligibilityService.evaluate(GrantKind.TEAM_ORG, EntitlementScopeKind.TEAM, 7L, 2))
                .willReturn(new EligibilityResult(true, List.of(), 2, 60));
        givenFullPlan();

        service.grantBetaPerk(GrantKind.TEAM_ORG, 2, EntitlementScopeKind.TEAM, 7L, 99L, false, 9L);

        verify(entitlementIssuanceService).issue(eq(EntitlementScopeKind.TEAM), eq(7L), eq(99L),
                eq(FULL_KEYS), eq(EntitlementSourceKind.BETA_GRANT), any(UUID.class), eq(NOW.plusYears(2)));
        verify(betaTesterBadgeAwardService, never()).awardBetaTesterBadge(anyLong(), anyInt());
        ArgumentCaptor<BetaGrantEntity> captor = ArgumentCaptor.forClass(BetaGrantEntity.class);
        verify(betaGrantRepository).save(captor.capture());
        assertThat(captor.getValue().getActiveMemberCountSnapshot()).isEqualTo(6);
    }

    @Test
    @DisplayName("skipCriteriaCheck=true: eligibility を評価せず付与する")
    void grant_skipCriteriaCheck_bypassesEvaluation() {
        given(betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, 42L, 1)).willReturn(Optional.empty());
        givenFullPlan();

        service.grantBetaPerk(GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, 42L, null, true, 9L);

        verify(eligibilityService, never()).evaluate(any(), any(), any(), anyInt());
        verify(entitlementIssuanceService).issue(any(), any(), any(), anyList(), any(), any(), isNull());
    }

    @Test
    @DisplayName("kind×scope 不整合(INDIVIDUAL×TEAM) は GRANT_SCOPE_MISMATCH(422)")
    void grant_kindScopeMismatch() {
        assertThatThrownBy(() -> service.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.TEAM, 7L, 99L, false, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.GRANT_SCOPE_MISMATCH);
        verify(entitlementIssuanceService, never()).issue(any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("beta_phase 範囲外(5) は BETA_PHASE_INVALID(400)")
    void grant_phaseInvalid() {
        assertThatThrownBy(() -> service.grantBetaPerk(
                GrantKind.INDIVIDUAL, 5, EntitlementScopeKind.USER, 42L, null, false, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.BETA_PHASE_INVALID);
    }

    @Test
    @DisplayName("同一 scope×phase 既存(取消含む) は GRANT_ALREADY_EXISTS(409)")
    void grant_duplicate() {
        given(betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, 42L, 1))
                .willReturn(Optional.of(grantEntity(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, null)));

        assertThatThrownBy(() -> service.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, 42L, null, false, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.GRANT_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("skip=false かつ未達 は ACTIVITY_CRITERIA_NOT_MET(422)・details に実測/閾値")
    void grant_criteriaNotMet() {
        given(betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, 42L, 1)).willReturn(Optional.empty());
        given(eligibilityService.evaluate(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, 1))
                .willReturn(new EligibilityResult(false,
                        List.of(new MetricProgress("membershipTenureDays", 20, 30)), 1, 60));

        assertThatThrownBy(() -> service.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, 42L, null, false, 9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(BetaPerkErrorCode.ACTIVITY_CRITERIA_NOT_MET);
                    assertThat(be.getFieldErrors()).singleElement().satisfies(fe -> {
                        assertThat(fe.getField()).isEqualTo("membershipTenureDays");
                        assertThat(fe.getMessage()).contains("actual=20", "required=30");
                    });
                });
        verify(entitlementIssuanceService, never()).issue(any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-I1: issue が例外を投げると後続(バッジ/通知)は実行されない（単一tx全ロールバック）")
    void grant_issueThrows_rollsBackAll() {
        given(betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, 42L, 1)).willReturn(Optional.empty());
        givenFullPlan();
        willThrow(new BusinessException(com.mannschaft.app.billing.EntitlementErrorCode.DUPLICATE_ENTITLEMENT))
                .given(entitlementIssuanceService)
                .issue(any(), any(), any(), anyList(), any(), any(), any());

        assertThatThrownBy(() -> service.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, 42L, null, true, 9L))
                .isInstanceOf(BusinessException.class);

        verify(betaTesterBadgeAwardService, never()).awardBetaTesterBadge(anyLong(), anyInt());
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(cacheEvictor, never()).evictScopeFeatures(any(), any(), any());
    }

    @Test
    @DisplayName("AC-I3: バッジ授与が失敗しても付与本体は成立する（issue 済み・通知される）")
    void grant_badgeFailure_grantStillSucceeds() {
        given(betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, 42L, 1)).willReturn(Optional.empty());
        givenFullPlan();
        willThrow(new RuntimeException("badge boom"))
                .given(betaTesterBadgeAwardService).awardBetaTesterBadge(42L, 1);

        service.grantBetaPerk(GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, 42L, null, true, 9L);

        verify(entitlementIssuanceService).issue(any(), any(), any(), anyList(), any(), any(), isNull());
        verify(notificationHelper).notify(eq(42L), eq("BETA_PERK_GRANTED"), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 取消

    @Test
    @DisplayName("AC-I4: 取消済み grant への再取消は GRANT_ALREADY_REVOKED(409)")
    void revoke_twice_alreadyRevoked() {
        BetaGrantEntity grant = grantEntity(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, null);
        grant.revoke(BetaRevokeReason.OTHER, 1L); // 1 回目（既に取消済みの状態を作る）。
        given(betaGrantRepository.findById(grant.getId())).willReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.revoke(grant.getId(), BetaRevokeReason.TERMS_VIOLATION, 2L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.GRANT_ALREADY_REVOKED);
    }

    @Test
    @DisplayName("取消成功: 由来 entitlements を全 revoke し evict する")
    void revoke_success_revokesEntitlements() {
        BetaGrantEntity grant = grantEntity(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, null);
        given(betaGrantRepository.findById(grant.getId())).willReturn(Optional.of(grant));
        EntitlementEntity e1 = EntitlementEntity.builder().featureKey("ads.hide").scopeKind(EntitlementScopeKind.USER).scopeId(42L).build();
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.BETA_GRANT, grant.getId())).willReturn(List.of(e1));

        service.revoke(grant.getId(), BetaRevokeReason.TERMS_VIOLATION, 2L, null);

        assertThat(grant.isRevoked()).isTrue();
        assertThat(e1.getRevokedAt()).isEqualTo(NOW);
        verify(entitlementRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("他テナントの grant 取消は GRANT_NOT_FOUND(404) 秘匿")
    void revoke_otherTenant_notFound() {
        BetaGrantEntity grant = grantEntity(GrantKind.TEAM_ORG, EntitlementScopeKind.TEAM, 7L, 99L);
        given(betaGrantRepository.findById(grant.getId())).willReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.revoke(grant.getId(), BetaRevokeReason.OTHER, 2L, 123L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.GRANT_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 延長

    @Test
    @DisplayName("延長(INDIVIDUAL) は EXTEND_NOT_APPLICABLE(422)")
    void extend_individual_notApplicable() {
        BetaGrantEntity grant = grantEntity(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, null);
        given(betaGrantRepository.findById(grant.getId())).willReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.extend(grant.getId(), 6, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.EXTEND_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("AC-P4: 延長は append-only。既存 entitlements を UPDATE せず、新 valid_until=最大+月数 で issue する")
    void extend_teamOrg_appendOnly() {
        BetaGrantEntity grant = grantEntity(GrantKind.TEAM_ORG, EntitlementScopeKind.TEAM, 7L, 99L);
        given(betaGrantRepository.findById(grant.getId())).willReturn(Optional.of(grant));
        LocalDateTime until = NOW.plusYears(2);
        EntitlementEntity e1 = EntitlementEntity.builder().featureKey("ads.hide")
                .scopeKind(EntitlementScopeKind.TEAM).scopeId(7L).validUntil(until).build();
        EntitlementEntity e2 = EntitlementEntity.builder().featureKey("template.premium_modules")
                .scopeKind(EntitlementScopeKind.TEAM).scopeId(7L).validUntil(until).build();
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.BETA_GRANT, grant.getId())).willReturn(List.of(e1, e2));

        service.extend(grant.getId(), 6, 99L);

        verify(entitlementIssuanceService).issue(eq(EntitlementScopeKind.TEAM), eq(7L), eq(99L),
                eq(List.of("ads.hide", "template.premium_modules")),
                eq(EntitlementSourceKind.BETA_GRANT), eq(grant.getId()), eq(until.plusMonths(6)));
        // append-only: 既存行の UPDATE(saveAll)はしない。
        verify(entitlementRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("延長月数が範囲外(25) は 400（COMMON_001）")
    void extend_monthsOutOfRange() {
        BetaGrantEntity grant = grantEntity(GrantKind.TEAM_ORG, EntitlementScopeKind.TEAM, 7L, 99L);
        given(betaGrantRepository.findById(grant.getId())).willReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.extend(grant.getId(), 25, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_001);
    }

    // ------------------------------------------------------------------ 退会一括取消

    @Test
    @DisplayName("revokeAllForUser: USER の有効特典を全取消し由来 entitlements を失効する")
    void revokeAllForUser_revokesActiveGrants() {
        BetaGrantEntity grant = grantEntity(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, null);
        given(betaGrantRepository.findByScopeKindAndScopeIdAndRevokedAtIsNull(
                EntitlementScopeKind.USER, 42L)).willReturn(List.of(grant));
        EntitlementEntity e1 = EntitlementEntity.builder().featureKey("ads.hide")
                .scopeKind(EntitlementScopeKind.USER).scopeId(42L).build();
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.BETA_GRANT, grant.getId())).willReturn(List.of(e1));

        service.revokeAllForUser(42L, BetaRevokeReason.WITHDRAWAL);

        assertThat(grant.isRevoked()).isTrue();
        assertThat(grant.getRevokeReason()).isEqualTo(BetaRevokeReason.WITHDRAWAL);
        assertThat(e1.getRevokedAt()).isEqualTo(NOW);
    }
}
