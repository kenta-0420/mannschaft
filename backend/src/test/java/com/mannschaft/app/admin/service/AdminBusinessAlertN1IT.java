package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.dto.AdminBusinessAlertSummaryResponse;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.PermissionScopeQueryService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.template.service.ModuleService;
import com.mannschaft.app.support.test.N1SeedSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 業務アラートサマリー（AdminBusinessAlertService#getSummary）の N+1 解消を固定する試練
 * （CMP-261103-1225 / #4270）。
 */
@Transactional
@DisplayName("AdminBusinessAlertService の N+1 解消")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdminBusinessAlertN1IT extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdminBusinessAlertService service;
    @Autowired
    private ChatChannelRepository channelRepository;
    @Autowired
    private ChatChannelMemberRepository memberRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private ModuleService moduleService;
    @Autowired
    private PermissionScopeQueryService permissionScopeQueryService;
    @PersistenceContext
    private EntityManager em;

    private N1SeedSupport seed;

    @BeforeEach
    void setUp() {
        seed = new N1SeedSupport(em);
    }

    private void enableReservationModule(Long teamId, boolean enabled) {
        seed.ensureModule("reservation", "OPTIONAL");
        em.createNativeQuery(
                "INSERT INTO team_enabled_modules (team_id, module_id, is_enabled, is_grandfathered, trial_used, "
                        + "created_at, updated_at) "
                        + "SELECT :tid, md.id, :en, 0, 0, NOW(), NOW() FROM module_definitions md "
                        + "WHERE md.slug = 'reservation'")
                .setParameter("tid", teamId)
                .setParameter("en", enabled)
                .executeUpdate();
    }

    private void inquiryChannel(Long teamId, Long userId, int unread) {
        ChatChannelEntity ch = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PRIVATE).teamId(teamId).name("inq-" + teamId)
                .isInquiryChannel(true).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(ch.getId()).userId(userId).unreadCount(unread).build());
    }

    /** 数値のチーム ID を使った URL（/teams/123/...）。CMP-261030-0648 AC-4 の否定 assert 用。 */
    private static final String NUMERIC_TEAM_URL = ".*/teams/\\d+(/|\\?|$).*";

    private String slugOf(Long teamId) {
        return (String) em.createNativeQuery("SELECT slug FROM teams WHERE id = :id")
                .setParameter("id", teamId)
                .getSingleResult();
    }

    private long measure(Statistics s,AdminBusinessAlertSummaryResponse[] out, int idx, Long userId) {
        em.clear();
        s.clear();
        out[idx] = service.getSummary(userId);
        return s.getPrepareStatementCount();
    }

    @Test
    @DisplayName("AC-1/AC-2: 管理チーム数を増やしても SQL 数が一定で、モジュール有効・問い合わせ未読が等価")
    void sqlCountIndependentOfAdminTeamCount() {
        Long small = seed.user("alert-small");
        Long st = seed.team("N1-alert-small", null);
        seed.joinTeam(small, st, "ADMIN");
        enableReservationModule(st, true);
        inquiryChannel(st, small, 2);

        Long large = seed.user("alert-large");
        Long t1 = seed.team("N1-alert-1", null);
        Long t2 = seed.team("N1-alert-2", null);
        Long t3 = seed.team("N1-alert-3", null);
        Long t4 = seed.team("N1-alert-4", null);
        seed.joinTeam(large, t1, "ADMIN");
        seed.joinTeam(large, t2, "ADMIN");
        seed.joinTeam(large, t3, "DEPUTY_ADMIN");
        seed.joinTeam(large, t4, "DEPUTY_ADMIN");
        enableReservationModule(t1, true);
        enableReservationModule(t2, false);
        enableReservationModule(t3, true);
        inquiryChannel(t1, large, 3);
        inquiryChannel(t3, large, 5);
        seed.flushAndClear();

        Statistics s = seed.stats();
        AdminBusinessAlertSummaryResponse[] res = new AdminBusinessAlertSummaryResponse[2];
        long smallCount = measure(s, res, 0, small);
        long largeCount = measure(s, res, 1, large);
        System.out.println("N1 admin-alert SQL small=" + smallCount + " large=" + largeCount);

        assertThat(res[0].getData().getTeams()).hasSize(1);
        assertThat(res[0].getData().getTeams().get(0).isReservationModuleEnabled()).isTrue();
        assertThat(res[0].getData().getTeams().get(0).getAlerts().getUnreadInquiries()).isEqualTo(2);

        Map<Long, AdminBusinessAlertSummaryResponse.TeamAlert> byTeam = res[1].getData().getTeams().stream()
                .collect(Collectors.toMap(AdminBusinessAlertSummaryResponse.TeamAlert::getTeamId, Function.identity()));
        assertThat(byTeam.keySet()).containsExactlyInAnyOrder(t1, t2, t3, t4);
        assertThat(byTeam.get(t1).isReservationModuleEnabled()).isTrue();
        assertThat(byTeam.get(t2).isReservationModuleEnabled()).isFalse();
        assertThat(byTeam.get(t3).isReservationModuleEnabled()).isTrue();
        assertThat(byTeam.get(t4).isReservationModuleEnabled()).isFalse();
        assertThat(byTeam.get(t1).getAlerts().getUnreadInquiries()).isEqualTo(3);
        assertThat(byTeam.get(t3).getAlerts().getUnreadInquiries()).isEqualTo(5);
        assertThat(byTeam.get(t2).getAlerts().getUnreadInquiries()).isZero();
        assertThat(res[1].getData().getTotalPending()).isEqualTo(8);
        // CMP-261030-0648: リンクはそのチームの slug で組む（FE のルートは slug しか受けない）。数値 URL は作らない
        for (Long teamId : List.of(t1, t2, t3, t4)) {
            String slug = slugOf(teamId);
            AdminBusinessAlertSummaryResponse.Links links = byTeam.get(teamId).getLinks();
            assertThat(links.getReservationsUrl()).isEqualTo("/teams/" + slug + "/reservations");
            assertThat(links.getReservationsUrl()).doesNotMatch(NUMERIC_TEAM_URL);
            if (links.getInquiryChannelUrl() != null) {
                assertThat(links.getInquiryChannelUrl()).doesNotMatch(NUMERIC_TEAM_URL);
            }
        }
        assertThat(byTeam.get(t3).getLinks().getInquiryChannelUrl()).startsWith("/teams/" + slugOf(t3) + "/chat?channel=");
        assertThat(byTeam.get(t1).getLinks().getInquiryChannelUrl()).startsWith("/teams/" + slugOf(t1) + "/chat?channel=");
        assertThat(byTeam.get(t2).getLinks().getInquiryChannelUrl()).isNull();

        assertThat(largeCount).as("管理チーム数に依存しない SQL 数").isEqualTo(smallCount);
    }

    /** DEPUTY_ADMIN に権限グループ経由で MANAGE_RESERVATIONS を付与する（pg.team_id で絞られる経路）。 */
    private void grantDeputyPermissionViaGroup(Long userId, Long teamId) {
        em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                        + "SELECT 'MANAGE_RESERVATIONS', 'MANAGE_RESERVATIONS', 'TEAM', NOW(), NOW() FROM DUAL "
                        + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_RESERVATIONS')")
                .executeUpdate();
        em.createNativeQuery("INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) VALUES (:tid, NULL, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("name", "n1-pg-" + teamId)
                .executeUpdate();
        Long groupId = seed.lastId();
        em.createNativeQuery("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                        + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = 'MANAGE_RESERVATIONS'")
                .setParameter("gid", groupId)
                .executeUpdate();
        em.createNativeQuery("INSERT INTO user_permission_groups (user_id, group_id, created_at) VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId)
                .setParameter("gid", groupId)
                .executeUpdate();
    }

    @Test
    @DisplayName("AC-2: チーム群一括判定（予約対象・モジュール有効）がチームごとの旧判定と一致する")
    void batchJudgementsMatchPerTeamJudgements() {
        Long me = seed.user("alert-oracle");
        Long other = seed.user("alert-oracle-other");
        Long admin = seed.team("N1-oracle-admin", null);
        Long deputyWithPerm = seed.team("N1-oracle-deputy-perm", null);
        Long deputyNoPerm = seed.team("N1-oracle-deputy-noperm", null);
        Long deputyPermElsewhere = seed.team("N1-oracle-deputy-elsewhere", null);
        seed.joinTeam(me, admin, "ADMIN");
        seed.joinTeam(me, deputyWithPerm, "DEPUTY_ADMIN");
        seed.joinTeam(me, deputyNoPerm, "DEPUTY_ADMIN");
        seed.joinTeam(me, deputyPermElsewhere, "DEPUTY_ADMIN");
        seed.joinTeam(other, deputyNoPerm, "ADMIN"); // 他人の ADMIN は自分の判定に影響しない
        grantDeputyPermissionViaGroup(me, deputyWithPerm);
        grantDeputyPermissionViaGroup(other, deputyPermElsewhere); // 他人への付与は自分に効かない
        enableReservationModule(admin, true);
        enableReservationModule(deputyNoPerm, false);
        seed.flushAndClear();

        List<Long> teamIds = List.of(admin, deputyWithPerm, deputyNoPerm, deputyPermElsewhere);
        Set<Long> expectedTargets = new HashSet<>();
        Map<Long, Boolean> expectedModule = new HashMap<>();
        for (Long teamId : teamIds) {
            boolean target = userRoleRepository.findAdminUserIdsByTeamId(teamId).contains(me)
                    || userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(teamId, "MANAGE_RESERVATIONS")
                            .contains(me);
            if (target) {
                expectedTargets.add(teamId);
            }
            expectedModule.put(teamId, moduleService.isModuleEnabledForTeam("reservation", teamId));
        }
        Set<Long> actualTargets = new HashSet<>(permissionScopeQueryService.findAdminTeamIds(me, teamIds));
        actualTargets.addAll(permissionScopeQueryService.findPermittedTeamIds(me, teamIds, "MANAGE_RESERVATIONS"));

        assertThat(expectedTargets).containsExactlyInAnyOrder(admin, deputyWithPerm);
        assertThat(actualTargets).isEqualTo(expectedTargets);
        assertThat(moduleService.isModuleEnabledForTeams("reservation", teamIds)).isEqualTo(expectedModule);
        assertThat(expectedModule).containsEntry(admin, true).containsEntry(deputyNoPerm, false)
                .containsEntry(deputyWithPerm, false);
        assertThat(moduleService.isModuleEnabledForTeams("reservation", List.of())).isEmpty();
        assertThat(moduleService.isModuleEnabledForTeams("no-such-module", teamIds).values()).containsOnly(false);
    }

    @Test
    @DisplayName("AC-3: 管理チームなしでも空 IN でエラーにならず空を返す")
    void noAdminTeams() {
        Long me = seed.user("alert-none");
        seed.flushAndClear();
        AdminBusinessAlertSummaryResponse res = service.getSummary(me);
        assertThat(res.getData().getTeams()).isEmpty();
        assertThat(res.getData().getTotalPending()).isZero();
    }
}
