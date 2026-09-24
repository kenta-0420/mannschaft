package com.mannschaft.app.provisioning;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.provisioning.service.ProvisioningAcceptanceService;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 検分 P1-5(f) AC12 根治: accept 途中失敗時のロールバックを実 DB で検証する。
 *
 * <p>金型: {@code ProvisioningAcceptanceConcurrencyIT}（クラス単位 {@code @Transactional} を
 * 使わず、{@code accept()} 自身の {@code @Transactional(REQUIRED)} が新規の物理トランザクションを
 * 開始・終了できるようにする。テストクラスに {@code @Transactional} を付けると、accept() の
 * トランザクションはテストの外側トランザクションへ JOIN するだけになり、例外発生後も
 * 同一コネクションからは commit 前の書込みが読めてしまう（＝真のロールバックを検証できない）
 * ため、あえて非トランザクションのテストクラスとする。</p>
 *
 * <p><b>失敗の注入方法</b>: acceptor へ事前に role_kind=SUPPORTER の active membership を
 * 挿入しておく。{@code MembershipService#join} は同一 scope への active membership が既に存在し、
 * role_kind が一致しない場合 {@code MEMBERSHIP_ACTIVE_EXISTS}（自然な業務例外）を投げる。
 * これは accept() の途中（ADMIN role 付与・scope activate の後、invitation.status 更新の前）で
 * 発生するため、accept() 全体のロールバックを自然な形で検証できる（モックによる強制注入ではない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱②-2 AC12: accept途中失敗時のロールバック")
class ProvisioningAcceptanceRollbackIT extends AbstractMySqlIntegrationTest {

    @Autowired private ProvisioningAcceptanceService acceptanceService;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager em;

    private Long orgId;
    private Long acceptorId;
    private Long issuerId;
    private UUID invitationId;
    private String plaintextToken;
    private Long adminRoleId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(tx -> {
            insertRoleIfAbsent("SYSTEM_ADMIN", 1);
            insertRoleIfAbsent("ADMIN", 2);
            insertRoleIfAbsent("MEMBER", 3);
        });
        adminRoleId = roleRepository.findByName("ADMIN").orElseThrow().getId();

        issuerId = insertUser("prov-rollback-issuer-" + System.nanoTime() + "@example.com");
        acceptorId = insertUser("prov-rollback-acceptor-" + System.nanoTime() + "@example.com");
        String acceptorEmail = (String) em.createNativeQuery("SELECT email FROM users WHERE id = :id")
                .setParameter("id", acceptorId).getSingleResult();

        orgId = insertOrganization("AC12ロールバックテスト組織-" + System.nanoTime(), "PROVISIONED");

        // 失敗注入: acceptor に SUPPORTER の active membership を事前挿入しておく
        // （accept() は role_kind=MEMBER で join() を呼ぶため不一致でACTIVE_EXISTSになる）。
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, 'ORGANIZATION', :orgId, 'SUPPORTER', NOW(), NOW(), NOW())")
                .setParameter("uid", acceptorId).setParameter("orgId", orgId).executeUpdate());

        invitationId = UUID.randomUUID();
        plaintextToken = "ac12-plaintext-token-" + UUID.randomUUID();
        String tokenHash = sha256Hex(plaintextToken);
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "INSERT INTO provisioning_invitations "
                                + "(id, organization_id, invite_email, token_hash, status, expires_at, "
                                + "issued_by, created_at, updated_at) "
                                + "VALUES (UNHEX(REPLACE(:idStr,'-','')), :orgId, :email, :hash, 'PENDING', "
                                + "DATE_ADD(NOW(), INTERVAL 7 DAY), :issuedBy, NOW(), NOW())")
                .setParameter("idStr", invitationId.toString())
                .setParameter("orgId", orgId)
                .setParameter("email", acceptorEmail)
                .setParameter("hash", tokenHash)
                .setParameter("issuedBy", issuerId)
                .executeUpdate());
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("DELETE FROM provisioning_invitations WHERE id = UNHEX(REPLACE(:idStr,'-',''))")
                    .setParameter("idStr", invitationId.toString()).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'ORGANIZATION' AND scope_id = :orgId")
                    .setParameter("orgId", orgId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE organization_id = :orgId")
                    .setParameter("orgId", orgId).executeUpdate();
            em.createNativeQuery("DELETE FROM audit_logs WHERE event_type LIKE 'PROVISIONING%' AND user_id = :uid")
                    .setParameter("uid", acceptorId).executeUpdate();
            em.createNativeQuery("DELETE FROM organizations WHERE id = :orgId")
                    .setParameter("orgId", orgId).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN (:acceptorId, :issuerId)")
                    .setParameter("acceptorId", acceptorId).setParameter("issuerId", issuerId).executeUpdate();
        });
    }

    @Test
    @DisplayName("AC12: membership付与での自然な失敗は accept() 全体をロールバックする"
            + "（招待はPENDINGのまま・ADMIN role未付与・スコープはPROVISIONEDのまま）")
    void acceptRollsBackEntirelyWhenMembershipJoinFails() {
        assertThatThrownBy(() -> acceptanceService.accept(plaintextToken, acceptorId))
                .isInstanceOf(BusinessException.class);

        String invitationStatus = (String) em.createNativeQuery(
                        "SELECT status FROM provisioning_invitations WHERE id = UNHEX(REPLACE(:idStr,'-',''))")
                .setParameter("idStr", invitationId.toString()).getSingleResult();
        assertThat(invitationStatus).as("招待はPENDINGのまま（accepted_atも書き込まれていない）")
                .isEqualTo("PENDING");

        long adminRoleCount = userRoleRepository.countByOrganizationIdAndRoleId(orgId, adminRoleId);
        assertThat(adminRoleCount).as("ADMIN roleはロールバックされ0件").isZero();

        String lifecycleStatus = (String) em.createNativeQuery(
                        "SELECT lifecycle_status FROM organizations WHERE id = :id")
                .setParameter("id", orgId).getSingleResult();
        assertThat(lifecycleStatus).as("スコープはPROVISIONEDのまま（activate()もロールバックされる）")
                .isEqualTo("PROVISIONED");

        long auditCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM audit_logs WHERE event_type = 'PROVISIONING_INVITATION_ACCEPTED' "
                                + "AND user_id = :uid")
                .setParameter("uid", acceptorId).getSingleResult()).longValue();
        assertThat(auditCount).as("承諾監査ログはロールバックされ記録されない").isZero();
    }

    private void insertRoleIfAbsent(String name, int priority) {
        boolean exists = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue() > 0;
        if (exists) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :priority, 0, NOW(), NOW())")
                .setParameter("name", name).setParameter("priority", priority).executeUpdate();
    }

    private Long insertUser(String email) {
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'Prov', 'テスト', 'Prov テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).executeUpdate());
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String lifecycleStatus) {
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, lifecycle_status, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PRIVATE', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), :lifecycleStatus, NOW(), NOW())")
                .setParameter("name", name).setParameter("lifecycleStatus", lifecycleStatus).executeUpdate());
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
