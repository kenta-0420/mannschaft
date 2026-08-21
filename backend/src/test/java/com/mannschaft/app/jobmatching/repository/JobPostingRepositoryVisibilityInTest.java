package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-028 Phase C — {@link JobPostingRepository#findVisibleByTeamId} 結合テスト。
 *
 * <p>受け入れ条件 AC-C2（歯抜けゼロ・CUSTOM 混在でも）・AC-C3（総件数正確）・
 * AC-C5（JOBBER_INTERNAL の許可側/拒否側）・AC-C7（fail-closed 維持）を実 MySQL で検証する。
 * 役職の投入方式は {@code JobPostingVisibilityResolverIntegrationTest} を踏襲する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("JobPostingRepository.findVisibleByTeamId — 結合テスト")
class JobPostingRepositoryVisibilityInTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private JobPostingRepository postingRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long jobberRoleId;
    private Long memberUserId;
    private Long jobberUserId;
    private Long nonMemberUserId;

    @BeforeEach
    void setUp() {
        // 冪等化: roles はグローバル参照テーブルのため INSERT IGNORE で二重INSERTを無害化する
        // （同一 name の重複INSERTは UNIQUE 制約違反になる。CI shard 再編成で同一 JVM 内の
        // 同居テストが変わり得るため、盲目的 INSERT は禁止。既存行があれば黙って再利用する）。
        em.createNativeQuery(
                "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('JOBBER', '助っ人 (有償)', 7, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();
        jobberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'JOBBER'").getSingleResult()).longValue();

        memberUserId = insertUser("jprepo.member@example.com");
        jobberUserId = insertUser("jprepo.jobber@example.com");
        nonMemberUserId = insertUser("jprepo.nonmember@example.com");

        teamId = insertTeam("JP結合Repo チーム");
        insertUserRole(jobberUserId, jobberRoleId, teamId);

        // AC-C2 の赤を検出できるフィクスチャ: 可視(JOBBER_PUBLIC_BOARD, OPEN) 20件の間に
        // 不可視(TEAM_MEMBERS) を10件、CUSTOM(JOBBER_INTERNAL・非JOBBERには不可視) を5件挟み込む。
        for (int i = 0; i < 20; i++) {
            insertPosting("jp-pub-" + i, memberUserId, "OPEN", "JOBBER_PUBLIC_BOARD");
            if (i < 10) {
                insertPosting("jp-team-" + i, memberUserId, "OPEN", "TEAM_MEMBERS");
            }
            if (i < 5) {
                insertPosting("jp-jobber-" + i, memberUserId, "OPEN", "JOBBER_INTERNAL");
            }
        }
        em.flush();
        em.clear();
    }

    /**
     * AC-C2: viewer が PUBLIC(JOBBER_PUBLIC_BOARD) のみ可視でも、20件存在する以上
     * size=20 で必ず 20 件返る。間に不可視行・CUSTOM(JOBBER_INTERNAL) 行が混ざっても歯抜けなし。
     */
    @Test
    @DisplayName("AC-C2: 可視行20件+不可視/CUSTOM行混在でも size=20 で必ず20件返る（歯抜けゼロ）")
    void 歯抜けゼロ() {
        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD),
                nonMemberUserId, false, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(p -> p.getVisibilityScope() == VisibilityScope.JOBBER_PUBLIC_BOARD);
    }

    /**
     * AC-C3: 総件数は実可視件数（JOBBER_PUBLIC_BOARD×OPEN = 20件）と一致する。
     */
    @Test
    @DisplayName("AC-C3: 総件数は実可視件数と一致する（上界近似ではない）")
    void 総件数は実可視件数と一致() {
        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD),
                nonMemberUserId, false, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(20L);
    }

    /**
     * AC-C5（拒否側）: JOBBER ロールを持たない非メンバーには JOBBER_INTERNAL も TEAM_MEMBERS も見えない。
     */
    @Test
    @DisplayName("AC-C5（拒否側）: 非JOBBERにはJOBBER_INTERNAL求人が見えない")
    void 非JOBBERには見えない() {
        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD),
                nonMemberUserId, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).noneMatch(p -> p.getVisibilityScope() == VisibilityScope.JOBBER_INTERNAL);
        assertThat(page.getContent()).noneMatch(p -> p.getVisibilityScope() == VisibilityScope.TEAM_MEMBERS);
    }

    /**
     * AC-C5（許可側）: JOBBER ロール保有者には JOBBER_INTERNAL 求人が見える
     * （拒否側だけだと「全部不可視になる」実装ミスに気付けないという申し送りに基づく）。
     */
    @Test
    @DisplayName("AC-C5（許可側）: JOBBERロール保有者にはJOBBER_INTERNAL求人が見える")
    void JOBBERには見える() {
        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD),
                jobberUserId, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSizeGreaterThanOrEqualTo(25); // 20 PUBLIC + 5 JOBBER_INTERNAL
        assertThat(page.getContent()).anyMatch(p -> p.getVisibilityScope() == VisibilityScope.JOBBER_INTERNAL);
    }

    /**
     * AC-C7（拒否側）: 未認証には JOBBER_PUBLIC_BOARD のみ見え、TEAM_MEMBERS・JOBBER_INTERNAL は漏れない。
     */
    @Test
    @DisplayName("AC-C7: 未認証にはJOBBER_PUBLIC_BOARDのみ可視・機微な行は漏れない")
    void 未認証はPUBLICのみ() {
        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD),
                null, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(p -> p.getVisibilityScope() == VisibilityScope.JOBBER_PUBLIC_BOARD);
    }

    /**
     * TEAM_MEMBERS まで可視なラダーを持つ viewer には TEAM_MEMBERS 求人が含まれる。
     */
    @Test
    @DisplayName("TEAM_MEMBERSまで可視な閲覧者にはTEAM_MEMBERS求人が含まれる")
    void TEAM_MEMBERSまで可視なら含まれる() {
        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD, VisibilityScope.TEAM_MEMBERS),
                memberUserId, false, PageRequest.of(0, 100));

        assertThat(page.getTotalElements()).isEqualTo(30L); // 20 PUBLIC + 10 TEAM_MEMBERS
        assertThat(page.getContent()).anyMatch(p -> p.getVisibilityScope() == VisibilityScope.TEAM_MEMBERS);
    }

    /**
     * DRAFT は作成者本人のみ可視。
     */
    @Test
    @DisplayName("DRAFTは作成者本人のみ可視")
    void DRAFTは作成者本人のみ() {
        Long myDraftId = insertPosting("jp-draft-mine", memberUserId, "DRAFT", "JOBBER_PUBLIC_BOARD");
        em.flush();
        em.clear();

        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD),
                memberUserId, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(JobPostingEntity::getId).contains(myDraftId);
    }

    /**
     * SystemAdmin には CANCELLED 求人も見える。
     */
    @Test
    @DisplayName("SystemAdminにはCANCELLED求人も見える")
    void SystemAdminには見える() {
        Long cancelledId = insertPosting("jp-cancelled-repo", memberUserId, "CANCELLED", "JOBBER_PUBLIC_BOARD");
        em.flush();
        em.clear();

        Page<JobPostingEntity> page = postingRepository.findVisibleByTeamId(
                teamId, null, Set.of(VisibilityScope.JOBBER_PUBLIC_BOARD),
                nonMemberUserId, true, PageRequest.of(0, 200));

        assertThat(page.getContent()).extracting(JobPostingEntity::getId).contains(cancelledId);
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
                        + "VALUES (:email, '姓', '名', '姓 名', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
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

    private Long insertPosting(String title, Long createdBy, String status, String visibilityScope) {
        em.createNativeQuery(
                "INSERT INTO job_postings ("
                        + "team_id, created_by_user_id, title, description, "
                        + "work_location_type, work_start_at, work_end_at, "
                        + "reward_type, base_reward_jpy, capacity, application_deadline_at, "
                        + "visibility_scope, status, version, created_at, updated_at) "
                        + "VALUES (:teamId, :createdBy, :title, '結合テスト用ダミー説明', "
                        + "'ONSITE', "
                        + "DATE_ADD(NOW(), INTERVAL 7 DAY), "
                        + "DATE_ADD(NOW(), INTERVAL 7 DAY) + INTERVAL 4 HOUR, "
                        + "'LUMP_SUM', 5000, 1, "
                        + "DATE_ADD(NOW(), INTERVAL 6 DAY), "
                        + ":visibility, :status, 0, NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("visibility", visibilityScope)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM job_postings WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }
}
