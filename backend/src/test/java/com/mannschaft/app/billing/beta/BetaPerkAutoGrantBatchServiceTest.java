package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BetaPerkAutoGrantBatchService} の単体テスト（F20.3 Phase2 Wave2a・判定ロジックの決定論的検証）。
 *
 * <p>bulk 結果をモックして「メモリ内判定・付与呼び出し・冪等 skip・途中失敗継続・N+1 回避」を検証する。
 * 実 DB を伴う N+1/冪等/退会除外は {@code BetaPerkAutoGrantBatchIT}（Testcontainers）で担保する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BetaPerkAutoGrantBatchService（個人ベータ特典 自動付与バッチ）")
class BetaPerkAutoGrantBatchServiceTest {

    private static final int PHASE = 1;
    /** 固定 Clock（評価ウィンドウ・在籍日数を決定論化）。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    @Mock private BetaPerkCriteriaRepository criteriaRepository;
    @Mock private LoginActivityQueryService loginActivityQueryService;
    @Mock private MembershipQueryService membershipQueryService;
    @Mock private BetaGrantRepository betaGrantRepository;
    @Mock private UserRepository userRepository;
    @Mock private BetaGrantService betaGrantService;

    private BetaPerkAutoGrantBatchService batch;

    @BeforeEach
    void setUp() {
        batch = new BetaPerkAutoGrantBatchService(
                criteriaRepository, loginActivityQueryService, membershipQueryService,
                betaGrantRepository, userRepository, betaGrantService, FIXED_CLOCK);
        // 既定は「有効・phase=1」。個別テストで無効化する。
        ReflectionTestUtils.setField(batch, "autoGrantEnabled", true);
        ReflectionTestUtils.setField(batch, "currentPhase", PHASE);
    }

    /** activeDays 指標のみ（min=5・window=30日）の enabled な INDIVIDUAL criteria。 */
    private BetaPerkCriteriaEntity activeDaysCriteria(int minActiveDays) {
        return BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE)
                .grantKind(GrantKind.INDIVIDUAL)
                .evaluationWindowDays(30)
                .minActiveDays(minActiveDays)
                .minMembershipTenureDays(null)
                .minActiveMembers(null)
                .enabled(true)
                .build();
    }

    private void stubSinglePage(List<Long> userIds) {
        Page<Long> page = new PageImpl<>(userIds, Pageable.unpaged(), userIds.size());
        when(userRepository.findActiveUserIdsForBeta(any())).thenReturn(page);
    }

    // ============================================================
    // AC-N3 / AC-C3: enabled=false は完全 no-op
    // ============================================================

    /**
     * AC-N3 / AC-C3: {@code mannschaft.beta.auto-grant.enabled=false} なら 1 件も付与しない
     * （走査すら行わない完全 no-op）。本番の起動ゲートはこの 1 フラグのみ。
     */
    @Test
    @DisplayName("AC-N3/AC-C3: auto-grant.enabled=false なら一切走査せず付与0（no-op）")
    void disabled_isNoOp() {
        ReflectionTestUtils.setField(batch, "autoGrantEnabled", false);

        batch.execute();

        verifyNoInteractions(criteriaRepository, userRepository, betaGrantService,
                loginActivityQueryService, membershipQueryService, betaGrantRepository);
    }

    // ============================================================
    // criteria 未定義 / 全指標NULL は付与0
    // ============================================================

    @Test
    @DisplayName("criteria 未定義（phase 該当なし）なら付与0・ユーザー走査もしない")
    void criteriaMissing_grantsNothing() {
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.empty());

        batch.execute();

        verify(userRepository, never()).findActiveUserIdsForBeta(any());
        verify(betaGrantService, never()).grantBetaPerk(any(), anyInt(), any(), anyLong(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("criteria が enabled=false なら付与0（未定義と同扱い）")
    void criteriaDisabled_grantsNothing() {
        BetaPerkCriteriaEntity disabled = activeDaysCriteria(5);
        disabled.setEnabled(false);
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(disabled));

        batch.execute();

        verify(userRepository, never()).findActiveUserIdsForBeta(any());
    }

    @Test
    @DisplayName("両指標NULL（無条件付与）なら付与0（バラ撒き防止）")
    void bothMetricsNull_grantsNothing() {
        BetaPerkCriteriaEntity bothNull = BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE).grantKind(GrantKind.INDIVIDUAL).evaluationWindowDays(30)
                .minActiveDays(null).minMembershipTenureDays(null).minActiveMembers(null)
                .enabled(true).build();
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(bothNull));

        batch.execute();

        verify(userRepository, never()).findActiveUserIdsForBeta(any());
        verify(betaGrantService, never()).grantBetaPerk(any(), anyInt(), any(), anyLong(), any(), anyBoolean(), any());
    }

    // ============================================================
    // AC-P1/P2: bulk は 1 ページ 1 回・per-user evaluate を呼ばない
    // ============================================================

    @Test
    @DisplayName("AC-P1/P2: 1ページのユーザー数に依らず activeDays bulk は1回だけ・per-user 版は呼ばない")
    void bulkQueriedOncePerPage_noPerUserCall() {
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(activeDaysCriteria(5)));
        List<Long> users = List.of(10L, 11L, 12L, 13L, 14L);
        stubSinglePage(users);
        when(betaGrantRepository.findGrantedScopeIds(eq(PHASE), eq(EntitlementScopeKind.USER), any()))
                .thenReturn(List.of());
        // 全員 activeDays 十分（付与される）。
        when(loginActivityQueryService.countDistinctActiveDaysWithinByUsers(any(), anyInt(), any()))
                .thenReturn(Map.of(10L, 9L, 11L, 9L, 12L, 9L, 13L, 9L, 14L, 9L));

        batch.execute();

        // bulk は 1 ページ 1 回のみ（ユーザー数 5 でも 1 回）。
        verify(loginActivityQueryService, times(1))
                .countDistinctActiveDaysWithinByUsers(any(), anyInt(), any());
        // per-user 版（@Cacheable evaluate の代替経路）は一切呼ばない。
        verify(loginActivityQueryService, never()).countDistinctActiveDaysWithin(anyLong(), anyInt(), any());
        // 5 人全員に付与（skipCriteriaCheck=true / grantedBy=null）。
        verify(betaGrantService, times(5)).grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                anyLong(), isNull(), eq(true), isNull());
    }

    // ============================================================
    // AC-B1: 境界（actual==min で付与・min-1 で非付与）
    // ============================================================

    @Test
    @DisplayName("AC-B1: activeDays==min ちょうどで付与・min-1 は非付与（境界は「以上」）")
    void boundary_activeDaysExactlyMinGrants_minMinusOneSkips() {
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(activeDaysCriteria(5)));
        List<Long> users = List.of(100L, 101L);
        stubSinglePage(users);
        when(betaGrantRepository.findGrantedScopeIds(eq(PHASE), eq(EntitlementScopeKind.USER), any()))
                .thenReturn(List.of());
        // 100=ちょうど5（付与）／101=4（非付与）。
        when(loginActivityQueryService.countDistinctActiveDaysWithinByUsers(any(), anyInt(), any()))
                .thenReturn(Map.of(100L, 5L, 101L, 4L));

        batch.execute();

        verify(betaGrantService, times(1)).grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(100L), isNull(), eq(true), isNull());
        verify(betaGrantService, never()).grantBetaPerk(
                any(), anyInt(), any(), eq(101L), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("AND評価: activeDays と在籍日数の両指標が非NULLなら両方満たす場合のみ付与")
    void bothMetrics_andEvaluation() {
        BetaPerkCriteriaEntity both = BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE).grantKind(GrantKind.INDIVIDUAL).evaluationWindowDays(30)
                .minActiveDays(5).minMembershipTenureDays(90).minActiveMembers(null)
                .enabled(true).build();
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(both));
        List<Long> users = List.of(200L, 201L, 202L);
        stubSinglePage(users);
        when(betaGrantRepository.findGrantedScopeIds(eq(PHASE), eq(EntitlementScopeKind.USER), any()))
                .thenReturn(List.of());
        // 200=両方満たす／201=activeDays不足／202=在籍不足。
        when(loginActivityQueryService.countDistinctActiveDaysWithinByUsers(any(), anyInt(), any()))
                .thenReturn(Map.of(200L, 5L, 201L, 4L, 202L, 10L));
        when(membershipQueryService.tenureDaysByUsers(any(), any()))
                .thenReturn(Map.of(200L, 90L, 201L, 200L, 202L, 89L));

        batch.execute();

        verify(betaGrantService, times(1)).grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(200L), isNull(), eq(true), isNull());
        verify(betaGrantService, never()).grantBetaPerk(any(), anyInt(), any(), eq(201L), any(), anyBoolean(), any());
        verify(betaGrantService, never()).grantBetaPerk(any(), anyInt(), any(), eq(202L), any(), anyBoolean(), any());
    }

    // ============================================================
    // 付与済み skip
    // ============================================================

    @Test
    @DisplayName("付与済み（skip-set）ユーザーは適格でも付与しない")
    void alreadyGranted_skipped() {
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(activeDaysCriteria(5)));
        List<Long> users = List.of(300L, 301L);
        stubSinglePage(users);
        // 300 は既付与（取消済み含む）。
        when(betaGrantRepository.findGrantedScopeIds(eq(PHASE), eq(EntitlementScopeKind.USER), any()))
                .thenReturn(List.of(300L));
        when(loginActivityQueryService.countDistinctActiveDaysWithinByUsers(any(), anyInt(), any()))
                .thenReturn(Map.of(300L, 30L, 301L, 30L));

        batch.execute();

        verify(betaGrantService, never()).grantBetaPerk(any(), anyInt(), any(), eq(300L), any(), anyBoolean(), any());
        verify(betaGrantService, times(1)).grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(301L), isNull(), eq(true), isNull());
    }

    // ============================================================
    // 途中失敗継続 / 冪等 skip（例外の吸収）
    // ============================================================

    @Test
    @DisplayName("途中失敗継続: 1ユーザーの付与が RuntimeException でも他ユーザーの付与は継続する")
    void partialFailure_continues() {
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(activeDaysCriteria(5)));
        List<Long> users = List.of(400L, 401L);
        stubSinglePage(users);
        when(betaGrantRepository.findGrantedScopeIds(eq(PHASE), eq(EntitlementScopeKind.USER), any()))
                .thenReturn(List.of());
        when(loginActivityQueryService.countDistinctActiveDaysWithinByUsers(any(), anyInt(), any()))
                .thenReturn(Map.of(400L, 30L, 401L, 30L));
        // 400 は付与失敗（想定外例外）、401 は成功。
        when(betaGrantService.grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(400L), isNull(), eq(true), isNull()))
                .thenThrow(new RuntimeException("boom"));

        batch.execute();

        // 401 は失敗に巻き込まれず付与される。
        verify(betaGrantService, times(1)).grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(401L), isNull(), eq(true), isNull());
    }

    @Test
    @DisplayName("冪等: GRANT_ALREADY_EXISTS / DataIntegrityViolation は握り潰さず skip として吸収し継続する")
    void idempotentSkips_absorbed() {
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(activeDaysCriteria(5)));
        List<Long> users = List.of(500L, 501L, 502L);
        stubSinglePage(users);
        when(betaGrantRepository.findGrantedScopeIds(eq(PHASE), eq(EntitlementScopeKind.USER), any()))
                .thenReturn(List.of());
        when(loginActivityQueryService.countDistinctActiveDaysWithinByUsers(any(), anyInt(), any()))
                .thenReturn(Map.of(500L, 30L, 501L, 30L, 502L, 30L));
        when(betaGrantService.grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(500L), isNull(), eq(true), isNull()))
                .thenThrow(new BusinessException(BetaPerkErrorCode.GRANT_ALREADY_EXISTS));
        when(betaGrantService.grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(501L), isNull(), eq(true), isNull()))
                .thenThrow(new DataIntegrityViolationException("uk_bg_scope_phase"));

        // 例外を投げず正常終了する（握り潰しではなく per-item 吸収）。
        batch.execute();

        // 502 は通常付与される。
        verify(betaGrantService, times(1)).grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(502L), isNull(), eq(true), isNull());
    }

    // ============================================================
    // AC-C4: 活動実績ゲート必須（幽霊アカウント穴の根治）
    // ============================================================

    /**
     * AC-C4: {@code minActiveDays=null} かつ {@code minMembershipTenureDays=30}（＝在籍日数だけ設定済み）の
     * criteria では <b>1 件も付与しない</b>。
     *
     * <p><b>穴</b>: 在籍日数は「登録してから何日経ったか」しか見ないため、一度も使っていない幽霊アカウントでも
     * 時間の経過だけで特典を得られてしまう。自動付与は「活動実績ゲート（activeDays）が非 NULL であること」を
     * 必須条件とし、activeDays 未設定の criteria は<b>無条件付与相当</b>として付与 0 で正常終了する
     * （シスアドが activeDays を設定するまで自動付与は動かない）。</p>
     *
     * <p>※ 本テストは旧 {@code tenureOnly_doesNotQueryActiveDays}（在籍のみで付与される想定）を置き換える。
     * 旧テストは「両指標 NULL のときだけ止まる」現行実装の挙動をそのまま追認しており、本 AC と両立しない。</p>
     */
    @Test
    @DisplayName("AC-C4: 在籍日数のみ（activeDays 未設定）の criteria では幽霊アカ防止のため付与0")
    void acC4_tenureOnlyCriteria_grantsNothing() {
        BetaPerkCriteriaEntity tenureOnly = BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE).grantKind(GrantKind.INDIVIDUAL).evaluationWindowDays(30)
                .minActiveDays(null).minMembershipTenureDays(30).minActiveMembers(null)
                .enabled(true).build();
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(tenureOnly));

        batch.execute();

        // 1 件も付与しない（活動実績ゲート未設定は無条件付与相当）。
        verify(betaGrantService, never()).grantBetaPerk(any(), anyInt(), any(), anyLong(), any(), anyBoolean(), any());
        // ユーザー走査・bulk 集計にも入らない（criteria 段階で止まる）。
        verify(userRepository, never()).findActiveUserIdsForBeta(any());
        verify(loginActivityQueryService, never()).countDistinctActiveDaysWithinByUsers(any(), anyInt(), any());
    }

    // ============================================================
    // AC-C2: activeDays の境界（min ちょうどは付与・min-1 は非付与）
    // ============================================================

    /**
     * AC-C2: {@code minActiveDays=14} のとき、activeDays=14 のユーザーは付与され、13 のユーザーは付与されない。
     * 境界は「以上（{@code actual >= required}）」。TZ 是正で集計値の作り方が変わっても、
     * 判定側の境界規則は不変であることを固定する。
     */
    @Test
    @DisplayName("AC-C2: minActiveDays=14 なら activeDays=14 は付与・13 は非付与（境界は「以上」）")
    void acC2_activeDaysBoundaryAtFourteen() {
        when(criteriaRepository.findById(new BetaPerkCriteriaId(PHASE, GrantKind.INDIVIDUAL)))
                .thenReturn(Optional.of(activeDaysCriteria(14)));
        List<Long> users = List.of(700L, 701L);
        stubSinglePage(users);
        when(betaGrantRepository.findGrantedScopeIds(eq(PHASE), eq(EntitlementScopeKind.USER), any()))
                .thenReturn(List.of());
        when(loginActivityQueryService.countDistinctActiveDaysWithinByUsers(any(), anyInt(), any()))
                .thenReturn(Map.of(700L, 14L, 701L, 13L));

        batch.execute();

        verify(betaGrantService, times(1)).grantBetaPerk(
                eq(GrantKind.INDIVIDUAL), eq(PHASE), eq(EntitlementScopeKind.USER),
                eq(700L), isNull(), eq(true), isNull());
        verify(betaGrantService, never()).grantBetaPerk(
                any(), anyInt(), any(), eq(701L), any(), anyBoolean(), any());
    }
}
