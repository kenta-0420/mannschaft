package com.mannschaft.app.role.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 組織一覧のSQL固定化と所属・役割・人数・参加日時の互換性を実MySQLで検証する。
 */
@AutoConfigureMockMvc
@DisplayName("組織一覧バッチ照会契約テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
class MeOrganizationsBatchQueryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;


    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    /** ユニーク slug 生成用。テスト間でデータが残るため衝突を避ける。 */
    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    private Long deputyRoleId;
    private Long systemRoleId;
    private Long memberRoleId;
    private Long adminRoleId;

    @BeforeEach
    void setUp() {
        // 各テストは独立性のため全関連テーブルを掃除する（singleton container で DB は共有される）。
        // roles は IDENTITY 採番で save の INSERT が即時実行される一方、deleteAll の DELETE は flush まで保留される。
        // 他の IT が roles にコミットした行（SYSTEM_ADMIN 等）が残っていると INSERT が先に走って一意制約違反になるため、
        // 再作成の前に DELETE を確定させる（roles が空である前提に頼らない）。
        userRoleRepository.deleteAll();
        userRoleRepository.flush();
        membershipRepository.deleteAll();
        membershipRepository.flush();
        teamRepository.deleteAll();
        teamRepository.flush();
        organizationRepository.deleteAll();
        organizationRepository.flush();
        roleRepository.deleteAll();
        roleRepository.flush();
        seedRoles();
    }

    /** 本番 V2.014 と同一 priority で固定 6 ロールを投入する（priority 小 = 強）。 */
    private void seedRoles() {
        systemRoleId = saveRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        adminRoleId = saveRole("ADMIN", "管理者", 2, false);
        deputyRoleId = saveRole("DEPUTY_ADMIN", "副管理者", 3, false);
        memberRoleId = saveRole("MEMBER", "メンバー", 4, false);
        saveRole("SUPPORTER", "サポーター", 5, false);
        saveRole("GUEST", "ゲスト", 6, false);
    }

    private Long saveRole(String name, String displayName, int priority, boolean isSystem) {
        return roleRepository.save(RoleEntity.builder()
                .name(name)
                .displayName(displayName)
                .priority(priority)
                .isSystem(isSystem)
                .build()).getId();
    }

    private OrganizationEntity saveOrg(String name) {
        return organizationRepository.save(OrganizationEntity.builder()
                .slug("org-batch-" + SLUG_SEQ.incrementAndGet()).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true).build());
    }

    private void assign(Long userId, Long orgId, Long roleId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId).organizationId(orgId).roleId(roleId).build());
    }

    private void join(Long userId, Long orgId, RoleKind kind, LocalDateTime joinedAt) {
        MembershipTestHelper.insertActiveUser(em, userId);
        membershipRepository.save(MembershipEntity.builder().userId(userId)
                .scopeType(ScopeType.ORGANIZATION).scopeId(orgId)
                .roleKind(kind).joinedAt(joinedAt).build());
    }

    @Test
    @WithMockUser(username = "1101")
    @DisplayName("MeController#getMyOrganizationsは1組織から21組織へ増えてもSQL数が増えない")
    void 組織数を増やしても一覧SQL数が増えない() throws Exception {
        assign(1101L, saveOrg("最初の組織").getId(), adminRoleId);
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long beforeOne = statistics.getPrepareStatementCount();
            mockMvc.perform(get("/api/v1/me/organizations"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
            long one = statistics.getPrepareStatementCount() - beforeOne;
            for (int i = 0; i < 20; i++) {
                assign(1101L, saveOrg("追加組織").getId(), adminRoleId);
            }
            em.flush();
            em.clear();
            long beforeMany = statistics.getPrepareStatementCount();
            mockMvc.perform(get("/api/v1/me/organizations"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(21)))
                    .andExpect(jsonPath("$.data[*].role", everyItem(is("ADMIN"))));
            long many = statistics.getPrepareStatementCount() - beforeMany;
            assertThat(many).as("1組織と21組織の一覧SQL数: %s → %s", one, many)
                    .isLessThanOrEqualTo(one);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    @WithMockUser(username = "1102")
    void 参加日時順と重複しない人数と件数メタデータを維持する() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusDays(10).withNano(0);
        OrganizationEntity later = saveOrg("後の組織");
        OrganizationEntity earlier = saveOrg("先の組織");
        assign(1102L, later.getId(), adminRoleId);
        join(1102L, later.getId(), RoleKind.MEMBER, base.plusDays(1));
        join(1102L, earlier.getId(), RoleKind.SUPPORTER, base);
        join(1103L, later.getId(), RoleKind.MEMBER, base);
        join(1103L, later.getId(), RoleKind.SUPPORTER, base);
        join(1104L, later.getId(), RoleKind.MEMBER, base);
        em.find(UserEntity.class, 1104L).freeze();
        OrganizationEntity unrelated = saveOrg("他テナント");
        join(1105L, unrelated.getId(), RoleKind.MEMBER, base);
        em.flush();
        em.clear();
        mockMvc.perform(get("/api/v1/me/organizations").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(earlier.getId()))
                .andExpect(jsonPath("$.data[0].role").value("SUPPORTER"))
                .andExpect(jsonPath("$.data[0].joinedAt").value(base.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))))
                .andExpect(jsonPath("$.data[1].id").value(later.getId()))
                .andExpect(jsonPath("$.data[1].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[1].memberCount").value(3))
                .andExpect(jsonPath("$.data[1].joinedAt").value(base.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))))
                .andExpect(jsonPath("$.meta.limit").value(1))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    @WithMockUser(username = "1106")
    void アーカイブと論理削除を除外する() throws Exception {
        OrganizationEntity archived = saveOrg("アーカイブ組織");
        OrganizationEntity deleted = saveOrg("削除組織");
        assign(1106L, archived.getId(), adminRoleId);
        assign(1106L, deleted.getId(), adminRoleId);
        archived.archive();
        deleted.softDelete();
        em.flush();
        em.clear();
        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/me/organizations").param("includeArchived", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(archived.getId()))
                .andExpect(jsonPath("$.data[0].isArchived").value(true));
    }

    @Test
    @WithMockUser(username = "1107")
    void 停止ユーザーの役割はMEMBERへ戻り未所属人数は0になる() throws Exception {
        assign(1107L, saveOrg("停止利用者組織").getId(), adminRoleId);
        em.find(UserEntity.class, 1107L).freeze();
        em.flush();
        em.clear();
        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].role").value("MEMBER"))
                .andExpect(jsonPath("$.data[0].memberCount").value(0));
    }

    @Test
    @WithMockUser(username = "1108")
    void システム管理者も本人所属だけを返す() throws Exception {
        OrganizationEntity own = saveOrg("所属組織");
        join(1108L, own.getId(), RoleKind.MEMBER, LocalDateTime.now());
        userRoleRepository.save(UserRoleEntity.builder().userId(1108L).roleId(systemRoleId).build());
        saveOrg("未所属組織");
        em.flush();
        em.clear();
        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(own.getId()))
                .andExpect(jsonPath("$.data[0].role").value("SYSTEM_ADMIN"));
    }

    @Test
    @WithMockUser(username = "1109")
    void 所属なしは空配列を返す() throws Exception {
        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
    }
}
