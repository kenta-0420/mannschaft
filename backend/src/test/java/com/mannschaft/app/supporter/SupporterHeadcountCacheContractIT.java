package com.mannschaft.app.supporter;

import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.config.RedisConfig;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261004-1942 試練: 組織・チームのヘッダ「サポーター ◯人」が応援・解除・承認で更新されること
 * （詳細 GET のキャッシュ反映）と、組織詳細にサポーター数が載ることの受け入れテスト（AC-6〜AC-12 の BE 部分）。
 *
 * <p><b>なぜ既存の Supporter 契約 IT と作法を変えるか</b>（Codex 軍議指摘への対処）:</p>
 * <ul>
 *   <li>既存の {@code SupporterScopeContractIT} 等はクラスに {@code @Transactional} が付いており、
 *       変更処理が実際にはコミットされない。キャッシュ無効化は {@code MembershipChangedEvent} の
 *       AFTER_COMMIT リスナーで行う設計のため、それではリスナーが一度も発火しない。
 *       本クラスは {@code @Transactional} を付けず、fixture は {@link TransactionTemplate} で明示コミットし、
 *       API 呼び出しもそれぞれ本物のトランザクションとしてコミットさせる。後始末は {@link #cleanUp()} で行う。</li>
 *   <li>共通基底の test プロファイルではキャッシュが {@code ConcurrentMapCacheManager}（トランザクション非連動・
 *       参照をそのまま保持）であり本番と挙動が違う。本クラスは Valkey 互換の Redis コンテナを起動し、
 *       <b>本番と同じ {@link RedisConfig#cacheManager} の構築コード</b>
 *       （{@code TransactionAwareRedisCacheManager} ＋ {@code FailOpenRedisCacheWriter} ＋ JSON シリアライズ・
 *       {@code team-detail}/{@code org-detail} の TTL 10 分）で作った CacheManager を {@code @Primary} として差し込む。
 *       各テストは「GET でキャッシュを温めた → キャッシュに実際に値が入った」ことを確かめてから変更処理を行う。</li>
 *   <li>AC-9（evict 失敗でも follow は成功）は、Redis への DEL/UNLINK だけを失敗させる接続プロキシで再現する
 *       （GET/SET は通す）。「evict が試みられたこと」も同じプロキシで記録して検証する
 *       （未実装のままでは evict が一度も試みられないため、成功ステータスだけでは偽 green になる）。</li>
 * </ul>
 *
 * <p>コンテキスト分岐について: {@code @Import} によりこのクラス専用の ApplicationContext が 1 つ増える
 * （{@code FeatureGateAspectIT} 等と同じ扱い）。本番と同じキャッシュ構成で AFTER_COMMIT 連動を検証するには
 * 共通コンテキストの Map キャッシュでは足りないため、意図して分岐させている。</p>
 *
 * <p>範囲外: メンバー数（memberCount）はテストしない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Import(SupporterHeadcountCacheContractIT.ProductionLikeCacheConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261004-1942 ヘッダ人数（サポーター数）のキャッシュ反映 受け入れテスト（AC-6〜12）")
class SupporterHeadcountCacheContractIT extends AbstractMySqlIntegrationTest {

    private static final String SUPPORTER_COUNT_PATH = "$.data.social.supporterCount";
    private static final String TAG = "cmp1942";

    /** 本番の Valkey 相当（Redis プロトコル互換）。既存の Redis 系 IT と同じイメージ・待機方式。 */
    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            // 開発機の Docker TCP プロキシでは exec 経由のポート確認が中継されないためログ待機にする
            // （WebSocketRelayMultiNodeIntegrationTest と同じ理由）
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections tcp.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    static {
        if (isDockerAvailable()) {
            VALKEY.start();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisDeleteFaultInjector faultInjector;

    @PersistenceContext
    private EntityManager em;

    /** 組織・チームの切替（同一シナリオを双子の EP に流す）。 */
    enum Kind {
        ORGANIZATION("/api/v1/organizations", "org-detail", "PUBLIC", "PRIVATE"),
        TEAM("/api/v1/teams", "team-detail", "PUBLIC", "SUPPORTERS_AND_ABOVE");

        final String basePath;
        final String cacheName;
        final String publicVisibility;
        /** 非所属者には見えない（サポーター以上 or 組織 PRIVATE）可視性。 */
        final String restrictedVisibility;

        Kind(String basePath, String cacheName, String publicVisibility, String restrictedVisibility) {
            this.basePath = basePath;
            this.cacheName = cacheName;
            this.publicVisibility = publicVisibility;
            this.restrictedVisibility = restrictedVisibility;
        }

        ScopeType scopeType() {
            return ScopeType.valueOf(name());
        }
    }

    /** スコープ 1 件（id と slug）。 */
    private record Scope(Kind kind, Long id, String slug) {
    }

    /** 本テストで作った行（後始末用）。テスト間・他テストクラスへ漏らさない。 */
    private final List<Scope> createdScopes = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        faultInjector.reset();
        transactionTemplate.executeWithoutResult(tx -> {
            for (Scope s : createdScopes) {
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type = :st AND scope_id = :sid")
                        .setParameter("st", s.kind().scopeType().name())
                        .setParameter("sid", s.id())
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM supporter_applications WHERE scope_type = :st AND scope_id = :sid")
                        .setParameter("st", s.kind().name())
                        .setParameter("sid", s.id())
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM supporter_settings WHERE scope_type = :st AND scope_id = :sid")
                        .setParameter("st", s.kind().name())
                        .setParameter("sid", s.id())
                        .executeUpdate();
                String col = s.kind() == Kind.ORGANIZATION ? "organization_id" : "team_id";
                em.createNativeQuery("DELETE FROM user_roles WHERE " + col + " = :sid")
                        .setParameter("sid", s.id())
                        .executeUpdate();
                String table = s.kind() == Kind.ORGANIZATION ? "organizations" : "teams";
                em.createNativeQuery("DELETE FROM " + table + " WHERE id = :sid")
                        .setParameter("sid", s.id())
                        .executeUpdate();
            }
            for (Long uid : createdUserIds) {
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid")
                        .setParameter("uid", uid).executeUpdate();
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :uid")
                        .setParameter("uid", uid).executeUpdate();
                em.createNativeQuery("DELETE FROM users WHERE id = :uid")
                        .setParameter("uid", uid).executeUpdate();
            }
        });
        for (Scope s : createdScopes) {
            Cache cache = cacheManager.getCache(s.kind().cacheName);
            if (cache != null) {
                cache.evict(s.slug());
            }
        }
        createdScopes.clear();
        createdUserIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 前提: 差し込んだ CacheManager が本番相当であること（偽 green 防止の自己検査）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("前提: 詳細 GET が使う CacheManager は本番と同じトランザクション連動の RedisCacheManager である")
    void 前提_CacheManagerは本番相当のトランザクション連動Redisである() {
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        assertThat(((RedisCacheManager) cacheManager).isTransactionAware()).isTrue();
        assertThat(cacheManager.getCache("team-detail")).isInstanceOf(TransactionAwareCacheDecorator.class);
        assertThat(cacheManager.getCache("org-detail")).isInstanceOf(TransactionAwareCacheDecorator.class);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-6: 承認制スコープの申請中・取消・却下は不変、承認だけ +1
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-6: 承認制スコープでは承認したときだけサポーター数が増える")
    class Ac6ApprovalRequired {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-6: 申請（PENDING）中はサポーター数が変わらない")
        void 申請中はサポーター数不変(Kind kind) throws Exception {
            Fixture f = approvalRequiredFixture(kind);
            Long applicant = newUser("ac6-pending");

            asUser(applicant);
            mockMvc.perform(post(kind.basePath + "/{slug}/follow", f.scope().slug()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));

            assertThat(dbSupporterCount(f.scope())).isEqualTo(1);
            assertThat(fetchSupporterCount(f.scope(), f.adminId())).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-6: 申請取消（DELETE follow で PENDING 削除）でもサポーター数が変わらない")
        void 申請取消でもサポーター数不変(Kind kind) throws Exception {
            Fixture f = approvalRequiredFixture(kind);
            Long applicant = newUser("ac6-cancel");
            insertPendingApplication(f.scope(), applicant);

            asUser(applicant);
            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", f.scope().slug()))
                    .andExpect(status().isNoContent());

            assertThat(pendingCount(f.scope(), applicant)).isZero();
            assertThat(dbSupporterCount(f.scope())).isEqualTo(1);
            assertThat(fetchSupporterCount(f.scope(), f.adminId())).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-6: 却下でもサポーター数が変わらない")
        void 却下でもサポーター数不変(Kind kind) throws Exception {
            Fixture f = approvalRequiredFixture(kind);
            Long applicant = newUser("ac6-reject");
            Long appId = insertPendingApplication(f.scope(), applicant);

            asUser(f.adminId());
            mockMvc.perform(post(kind.basePath + "/{slug}/supporter-applications/{id}/reject",
                            f.scope().slug(), appId))
                    .andExpect(status().isNoContent());

            assertThat(dbSupporterCount(f.scope())).isEqualTo(1);
            assertThat(fetchSupporterCount(f.scope(), f.adminId())).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-6: 承認したときだけサポーター数が +1 される（温めたキャッシュが残っていても反映される）")
        void 承認でサポーター数が1増える(Kind kind) throws Exception {
            Fixture f = approvalRequiredFixture(kind);
            Long applicant = newUser("ac6-approve");
            Long appId = insertPendingApplication(f.scope(), applicant);

            asUser(f.adminId());
            mockMvc.perform(post(kind.basePath + "/{slug}/supporter-applications/{id}/approve",
                            f.scope().slug(), appId))
                    .andExpect(status().isNoContent());

            assertThat(dbSupporterCount(f.scope())).isEqualTo(2);
            assertThat(fetchSupporterCount(f.scope(), f.adminId())).isEqualTo(2);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7: 温めたキャッシュがあっても follow / unfollow / 一括承認で即反映
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-7: キャッシュを温めた後でも応援・解除・承認が詳細 GET に反映される")
    class Ac7CacheReflection {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-7: 温める → follow で +1 → unfollow で -1")
        void followで増えunfollowで減る(Kind kind) throws Exception {
            Fixture f = autoApproveFixture(kind, kind.publicVisibility);
            Long fan = newUser("ac7-fan");

            asUser(fan);
            mockMvc.perform(post(kind.basePath + "/{slug}/follow", f.scope().slug()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
            assertThat(dbSupporterCount(f.scope())).isEqualTo(2);
            assertThat(fetchSupporterCount(f.scope(), f.adminId()))
                    .as("follow 後の再 GET は温めたキャッシュ（1）ではなく実数（2）を返す")
                    .isEqualTo(2);

            assertCached(f.scope());
            asUser(fan);
            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", f.scope().slug()))
                    .andExpect(status().isNoContent());
            assertThat(dbSupporterCount(f.scope())).isEqualTo(1);
            assertThat(fetchSupporterCount(f.scope(), f.adminId()))
                    .as("unfollow 後の再 GET は温めたキャッシュ（2）ではなく実数（1）を返す")
                    .isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-7: 一括承認（全件成功）でも温めたキャッシュを越えて人数が反映される")
        void 一括承認の成功が反映される(Kind kind) throws Exception {
            Fixture f = approvalRequiredFixture(kind);
            Long a1 = insertPendingApplication(f.scope(), newUser("ac7-bulk1"));
            Long a2 = insertPendingApplication(f.scope(), newUser("ac7-bulk2"));

            asUser(f.adminId());
            mockMvc.perform(post(kind.basePath + "/{slug}/supporter-applications/bulk-approve", f.scope().slug())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"applicationIds\":[" + a1 + "," + a2 + "]}"))
                    .andExpect(status().isNoContent());

            assertThat(dbSupporterCount(f.scope())).isEqualTo(3);
            assertThat(fetchSupporterCount(f.scope(), f.adminId())).isEqualTo(3);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-7: 無効化は対象スコープの slug 1 件だけ（無関係スコープの温めたキャッシュは残る）")
        void 無効化は対象スコープだけ(Kind kind) throws Exception {
            Fixture target = autoApproveFixture(kind, kind.publicVisibility);
            Fixture other = autoApproveFixture(kind, kind.publicVisibility);
            Long fan = newUser("ac7-single");

            asUser(fan);
            mockMvc.perform(post(kind.basePath + "/{slug}/follow", target.scope().slug()))
                    .andExpect(status().isCreated());

            assertThat(fetchSupporterCount(target.scope(), target.adminId())).isEqualTo(2);
            assertThat(cachedValue(other.scope()))
                    .as("無関係スコープ %s のキャッシュまで消している（allEntries 等の過剰 evict）", other.scope().slug())
                    .isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8: 一括承認の途中失敗は全ロールバック・キャッシュは残る
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-8: 一括承認の途中失敗は全ロールバックされ、温めたキャッシュは evict されない")
    class Ac8BulkApproveRollback {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-8: 2件目が存在しない申請IDなら 404・人数不変・キャッシュは温めた値のまま DB 実数と一致")
        void 二件目が不在なら全ロールバック(Kind kind) throws Exception {
            Fixture f = approvalRequiredFixture(kind);
            Long applicant = newUser("ac8-missing");
            Long a1 = insertPendingApplication(f.scope(), applicant);
            long missingId = maxApplicationId() + 100_000L;

            assertBulkApproveRollsBack(f, applicant, a1, missingId);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-8: 2件目が別スコープの申請IDなら 404・人数不変・キャッシュは温めた値のまま DB 実数と一致")
        void 二件目が別スコープなら全ロールバック(Kind kind) throws Exception {
            Fixture f = approvalRequiredFixture(kind);
            Scope foreign = newScope(kind, kind.publicVisibility);
            Long applicant = newUser("ac8-foreign");
            Long a1 = insertPendingApplication(f.scope(), applicant);
            Long foreignApp = insertPendingApplication(foreign, newUser("ac8-foreign-applicant"));

            assertBulkApproveRollsBack(f, applicant, a1, foreignApp);
            assertThat(pendingCountById(foreignApp)).as("別スコープの申請も PENDING のまま").isEqualTo(1);
        }

        private void assertBulkApproveRollsBack(Fixture f, Long applicant, Long a1, long badId) throws Exception {
            Kind kind = f.scope().kind();
            Object warmed = cachedValue(f.scope());
            assertThat(warmed).as("前提: 一括承認の前にキャッシュが温まっている").isNotNull();

            asUser(f.adminId());
            mockMvc.perform(post(kind.basePath + "/{slug}/supporter-applications/bulk-approve", f.scope().slug())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"applicationIds\":[" + a1 + "," + badId + "]}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SUPPORTER_003"));

            assertThat(dbSupporterCount(f.scope())).as("1件目の承認もロールバックされている").isEqualTo(1);
            assertThat(activeSupporter(f.scope(), applicant)).isZero();
            assertThat(pendingCountById(a1)).as("1件目の申請は PENDING のまま").isEqualTo(1);
            assertThat(faultInjector.deleteAttemptsFor(kind.cacheName, f.scope().slug()))
                    .as("ロールバックされた一括承認でキャッシュを evict してはならない（AFTER_COMMIT であること）")
                    .isZero();
            assertThat(cachedValue(f.scope())).as("温めたキャッシュが残っている").isNotNull();
            assertThat(fetchSupporterCount(f.scope(), f.adminId()))
                    .as("次の GET も旧値（=DB 実数 1）")
                    .isEqualTo(dbSupporterCount(f.scope()))
                    .isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-9: evict が例外でも follow は成功・コミット済み
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-9: キャッシュ evict が失敗しても応援・解除は成功しコミットされる")
    class Ac9EvictFailure {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-9: Valkey の DEL が例外でも follow は 201 で SUPPORTER 所属がコミットされている（evict は試みられる）")
        void evict失敗でもfollowは成功(Kind kind) throws Exception {
            Fixture f = autoApproveFixture(kind, kind.publicVisibility);
            Long fan = newUser("ac9-follow");
            faultInjector.failDeletes(true);

            asUser(fan);
            mockMvc.perform(post(kind.basePath + "/{slug}/follow", f.scope().slug()))
                    .andExpect(status().isCreated());

            assertThat(activeSupporter(f.scope(), fan)).as("membership はコミットされている").isEqualTo(1);
            assertThat(faultInjector.deleteAttemptsFor(kind.cacheName, f.scope().slug()))
                    .as("コミット後に対象 slug のキャッシュ evict が試みられている（失敗は握られて応答に影響しない）")
                    .isPositive();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-9: Valkey の DEL が例外でも unfollow は 204 で SUPPORTER 所属の終了がコミットされている")
        void evict失敗でもunfollowは成功(Kind kind) throws Exception {
            Fixture f = autoApproveFixture(kind, kind.publicVisibility);
            faultInjector.failDeletes(true);

            asUser(f.supporterId());
            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", f.scope().slug()))
                    .andExpect(status().isNoContent());

            assertThat(activeSupporter(f.scope(), f.supporterId())).as("所属の終了はコミットされている").isZero();
            assertThat(faultInjector.deleteAttemptsFor(kind.cacheName, f.scope().slug())).isPositive();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10: 組織の social.supporterCount
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-10: 組織詳細の social.supporterCount はアクティブな SUPPORTER 数")
    class Ac10OrganizationSupporterCount {

        @Test
        @DisplayName("AC-10: 退会済み（left_at あり）と MEMBER を除いたアクティブ SUPPORTER 数と一致する")
        void 退会済みとMEMBERを除いた数と一致() throws Exception {
            Scope org = newScope(Kind.ORGANIZATION, "PUBLIC");
            Long viewer = newUser("ac10-viewer");
            Long s1 = newUser("ac10-s1");
            Long s2 = newUser("ac10-s2");
            Long former = newUser("ac10-former");
            Long member = newUser("ac10-member");
            transactionTemplate.executeWithoutResult(tx -> {
                MembershipTestHelper.insertMembership(em, s1, ScopeType.ORGANIZATION, org.id(), RoleKind.SUPPORTER);
                MembershipTestHelper.insertMembership(em, s2, ScopeType.ORGANIZATION, org.id(), RoleKind.SUPPORTER);
                MembershipTestHelper.insertMembership(em, member, ScopeType.ORGANIZATION, org.id(), RoleKind.MEMBER);
                insertLeftSupporter(former, org);
            });

            asUser(viewer);
            mockMvc.perform(get(Kind.ORGANIZATION.basePath + "/{slug}", org.slug()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(SUPPORTER_COUNT_PATH).value(2));
            assertThat(dbSupporterCount(org)).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-10: サポーター 0 人の組織では 0 を返す（null・欠落ではない）")
        void ゼロ人なら0() throws Exception {
            Scope org = newScope(Kind.ORGANIZATION, "PUBLIC");
            Long viewer = newUser("ac10-zero");

            asUser(viewer);
            mockMvc.perform(get(Kind.ORGANIZATION.basePath + "/{slug}", org.slug()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(SUPPORTER_COUNT_PATH).isNumber())
                    .andExpect(jsonPath(SUPPORTER_COUNT_PATH).value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11（BE）: 唯一の所属がサポーターの人が解除すると、以後の詳細 GET は不可
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-11（BE）: 非公開スコープのサポーターが解除すると以後の詳細 GET は 403")
    class Ac11UnfollowLosesAccess {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-11: 唯一の所属がサポーターの人は解除 204 の後、キャッシュが温まっていても詳細 GET が 403・人数を返さない")
        void 解除後の詳細GETは403(Kind kind) throws Exception {
            Fixture f = autoApproveFixture(kind, kind.restrictedVisibility);
            Long supporter = f.supporterId();

            // 解除前はサポーター本人が詳細を見られる（同時にキャッシュを温める）
            asUser(supporter);
            mockMvc.perform(get(kind.basePath + "/{slug}", f.scope().slug()))
                    .andExpect(status().isOk());
            assertCached(f.scope());

            asUser(supporter);
            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", f.scope().slug()))
                    .andExpect(status().isNoContent());

            asUser(supporter);
            MvcResult denied = mockMvc.perform(get(kind.basePath + "/{slug}", f.scope().slug()))
                    .andExpect(status().isForbidden())
                    .andReturn();
            assertThat(denied.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain("supporterCount");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12: 別テナントはキャッシュが温まっていても非公開スコープの詳細を見られない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-12: 別テナントの非所属ユーザーはキャッシュ経由でも人数を取得できない")
    class Ac12CrossTenant {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-12: 管理者がキャッシュを温めた後でも、別組織の管理者（非所属）の詳細 GET は 403 で人数を返さない")
        void 別テナントは403で人数を返さない(Kind kind) throws Exception {
            Fixture f = autoApproveFixture(kind, kind.restrictedVisibility);
            Scope otherTenant = newScope(Kind.ORGANIZATION, "PUBLIC");
            Long foreignAdmin = newUser("ac12-foreign-admin");
            transactionTemplate.executeWithoutResult(tx -> {
                MembershipTestHelper.insertMembership(
                        em, foreignAdmin, ScopeType.ORGANIZATION, otherTenant.id(), RoleKind.MEMBER);
                MembershipTestHelper.insertUserRole(em, foreignAdmin, "ADMIN", null, otherTenant.id());
            });
            assertCached(f.scope());

            asUser(foreignAdmin);
            MvcResult denied = mockMvc.perform(get(kind.basePath + "/{slug}", f.scope().slug()))
                    .andExpect(status().isForbidden())
                    .andReturn();
            assertThat(denied.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain("supporterCount");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // fixture
    // ═════════════════════════════════════════════════════════════════════

    /** スコープ・その ADMIN・既存サポーター 1 人。キャッシュは ADMIN の GET で温め済み（人数 1）。 */
    private record Fixture(Scope scope, Long adminId, Long supporterId) {
    }

    /** 自動承認（設定なし＝既定 ON）のスコープ。 */
    private Fixture autoApproveFixture(Kind kind, String visibility) throws Exception {
        return fixture(kind, visibility, false);
    }

    /** 承認制（supporter_settings.auto_approve = 0）の公開スコープ。 */
    private Fixture approvalRequiredFixture(Kind kind) throws Exception {
        return fixture(kind, kind.publicVisibility, true);
    }

    private Fixture fixture(Kind kind, String visibility, boolean approvalRequired) throws Exception {
        Scope scope = newScope(kind, visibility);
        Long admin = newUser("admin");
        Long supporter = newUser("supporter");
        transactionTemplate.executeWithoutResult(tx -> {
            MembershipTestHelper.insertMembership(em, admin, kind.scopeType(), scope.id(), RoleKind.MEMBER);
            if (kind == Kind.ORGANIZATION) {
                MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, scope.id());
            } else {
                MembershipTestHelper.insertUserRole(em, admin, "ADMIN", scope.id(), null);
            }
            MembershipTestHelper.insertMembership(em, supporter, kind.scopeType(), scope.id(), RoleKind.SUPPORTER);
            if (approvalRequired) {
                em.createNativeQuery("INSERT INTO supporter_settings (scope_type, scope_id, auto_approve) "
                                + "VALUES (:st, :sid, 0)")
                        .setParameter("st", kind.name())
                        .setParameter("sid", scope.id())
                        .executeUpdate();
            }
        });
        // キャッシュを温め、実際に値が入ったことを確かめる（温めの成立を検証の前提にする）
        assertThat(fetchSupporterCount(scope, admin)).as("前提: 温める時点の人数").isEqualTo(1);
        assertCached(scope);
        faultInjector.reset();
        return new Fixture(scope, admin, supporter);
    }

    private Scope newScope(Kind kind, String visibility) {
        String slug = TAG + (kind == Kind.ORGANIZATION ? "o-" : "t-") + Long.toHexString(System.nanoTime());
        Scope scope = transactionTemplate.execute(tx -> {
            if (kind == Kind.ORGANIZATION) {
                em.createNativeQuery(
                                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                        + "supporter_enabled, version, slug, created_at, updated_at) "
                                        + "VALUES (:name, 'OTHER', :vis, 'NONE', 1, 0, :slug, NOW(), NOW())")
                        .setParameter("name", "人数試練組織 " + slug)
                        .setParameter("vis", visibility)
                        .setParameter("slug", slug)
                        .executeUpdate();
            } else {
                em.createNativeQuery(
                                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                        + "created_at, updated_at) "
                                        + "VALUES (:name, :vis, 1, 0, 0, :slug, NOW(), NOW())")
                        .setParameter("name", "人数試練チーム " + slug)
                        .setParameter("vis", visibility)
                        .setParameter("slug", slug)
                        .executeUpdate();
            }
            String table = kind == Kind.ORGANIZATION ? "organizations" : "teams";
            Number id = (Number) em.createNativeQuery("SELECT id FROM " + table + " WHERE slug = :slug")
                    .setParameter("slug", slug)
                    .getSingleResult();
            return new Scope(kind, id.longValue(), slug);
        });
        createdScopes.add(scope);
        return scope;
    }

    private Long newUser(String role) {
        String email = TAG + "-" + role + "-" + System.nanoTime() + "@example.com";
        Long id = transactionTemplate.execute(tx -> {
            em.createNativeQuery(
                            "INSERT INTO users ("
                                    + "email, last_name, first_name, display_name, status, "
                                    + "is_searchable, handle_searchable, contact_approval_required, "
                                    + "online_visibility, dm_receive_from, encryption_key_version, "
                                    + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                    + "care_notification_enabled, offline_only, "
                                    + "created_at, updated_at) "
                                    + "VALUES (:email, '人数試練', 'テスト', '人数試練テスト', 'ACTIVE', "
                                    + "1, 1, 1, "
                                    + "'NOBODY', 'ANYONE', 1, "
                                    + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                    + "1, 0, "
                                    + "NOW(), NOW())")
                    .setParameter("email", email)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                    .setParameter("email", email)
                    .getSingleResult()).longValue();
        });
        createdUserIds.add(id);
        return id;
    }

    private Long insertPendingApplication(Scope scope, Long applicantUserId) {
        return transactionTemplate.execute(tx -> {
            em.createNativeQuery(
                            "INSERT INTO supporter_applications (scope_type, scope_id, user_id, message, status, "
                                    + "created_at, updated_at) "
                                    + "VALUES (:st, :sid, :uid, '人数試練', 'PENDING', NOW(), NOW())")
                    .setParameter("st", scope.kind().name())
                    .setParameter("sid", scope.id())
                    .setParameter("uid", applicantUserId)
                    .executeUpdate();
            return ((Number) em.createNativeQuery(
                            "SELECT id FROM supporter_applications WHERE scope_type = :st AND scope_id = :sid "
                                    + "AND user_id = :uid AND status = 'PENDING'")
                    .setParameter("st", scope.kind().name())
                    .setParameter("sid", scope.id())
                    .setParameter("uid", applicantUserId)
                    .getSingleResult()).longValue();
        });
    }

    /** 1 日前に自主退会済みの SUPPORTER 履歴（left_at 非 NULL）を 1 行作る（呼び出し側のトランザクション内）。 */
    private void insertLeftSupporter(Long userId, Scope scope) {
        em.createNativeQuery(
                        "INSERT INTO memberships ("
                                + "user_id, scope_type, scope_id, role_kind, "
                                + "joined_at, left_at, leave_reason, invited_by, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, :st, :sid, 'SUPPORTER', "
                                + "NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 1 DAY, 'SELF', NULL, "
                                + "NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("st", scope.kind().scopeType().name())
                .setParameter("sid", scope.id())
                .executeUpdate();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 観測ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void asUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** 詳細 GET を 200 で取得し social.supporterCount を返す（欠落なら失敗）。 */
    private long fetchSupporterCount(Scope scope, Long viewerId) throws Exception {
        asUser(viewerId);
        MvcResult result = mockMvc.perform(get(scope.kind().basePath + "/{slug}", scope.slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SUPPORTER_COUNT_PATH).isNumber())
                .andReturn();
        Number n = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                SUPPORTER_COUNT_PATH);
        return n.longValue();
    }

    private Object cachedValue(Scope scope) {
        Cache cache = cacheManager.getCache(scope.kind().cacheName);
        assertThat(cache).isNotNull();
        Cache.ValueWrapper wrapper = cache.get(scope.slug());
        return wrapper == null ? null : wrapper.get();
    }

    private void assertCached(Scope scope) {
        assertThat(cachedValue(scope))
                .as("前提: %s の詳細がキャッシュ %s に入っている", scope.slug(), scope.kind().cacheName)
                .isNotNull();
    }

    private long dbSupporterCount(Scope scope) {
        return count("SELECT COUNT(*) FROM memberships WHERE scope_type = :st AND scope_id = :sid "
                + "AND role_kind = 'SUPPORTER' AND left_at IS NULL", scope, null);
    }

    private long activeSupporter(Scope scope, Long userId) {
        return count("SELECT COUNT(*) FROM memberships WHERE scope_type = :st AND scope_id = :sid "
                + "AND role_kind = 'SUPPORTER' AND left_at IS NULL AND user_id = :uid", scope, userId);
    }

    private long pendingCount(Scope scope, Long userId) {
        return transactionTemplate.execute(tx -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM supporter_applications WHERE scope_type = :st AND scope_id = :sid "
                                + "AND user_id = :uid AND status = 'PENDING'")
                .setParameter("st", scope.kind().name())
                .setParameter("sid", scope.id())
                .setParameter("uid", userId)
                .getSingleResult()).longValue());
    }

    private long pendingCountById(Long applicationId) {
        return transactionTemplate.execute(tx -> ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM supporter_applications WHERE id = :id AND status = 'PENDING'")
                .setParameter("id", applicationId)
                .getSingleResult()).longValue());
    }

    private long maxApplicationId() {
        return transactionTemplate.execute(tx -> ((Number) em.createNativeQuery(
                "SELECT COALESCE(MAX(id), 0) FROM supporter_applications").getSingleResult()).longValue());
    }

    private long count(String sql, Scope scope, Long userId) {
        return transactionTemplate.execute(tx -> {
            var q = em.createNativeQuery(sql)
                    .setParameter("st", scope.kind().scopeType().name())
                    .setParameter("sid", scope.id());
            if (userId != null) {
                q.setParameter("uid", userId);
            }
            return ((Number) q.getSingleResult()).longValue();
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // 本番相当キャッシュ構成
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 本番の {@link RedisConfig#cacheManager} をそのまま使い、接続先だけ本クラスの Redis コンテナへ向ける。
     * 接続は DEL/UNLINK の失敗注入と試行記録ができるプロキシを挟む（GET/SET は素通し）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProductionLikeCacheConfig {

        @Bean
        RedisDeleteFaultInjector redisDeleteFaultInjector() {
            return new RedisDeleteFaultInjector(VALKEY.getHost(), VALKEY.getFirstMappedPort());
        }

        @Bean
        @Primary
        CacheManager productionLikeRedisCacheManager(RedisDeleteFaultInjector injector,
                                                     CacheErrorHandler cacheErrorHandler) {
            return new RedisConfig().cacheManager(injector.connectionFactory(), cacheErrorHandler);
        }
    }

    /**
     * Redis 接続のプロキシ。DEL/UNLINK の試行キーを記録し、スイッチが入っていれば例外を投げる
     * （Valkey 障害で evict だけが失敗する状況の再現）。
     */
    static final class RedisDeleteFaultInjector implements DisposableBean {

        private static final String KEY_PREFIX = "mannschaft:cache:";

        private final LettuceConnectionFactory delegate;
        private final RedisConnectionFactory proxy;
        private final AtomicBoolean failDeletes = new AtomicBoolean(false);
        private final List<String> deleteAttempts = new CopyOnWriteArrayList<>();

        RedisDeleteFaultInjector(String host, int port) {
            this.delegate = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
            this.delegate.afterPropertiesSet();
            this.delegate.start();
            this.proxy = (RedisConnectionFactory) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{RedisConnectionFactory.class},
                    (p, method, args) -> {
                        Object result = invoke(delegate, method, args);
                        if ("getConnection".equals(method.getName()) && result instanceof RedisConnection c) {
                            return wrap(c, RedisConnection.class);
                        }
                        return result;
                    });
        }

        RedisConnectionFactory connectionFactory() {
            return proxy;
        }

        void failDeletes(boolean fail) {
            failDeletes.set(fail);
        }

        void reset() {
            failDeletes.set(false);
            deleteAttempts.clear();
        }

        /** {@code mannschaft:cache:{cacheName}:{key}} への DEL/UNLINK 試行回数。 */
        long deleteAttemptsFor(String cacheName, String key) {
            String full = KEY_PREFIX + cacheName + ":" + key;
            return deleteAttempts.stream().filter(full::equals).count();
        }

        @SuppressWarnings("unchecked")
        private <T> T wrap(T target, Class<T> iface) {
            InvocationHandler handler = (p, method, args) -> {
                String name = method.getName();
                if ("keyCommands".equals(name)) {
                    return wrap((RedisKeyCommands) invoke(target, method, args), RedisKeyCommands.class);
                }
                if (("del".equals(name) || "unlink".equals(name)) && args != null && args.length == 1
                        && args[0] instanceof byte[][] keys) {
                    for (byte[] k : keys) {
                        deleteAttempts.add(new String(k, StandardCharsets.UTF_8));
                    }
                    if (failDeletes.get()) {
                        throw new org.springframework.data.redis.RedisConnectionFailureException(
                                "試練: Valkey の DEL を意図的に失敗させた");
                    }
                }
                return invoke(target, method, args);
            };
            return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{iface}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }
    }
}
