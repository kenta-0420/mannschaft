package com.mannschaft.app.role.event;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱①「ADMINゼロ根治」検分反映（P1-1） — {@link RolePurgeScopeExecutor} の
 * トランザクション分離契約テスト。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §9 / §13。
 * 「スコープ2件中1件が例外→もう1件はコミットされる」ことを実 MySQL で検証する。</p>
 *
 * <p><b>番人が自分自身を測らないための注意</b>: アサーション自体は
 * {@code TransactionTemplate} の外（本テストメソッドは非トランザクショナル）で行い、
 * DB へ新しい接続で問い合わせる。もし本テストクラスに {@code @Transactional} を
 * 付けてしまうと、テスト自身のロールバック前提と {@code processScope} の
 * {@code REQUIRES_NEW} が絡み合い、「本当にコミットされたか」を検査できなくなる
 * （番人が自分の観測手段ごと巻き込まれて偽陰性になる）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱①ADMINゼロ根治 P1-1 RolePurgeScopeExecutor トランザクション分離契約")
class RolePurgeScopeExecutorTransactionIsolationIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private RolePurgeScopeExecutor scopeExecutor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager em;

    private Long userId;
    private Long okTeamId;
    private Long failTeamId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(tx -> {
            userId = insertUser("rpse-user-" + System.nanoTime() + "@example.com");
            okTeamId = insertTeam("RPSE OKチーム", "rpse-ok-" + Long.toHexString(System.nanoTime()));
            failTeamId = insertTeam("RPSE 失敗チーム", "rpse-fail-" + Long.toHexString(System.nanoTime()));

            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, okTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", okTeamId, null);
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, failTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", failTeamId, null);
        });
    }

    @AfterEach
    void tearDown() {
        if (userId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid")
                    .setParameter("uid", userId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :uid")
                    .setParameter("uid", userId).executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id IN (:ok, :fail)")
                    .setParameter("ok", okTeamId).setParameter("fail", failTeamId).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id = :uid")
                    .setParameter("uid", userId).executeUpdate();
        });
    }

    @Test
    @DisplayName("P1-1: 2スコープ中1件が例外でも、もう1件は独立コミットされる（rollback-only巻き添えなし）")
    void 一件の失敗がもう一件のコミットを巻き込まない() {
        UUID purgeId = UUID.randomUUID();

        // okTeamId は正常に処理される。failTeamId は存在しないスコープIDを渡して
        // removeMemberWithoutAdminCheck が ROLE_001 を投げる状態を作る
        // （呼び出し元の RolePurgeEventListener と同じ try/catch ループを模して、
        // 1件目の呼び出し自体を意図的に失敗させる）。
        scopeExecutor.processScope(userId, okTeamId, "TEAM", true, purgeId);
        try {
            scopeExecutor.processScope(userId, 999_999_999L, "TEAM", false, purgeId);
        } catch (Exception ignored) {
            // 呼び出し元（RolePurgeEventListener）と同じく、失敗しても後続処理は継続する前提。
        }

        // 新しい問い合わせで実 DB 状態を確認する（このテストメソッド自体は非トランザクショナル
        // なので、EntityManager のキャッシュではなく実際にコミットされたDB状態を見る）。
        em.clear();
        long okTeamAdminRows = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_roles WHERE user_id = :uid AND team_id = :tid")
                .setParameter("uid", userId)
                .setParameter("tid", okTeamId)
                .getSingleResult()).longValue();

        // okTeamId 側は removeMemberWithoutAdminCheck により ADMIN 行が削除されている
        // （= 失敗した failTeamId 側の処理に巻き込まれてロールバックされていない）。
        assertThat(okTeamAdminRows).isEqualTo(0L);
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'RPSE', 'テスト', 'RPSE テスト', 'ACTIVE', "
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
    }

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
