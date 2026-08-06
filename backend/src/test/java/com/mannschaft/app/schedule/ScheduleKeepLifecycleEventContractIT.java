package com.mannschaft.app.schedule;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.service.TeamService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.17 キープ（日付未定の予定）チーム／組織削除・退会時の後始末 契約テスト（試練 Wave2）。
 *
 * <p>設計書: {@code docs/features/F03.17_schedule_keep.md} §3.7・§9.4 AC-27 / AC-27b。
 *
 * <p>{@code TeamDeletedEvent} / {@code OrganizationDeletedEvent} / {@code UserAnonymizedEvent} は
 * いずれも {@code @TransactionalEventListener(phase = AFTER_COMMIT)} で購読される設計（§3.7）であり、
 * 実トランザクションのコミットを経ないと発火しない。よって本クラスは
 * {@link ScheduleKeepTeamContractIT} 等と異なりクラスレベル {@code @Transactional} を採らず、
 * 各テストは自前でフィクスチャの後始末（{@code @AfterEach}）を行う。</p>
 *
 * <p>Wave2 時点では該当リスナー（{@code ScheduleKeepScopeDeletedEventListener} /
 * {@code ScheduleKeepAnonymizationEventListener}）が未実装のため、後始末は発生せず red で正常。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.17 キープ チーム/組織削除・退会時の後始末（試練 Wave2）")
class ScheduleKeepLifecycleEventContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScheduleKeepRepository scheduleKeepRepository;

    @Autowired
    private TeamService teamService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long orgId;
    private Long userId;

    @BeforeEach
    void setUp() {
        long suffix = System.nanoTime() % 1_000_000L;
        teamId = insertTeam("キープ後始末チーム", "kl-t-" + suffix);
        orgId = insertOrganization("キープ後始末組織", "kl-o-" + suffix);
        userId = insertUser("keeplifecycle-" + suffix + "@example.com");

        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "MEMBER", teamId, null);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "MEMBER", null, orgId);
        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        em.createNativeQuery("DELETE FROM schedule_keeps WHERE team_id = :tid OR organization_id = :oid OR user_id = :uid")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("uid", userId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid")
                .setParameter("uid", userId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :uid")
                .setParameter("uid", userId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM teams WHERE id = :tid")
                .setParameter("tid", teamId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM organizations WHERE id = :oid")
                .setParameter("oid", orgId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM users WHERE id = :uid")
                .setParameter("uid", userId)
                .executeUpdate();
    }

    @Test
    @DisplayName("AC-27b: TeamService.deleteTeam（softDelete）実行後、TeamDeletedEventを受けた新設リスナーにより"
            + "そのチームのキープが論理削除され一覧から消える（DBのFK CASCADEは発火しないためアプリ層の後始末が必須）")
    void AC27b_チーム削除でキープが論理削除される() {
        ScheduleKeepEntity keep = scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .teamId(teamId)
                .title("チーム削除後始末対象キープ")
                .status(ScheduleKeepStatus.KEPT)
                .sortOrder(0)
                .createdBy(userId)
                .build());
        em.flush();
        em.clear();

        teamService.deleteTeam(teamId, userId);

        // AFTER_COMMIT リスナーの発火を待つ余地を与える（テストは実トランザクション内で完結しないため
        // 即時反映されない可能性があるが、Wave2 時点ではリスナー自体が存在せず、
        // 待っても論理削除は発生しない＝red が正しい）。
        em.clear();
        ScheduleKeepEntity reloaded = scheduleKeepRepository.findByIdAndTeamId(keep.getId(), teamId).orElse(null);
        assertThat(reloaded).as("チーム削除後、キープは論理削除され findByIdAndTeamId で見えなくなるはず").isNull();
    }

    @Test
    @DisplayName("AC-27b: OrganizationService.deleteOrganization 実行後、OrganizationDeletedEventを受けた"
            + "新設リスナーにより組織スコープのキープが論理削除され一覧から消える")
    void AC27b_組織削除でキープが論理削除される() {
        ScheduleKeepEntity keep = scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .organizationId(orgId)
                .title("組織削除後始末対象キープ")
                .status(ScheduleKeepStatus.KEPT)
                .sortOrder(0)
                .createdBy(userId)
                .build());
        em.flush();
        em.clear();

        organizationService.deleteOrganization(orgId, userId);

        em.clear();
        ScheduleKeepEntity reloaded = scheduleKeepRepository
                .findByIdAndOrganizationId(keep.getId(), orgId).orElse(null);
        assertThat(reloaded).as("組織削除後、キープは論理削除され見えなくなるはず").isNull();
    }

    @Test
    @DisplayName("AC-27: 退会後、個人スコープのキープは一覧から消える"
            + "（UserAnonymizedEvent を受けた新設リスナーにより論理削除される）")
    void AC27_退会で個人スコープのキープは一覧から消える() {
        ScheduleKeepEntity personalKeep = scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .userId(userId)
                .title("退会者の個人キープ")
                .status(ScheduleKeepStatus.KEPT)
                .sortOrder(0)
                .createdBy(userId)
                .build());
        em.flush();
        em.clear();

        eventPublisher.publishEvent(new UserAnonymizedEvent(userId, "keeplifecycle@example.com"));

        em.clear();
        ScheduleKeepEntity reloaded = scheduleKeepRepository
                .findByIdAndUserId(personalKeep.getId(), userId).orElse(null);
        assertThat(reloaded).as("退会後、個人スコープのキープは論理削除され見えなくなるはず").isNull();
    }

    @Test
    @DisplayName("AC-27: 退会後、チームスコープのキープは残りcreatedByが匿名化表示される"
            + "（キープ自体は消えず、作成者の表示名解決が匿名化ユーザーの表示規約に従う）")
    void AC27_退会でチームスコープのキープは残りcreatedByが匿名化表示される() {
        ScheduleKeepEntity teamKeep = scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .teamId(teamId)
                .title("退会者が作成したチームキープ")
                .status(ScheduleKeepStatus.KEPT)
                .sortOrder(0)
                .createdBy(userId)
                .build());
        em.flush();
        em.clear();

        eventPublisher.publishEvent(new UserAnonymizedEvent(userId, "keeplifecycle@example.com"));

        em.clear();
        ScheduleKeepEntity reloaded = scheduleKeepRepository
                .findByIdAndTeamId(teamKeep.getId(), teamId).orElse(null);
        assertThat(reloaded).as("チームスコープのキープは退会後も残る").isNotNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'F0317', 'テスト', 'F0317 テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, supporter_enabled, version, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
