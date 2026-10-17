package com.mannschaft.app.role;

import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 個人招待の閉鎖世代契約（#3892）。
 *
 * <p>フィクスチャと閉鎖を明示コミットする。失効イベントを意図的に配送しない状態で、
 * 親の閉鎖と世代だけで旧リンクを拒めることを確かめる。クラスにTXを付けて親専属の新しい読取TXから
 * フィクスチャが見えなくなる偽の失敗を避ける。外部境界以外のBean/DBはモックしない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("個人招待の閉鎖世代と解除後非復元")
class InviteClosureGenerationIT extends AbstractMySqlIntegrationTest {

    @Autowired private InviteService inviteService;
    @Autowired private InviteTokenRepository tokenRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Fixture> fixtures = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Fixture fixture : fixtures) {
            jdbc.update("DELETE FROM invite_tokens WHERE token = ?", fixture.token());
        }
        for (Long userId : createdUserIds) {
            jdbc.update("DELETE FROM user_permission_groups WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM my_scope_folder_items WHERE folder_id IN (SELECT id FROM my_scope_folders WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM my_scope_folders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (Fixture fixture : fixtures) {
            jdbc.update("DELETE FROM " + fixture.table() + " WHERE id = ?", fixture.scopeId());
        }
        for (Long roleId : createdRoleIds) {
            jdbc.update("DELETE FROM roles WHERE id = ?", roleId);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G06: 失効listenerが未配送でも休止した親の招待previewは無効")
    void archivedInviteIsInvalidBeforeRevocationDelivery(String scopeType) {
        Fixture fixture = fixture(scopeType);
        assertThat(inviteService.previewInvite(fixture.token()).getData().isValid()).isTrue();

        jdbc.update("UPDATE " + fixture.table()
                + " SET archived_at = UTC_TIMESTAMP(), closure_generation = closure_generation + 1 WHERE id = ?",
                fixture.scopeId());

        assertThat(tokenRepository.findByToken(fixture.token()).orElseThrow().getRevokedAt()).isNull();
        assertThat(inviteService.previewInvite(fixture.token()).getData().isValid())
                .as("親の休止は物理失効の配送前から招待を無効にする").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G07: 失効listener未配送のまま休止解除しても旧招待previewは無効")
    void reopenedInviteRemainsInvalidBeforeRevocationDelivery(String scopeType) {
        Fixture fixture = fixture(scopeType);
        jdbc.update("UPDATE " + fixture.table()
                + " SET archived_at = UTC_TIMESTAMP(), closure_generation = closure_generation + 1 WHERE id = ?",
                fixture.scopeId());
        jdbc.update("UPDATE " + fixture.table() + " SET archived_at = NULL WHERE id = ?", fixture.scopeId());

        assertThat(tokenRepository.findByToken(fixture.token()).orElseThrow().getRevokedAt()).isNull();
        assertThat(inviteService.previewInvite(fixture.token()).getData().isValid())
                .as("解除は閉鎖世代を巻き戻さず旧リンクを復元しない").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G11: 未閉鎖の現在世代の招待previewは従来どおり有効")
    void openCurrentGenerationInviteRemainsValid(String scopeType) {
        Fixture fixture = fixture(scopeType);
        assertThat(inviteService.previewInvite(fixture.token()).getData().isValid()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,true", "TEAM,false", "ORGANIZATION,true", "ORGANIZATION,false"})
    @DisplayName("G08: 閉鎖が完了してから開始した受諾は旧世代を拒否し加入・権限・回数を変更しない")
    void oldGenerationJoinChangesNothing(String scopeType, boolean remainsArchived) {
        Fixture fixture = fixture(scopeType);
        Long userId = new TransactionTemplate(transactionManager).execute(status -> userRepository.saveAndFlush(
                UserEntity.builder().email(UUID.randomUUID() + "@example.com")
                        .lastName("世代").firstName("試験").displayName("世代試験")
                        .isSearchable(true).locale("ja").timezone("Asia/Tokyo")
                        .status(UserEntity.UserStatus.ACTIVE).build()).getId());
        createdUserIds.add(userId);
        jdbc.update("UPDATE " + fixture.table()
                        + " SET archived_at = UTC_TIMESTAMP(), closure_generation = closure_generation + 1 WHERE id = ?",
                fixture.scopeId());
        if (!remainsArchived) {
            jdbc.update("UPDATE " + fixture.table() + " SET archived_at = NULL WHERE id = ?", fixture.scopeId());
        }

        assertThatThrownBy(() -> inviteService.joinByInvite(fixture.token(), userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(RoleErrorCode.ROLE_002));

        for (String table : List.of("memberships", "user_roles", "user_permission_groups")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId))
                    .as("旧招待の拒否時は%sを増やさない", table).isZero();
        }
        assertThat(tokenRepository.findByToken(fixture.token()).orElseThrow().getUsedCount()).isZero();
    }

    private Fixture fixture(String scopeType) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String slug = "ig-" + UUID.randomUUID().toString().substring(0, 18);
            String table = "TEAM".equals(scopeType) ? "teams" : "organizations";
            Long scopeId = "TEAM".equals(scopeType)
                    ? teamRepository.saveAndFlush(TeamEntity.builder().name("世代試験チーム").slug(slug)
                            .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build()).getId()
                    : organizationRepository.saveAndFlush(OrganizationEntity.builder().name("世代試験組織").slug(slug)
                            .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                            .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                            .supporterEnabled(true).build()).getId();
            RoleEntity role = roleRepository.findByName("MEMBER").orElseGet(() -> {
                RoleEntity created = roleRepository.saveAndFlush(RoleEntity.builder().name("MEMBER")
                        .displayName("メンバー").priority(4).isSystem(true).build());
                createdRoleIds.add(created.getId());
                return created;
            });
            String token = UUID.randomUUID().toString();
            InviteTokenEntity.InviteTokenEntityBuilder<?, ?> builder = InviteTokenEntity.builder()
                    .token(token).roleId(role.getId()).usedCount(0);
            if ("TEAM".equals(scopeType)) {
                builder.teamId(scopeId);
            } else {
                builder.organizationId(scopeId);
            }
            tokenRepository.saveAndFlush(builder.build());
            Fixture fixture = new Fixture(table, scopeId, token);
            fixtures.add(fixture);
            return fixture;
        });
    }

    private record Fixture(String table, Long scopeId, String token) { }
}
