package com.mannschaft.app.team.batch;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F15.4 Phase 4 — {@link TeamMemberCountBackfillBatchService} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、夜次再集計バッチが
 * {@code teams.member_count} を {@code user_roles} ベースの実数に補正することを検証する。</p>
 *
 * <p>{@code TeamVisibilityResolverIntegrationTest} の方式を踏襲し、
 * {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery} で
 * users / roles / user_roles / teams を直接 INSERT する。</p>
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>ずらした member_count が user_roles ベースの実数に戻ること</li>
 *   <li>論理削除済み team（{@code deleted_at IS NOT NULL}）は更新対象外であること</li>
 *   <li>user_roles が 0 件のチームは 0 に補正されること</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("TeamMemberCountBackfillBatchService 結合テスト")
class TeamMemberCountBackfillBatchServiceIntegrationTest extends AbstractMySqlIntegrationTest {

    /**
     * ShedLock の {@code shedlock} テーブルは Flyway 経由で本番に作成されるが、
     * テスト環境は {@code spring.flyway.enabled=false} + {@code ddl-auto=create} のため
     * Hibernate がエンティティから DDL を生成する方式となり、{@code shedlock} テーブルは作成されない。
     * そのため {@link org.springframework.scheduling.annotation.Scheduled @Scheduled} +
     * {@link net.javacrumbs.shedlock.spring.annotation.SchedulerLock @SchedulerLock} を付与した
     * {@link TeamMemberCountBackfillBatchService#recalculateAll()} を呼ぶと AOP が
     * {@code shedlock} テーブルに対して INSERT を流し SQLSyntaxErrorException で落ちる。
     * 本テストの責務は SQL 補正ロジックの検証であり、分散排他制御は対象外のため
     * {@link LockProvider} を no-op モックに差し替える。
     */
    @MockitoBean
    private LockProvider lockProvider;

    @Autowired
    private TeamMemberCountBackfillBatchService batch;

    @Autowired
    private TeamRepository teamRepository;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long teamWithMembersId;
    private Long teamEmptyId;
    private Long teamDeletedId;

    @BeforeEach
    void setUp() {
        // ShedLock の no-op スタブ: 常にロック取得成功扱いとし、@SchedulerLock 経由の
        // shedlock テーブル INSERT/UPDATE を完全にスキップする。
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));

        // MEMBER ロール
        // 冪等化: insertRoleIfAbsent 参照（存在確認してから INSERT。INSERT IGNORE は使用禁止）
        insertRoleIfAbsent("MEMBER", "メンバー", 4, false);
        em.flush();
        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();

        // 3 ユーザー
        Long u1 = insertUser("backfill.u1@example.com", "テスト", "一郎");
        Long u2 = insertUser("backfill.u2@example.com", "テスト", "二郎");
        Long u3 = insertUser("backfill.u3@example.com", "テスト", "三郎");

        // 3 チーム
        teamWithMembersId = insertTeam("BF_チーム_メンバーあり");
        teamEmptyId = insertTeam("BF_チーム_メンバーなし");
        teamDeletedId = insertTeam("BF_チーム_削除済");

        // teamWithMembersId に 3 メンバー、teamDeletedId に 1 メンバー
        insertUserRole(u1, memberRoleId, teamWithMembersId);
        insertUserRole(u2, memberRoleId, teamWithMembersId);
        insertUserRole(u3, memberRoleId, teamWithMembersId);
        insertUserRole(u1, memberRoleId, teamDeletedId);

        // teamDeletedId を論理削除
        em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", teamDeletedId)
                .executeUpdate();

        // ドリフトを意図的に発生させる: 全 team の member_count を実数とずれた値に上書き
        em.createNativeQuery("UPDATE teams SET member_count = 999 WHERE id IN (:t1, :t2, :t3)")
                .setParameter("t1", teamWithMembersId)
                .setParameter("t2", teamEmptyId)
                .setParameter("t3", teamDeletedId)
                .executeUpdate();

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("recalculateAll: ずらした member_count が user_roles ベースの実数に戻る")
    void recalculateAll_corrects_drift() {
        batch.recalculateAll();
        em.flush();
        em.clear();

        Long withMembers = ((Number) em.createNativeQuery(
                "SELECT member_count FROM teams WHERE id = :id")
                .setParameter("id", teamWithMembersId)
                .getSingleResult()).longValue();
        assertThat(withMembers).as("user_roles 3 件のチームは 3 に補正").isEqualTo(3L);

        Long empty = ((Number) em.createNativeQuery(
                "SELECT member_count FROM teams WHERE id = :id")
                .setParameter("id", teamEmptyId)
                .getSingleResult()).longValue();
        assertThat(empty).as("user_roles 0 件のチームは 0 に補正").isEqualTo(0L);
    }

    @Test
    @DisplayName("recalculateAll: 論理削除済み team は更新対象外（999 のまま）")
    void recalculateAll_skips_soft_deleted() {
        batch.recalculateAll();
        em.flush();
        em.clear();

        // @SQLRestriction("deleted_at IS NULL") の影響を受けないよう nativeQuery で取得
        Long deleted = ((Number) em.createNativeQuery(
                "SELECT member_count FROM teams WHERE id = :id")
                .setParameter("id", teamDeletedId)
                .getSingleResult()).longValue();
        assertThat(deleted).as("論理削除済みは WHERE 句で除外され、ずらした 999 のまま").isEqualTo(999L);
    }

    // =========================================================================
    // ヘルパ
    // =========================================================================

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, NULL, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .executeUpdate();
    }

    private void insertRoleIfAbsent(String name, String displayName, int priority, boolean isSystem) {
        // 冪等化: roles はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは roles の UNIQUE 制約違反になる。INSERT IGNORE は
        // 重複キー以外にもデータ切り詰め・NOT NULL違反等の異常を警告に格下げして黙って
        // 通してしまうため使用禁止。CI shard 再編成で同居テストが変わり得るため
        // 事前に SELECT で存在確認する）。
        Number existingRoleCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existingRoleCount.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

}
