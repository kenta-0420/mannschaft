package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureCategory;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gamification.AwardedBy;
import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.BadgeType;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.UserBadgeRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20.3 ベータ特典 Phase1: {@link BetaGrantService} の<b>書込パス</b>を実 MySQL（Testcontainers）越しに
 * 検証する統合テスト（マスター御裁可・実機検証の恒久ガード化）。
 *
 * <p><b>なぜモック UT では足りないか</b>: {@code BetaGrantServiceTest} は各 Repository をモック化した純 UT で、
 * 「付与メタ save →権利発行→バッジ授与」の呼び出し順は検証できても、<b>実 DB 上の原子性・取消カスケードの
 * 反映・延長の append-only（既存行を UPDATE しないこと）</b>までは保証できない。当プロジェクトは
 * 「モック UT 偽 green → 実 DB で露見」の事故が多いため（memory 多数）、本 IT で書込結果を実クエリで確認し
 * 恒久ガード化する。</p>
 *
 * <p><b>金型</b>: {@link AbstractMySqlIntegrationTest} を継承（{@code @SpringBootTest} /
 * {@code @Testcontainers} / {@code @ActiveProfiles} は基底のみ・再宣言禁止）。
 * {@code @EnabledIf} は JUnit5 で継承されないため派生で再宣言必須。</p>
 *
 * <p><b>テストプロファイルの前提</b>: {@code ddl-auto=create}（Flyway 無効）ゆえマイグレーションのシードは
 * 入らない（memory {@code feedback_test_profile_ddl_create_skips_flyway_seed}）。よって FULL プランの
 * {@code plan_features}・{@code feature_catalog}・ベータテスター称号バッジを {@code @BeforeEach} で手動シードする。
 * 付与は全て {@code skipCriteriaCheck=true} で呼び eligibility 評価（criteria/activity シード）を回避する。</p>
 *
 * <p><b>データ衝突回避</b>: 実 DB（ロールバックしない・コンテナは JVM 内で共有）ゆえ、テスト毎に scope_id を
 * ユニーク定数にして {@code uk_bg_scope_phase} / {@code uk_ent_grant} の衝突を避ける
 * （{@link BetaGrantRepositoryIT} が使う 1001〜4004 とも重複しない 81_00_00x 帯）。</p>
 */
@DisplayName("BetaGrant 書込パス統合テスト（付与原子性・取消カスケード・延長append-only）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BetaGrantWriteFlowIT extends AbstractMySqlIntegrationTest {

    /** FULL プランに展開される機能キー（設計書 README §1・付与時スナップショット）。 */
    private static final List<String> FULL_KEYS = List.of(
            FeatureKeys.LEGACY_PAID_PLAN_BUNDLE,
            FeatureKeys.TEMPLATE_PREMIUM_MODULES,
            FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED,
            FeatureKeys.ADS_HIDE,
            FeatureKeys.MONETIZATION_PAYWALL,
            FeatureKeys.MONETIZATION_MEMBERSHIP_FEE);

    /** シスアド操作者（監査用・論理参照）。 */
    private static final Long ADMIN_ID = 9_000_001L;

    // テスト毎にユニークな scope_id（実 DB でロールバックしないため衝突回避）。
    private static final Long USER_ATOMICITY = 81_00_001L;
    private static final Long USER_GATE = 81_00_002L;
    private static final Long USER_REVOKE = 81_00_003L;
    private static final Long ORG_EXTEND = 81_00_004L;
    private static final Long USER_WITHDRAWAL = 81_00_005L;
    private static final Long USER_WITHDRAWAL_NOGRANT = 81_00_006L;
    private static final Long USER_DUPLICATE = 81_00_007L;

    // ベータテスター称号バッジのシード識別（BetaTesterBadgeAwardService と一致）。
    private static final String BADGE_SCOPE_TYPE = "PLATFORM";
    private static final Long BADGE_SCOPE_ID = 0L;
    private static final String BADGE_NAME = "ベータテスター";

    @Autowired
    private BetaGrantService betaGrantService;
    @Autowired
    private BetaGrantRepository betaGrantRepository;
    @Autowired
    private EntitlementRepository entitlementRepository;
    @Autowired
    private PlanFeatureRepository planFeatureRepository;
    @Autowired
    private FeatureCatalogRepository featureCatalogRepository;
    @Autowired
    private BadgeRepository badgeRepository;
    @Autowired
    private UserBadgeRepository userBadgeRepository;
    @Autowired
    private EntitlementQueryService entitlementQueryService;
    /** サービスと同一の時刻基準（アプリの Clock bean）。テストの範囲計算を system TZ でなくこれに合わせる
     *  （memory feedback_it_fixture_datetime_tz_bind: CI(UTC runner)と JST の 9h ズレで範囲外になる罠の回避）。 */
    @Autowired
    private Clock clock;

    /**
     * マスタデータを手動シードする（ddl-auto=create ＝ Flyway シード無し）。
     * plan_features / feature_catalog は自然キーで upsert（冪等）、バッジは IDENTITY のため未存在時のみ挿入。
     */
    @BeforeEach
    void seedMasterData() {
        // FULL プランの plan_features（6 キー）。resolveFullPlanFeatureKeys() が引く。
        for (String key : FULL_KEYS) {
            planFeatureRepository.save(PlanFeatureEntity.builder()
                    .planKey("FULL")
                    .featureKey(key)
                    .build());
        }
        // feature_catalog（enabled=true・FREE 非掲載）。isEntitled が entitlement 経路で true を返すために必須。
        int sort = 0;
        for (String key : FULL_KEYS) {
            FeatureCategory category = key.startsWith("monetization.")
                    ? FeatureCategory.REVENUE : FeatureCategory.INTERNAL;
            featureCatalogRepository.save(FeatureCatalogEntity.builder()
                    .featureKey(key)
                    .category(category)
                    .addonAvailable(Boolean.FALSE)
                    .freeForNonprofit(Boolean.FALSE)
                    .displayNameKey("feature." + key + ".name")
                    .descriptionKey("feature." + key + ".desc")
                    .sortOrder(sort++)
                    .enabled(Boolean.TRUE)
                    .build());
        }
        // ベータテスター称号バッジ（PLATFORM/0/ベータテスター・SPECIAL/MANUAL/システム）。INDIVIDUAL 付与時の授与用。
        if (badgeRepository
                .findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(BADGE_SCOPE_TYPE, BADGE_SCOPE_ID, BADGE_NAME)
                .isEmpty()) {
            badgeRepository.save(BadgeEntity.builder()
                    .scopeType(BADGE_SCOPE_TYPE)
                    .scopeId(BADGE_SCOPE_ID)
                    .name(BADGE_NAME)
                    .badgeType(BadgeType.SPECIAL)
                    .conditionType(BadgeConditionType.MANUAL)
                    .isSystem(Boolean.TRUE)
                    .isRepeatable(Boolean.FALSE)
                    .isActive(Boolean.TRUE)
                    .build());
        }
    }

    // ============================================================
    // シナリオ 1: 付与の原子性（付与メタ＋entitlements 6 行＋称号バッジが同一 tx で確定）
    // ============================================================

    @Test
    @DisplayName("付与の原子性: INDIVIDUAL 付与で beta_grants 1 行＋FULL6キーの entitlements＋称号バッジが同一 tx で確定する")
    void grantIndividual_persistsGrantEntitlementsAndBadge_atomically() {
        BetaGrantEntity grant = betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, USER_ATOMICITY,
                null, true, ADMIN_ID);

        // 付与メタが実在する。
        BetaGrantEntity persisted = betaGrantRepository.findById(grant.getId()).orElseThrow();
        assertThat(persisted.getGrantKind()).isEqualTo(GrantKind.INDIVIDUAL);
        assertThat(persisted.getScopeKind()).isEqualTo(EntitlementScopeKind.USER);
        assertThat(persisted.getScopeId()).isEqualTo(USER_ATOMICITY);
        assertThat(persisted.isRevoked()).isFalse();
        assertThat(persisted.getGrantedBy()).isEqualTo(ADMIN_ID);

        // entitlements が FULL 6 キー分・同一発行元で出来ている（USER スコープ・無期限・BETA_GRANT 由来）。
        List<EntitlementEntity> ents = activeEntitlementsOf(grant.getId());
        assertThat(ents).hasSize(FULL_KEYS.size());
        assertThat(ents).allSatisfy(e -> {
            assertThat(e.getScopeKind()).isEqualTo(EntitlementScopeKind.USER);
            assertThat(e.getScopeId()).isEqualTo(USER_ATOMICITY);
            assertThat(e.getSourceKind()).isEqualTo(EntitlementSourceKind.BETA_GRANT);
            assertThat(e.getSourceRefId()).isEqualTo(grant.getId());
            assertThat(e.getValidUntil()).isNull(); // INDIVIDUAL=無期限
            assertThat(e.getRevokedAt()).isNull();
        });
        assertThat(ents).extracting(EntitlementEntity::getFeatureKey)
                .containsExactlyInAnyOrderElementsOf(FULL_KEYS);

        // 称号バッジ行（user_badges・awarded_by=SYSTEM・BETA_PHASE_1）が出来ている。
        BadgeEntity badge = badgeRepository
                .findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(BADGE_SCOPE_TYPE, BADGE_SCOPE_ID, BADGE_NAME)
                .orElseThrow();
        List<UserBadgeEntity> userBadges = userBadgeRepository.findByUserId(USER_ATOMICITY);
        assertThat(userBadges).singleElement().satisfies(ub -> {
            assertThat(ub.getBadgeId()).isEqualTo(badge.getId());
            assertThat(ub.getPeriodLabel()).isEqualTo("BETA_PHASE_1");
            assertThat(ub.getAwardedBy()).isEqualTo(AwardedBy.SYSTEM);
        });
    }

    // ============================================================
    // シナリオ 2: 実ゲート反映（付与→ EntitlementQueryService.isEntitled が true）
    // ============================================================

    @Test
    @DisplayName("実ゲート反映: 付与後 EntitlementQueryService.isEntitled(ads.hide) が true（ペイウォール解錠の連結を証明）")
    void grant_reflectsInEntitlementGate() {
        // 付与前は権利が無い（entitlement 経路が false・FREE 非掲載を確認）。
        assertThat(entitlementQueryService.isEntitled(
                EntitlementScopeKind.USER, USER_GATE, FeatureKeys.ADS_HIDE)).isFalse();

        betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, USER_GATE,
                null, true, ADMIN_ID);

        // 付与後は実ゲートが解錠される（判定は entitlements の有効行を根拠にする）。
        assertThat(entitlementQueryService.isEntitled(
                EntitlementScopeKind.USER, USER_GATE, FeatureKeys.ADS_HIDE)).isTrue();
    }

    // ============================================================
    // シナリオ 3: 取消カスケード（beta_grants 終端化＋由来 entitlements 全 revoke＋ゲート false）
    // ============================================================

    @Test
    @DisplayName("取消カスケード: revoke で beta_grants が終端化し由来 entitlements が全 revoke され isEntitled=false になる")
    void revoke_cascadesToEntitlementsAndGate() {
        BetaGrantEntity grant = betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, USER_REVOKE,
                null, true, ADMIN_ID);
        assertThat(activeEntitlementsOf(grant.getId())).hasSize(FULL_KEYS.size());
        assertThat(entitlementQueryService.isEntitled(
                EntitlementScopeKind.USER, USER_REVOKE, FeatureKeys.ADS_HIDE)).isTrue();

        betaGrantService.revoke(grant.getId(), BetaRevokeReason.TERMS_VIOLATION, ADMIN_ID, null);

        // 付与メタが終端化している（revoked_at/revoked_by/revoke_reason セット）。
        BetaGrantEntity revoked = betaGrantRepository.findById(grant.getId()).orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevokedBy()).isEqualTo(ADMIN_ID);
        assertThat(revoked.getRevokeReason()).isEqualTo(BetaRevokeReason.TERMS_VIOLATION);

        // 由来 entitlements が全て revoke されている（未取消行が 0 件）。
        assertThat(activeEntitlementsOf(grant.getId())).isEmpty();

        // 実ゲートが施錠される。
        assertThat(entitlementQueryService.isEntitled(
                EntitlementScopeKind.USER, USER_REVOKE, FeatureKeys.ADS_HIDE)).isFalse();
    }

    // ============================================================
    // シナリオ 4: 延長 append-only（AC-P4・最重要）
    // ============================================================

    @Test
    @DisplayName("延長 append-only: extend は新 6 行を追加し既存 6 行の valid_until を UPDATE しない（新行=元max+6ヶ月）")
    void extend_isAppendOnly_originalRowsUntouched() {
        // サービスは注入 Clock で now.plusYears(2) を計算するため、テストの範囲も同じ Clock 基準にする
        // （system TZ の LocalDateTime.now() では CI(UTC) と JST の 9h ズレで範囲外になる）。
        LocalDateTime beforeGrant = LocalDateTime.now(clock);
        BetaGrantEntity grant = betaGrantService.grantBetaPerk(
                GrantKind.TEAM_ORG, 1, EntitlementScopeKind.ORG, ORG_EXTEND,
                ORG_EXTEND, true, ADMIN_ID);

        // 付与直後: FULL 6 行・valid_until ≈ 付与時刻 + 2 年。
        List<EntitlementEntity> original = activeEntitlementsOf(grant.getId());
        assertThat(original).hasSize(FULL_KEYS.size());
        LocalDateTime lowerBound = beforeGrant.plusYears(2).minusMinutes(5);
        LocalDateTime upperBound = LocalDateTime.now(clock).plusYears(2).plusMinutes(5);
        assertThat(original).allSatisfy(e ->
                assertThat(e.getValidUntil()).isNotNull().isBetween(lowerBound, upperBound));

        // 延長前の (id → valid_until) を控えておき、延長後に既存行が不変であることを厳密に照合する。
        Map<UUID, LocalDateTime> originalUntilById = original.stream()
                .collect(Collectors.toMap(e -> e.getId(), EntitlementEntity::getValidUntil));
        LocalDateTime originalMax = original.stream()
                .map(EntitlementEntity::getValidUntil)
                .max(LocalDateTime::compareTo)
                .orElseThrow();

        betaGrantService.extend(grant.getId(), 6, ORG_EXTEND);

        // 延長後: 有効行が 12 行（既存 6 ＋ 新 6・いずれも未取消）。
        List<EntitlementEntity> afterExtend = activeEntitlementsOf(grant.getId());
        assertThat(afterExtend).hasSize(FULL_KEYS.size() * 2);

        // 既存 6 行は valid_until が UPDATE されていない（append-only の核心）。
        List<EntitlementEntity> originalRows = afterExtend.stream()
                .filter(e -> originalUntilById.containsKey(e.getId()))
                .toList();
        assertThat(originalRows).hasSize(FULL_KEYS.size());
        assertThat(originalRows).allSatisfy(e ->
                assertThat(e.getValidUntil()).isEqualTo(originalUntilById.get(e.getId())));

        // 新 6 行は元 max + 6 ヶ月・FULL6 キーを網羅する。
        List<EntitlementEntity> newRows = afterExtend.stream()
                .filter(e -> !originalUntilById.containsKey(e.getId()))
                .toList();
        assertThat(newRows).hasSize(FULL_KEYS.size());
        LocalDateTime expectedNewUntil = originalMax.plusMonths(6);
        assertThat(newRows).allSatisfy(e ->
                assertThat(e.getValidUntil()).isEqualTo(expectedNewUntil));
        assertThat(newRows).extracting(EntitlementEntity::getFeatureKey)
                .containsExactlyInAnyOrderElementsOf(FULL_KEYS);
    }

    // ============================================================
    // シナリオ 5: 退会一括取消（AC-A8）
    // ============================================================

    @Test
    @DisplayName("退会一括取消: revokeAllForUser で当該ユーザーの全付与が revoke＋失効。付与の無いユーザーは no-op")
    void revokeAllForUser_revokesAllGrantsOfUser_noopForOthers() {
        BetaGrantEntity grant = betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, USER_WITHDRAWAL,
                null, true, ADMIN_ID);
        assertThat(activeEntitlementsOf(grant.getId())).hasSize(FULL_KEYS.size());

        betaGrantService.revokeAllForUser(USER_WITHDRAWAL, BetaRevokeReason.WITHDRAWAL);

        // 当該ユーザーの付与が全て取消され、entitlements が失効している。
        BetaGrantEntity revoked = betaGrantRepository.findById(grant.getId()).orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevokeReason()).isEqualTo(BetaRevokeReason.WITHDRAWAL);
        assertThat(revoked.getRevokedBy()).isNull(); // システム取消。
        assertThat(activeEntitlementsOf(grant.getId())).isEmpty();
        assertThat(entitlementQueryService.isEntitled(
                EntitlementScopeKind.USER, USER_WITHDRAWAL, FeatureKeys.ADS_HIDE)).isFalse();

        // 付与の無いユーザーでは何も起きない（例外なし・付与ゼロのまま）。
        betaGrantService.revokeAllForUser(USER_WITHDRAWAL_NOGRANT, BetaRevokeReason.WITHDRAWAL);
        assertThat(betaGrantRepository.findByScopeKindAndScopeIdOrderByGrantedAtDesc(
                EntitlementScopeKind.USER, USER_WITHDRAWAL_NOGRANT)).isEmpty();
    }

    // ============================================================
    // シナリオ 6: 二重付与拒否（service ガード＋ uk_bg_scope_phase の二重防御）
    // ============================================================

    @Test
    @DisplayName("二重付与拒否: 同一 scope×phase の 2 回目の付与は BusinessException(GRANT_ALREADY_EXISTS)")
    void grantTwiceSameScopePhase_rejected() {
        betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, USER_DUPLICATE,
                null, true, ADMIN_ID);

        assertThatThrownBy(() -> betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER, USER_DUPLICATE,
                null, true, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.GRANT_ALREADY_EXISTS);
    }

    /** 発行元（BETA_GRANT × grantId）に紐づく<b>未取消</b>の entitlements を取得する。 */
    private List<EntitlementEntity> activeEntitlementsOf(UUID grantId) {
        return entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.BETA_GRANT, grantId);
    }
}
