package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練B補完（AC-22）。
 *
 * <p>軍議第8版確定稿 §3.3「送信 API」の処理契約「1トランザクション内で『本体の INSERT（QUEUED）→
 * targets の INSERT → fanout ジョブの INSERT』を行い…」（AC-22）を対象とする。
 *
 * <p>認可・宛先解決（甲隊担当の {@code ConfirmableTargetAuthorizationValidator} /
 * {@code ConfirmableTargetSelectionValidator}）は骨格段階でまだ {@code UnsupportedOperationException}
 * を投げるため、公開 API（{@code sendAsync}）経由では「トランザクション原子性」だけを狙い撃ちして
 * 検証できない。そこで、本試練は {@link ConfirmableNotificationService#createQueuedNotificationWithTargetsAndJob}
 * （パッケージプライベート・認可/宛先解決より後段の「1トランザクション単位」そのもの）を直接呼び出す。
 *
 * <p>失敗の注入は<b>DB 側の手段</b>で行う（モックで Service を差し替えない）: {@code target_id} 列は
 * {@code BIGINT UNSIGNED NOT NULL} のため、負数を渡すと MySQL の範囲外エラーで targets の INSERT が
 * 失敗する（{@code V222.20260924054214__create_confirmable_notification_targets.sql}）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("送信の原子性 試練（AC-22）")
class ConfirmableNotificationCreateAtomicityIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private ConfirmableNotificationService notificationService;
    @Autowired
    private ConfirmableNotificationRepository notificationRepository;
    @Autowired
    private ConfirmableNotificationTargetRepository targetRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate txTemplate;
    private Long orgId;
    private Long adminUserId;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        TransactionTemplate setupTx = new TransactionTemplate(transactionManager);
        setupTx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        orgId = setupTx.execute(status -> insertOrganization());
        adminUserId = setupTx.execute(status -> insertUser());
    }

    @Test
    @DisplayName("AC-22: targetsのINSERTが失敗すると、本体もfanoutジョブも1件も残らない（同一トランザクション）")
    void ac22_targetsInsert失敗で本体もジョブも残らない() {
        String title = "AC-22試練-" + SEQ.incrementAndGet();
        // target_id に負数（BIGINT UNSIGNED の範囲外）を与え、DB側の制約違反でtargetsのINSERTを失敗させる。
        List<ConfirmableTargetSpec> invalidTargets =
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, -1L));

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status ->
                notificationService.createQueuedNotificationWithTargetsAndJob(
                        ScopeType.ORGANIZATION, orgId, title, null,
                        ConfirmableNotificationPriority.NORMAL, null, null, null,
                        UnconfirmedVisibility.CREATOR_AND_ADMIN, adminUserId, invalidTargets)))
                .isInstanceOfAny(DataIntegrityViolationException.class, RuntimeException.class);

        // AC-22: 本体（confirmable_notifications）は1件も残らない。
        long notificationCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM confirmable_notifications WHERE scope_id = :orgId AND title = :title")
                .setParameter("orgId", orgId)
                .setParameter("title", title)
                .getSingleResult()).longValue();
        assertThat(notificationCount).as("AC-22: targetsのINSERT失敗時、本体の行も残らない").isZero();

        // AC-22: targets も1件も残らない。
        long targetCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM confirmable_notification_targets t "
                                + "JOIN confirmable_notifications n ON n.id = t.confirmable_notification_id "
                                + "WHERE n.scope_id = :orgId AND n.title = :title")
                .setParameter("orgId", orgId)
                .setParameter("title", title)
                .getSingleResult()).longValue();
        assertThat(targetCount).as("AC-22: 失敗したtargetsは残らない").isZero();

        // AC-22: fanoutジョブも1件も残らない（本体が無い以上、scope_refで引ける行があってはならない）。
        long jobCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notification_fanout_jobs WHERE scope_type = 'CONFIRMABLE_TARGETS'")
                .getSingleResult()).longValue();
        assertThat(jobCount).as("AC-22: 本体が無い以上、fanoutジョブも孤立して残ってはならない").isZero();
    }

    @Test
    @DisplayName("正常系: 有効なtargetsなら本体・targets・fanoutジョブが同一トランザクションで確定する")
    void 正常系_本体targetsジョブが確定する() {
        String title = "AC-22正常系-" + SEQ.incrementAndGet();
        List<ConfirmableTargetSpec> validTargets =
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, orgId));

        Long notificationId = txTemplate.execute(status ->
                notificationService.createQueuedNotificationWithTargetsAndJob(
                        ScopeType.ORGANIZATION, orgId, title, null,
                        ConfirmableNotificationPriority.NORMAL, null, null, null,
                        UnconfirmedVisibility.CREATOR_AND_ADMIN, adminUserId, validTargets).getId());

        assertThat(notificationRepository.findById(notificationId)).isPresent();
        assertThat(targetRepository.findByConfirmableNotificationId(notificationId)).hasSize(1);
        long jobCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notification_fanout_jobs "
                                + "WHERE scope_type = 'CONFIRMABLE_TARGETS' AND scope_ref = :ref")
                .setParameter("ref", String.valueOf(notificationId))
                .getSingleResult()).longValue();
        assertThat(jobCount).as("正常系: fanoutジョブが同一トランザクションで確定する").isEqualTo(1L);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private Long insertUser() {
        int n = SEQ.incrementAndGet();
        String email = "cnat-" + n + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, 'CNAT', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "CNAT 利用者" + n)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
        em.flush();
        return id;
    }

    private Long insertOrganization() {
        String name = "CNAT組織-" + SEQ.incrementAndGet();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cnat-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        em.flush();
        return id;
    }
}
