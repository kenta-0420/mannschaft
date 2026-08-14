package com.mannschaft.app.team.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2786 丙層 AC-18: {@code TeamRepository#recalculateMemberCounts} が
 * {@code memberships} 在籍者を数えず、誤った {@code teams.member_count} を書き込む欠陥の
 * 受け入れテスト（試練 = テスト先行）。
 *
 * <p>{@code V60.010} で MEMBER / SUPPORTER の在籍行は {@code user_roles} から
 * {@code memberships} へ完全移行済みであり、{@code user_roles} に残るのは
 * SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / GUEST / JOBBER のみである。
 * 再集計バッチが {@code user_roles} だけを数えている限り、{@code member_count} は
 * 役職者の人数まで縮む。</p>
 *
 * <p>本層で本メソッドだけ毒性が別格なのは、他が「読み取りの取りこぼし」であるのに対し
 * これは {@code @Modifying} の書き込みであり、<b>誤った数値を DB に恒久的に焼き付ける</b>
 * ためである。同期更新リスナーが正しく数えていても、夜次の再集計が上書きして壊す。</p>
 *
 * <p><b>検出器の罠への対処</b>: 「再集計後の値」と「別途数え直した値」を突き合わせるだけの
 * テストは、両者が等しく壊れているときに偽 green になる。本テストは先に
 * 「在籍実勢がいくつであるか」を定数で固定するアサーションを置いてから
 * {@code member_count} と比較する。</p>
 */
@Transactional
@DisplayName("Issue #2786 丙層: member_count 再集計バッチが memberships 在籍者を数えない")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TeamMemberCountRecalculationMembershipsTest extends AbstractMySqlIntegrationTest {

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TeamRepository teamRepository;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private TeamEntity persistTeam() {
        int n = nextSeq();
        TeamEntity team = TeamEntity.builder()
                .slug("i2786-team-" + n)
                .name("2786テストチーム" + n)
                .visibility(TeamEntity.Visibility.PRIVATE)
                .supporterEnabled(true)
                .memberCount(0L)
                .build();
        em.persist(team);
        return team;
    }

    private Long persistUser(UserEntity.UserStatus status) {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("i2786-count-" + n + "@example.com")
                .lastName("再集計")
                .firstName("対象" + n)
                .displayName("再集計対象" + n)
                .status(status)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user.getId();
    }

    private Long persistActiveUser() {
        return persistUser(UserEntity.UserStatus.ACTIVE);
    }

    /**
     * 指定名のロールを取得（無ければ作成）する。
     * test profile は Flyway 無効で {@code roles} が空表のため、必要なロール行は自前で用意する。
     */
    private Long persistRoleIfNeeded(String name, int priority) {
        List<?> found = em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getResultList();
        if (!found.isEmpty()) {
            return ((Number) found.get(0)).longValue();
        }
        RoleEntity role = RoleEntity.builder()
                .name(name)
                .displayName(name)
                .priority(priority)
                .isSystem(true)
                .build();
        em.persist(role);
        em.flush();
        return role.getId();
    }

    private void grantTeamRole(Long userId, Long teamId, String roleName, int priority) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded(roleName, priority))
                .teamId(teamId)
                .build();
        em.persist(ur);
    }

    private void addTeamMembership(Long userId, Long teamId, RoleKind roleKind, LocalDateTime leftAt) {
        MembershipEntity ms = MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(teamId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .leftAt(leftAt)
                .build();
        em.persist(ms);
    }

    private void softDeleteUser(Long userId) {
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    /**
     * 再集計バッチを実行し、対象チームを 1 次キャッシュを介さずに読み直す。
     *
     * <p>{@code recalculateMemberCounts} は {@code @Modifying} のネイティブ UPDATE であり、
     * 永続コンテキストを素通りする。flush / clear を挟まずに {@code findById} すると
     * 1 次キャッシュに残る更新前のインスタンスが返り、書き込み結果を観測できない。</p>
     */
    private long recalculateAndReadMemberCount(Long teamId) {
        em.flush();
        teamRepository.recalculateMemberCounts();
        flushClear();
        return teamRepository.findById(teamId).orElseThrow().getMemberCount();
    }

    /** {@code memberships} 上の在籍実勢を独立に数える（基準値の固定用）。 */
    private long countLiveTeamMemberships(Long teamId) {
        Number n = (Number) em.createNativeQuery("""
                        SELECT COUNT(DISTINCT ms.user_id) FROM memberships ms
                        JOIN users u ON u.id = ms.user_id
                        WHERE ms.scope_type = 'TEAM' AND ms.scope_id = :teamId
                          AND ms.left_at IS NULL
                          AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
                        """)
                .setParameter("teamId", teamId)
                .getSingleResult();
        return n.longValue();
    }

    // =====================================================================
    // AC-18: 再集計後の member_count が実勢と一致する
    // =====================================================================

    /**
     * AC-18: {@code memberships} にのみ在籍する一般メンバーが再集計に数えられ、
     * {@code teams.member_count} が実勢と一致すること。
     */
    @Test
    @DisplayName("AC-18: 再集計後のmember_countはmemberships専属の一般メンバーを数える")
    void ac18_再集計はmemberships専属の一般メンバーを数える() {
        TeamEntity team = persistTeam();
        Long teamId = team.getId();

        // user_roles 行を一切持たず memberships にのみ在籍する一般メンバー 3 名（V60.010 後の正常な姿）
        for (int i = 0; i < 3; i++) {
            addTeamMembership(persistActiveUser(), teamId, RoleKind.MEMBER, null);
        }
        flushClear();

        // 基準値を先に固定する。実勢そのものが 0 に潰れていれば
        // 「member_count と実勢が一致する」だけのアサーションは偽 green になる。
        assertThat(countLiveTeamMemberships(teamId))
                .as("フィクスチャの在籍実勢そのものが 3 名であることを先に固定する")
                .isEqualTo(3L);

        assertThat(recalculateAndReadMemberCount(teamId))
                .as("再集計バッチは memberships 専属の一般メンバー 3 名を数えるべきである")
                .isEqualTo(3L);
    }

    /**
     * AC-18【陽性対照】: {@code user_roles} に ADMIN 行のみを持つ役職者が
     * 再集計後も従来どおり数えられ、一般メンバーと合算されること。
     *
     * <p>候補集合を {@code memberships} へ広げる過程で、{@code user_roles} 由来の
     * 役職者を落とす逆向きの回帰が起きないことを締める番人である。</p>
     */
    @Test
    @DisplayName("AC-18【陽性対照】: user_roles専属のADMIN役職者は従来どおり数えられ一般メンバーと合算される")
    void ac18_陽性対照_userRoles専属の役職者も合算される() {
        TeamEntity team = persistTeam();
        Long teamId = team.getId();

        // user_roles に ADMIN 行のみを持つ役職者 2 名（V60.010 後もこの姿で残る）
        grantTeamRole(persistActiveUser(), teamId, "ADMIN", 2);
        grantTeamRole(persistActiveUser(), teamId, "DEPUTY_ADMIN", 3);
        // memberships にのみ在籍する一般メンバー 4 名
        for (int i = 0; i < 4; i++) {
            addTeamMembership(persistActiveUser(), teamId, RoleKind.MEMBER, null);
        }
        flushClear();

        assertThat(recalculateAndReadMemberCount(teamId))
                .as("役職者 2 名と memberships 専属の一般メンバー 4 名の合計 6 名が member_count となるべきである")
                .isEqualTo(6L);
    }

    /**
     * AC-18【重複排除】: 両系統（{@code user_roles} と {@code memberships}）に
     * 行を持つ者が重複せず 1 名として数えられること。
     *
     * <p>2 系統を UNION する際に重複排除を落とすと、移行期に両方へ行を持つ利用者が
     * 二重計上され、{@code member_count} が実勢を上回る。</p>
     */
    @Test
    @DisplayName("AC-18【重複排除】: 両系統に行を持つ者は1名としてのみ数えられる")
    void ac18_重複排除_両系統保有者は1名として数えられる() {
        TeamEntity team = persistTeam();
        Long teamId = team.getId();

        // 同一チームに user_roles の ADMIN 行と memberships の MEMBER 行の両方を持つ
        Long dual = persistActiveUser();
        grantTeamRole(dual, teamId, "ADMIN", 2);
        addTeamMembership(dual, teamId, RoleKind.MEMBER, null);
        // memberships 専属の一般メンバー 1 名
        addTeamMembership(persistActiveUser(), teamId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(recalculateAndReadMemberCount(teamId))
                .as("両系統に行を持つ者は 1 名に畳まれ、合計 2 名となるべきである")
                .isEqualTo(2L);
    }

    /**
     * AC-18【境界】: 退会済（{@code left_at} 非 NULL）の membership、
     * 論理削除済ユーザー、非 ACTIVE ユーザーが {@code member_count} に含まれないこと。
     *
     * <p>2 系統へ候補集合を広げる際に除外条件を落とすと、{@code member_count} が
     * 実勢を上回る方向へ壊れる。取りこぼしの修正と同時に締める必要がある。</p>
     */
    @Test
    @DisplayName("AC-18【境界】: 退会済membership・論理削除済・非ACTIVEユーザーはmember_countに含まれない")
    void ac18_境界_退会済と論理削除済と非ACTIVEは含まれない() {
        TeamEntity team = persistTeam();
        Long teamId = team.getId();

        // 数えられるべき唯一の在籍者
        addTeamMembership(persistActiveUser(), teamId, RoleKind.MEMBER, null);

        // 退会済 membership
        addTeamMembership(persistActiveUser(), teamId, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));
        // 非 ACTIVE ユーザー（両系統）
        addTeamMembership(persistUser(UserEntity.UserStatus.FROZEN), teamId, RoleKind.MEMBER, null);
        grantTeamRole(persistUser(UserEntity.UserStatus.FROZEN), teamId, "ADMIN", 2);
        // 論理削除済ユーザー（両系統）
        Long deletedViaMemberships = persistActiveUser();
        addTeamMembership(deletedViaMemberships, teamId, RoleKind.MEMBER, null);
        Long deletedViaUserRoles = persistActiveUser();
        grantTeamRole(deletedViaUserRoles, teamId, "ADMIN", 2);

        em.flush();
        softDeleteUser(deletedViaMemberships);
        softDeleteUser(deletedViaUserRoles);
        flushClear();

        assertThat(countLiveTeamMemberships(teamId))
                .as("memberships 側の在籍実勢は除外条件適用後 1 名であることを先に固定する")
                .isEqualTo(1L);

        assertThat(recalculateAndReadMemberCount(teamId))
                .as("退会済・論理削除済・非 ACTIVE を除いた 1 名のみが member_count となるべきである")
                .isEqualTo(1L);
    }

    /**
     * AC-18【境界】: 別チームの在籍者を巻き込まないこと。
     *
     * <p>再集計は全 teams を一括で更新するため、スコープの結合条件を誤ると
     * 隣のチームの在籍者まで数え込む。</p>
     */
    @Test
    @DisplayName("AC-18【境界】: 再集計は別チームの在籍者を巻き込まない")
    void ac18_境界_別チームの在籍者を巻き込まない() {
        TeamEntity target = persistTeam();
        TeamEntity other = persistTeam();

        addTeamMembership(persistActiveUser(), target.getId(), RoleKind.MEMBER, null);
        for (int i = 0; i < 3; i++) {
            addTeamMembership(persistActiveUser(), other.getId(), RoleKind.MEMBER, null);
        }
        flushClear();

        assertThat(recalculateAndReadMemberCount(target.getId()))
                .as("対象チームの在籍者は 1 名であり、別チームの 3 名を巻き込んではならない")
                .isEqualTo(1L);
        assertThat(teamRepository.findById(other.getId()).orElseThrow().getMemberCount())
                .as("別チーム側も自チームの在籍者 3 名のみを数える")
                .isEqualTo(3L);
    }
}
