package com.mannschaft.app.billing.beta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.dto.BetaGrantDetailResponse;
import com.mannschaft.app.billing.beta.dto.BetaGrantPageResponse;
import com.mannschaft.app.billing.beta.dto.MyBetaPerksResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F20.3 {@link BetaGrantQueryService} の /me eligibility 挙動 単体試練（AC-N4）。
 *
 * <p>現行フェーズの criteria が未定義（{@link BetaPerkErrorCode#CRITERIA_NOT_FOUND}）のとき、eligibility は
 * <b>null で返る</b>（例外を握り潰す症状隠蔽ではなく、設計仕様に基づく唯一の例外的 catch・NPE/404 にしない）。
 * それ以外の {@link BusinessException} は素通しで伝播する（対処療法にしない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F20.3 BetaGrantQueryService /me eligibility 試練")
class BetaGrantQueryServiceTest {

    @Mock
    private BetaGrantRepository betaGrantRepository;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private BetaPerkEligibilityService eligibilityService;
    @Mock
    private BetaPerkScopeNameResolver scopeNameResolver;
    @Spy
    private BetaGrantResponseMapper mapper = new BetaGrantResponseMapper(new ObjectMapper());

    @InjectMocks
    private BetaGrantQueryService queryService;

    private static final long USER_ID = 9L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(queryService, "currentPhase", 2);
        // lenient: 本スタブは getMyBetaPerks 系テストのみ使用する（searchGrants/getDetail 系テストでは
        // 未使用となり MockitoExtension の strict stub 検査に引っかかるため）。
        org.mockito.Mockito.lenient()
                .when(betaGrantRepository.findByScopeKindAndScopeIdOrderByGrantedAtDesc(
                        EntitlementScopeKind.USER, USER_ID))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("AC-N4: criteria 未定義（CRITERIA_NOT_FOUND）のとき eligibility は null（404/NPE にしない）")
    void getMyBetaPerks_criteriaNotFound_eligibilityNull() {
        willThrow(new BusinessException(BetaPerkErrorCode.CRITERIA_NOT_FOUND))
                .given(eligibilityService).evaluate(
                        eq(GrantKind.INDIVIDUAL), eq(EntitlementScopeKind.USER), eq(USER_ID), anyInt());

        MyBetaPerksResponse res = queryService.getMyBetaPerks(USER_ID);

        assertThat(res.getEligibility()).isNull();
        assertThat(res.getGrants()).isEmpty();
    }

    @Test
    @DisplayName("AC-N4: criteria 定義済みなら eligibility を返す")
    void getMyBetaPerks_criteriaDefined_eligibilityPresent() {
        given(eligibilityService.evaluate(
                eq(GrantKind.INDIVIDUAL), eq(EntitlementScopeKind.USER), eq(USER_ID), anyInt()))
                .willReturn(new EligibilityResult(false,
                        List.of(new MetricProgress("activeDays", 9, 14)), 2, 30));

        MyBetaPerksResponse res = queryService.getMyBetaPerks(USER_ID);

        assertThat(res.getEligibility()).isNotNull();
        assertThat(res.getEligibility().getBetaPhase()).isEqualTo(2);
        assertThat(res.getEligibility().isEligible()).isFalse();
        assertThat(res.getEligibility().getMetrics()).hasSize(1);
    }

    @Test
    @DisplayName("AC-N4: CRITERIA_NOT_FOUND 以外の BusinessException は伝播する（握り潰さない）")
    void getMyBetaPerks_otherBusinessException_propagates() {
        willThrow(new BusinessException(BetaPerkErrorCode.BETA_PHASE_INVALID))
                .given(eligibilityService).evaluate(any(), any(), any(), anyInt());

        assertThatThrownBy(() -> queryService.getMyBetaPerks(USER_ID))
                .isInstanceOf(BusinessException.class);
    }

    // ============================================================
    // Phase3 追補: 表示名（scopeDisplayName/grantedByName）のバルク解決・N+1 回避
    // ============================================================

    private static BetaGrantEntity grant(
            EntitlementScopeKind scopeKind, GrantKind grantKind, Long scopeId, Long grantedBy) {
        BetaGrantEntity g = BetaGrantEntity.builder()
                .grantKind(grantKind)
                .betaPhase(2)
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .grantedAt(LocalDateTime.now())
                .grantedBy(grantedBy)
                .build();
        g.setId(UUID.randomUUID());
        return g;
    }

    @Test
    @DisplayName("Phase3: 付与一覧は scope種別(TEAM/ORG/USER)ごと・grantedByごとに各1回だけ名前解決する(N+1回避)")
    void searchGrants_resolvesScopeAndGrantedByNamesInBulk_noNPlusOne() {
        BetaGrantEntity teamGrant = grant(EntitlementScopeKind.TEAM, GrantKind.TEAM_ORG, 10L, 100L);
        BetaGrantEntity orgGrant = grant(EntitlementScopeKind.ORG, GrantKind.TEAM_ORG, 20L, 100L);
        BetaGrantEntity userGrant = grant(EntitlementScopeKind.USER, GrantKind.INDIVIDUAL, 30L, null);

        given(betaGrantRepository.searchGrantsWithScope(any(), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(teamGrant, orgGrant, userGrant)));
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(any(), any()))
                .willReturn(List.of());
        given(scopeNameResolver.resolveScopeNames(eq(EntitlementScopeKind.TEAM), any()))
                .willReturn(Map.of(10L, "チームA"));
        given(scopeNameResolver.resolveScopeNames(eq(EntitlementScopeKind.ORG), any()))
                .willReturn(Map.of(20L, "組織B"));
        given(scopeNameResolver.resolveScopeNames(eq(EntitlementScopeKind.USER), any()))
                .willReturn(Map.of(30L, "山田太郎"));
        given(scopeNameResolver.resolveUserNames(any()))
                .willReturn(Map.of(100L, "管理者鈴木"));

        BetaGrantPageResponse res = queryService.searchGrants(null, null, null, null, null, 0, 20);

        assertThat(res.getContent()).hasSize(3);
        BetaGrantDetailResponse team = res.getContent().stream()
                .filter(d -> "TEAM".equals(d.getScopeKind())).findFirst().orElseThrow();
        BetaGrantDetailResponse org = res.getContent().stream()
                .filter(d -> "ORG".equals(d.getScopeKind())).findFirst().orElseThrow();
        BetaGrantDetailResponse user = res.getContent().stream()
                .filter(d -> "USER".equals(d.getScopeKind())).findFirst().orElseThrow();

        assertThat(team.getScopeDisplayName()).isEqualTo("チームA");
        assertThat(team.getGrantedByName()).isEqualTo("管理者鈴木");
        assertThat(org.getScopeDisplayName()).isEqualTo("組織B");
        assertThat(org.getGrantedByName()).isEqualTo("管理者鈴木");
        assertThat(user.getScopeDisplayName()).isEqualTo("山田太郎");
        // grantedBy が null（自動付与バッチ）のとき grantedByName も null（FE が SYSTEM 表示に使う）。
        assertThat(user.getGrantedByName()).isNull();

        // N+1 回避の証明: scope種別ごとに 1 回だけ（ページ内 3 件でも 3 回に増えない）・grantedBy解決も 1 回だけ。
        verify(scopeNameResolver, times(1)).resolveScopeNames(eq(EntitlementScopeKind.TEAM), any());
        verify(scopeNameResolver, times(1)).resolveScopeNames(eq(EntitlementScopeKind.ORG), any());
        verify(scopeNameResolver, times(1)).resolveScopeNames(eq(EntitlementScopeKind.USER), any());
        verify(scopeNameResolver, times(1)).resolveUserNames(any());
    }

    @Test
    @DisplayName("Phase3: 付与一覧で該当scope種別が0件ならその種別の名前解決は呼ばない")
    void searchGrants_skipsResolverCallForAbsentScopeKind() {
        BetaGrantEntity teamGrant = grant(EntitlementScopeKind.TEAM, GrantKind.TEAM_ORG, 10L, null);

        given(betaGrantRepository.searchGrantsWithScope(any(), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(teamGrant)));
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(any(), any()))
                .willReturn(List.of());
        given(scopeNameResolver.resolveScopeNames(eq(EntitlementScopeKind.TEAM), any()))
                .willReturn(Map.of(10L, "チームA"));

        queryService.searchGrants(null, null, null, null, null, 0, 20);

        verify(scopeNameResolver, times(1)).resolveScopeNames(eq(EntitlementScopeKind.TEAM), any());
        verify(scopeNameResolver, never())
                .resolveScopeNames(eq(EntitlementScopeKind.ORG), any());
        verify(scopeNameResolver, never())
                .resolveScopeNames(eq(EntitlementScopeKind.USER), any());
        verify(scopeNameResolver, never()).resolveUserNames(any());
    }

    @Test
    @DisplayName("Phase3: 付与詳細(単票)にも scopeDisplayName/grantedByName が載る")
    void getDetail_includesScopeAndGrantedByName() {
        BetaGrantEntity teamGrant = grant(EntitlementScopeKind.TEAM, GrantKind.TEAM_ORG, 10L, 100L);
        given(betaGrantRepository.findById(teamGrant.getId())).willReturn(java.util.Optional.of(teamGrant));
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(any(), any()))
                .willReturn(List.of());
        given(scopeNameResolver.resolveScopeNames(EntitlementScopeKind.TEAM, List.of(10L)))
                .willReturn(Map.of(10L, "チームA"));
        given(scopeNameResolver.resolveUserNames(List.of(100L)))
                .willReturn(Map.of(100L, "管理者鈴木"));

        BetaGrantDetailResponse detail = queryService.getDetail(teamGrant.getId());

        assertThat(detail.getScopeDisplayName()).isEqualTo("チームA");
        assertThat(detail.getGrantedByName()).isEqualTo("管理者鈴木");
    }

    @Test
    @DisplayName("Phase3: grantedBy が null（自動付与バッチ）の単票詳細は grantedByName も null（user解決を呼ばない）")
    void getDetail_grantedByNull_grantedByNameNullAndSkipsUserResolve() {
        BetaGrantEntity userGrant = grant(EntitlementScopeKind.USER, GrantKind.INDIVIDUAL, 30L, null);
        given(betaGrantRepository.findById(userGrant.getId())).willReturn(java.util.Optional.of(userGrant));
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(any(), any()))
                .willReturn(List.of());
        given(scopeNameResolver.resolveScopeNames(EntitlementScopeKind.USER, List.of(30L)))
                .willReturn(Map.of(30L, "山田太郎"));

        BetaGrantDetailResponse detail = queryService.getDetail(userGrant.getId());

        assertThat(detail.getScopeDisplayName()).isEqualTo("山田太郎");
        assertThat(detail.getGrantedByName()).isNull();
        verify(scopeNameResolver, never()).resolveUserNames(any());
    }
}
