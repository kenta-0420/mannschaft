package com.mannschaft.app.team.migration;

import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01.2.1 1-A（試練・red）: 新 Entity（{@link OrgTeamGroupEntity}・{@link TeamOrgAffiliationRestrictionEntity}）と
 * 列追加した {@link TeamOrgMembershipEntity} を、<b>実 Flyway スキーマ</b>に対して
 * 「永続化 → 再読込」で往復検証する。
 *
 * <p>{@code test} プロファイルの ddl-auto=create では Entity から DDL が作られるため
 * Entity と Flyway の食い違いが原理的に見えない。本クラスは flyway 有効・ddl-auto=none に上書きして、
 * Flyway の DDL（生成列・CHECK・UNIQUE・既定値）が Entity 経由でも効いていることを測る。
 * （前例: {@code EntityDigitBoundaryColumnFlywaySchemaIT}）</p>
 *
 * <p>対応 AC: M01（Entity 側の既定 direction）・M03・G135（DDL 側の一意制約が Entity 経由で 409 相当の例外になる素地）。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Testcontainers
@Transactional
@EnabledIf("com.mannschaft.app.team.migration.TeamOrgAffiliationEntityRoundtripFlywayIT#isDockerAvailable")
@DisplayName("F01.2.1 1-A Entity 往復（実 Flyway スキーマ）")
class TeamOrgAffiliationEntityRoundtripFlywayIT {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_org_groups_entity")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
            awaitRealConnectivity();
        }
    }

    /** コンテナ起動直後のハンドシェイク未完了で Spring の初回接続が落ちるのを避ける。 */
    private static void awaitRealConnectivity() {
        long deadline = System.currentTimeMillis() + 180_000L;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                 java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT 1");
                return;
            } catch (Exception e) {
                last = new IllegalStateException("MySQL への実接続がまだ成立しない", e);
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
        throw last != null ? last : new IllegalStateException("MySQL への実接続がタイムアウトした");
    }

    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private OrgTeamGroupRepository groupRepository;

    @Autowired
    private TeamOrgAffiliationRestrictionRepository restrictionRepository;

    @BeforeEach
    void disableForeignKeyChecks() {
        // 加盟行は teams / users への旧 FK が残っている可能性があるため、実行中のトランザクションに限り外す
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();
    }

    @AfterEach
    void restoreForeignKeyChecks() {
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate();
    }

    // ------------------------------------------------------------------
    // TeamOrgMembershipEntity（列追加）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("加盟行: direction・groupId・message・updatedAt が保存→再読込で保持される")
    void 加盟行の新列が往復する() {
        UUID groupId = UUID.randomUUID();
        TeamOrgMembershipEntity saved = em.merge(TeamOrgMembershipEntity.builder()
                .teamId(9301L)
                .organizationId(8301L)
                .status(TeamOrgMembershipEntity.Status.PENDING)
                .direction(TeamOrgAffiliationDirection.TEAM_APPLY)
                .groupId(groupId)
                .message("ご検討をお願いします")
                .invitedBy(1L)
                .invitedAt(LocalDateTime.of(2026, 9, 29, 10, 0))
                .build());
        em.flush();
        em.clear();

        TeamOrgMembershipEntity reloaded = em.find(TeamOrgMembershipEntity.class, saved.getId());
        assertThat(reloaded.getDirection()).isEqualTo(TeamOrgAffiliationDirection.TEAM_APPLY);
        assertThat(reloaded.getGroupId()).isEqualTo(groupId);
        assertThat(reloaded.getMessage()).isEqualTo("ご検討をお願いします");
        assertThat(reloaded.getUpdatedAt()).as("updated_at は永続化時に入る").isNotNull();
    }

    @Test
    @DisplayName("M01(Entity): direction 未指定なら ORG_INVITE、groupId・message は NULL のまま往復する")
    void direction未指定はORG_INVITEで往復する() {
        TeamOrgMembershipEntity saved = em.merge(TeamOrgMembershipEntity.builder()
                .teamId(9302L)
                .organizationId(8302L)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(LocalDateTime.of(2026, 9, 29, 10, 0))
                .build());
        em.flush();
        em.clear();

        TeamOrgMembershipEntity reloaded = em.find(TeamOrgMembershipEntity.class, saved.getId());
        assertThat(reloaded.getDirection()).isEqualTo(TeamOrgAffiliationDirection.ORG_INVITE);
        assertThat(reloaded.getGroupId()).isNull();
        assertThat(reloaded.getMessage()).isNull();
    }

    // ------------------------------------------------------------------
    // OrgTeamGroupEntity / OrgTeamGroupRepository
    // ------------------------------------------------------------------

    @Test
    @DisplayName("グループ: 全項目が保存→再読込で保持され、生成列 active_name は name と一致する")
    void グループが往復する() {
        OrgTeamGroupEntity saved = groupRepository.saveAndFlush(group(7401L, "平成20年度卒", 3));
        em.clear();

        OrgTeamGroupEntity reloaded = groupRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getId()).as("UUIDv7 が採番される").isNotNull();
        assertThat(reloaded.getOrganizationId()).isEqualTo(7401L);
        assertThat(reloaded.getName()).isEqualTo("平成20年度卒");
        assertThat(reloaded.getDescription()).isEqualTo("補足説明");
        assertThat(reloaded.getSortOrder()).isEqualTo(3);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(reloaded.getActiveName()).as("生存中の active_name は name").isEqualTo("平成20年度卒");
    }

    @Test
    @DisplayName("グループ: AbstractTenantAwareRepository の組織スコープ検索が論理削除を除外する")
    void グループのテナントスコープ検索は論理削除を除外する() {
        OrgTeamGroupEntity alive = groupRepository.saveAndFlush(group(7501L, "生存", 0));
        OrgTeamGroupEntity deleted = groupRepository.saveAndFlush(
                group(7501L, "削除済み", 1).toBuilder().deletedAt(LocalDateTime.of(2026, 9, 1, 0, 0)).build());
        groupRepository.saveAndFlush(group(7502L, "他組織", 0));
        em.clear();

        List<OrgTeamGroupEntity> found = groupRepository.findByOrganizationIdAndDeletedAtIsNull(7501L);
        assertThat(found).extracting(OrgTeamGroupEntity::getName).containsExactly("生存");
        assertThat(groupRepository.countByOrganizationIdAndDeletedAtIsNull(7501L)).isEqualTo(1);
        assertThat(groupRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(alive.getId(), 7501L)).isPresent();
        assertThat(groupRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(alive.getId(), 7502L))
                .as("越境（別組織 ID）では引けない").isEmpty();
        assertThat(groupRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(deleted.getId(), 7501L))
                .as("論理削除済みは引けない").isEmpty();
    }

    @Test
    @DisplayName("G135: 同じ組織・同名の生存グループを Entity 経由で保存すると DataIntegrityViolationException")
    void 同名の生存グループは一意制約違反になる() {
        groupRepository.saveAndFlush(group(7601L, "重複名", 0));

        assertThatThrownBy(() -> groupRepository.saveAndFlush(group(7601L, "重複名", 1)))
                .as("500 ではなく 409 ORG_065 に写像できるよう、DataIntegrityViolationException として現れる")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("グループ: 論理削除後は同名を再作成でき、削除済み行の active_name は NULL になる")
    void 論理削除後は同名を再作成できる() {
        OrgTeamGroupEntity first = groupRepository.saveAndFlush(group(7701L, "再利用名", 0));
        groupRepository.saveAndFlush(first.toBuilder().deletedAt(LocalDateTime.of(2026, 9, 1, 0, 0)).build());
        em.clear();

        OrgTeamGroupEntity second = groupRepository.saveAndFlush(group(7701L, "再利用名", 0));
        em.clear();

        assertThat(groupRepository.findById(first.getId()).orElseThrow().getActiveName())
                .as("削除済みは active_name = NULL").isNull();
        assertThat(groupRepository.findById(second.getId()).orElseThrow().getActiveName())
                .isEqualTo("再利用名");
        assertThat(groupRepository.findByOrganizationIdAndDeletedAtIsNull(7701L)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // TeamOrgAffiliationRestrictionEntity / Repository
    // ------------------------------------------------------------------

    @Test
    @DisplayName("制限: COOLDOWN と BLOCK の全項目が保存→再読込で保持される")
    void 制限が往復する() {
        LocalDateTime until = LocalDateTime.of(2026, 10, 29, 12, 0);
        TeamOrgAffiliationRestrictionEntity cooldown = restrictionRepository.saveAndFlush(
                restriction(6301L, 5301L, TeamOrgAffiliationDirection.TEAM_APPLY,
                        TeamOrgAffiliationRestrictionKind.COOLDOWN,
                        TeamOrgAffiliationRestrictionReason.REJECTED, until));
        TeamOrgAffiliationRestrictionEntity block = restrictionRepository.saveAndFlush(
                restriction(6301L, 5302L, TeamOrgAffiliationDirection.ORG_INVITE,
                        TeamOrgAffiliationRestrictionKind.BLOCK,
                        TeamOrgAffiliationRestrictionReason.DECLINED, null));
        em.clear();

        TeamOrgAffiliationRestrictionEntity c = restrictionRepository.findById(cooldown.getId()).orElseThrow();
        assertThat(c.getOrganizationId()).isEqualTo(6301L);
        assertThat(c.getTeamId()).isEqualTo(5301L);
        assertThat(c.getDirection()).isEqualTo(TeamOrgAffiliationDirection.TEAM_APPLY);
        assertThat(c.getKind()).isEqualTo(TeamOrgAffiliationRestrictionKind.COOLDOWN);
        assertThat(c.getReason()).isEqualTo(TeamOrgAffiliationRestrictionReason.REJECTED);
        assertThat(c.getRestrictedUntil()).isEqualTo(until);
        assertThat(c.getCreatedBy()).isEqualTo(1L);
        assertThat(c.getCreatedAt()).isNotNull();
        assertThat(c.getUpdatedAt()).isNotNull();

        TeamOrgAffiliationRestrictionEntity b = restrictionRepository.findById(block.getId()).orElseThrow();
        assertThat(b.getKind()).isEqualTo(TeamOrgAffiliationRestrictionKind.BLOCK);
        assertThat(b.getRestrictedUntil()).isNull();
        assertThat(restrictionRepository.findByOrganizationIdAndTeamIdAndDirection(
                6301L, 5301L, TeamOrgAffiliationDirection.TEAM_APPLY)).isPresent();
        assertThat(restrictionRepository.findByOrganizationIdAndTeamIdAndDirection(
                6301L, 5301L, TeamOrgAffiliationDirection.ORG_INVITE))
                .as("向きが違えば別行").isEmpty();
    }

    @Test
    @DisplayName("G135: 同じ (組織, チーム, 向き) の制限を2行保存すると一意制約違反")
    void 制限の一意制約() {
        restrictionRepository.saveAndFlush(restriction(6401L, 5401L, TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionKind.BLOCK, TeamOrgAffiliationRestrictionReason.REJECTED, null));

        assertThatThrownBy(() -> restrictionRepository.saveAndFlush(restriction(6401L, 5401L,
                TeamOrgAffiliationDirection.TEAM_APPLY, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                TeamOrgAffiliationRestrictionReason.WITHDRAWN, LocalDateTime.of(2026, 12, 31, 0, 0))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("制限: BLOCK に restricted_until を入れる不整合は CHECK 違反として拒否される")
    void 制限のCHECK違反() {
        assertThatThrownBy(() -> restrictionRepository.saveAndFlush(restriction(6501L, 5501L,
                TeamOrgAffiliationDirection.TEAM_APPLY, TeamOrgAffiliationRestrictionKind.BLOCK,
                TeamOrgAffiliationRestrictionReason.REJECTED, LocalDateTime.of(2026, 12, 31, 0, 0))))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_toar_until");
    }

    // ------------------------------------------------------------------
    // ヘルパ
    // ------------------------------------------------------------------

    private static OrgTeamGroupEntity group(long orgId, String name, int sortOrder) {
        return OrgTeamGroupEntity.builder()
                .organizationId(orgId)
                .name(name)
                .description("補足説明")
                .sortOrder(sortOrder)
                .createdBy(1L)
                .updatedBy(1L)
                .build();
    }

    private static TeamOrgAffiliationRestrictionEntity restriction(long orgId, long teamId,
            TeamOrgAffiliationDirection direction, TeamOrgAffiliationRestrictionKind kind,
            TeamOrgAffiliationRestrictionReason reason, LocalDateTime until) {
        return TeamOrgAffiliationRestrictionEntity.builder()
                .organizationId(orgId)
                .teamId(teamId)
                .direction(direction)
                .kind(kind)
                .reason(reason)
                .restrictedUntil(until)
                .createdBy(1L)
                .build();
    }
}
