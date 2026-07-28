package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureCategory;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.BadgeType;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20.3 Phase2 Wave2a: {@link BetaPerkAutoGrantBatchService} と bulk クエリを実 MySQL（Testcontainers）越しに
 * 検証する統合テスト（試練→green・マスター御裁可の恒久ガード化）。
 *
 * <p><b>なぜ IT が必要か</b>: N+1 回避（読み取りクエリのユーザー数非依存）・冪等（二重付与ゼロ）・退会申請中の除外
 * （{@code @SQLRestriction} 貫通）は、実 DB のクエリ挙動・UNIQUE 制約・論理削除フィルタに依存するため、モック UT
 * では担保できない（memory 多数: モック UT 偽 green → 実 DB で露見）。判定ロジックの決定論的検証は
 * {@link BetaPerkAutoGrantBatchServiceTest}（純 UT）に置く。</p>
 *
 * <p><b>金型</b>: {@link com.mannschaft.app.billing.beta.BetaGrantWriteFlowIT}（{@link AbstractMySqlIntegrationTest}
 * 継承・{@code @SpringBootTest}/{@code @Testcontainers}/{@code @ActiveProfiles} は基底のみ・{@code @EnabledIf} は
 * JUnit5 で継承されないため再宣言必須）。</p>
 *
 * <p><b>test プロファイルの前提</b>: {@code ddl-auto=create}（Flyway 無効）ゆえマイグレーションのシードは入らない
 * （memory {@code feedback_test_profile_ddl_create_skips_flyway_seed}）。FULL プランの {@code plan_features}・
 * {@code feature_catalog}・称号バッジ・{@code beta_perk_criteria} を手動シードする。ログイン記録は
 * {@code audit_logs} の {@code @PrePersist} が {@code created_at} を強制的に {@code now()} に上書きするため、
 * 過去日を持たせるには {@link JdbcTemplate} で直接 INSERT する（日時はアプリ {@link Clock} 基準で計算し UTC ランナーと
 * JST の 9h ズレを避ける・memory {@code feedback_it_fixture_datetime_tz_bind}）。</p>
 *
 * <p><b>共有コンテナのデータ非分離</b>: コンテナは JVM 内共有・ロールバックしないため、ユーザーは毎回新規生成し
 * （id は IDENTITY 採番）、アサーションは<b>自分が生成した user_id に対してのみ</b>行う（全体件数に依存しない）。</p>
 */
@DisplayName("BetaPerkAutoGrant 自動付与バッチ統合テスト（N+1回避・冪等・退会除外・境界・bulk）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BetaPerkAutoGrantBatchIT extends AbstractMySqlIntegrationTest {

    private static final int PHASE = 1;
    private static final int MIN_ACTIVE_DAYS = 5;
    private static final int WINDOW_DAYS = 30;
    private static final Long ADMIN_ID = 9_100_001L;

    private static final List<String> FULL_KEYS = List.of(
            FeatureKeys.LEGACY_PAID_PLAN_BUNDLE,
            FeatureKeys.TEMPLATE_PREMIUM_MODULES,
            FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED,
            FeatureKeys.ADS_HIDE,
            FeatureKeys.MONETIZATION_PAYWALL,
            FeatureKeys.MONETIZATION_MEMBERSHIP_FEE);

    private static final String BADGE_SCOPE_TYPE = "PLATFORM";
    private static final Long BADGE_SCOPE_ID = 0L;
    private static final String BADGE_NAME = "ベータテスター";

    @Autowired private BetaPerkAutoGrantBatchService batch;
    @Autowired private BetaGrantService betaGrantService;
    @Autowired private BetaGrantRepository betaGrantRepository;
    @Autowired private BetaPerkCriteriaRepository criteriaRepository;
    @Autowired private LoginActivityQueryService loginActivityQueryService;
    @Autowired private MembershipQueryService membershipQueryService;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private PlanFeatureRepository planFeatureRepository;
    @Autowired private FeatureCatalogRepository featureCatalogRepository;
    @Autowired private BadgeRepository badgeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private Clock clock;

    @BeforeEach
    void seed() {
        // FULL プラン構成（付与時スナップショット）。
        for (String key : FULL_KEYS) {
            planFeatureRepository.save(PlanFeatureEntity.builder().planKey("FULL").featureKey(key).build());
        }
        int sort = 0;
        for (String key : FULL_KEYS) {
            FeatureCategory category = key.startsWith("monetization.")
                    ? FeatureCategory.REVENUE : FeatureCategory.INTERNAL;
            featureCatalogRepository.save(FeatureCatalogEntity.builder()
                    .featureKey(key).category(category)
                    .addonAvailable(Boolean.FALSE).freeForNonprofit(Boolean.FALSE)
                    .displayNameKey("feature." + key + ".name").descriptionKey("feature." + key + ".desc")
                    .sortOrder(sort++).enabled(Boolean.TRUE).build());
        }
        if (badgeRepository
                .findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(BADGE_SCOPE_TYPE, BADGE_SCOPE_ID, BADGE_NAME)
                .isEmpty()) {
            badgeRepository.save(BadgeEntity.builder()
                    .scopeType(BADGE_SCOPE_TYPE).scopeId(BADGE_SCOPE_ID).name(BADGE_NAME)
                    .badgeType(BadgeType.SPECIAL).conditionType(BadgeConditionType.MANUAL)
                    .isSystem(Boolean.TRUE).isRepeatable(Boolean.FALSE).isActive(Boolean.TRUE).build());
        }
        // phase1 INDIVIDUAL criteria（activeDays>=5・window=30日・enabled）。合成キーで冪等 upsert。
        criteriaRepository.save(BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE).grantKind(GrantKind.INDIVIDUAL).evaluationWindowDays(WINDOW_DAYS)
                .minActiveDays(MIN_ACTIVE_DAYS).minMembershipTenureDays(null).minActiveMembers(null)
                .enabled(true).build());
    }

    // ============================================================
    // 付与ハッピーパス / 非適格
    // ============================================================

    @Test
    @DisplayName("適格ユーザー（activeDays>=5）に付与され entitlements が発行される。非適格（4日）は付与されない")
    void grantsEligible_skipsIneligible() {
        setEnabled(true);
        Long eligible = persistActiveUser();
        insertLoginDays(eligible, MIN_ACTIVE_DAYS);        // ちょうど 5 日
        Long ineligible = persistActiveUser();
        insertLoginDays(ineligible, MIN_ACTIVE_DAYS - 1);  // 4 日

        batch.execute();

        assertThat(grantsOf(eligible)).hasSize(1);
        assertThat(activeEntitlementsOf(firstGrantId(eligible))).hasSize(FULL_KEYS.size());
        assertThat(grantsOf(ineligible)).isEmpty();
    }

    @Test
    @DisplayName("AC-B1 境界: activeDays==min ちょうどで付与・min-1 では非付与（境界は「以上」）")
    void boundary_exactlyMinGrants_minMinusOneSkips() {
        setEnabled(true);
        Long exact = persistActiveUser();
        insertLoginDays(exact, MIN_ACTIVE_DAYS);
        Long below = persistActiveUser();
        insertLoginDays(below, MIN_ACTIVE_DAYS - 1);

        batch.execute();

        assertThat(grantsOf(exact)).hasSize(1);
        assertThat(grantsOf(below)).isEmpty();
    }

    /**
     * AC-C2（境界・実DB）: {@code beta_perk_criteria.min_active_days = 14} を手動シードしたとき、
     * activeDays=14 のユーザーは付与され {@code beta_grants} と {@code entitlements} に行ができ、
     * activeDays=13 のユーザーには付与されない。
     *
     * <p>{@link #boundary_exactlyMinGrants_minMinusOneSkips}（min=5）と同じ境界則を、運用で実際に用いる
     * しきい値 14 かつ<b>付与の副作用（entitlements 発行）まで実 DB で</b>確認する。判定だけでなく書き込み側の
     * 連鎖が閾値変更で壊れないことを固定する。</p>
     */
    @Test
    @DisplayName("AC-C2 境界(実DB): min_active_days=14 で activeDays=14 は付与＋entitlements発行・13 は非付与")
    void acC2_minActiveDays14_boundaryGrantsAndIssuesEntitlements() {
        setEnabled(true);
        // phase1 criteria を min_active_days=14 へ上書き（合成キー upsert）。
        criteriaRepository.save(BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE).grantKind(GrantKind.INDIVIDUAL).evaluationWindowDays(WINDOW_DAYS)
                .minActiveDays(14).minMembershipTenureDays(null).minActiveMembers(null)
                .enabled(true).build());
        Long exact = persistActiveUser();
        insertLoginDays(exact, 14);
        Long below = persistActiveUser();
        insertLoginDays(below, 13);

        batch.execute();

        assertThat(grantsOf(exact)).as("activeDays=14 は境界『以上』ゆえ付与される").hasSize(1);
        assertThat(activeEntitlementsOf(firstGrantId(exact)))
                .as("付与された grant から FULL プラン相当の entitlements が発行される")
                .hasSize(FULL_KEYS.size());
        assertThat(grantsOf(below)).as("activeDays=13 は閾値未満ゆえ非付与").isEmpty();
    }

    /**
     * AC-C4（幽霊アカウントの穴）: {@code min_active_days=NULL} ／ {@code min_membership_tenure_days=30} の
     * criteria では、在籍 30 日超でも<b>ログイン記録が 1 件も無いユーザーには 1 件も付与しない</b>。
     *
     * <p>在籍日数だけを条件にすると「登録して放置しただけのアカウント」に自動でベータ特典がばら撒かれる
     * （＝活動実績ゲートの趣旨に反する）。現行実装は {@code minActiveDays == null} のとき activeDays を一切見ない
     * ため付与してしまうので red。</p>
     */
    @Test
    @DisplayName("AC-C4: min_active_days=NULL・在籍30日条件でも、ログイン0の幽霊アカウントには付与しない")
    void acC4_tenureOnlyCriteria_doesNotGrantToUserWithoutAnyLogin() {
        setEnabled(true);
        // 在籍日数のみを条件にした criteria（activeDays 指標は NULL）。
        criteriaRepository.save(BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE).grantKind(GrantKind.INDIVIDUAL).evaluationWindowDays(WINDOW_DAYS)
                .minActiveDays(null).minMembershipTenureDays(30).minActiveMembers(null)
                .enabled(true).build());
        // 在籍 40 日（閾値 30 を超える）だが、ログイン記録は 1 件も無い＝完全な休眠アカウント。
        Long ghost = persistActiveUser();
        persistActiveMembership(ghost, LocalDateTime.now(clock).minusDays(40));

        batch.execute();

        assertThat(grantsOf(ghost))
                .as("在籍日数を満たしていても活動実績ゼロなら自動付与の対象外（バラ撒き防止）")
                .isEmpty();
    }

    // ============================================================
    // AC-N3: enabled=false は付与0
    // ============================================================

    @Test
    @DisplayName("AC-N3: enabled=false なら適格ユーザーがいても付与0")
    void disabled_grantsNothing() {
        setEnabled(false);
        Long eligible = persistActiveUser();
        insertLoginDays(eligible, MIN_ACTIVE_DAYS + 3);

        batch.execute();

        assertThat(grantsOf(eligible)).isEmpty();
    }

    // ============================================================
    // AC-I2/I5 冪等: 2連続実行で二重付与なし
    // ============================================================

    @Test
    @DisplayName("AC-I2/I5 冪等: バッチ2連続実行でも beta_grants は 1 行/ユーザー（二重付与ゼロ）")
    void idempotent_noDoubleGrant() {
        setEnabled(true);
        Long eligible = persistActiveUser();
        insertLoginDays(eligible, MIN_ACTIVE_DAYS + 2);

        batch.execute();
        batch.execute();

        assertThat(grantsOf(eligible)).hasSize(1);
        assertThat(activeEntitlementsOf(firstGrantId(eligible))).hasSize(FULL_KEYS.size());
    }

    // ============================================================
    // 退会申請中（deleted_at セット）は除外
    // ============================================================

    @Test
    @DisplayName("退会申請中（status=ACTIVE のまま deleted_at セット）の適格ユーザーは @SQLRestriction で走査対象外＝付与0")
    void withdrawingUser_excluded() {
        setEnabled(true);
        Long withdrawing = persistActiveUser();
        insertLoginDays(withdrawing, MIN_ACTIVE_DAYS + 5);
        // 弱匿名化: status は ACTIVE のまま deleted_at を立てる（退会撤回ウィンドウ中の状態）。UserEntity は
        // @Setter を持たないため直接 UPDATE する（@SQLRestriction は JPQL 側で効くので走査対象から外れることを検証）。
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(clock)), withdrawing);

        batch.execute();

        assertThat(grantsOf(withdrawing)).isEmpty();
    }

    // ============================================================
    // 両指標NULL は付与0
    // ============================================================

    @Test
    @DisplayName("criteria が全指標NULL（無条件付与）なら付与0（バラ撒き防止）")
    void bothMetricsNull_grantsNothing() {
        setEnabled(true);
        // phase1 criteria を全指標NULLへ上書き（合成キー upsert）。
        criteriaRepository.save(BetaPerkCriteriaEntity.builder()
                .betaPhase(PHASE).grantKind(GrantKind.INDIVIDUAL).evaluationWindowDays(WINDOW_DAYS)
                .minActiveDays(null).minMembershipTenureDays(null).minActiveMembers(null)
                .enabled(true).build());
        Long user = persistActiveUser();
        insertLoginDays(user, MIN_ACTIVE_DAYS + 5);

        batch.execute();

        assertThat(grantsOf(user)).isEmpty();
    }

    // ============================================================
    // AC-P1/P2: 読み取りクエリはユーザー数非依存（N+1 回避）
    // ============================================================

    @Test
    @DisplayName("AC-P1/P2: 付与対象ゼロの1走査で発行クエリ数はユーザー数より遥かに少ない（per-user評価をしない）")
    void readPath_isUserCountIndependent() {
        setEnabled(true);
        // 非適格ユーザー（ログイン記録なし=activeDays 0）を多数投入して活性ユーザー数を確実に押し上げる。
        for (int i = 0; i < 30; i++) {
            persistActiveUser();
        }
        // 事前 settling: 既存の適格ユーザー（他テストの汚染含む）を一度付与しきり、以降の走査で新規付与ゼロにする。
        batch.execute();

        long activeUsers = userRepository.countByStatus(UserEntity.UserStatus.ACTIVE);
        assertThat(activeUsers).isGreaterThanOrEqualTo(30);

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        batch.execute(); // 新規付与ゼロ（全員 付与済み or 非適格）。実行されるのは bulk 読み取りのみ。

        long queryCount = stats.getQueryExecutionCount();
        // per-user 評価なら activeUsers に比例して増える。ページ内定数本クエリなら activeUsers を大きく下回る。
        assertThat(queryCount)
                .as("読み取りクエリ数=%d は活性ユーザー数=%d を大きく下回るべき（N+1でない）", queryCount, activeUsers)
                .isLessThan(activeUsers);
        assertThat(queryCount).isLessThanOrEqualTo(20L);
    }

    // ============================================================
    // bulk クエリ単体の正しさ（Repository/QueryService 経由）
    // ============================================================

    @Test
    @DisplayName("bulk activeDays: 複数ユーザーの distinct ログイン日数を1クエリで正しく返す（記録なしは欠損）")
    void bulk_activeDays_correct() {
        Long u3 = persistActiveUser();
        insertLoginDays(u3, 3);
        Long u7 = persistActiveUser();
        insertLoginDays(u7, 7);
        Long uNone = persistActiveUser(); // ログインなし

        LocalDateTime since = LocalDateTime.now(clock).minusDays(WINDOW_DAYS);
        Map<Long, Long> map = loginActivityQueryService
                .countDistinctActiveDaysByUsers(List.of(u3, u7, uNone), since);

        assertThat(map).containsEntry(u3, 3L).containsEntry(u7, 7L);
        assertThat(map).doesNotContainKey(uNone); // 記録なしは欠損（呼び出し側で 0 扱い）。
    }

    @Test
    @DisplayName("bulk 在籍日数: 複数ユーザーの最古有効所属からの経過日数を1クエリで返す（有効所属なしは欠損）")
    void bulk_tenureDays_correct() {
        LocalDateTime now = LocalDateTime.now(clock);
        Long u100 = persistActiveUser();
        persistActiveMembership(u100, now.minusDays(100));
        Long u40 = persistActiveUser();
        persistActiveMembership(u40, now.minusDays(40));
        Long uNone = persistActiveUser(); // 所属なし

        Map<Long, Long> map = membershipQueryService.tenureDaysByUsers(List.of(u100, u40, uNone), now);

        assertThat(map.get(u100)).isBetween(99L, 101L);
        assertThat(map.get(u40)).isBetween(39L, 41L);
        assertThat(map).doesNotContainKey(uNone);
    }

    @Test
    @DisplayName("bulk 付与済み skip-set: 付与済み（取消済みを含む）scope_id を返し、未付与は含まない")
    void bulk_grantedScopeIds_includesRevoked() {
        Long granted = persistActiveUser();
        Long revoked = persistActiveUser();
        Long none = persistActiveUser();

        betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, PHASE, EntitlementScopeKind.USER, granted, null, true, ADMIN_ID);
        var revokedGrant = betaGrantService.grantBetaPerk(
                GrantKind.INDIVIDUAL, PHASE, EntitlementScopeKind.USER, revoked, null, true, ADMIN_ID);
        betaGrantService.revoke(revokedGrant.getId(), BetaRevokeReason.TERMS_VIOLATION, ADMIN_ID, null);

        List<Long> set = betaGrantRepository.findGrantedScopeIds(
                PHASE, EntitlementScopeKind.USER, List.of(granted, revoked, none));

        assertThat(set).contains(granted, revoked); // 取消済みも再付与不可ゆえ skip-set に載る。
        assertThat(set).doesNotContain(none);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private void setEnabled(boolean enabled) {
        ReflectionTestUtils.setField(batch, "autoGrantEnabled", enabled);
    }

    /** ACTIVE / 未削除の新規ユーザーを 1 件作成し、採番された id を返す。 */
    private Long persistActiveUser() {
        UserEntity user = UserEntity.builder()
                .email("beta-autogrant-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .lastName("ベータ").firstName("対象").displayName("ベータ対象")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(true)
                .build();
        return userRepository.save(user).getId();
    }

    /** {@code distinctDays} 個の異なる日付に LOGIN_SUCCESS を投入する（@PrePersist 回避のため直接 INSERT）。 */
    private void insertLoginDays(Long userId, int distinctDays) {
        LocalDateTime base = LocalDateTime.now(clock).withHour(12).withMinute(0).withSecond(0).withNano(0);
        for (int i = 0; i < distinctDays; i++) {
            LocalDateTime ts = base.minusDays(i + 1); // now-1 .. now-distinctDays（ウィンドウ30日内）
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (user_id, event_type, created_at) VALUES (?, ?, ?)",
                    userId, "LOGIN_SUCCESS", Timestamp.valueOf(ts));
        }
    }

    /** 指定 joined_at の有効所属（left_at IS NULL）を作成する。 */
    private void persistActiveMembership(Long userId, LocalDateTime joinedAt) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId).scopeType(ScopeType.TEAM).scopeId(7_777_001L)
                .roleKind(RoleKind.MEMBER).joinedAt(joinedAt).build());
    }

    private List<BetaGrantEntity> grantsOf(Long userId) {
        return betaGrantRepository.findByScopeKindAndScopeIdOrderByGrantedAtDesc(
                EntitlementScopeKind.USER, userId);
    }

    private java.util.UUID firstGrantId(Long userId) {
        return grantsOf(userId).get(0).getId();
    }

    private List<EntitlementEntity> activeEntitlementsOf(java.util.UUID grantId) {
        return entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.BETA_GRANT, grantId);
    }
}
