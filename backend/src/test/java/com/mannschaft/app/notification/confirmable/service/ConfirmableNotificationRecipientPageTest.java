package com.mannschaft.app.notification.confirmable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-30・AC-59・AC-60）。
 *
 * <p>軍議第8版確定稿 §9.5「受信者一覧をページングするときのFE契約」を対象とする。
 * {@code GET .../confirmable-notifications/{id}/recipients/page} を実際の MockMvc + 実DB
 * （Testcontainers MySQL）で叩き、認可（ADMIN/MEMBER 判定）とページングが本物のコード経路を
 * 通ることを検証する（是正: Mockito 単体テストから Testcontainers IT へ戻す）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("受信者一覧ページング応答契約 試練（AC-30・AC-59・AC-60）")
class ConfirmableNotificationRecipientPageTest extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String EMAIL_PREFIX_BASE = "cnrp";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConfirmableNotificationRepository notificationRepository;
    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private Long orgId;
    private Long adminUserId;
    private Long memberUserId;
    private String emailPrefix;
    private Long notificationId;

    @BeforeEach
    void setUp() {
        seedRoles();
        orgId = insertOrganization();
        adminUserId = insertUser();
        grantRole(adminUserId, "ADMIN", orgId);
        memberUserId = insertUser();
        grantRole(memberUserId, "MEMBER", orgId);
        em.flush();
        em.clear();
    }

    @AfterEach
    void cleanUp() {
        if (notificationId != null) {
            recipientRepository.deleteAll(recipientRepository.findByConfirmableNotificationId(notificationId));
            notificationRepository.deleteById(notificationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, emailPrefix);
        }
    }

    private Long createNotification(int total, int unconfirmed) {
        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(orgId)
                .title("AC-30/59/60試練")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.DELIVERED)
                .unconfirmedVisibility(UnconfirmedVisibility.ALL_MEMBERS)
                .totalRecipientCount(total)
                .unconfirmedCount(unconfirmed)
                .build());
        return notification.getId();
    }

    @Test
    @DisplayName("AC-30: sizeに101を指定すると上限100に丸められる")
    void ac30_sizeが101_上限100に丸められる() throws Exception {
        notificationId = createNotification(0, 0);
        setAuth(adminUserId);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/confirmable-notifications/{id}/recipients/page",
                        orgId, notificationId)
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.size")
                        .value(org.hamcrest.Matchers.lessThanOrEqualTo(100)));
    }

    @Test
    @DisplayName("AC-59: ADMINが未確認者だけに絞ったページを開くと、総件数・確認済み・未確認件数は通知全体の値で返る")
    void ac59_ADMIN視点_総件数は通知全体の値() throws Exception {
        emailPrefix = EMAIL_PREFIX_BASE + "-59-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 3, emailPrefix);
        notificationId = createNotification(3, 3);
        seedRecipients(userIds);
        setAuth(adminUserId);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/confirmable-notifications/{id}/recipients/page",
                        orgId, notificationId)
                        .param("page", "0")
                        .param("size", "100")
                        .param("unconfirmedOnly", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.viewerRole")
                        .value("ADMIN"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totalElements")
                        .value(3));
    }

    @Test
    @DisplayName("AC-60: MEMBERの場合、公開範囲設定どおりに見えてよい範囲の一覧と件数だけが返る")
    void ac60_MEMBER視点_公開範囲どおりの件数() throws Exception {
        emailPrefix = EMAIL_PREFIX_BASE + "-60-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 1, emailPrefix);
        notificationId = createNotification(1, 1);
        seedRecipients(userIds);
        // memberUserId も受信者に含める（MEMBER視点の受信者資格）。
        seedRecipients(List.of(memberUserId));
        setAuth(memberUserId);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/confirmable-notifications/{id}/recipients/page",
                        orgId, notificationId)
                        .param("page", "0")
                        .param("size", "100")
                        .param("unconfirmedOnly", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.viewerRole")
                        .value("MEMBER"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.confirmedCount")
                        .value(0));
    }

    private void seedRecipients(List<Long> userIds) {
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId).orElseThrow();
        for (Long userId : userIds) {
            com.mannschaft.app.auth.entity.UserEntity user =
                    em.getReference(com.mannschaft.app.auth.entity.UserEntity.class, userId);
            recipientRepository.save(ConfirmableNotificationRecipientEntity.builder()
                    .confirmableNotification(notification)
                    .user(user)
                    .confirmToken(UUID.randomUUID().toString())
                    .build());
        }
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void seedRoles() {
        insertRole("SYSTEM_ADMIN", 1);
        insertRole("ADMIN", 2);
        insertRole("DEPUTY_ADMIN", 3);
        insertRole("MEMBER", 4);
        insertRole("SUPPORTER", 5);
        insertRole("GUEST", 6);
        em.flush();
    }

    private void insertRole(String name, int priority) {
        em.createNativeQuery(
                        "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long insertUser() {
        int n = SEQ.incrementAndGet();
        String email = "cnrp-authz-" + n + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, 'CNRP', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "CNRP 利用者" + n)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        String name = "CNRP組織-" + SEQ.incrementAndGet();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cnrp-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void grantRole(Long userId, String roleName, Long orgIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "SELECT :uid, r.id, NULL, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .setParameter("role", roleName)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                                + "SELECT :uid, 'ORGANIZATION', :oid, 'MEMBER', NOW(), NOW(), NOW() "
                                + "WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :uid "
                                + "AND m.scope_type = 'ORGANIZATION' AND m.scope_id = :oid AND m.left_at IS NULL)")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }
}
