package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.advertising.controller.OrganizationOperationalAdCampaignController;
import com.mannschaft.app.advertising.controller.SystemAdminOperationalAdCampaignController;
import com.mannschaft.app.advertising.dto.RejectOperationalCampaignRequest;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.19.1 AC-1.9 運用型キャンペーン監査ログ試練テスト。
 *
 * <p>submit / approve / reject / pause / resume / end の 6 操作で
 * {@code OPERATIONAL_CAMPAIGN_*} 6 イベントが {@code audit_logs} に記録されることを検証する
 * （正本 §16 F09.19.1）。</p>
 *
 * <p>監査ログは {@code AuditLogService.record} が {@code @Async} + 独立トランザクションで書き込むため、
 * {@code RepairPlanAuditLogIntegrationTest} で確立済みの
 * {@code Awaitility + REQUIRES_NEW TransactionTemplate} パターンで検証する。
 * 非同期書き込みはテストトランザクションの外で commit されるため、@AfterEach で
 * {@code OPERATIONAL_CAMPAIGN_%} 行を REQUIRES_NEW で掃除して共有 DB の汚染を防ぐ。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.1 AC-1.9 運用型キャンペーン監査ログ試練テスト")
class OperationalAdCampaignAuditLogIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrganizationOperationalAdCampaignController controller;

    @Autowired
    private SystemAdminOperationalAdCampaignController adminController;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private Long orgId;
    private Long adminId;
    private Long sysAdminId;
    private Long rateCardId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        insertRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        insertRole("ADMIN", "管理者", 2, false);

        adminId = insertUser("audit-admin@example.com");
        sysAdminId = insertUser("audit-sysadmin@example.com");
        orgId = insertOrganization("監査ログ 組織");

        insertUserRole(adminId, roleId("ADMIN"), orgId);
        insertUserRole(sysAdminId, roleId("SYSTEM_ADMIN"), null);
        accountId = insertAdvertiserAccount(orgId, "監査ログ広告主");
        rateCardId = insertRateCard();

        em.flush();
        em.clear();
        setAuthentication(adminId);
    }

    @AfterEach
    void cleanUpAsyncCommittedAuditRows() {
        SecurityContextHolder.clearContext();
        // @Async の監査書き込みはテスト tx の外で commit されるため REQUIRES_NEW で掃除する
        TransactionTemplate newTx = new TransactionTemplate(txManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        newTx.executeWithoutResult(status -> em.createNativeQuery(
                        "DELETE FROM audit_logs WHERE event_type LIKE 'OPERATIONAL_CAMPAIGN%'")
                .executeUpdate());
    }

    @Test
    @DisplayName("ac1_9: submit/approve/pause/resume/end で対応する OPERATIONAL_CAMPAIGN_* イベントが audit_logs に記録される")
    void ac1_9_遷移5操作の監査イベントが記録される() {
        Long campaignId = insertCampaign(orgId, "監査対象", "DRAFT");
        em.flush();

        controller.submit(orgId, campaignId);
        assertAuditEventRecorded("OPERATIONAL_CAMPAIGN_SUBMITTED", orgId);

        setAuthentication(sysAdminId);
        adminController.approve(campaignId);
        assertAuditEventRecorded("OPERATIONAL_CAMPAIGN_APPROVED", orgId);

        setAuthentication(adminId);
        controller.pause(orgId, campaignId);
        assertAuditEventRecorded("OPERATIONAL_CAMPAIGN_PAUSED", orgId);

        controller.resume(orgId, campaignId);
        assertAuditEventRecorded("OPERATIONAL_CAMPAIGN_RESUMED", orgId);

        controller.end(orgId, campaignId);
        assertAuditEventRecorded("OPERATIONAL_CAMPAIGN_ENDED", orgId);
    }

    @Test
    @DisplayName("ac1_9_ac1_11: reject で OPERATIONAL_CAMPAIGN_REJECTED イベントが理由とともに audit_logs に記録される")
    void ac1_9_rejectの監査イベントが記録される() {
        Long campaignId = insertCampaign(orgId, "監査対象reject", "PENDING_REVIEW");
        em.flush();

        setAuthentication(sysAdminId);
        adminController.reject(campaignId, new RejectOperationalCampaignRequest("素材が規約違反"));

        assertAuditEventRecorded("OPERATIONAL_CAMPAIGN_REJECTED", orgId);
        // 正本 §6.5 / AC-1.11: 差戻し理由と campaignId が metadata に永続化されること
        assertRejectMetadataRecorded(orgId, campaignId, "素材が規約違反");
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** @Async 記録される監査ログを Awaitility + REQUIRES_NEW で最大 5 秒待って検証する（確立済みパターン）。 */
    private void assertAuditEventRecorded(String eventType, Long organizationId) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionTemplate newTx = new TransactionTemplate(txManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean recorded = newTx.execute(status ->
                    auditLogRepository.findAll().stream()
                            .anyMatch(log -> eventType.equals(log.getEventType())
                                    && organizationId.equals(log.getOrganizationId())));
            assertThat(recorded)
                    .as("audit_logs に " + eventType + " が organizationId=" + organizationId + " で記録されること")
                    .isTrue();
        });
    }

    /** reject 監査ログの metadata に campaignId と差戻し理由が JSON で残ることを検証する。 */
    private void assertRejectMetadataRecorded(Long organizationId, Long campaignId, String reason) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TransactionTemplate newTx = new TransactionTemplate(txManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            String metadata = newTx.execute(status ->
                    auditLogRepository.findAll().stream()
                            .filter(log -> "OPERATIONAL_CAMPAIGN_REJECTED".equals(log.getEventType())
                                    && organizationId.equals(log.getOrganizationId()))
                            .map(log -> log.getMetadata())
                            .findFirst()
                            .orElse(null));
            assertThat(metadata)
                    .as("reject の監査 metadata に理由と campaignId が記録されること（正本 §6.5）")
                    .isNotNull()
                    .contains(reason)
                    .contains(String.valueOf(campaignId));
        });
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        // 冪等化: roles はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは roles の UNIQUE 制約違反になる。CI shard 再編成で
        // 同一 JVM 内の同居テストが変わり得るため、盲目的 INSERT は禁止）。
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

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, '監査', 'テスト', '監査 テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleId, Long orgId) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, NULL, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }

    private Long insertAdvertiserAccount(Long orgId, String companyName) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :oid, 'ACTIVE', :cn, 'ads@example.com', "
                                + "'STRIPE', 100000, NOW(), NOW())")
                .setParameter("oid", orgId)
                .setParameter("cn", companyName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM advertiser_accounts").getSingleResult()).longValue();
    }

    private Long insertRateCard() {
        em.createNativeQuery(
                        "INSERT INTO ad_rate_cards (target_prefecture, target_template, pricing_model, "
                                + "unit_price, min_daily_budget, effective_from, effective_until, "
                                + "created_by, created_at, updated_at) "
                                + "VALUES (NULL, NULL, 'CPM', 500.0000, 1000.00, "
                                + "DATE_SUB(CURDATE(), INTERVAL 30 DAY), NULL, :uid, NOW(), NOW())")
                .setParameter("uid", sysAdminId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_rate_cards").getSingleResult()).longValue();
    }

    private Long insertCampaign(Long orgId, String name, String status) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_account_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, rate_card_id, unit_price_snapshot, "
                                + "created_at, updated_at) "
                                + "VALUES (:aid, :name, :status, 'CPM', 1000.00, "
                                + "DATE_ADD(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                                + ":cardId, 500.0000, NOW(), NOW())")
                .setParameter("aid", accountId)
                .setParameter("name", name)
                .setParameter("status", status)
                .setParameter("cardId", rateCardId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_campaigns").getSingleResult()).longValue();
    }
}
