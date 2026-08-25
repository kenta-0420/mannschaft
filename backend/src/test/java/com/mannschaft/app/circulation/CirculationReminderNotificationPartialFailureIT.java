package com.mannschaft.app.circulation;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回覧文書手動リマインド — 受信者1名分の通知永続化失敗が、業務処理（リマインド送信そのもの）にも
 * 他の受信者への通知にも波及しないことの実 DB 検証（Issue #2834 / CMP-056 横展開）。
 *
 * <h2>背景（根治した欠陥）</h2>
 * <p>是正前の {@code CirculationService#remindDocument} は {@code @Transactional} な業務メソッドの
 * 内側で受信者ごとに {@code NotificationService#createNotification} を直接呼んでいた。1受信者分の
 * 通知永続化が DB 例外で失敗すると rollback-only が立ち、{@code try/catch} で握りつぶして続行しても
 * 他の受信者ぶんの通知がコミット時にまとめて消えていた（Issue #2834 の典型形。受信者ループの
 * 「1件失敗が全体を巻き添えにする」構図は #2655/#2660/#2664 と同型）。本クラスは是正後、
 * 業務トランザクションが {@code CirculationReminderNotificationEvent} を publish するだけに留め、
 * 受信者の解決・配送は {@code AFTER_COMMIT} の {@code CirculationReminderNotificationListener} が
 * 受信者ごと {@code REQUIRES_NEW} で1件ずつ独立実行することで、1受信者の失敗が他へ波及しないことを
 * 実測する。</p>
 *
 * <h2>実際の永続化例外を起こす方法</h2>
 * <p>{@link NotificationService#createNotification} をモックで直接例外送出させるのではなく、
 * {@code notifications.notification_type}（{@code @Column(nullable = false)}）に意図的に
 * {@code null} を渡して実際のサービス実体（{@link AopTestUtils} でプロキシを外した本体）を
 * 呼び出し、MySQL の NOT NULL 制約違反という本物の永続化例外を発生させる。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むとコミットが
 * 起きず {@code AFTER_COMMIT} が発火しないまま「巻き戻らないことを確認できた」ことになってしまう
 * （偽の緑）。よって本クラスはトランザクションを張らず、フィクスチャ投入は
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F13 回覧文書手動リマインド 通知永続化の1受信者失敗が業務処理・他受信者へ波及しないこと")
class CirculationReminderNotificationPartialFailureIT extends AbstractMySqlIntegrationTest {

    private static final String REMIND = "/api/v1/circulation-documents/{documentId}/remind";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * {@link NotificationService#createNotification} を spy し、受信者Cだけ NOT NULL 制約違反
     * （実際の永続化例外）に差し替える。プロキシ経由の再帰呼び出しを避けるため、
     * {@link AopTestUtils#getUltimateTargetObject} で実体を取得してから呼び直す。
     */
    @MockitoSpyBean
    private NotificationService notificationService;

    @PersistenceContext
    private EntityManager em;

    private Long adminId;
    private Long recipientOkId;
    private Long recipientBrokenId;
    private Long recipientOk2Id;
    private Long documentId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> {
            Long teamId = insertTeam(em, "circ-notify-pf-" + nonce);
            adminId = insertUser(em, "circpf-admin-" + nonce + "@example.com");
            recipientOkId = insertUser(em, "circpf-b-" + nonce + "@example.com");
            recipientBrokenId = insertUser(em, "circpf-c-" + nonce + "@example.com");
            recipientOk2Id = insertUser(em, "circpf-d-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, recipientOkId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, recipientBrokenId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, recipientOk2Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            em.flush();

            documentId = insertDocument(em, "TEAM", teamId, adminId, "ACTIVE");
            insertRecipient(em, documentId, recipientOkId, "PENDING");
            insertRecipient(em, documentId, recipientBrokenId, "PENDING");
            insertRecipient(em, documentId, recipientOk2Id, "PENDING");
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("受信者3人のうち1人分だけ通知永続化が失敗しても、業務処理（remindDocument自体）と"
            + "残り2人の通知はコミットされて残る")
    void 受信者1人の通知永続化失敗が業務処理と他受信者を巻き添えにしない() throws Exception {
        // recipientBrokenId 宛の createNotification だけ NOT NULL 違反で失敗させる。他は実処理を通す。
        willAnswer(invocation -> {
            Long targetUserId = invocation.getArgument(0);
            if (targetUserId.equals(recipientBrokenId)) {
                NotificationService real = AopTestUtils.getUltimateTargetObject(notificationService);
                return real.createNotification(
                        invocation.getArgument(0),
                        null, // notificationType に null → NOT NULL 制約違反で実際の永続化例外
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5),
                        invocation.getArgument(6),
                        invocation.getArgument(7),
                        invocation.getArgument(8),
                        invocation.getArgument(9),
                        invocation.getArgument(10));
            }
            return invocation.callRealMethod();
        }).given(notificationService).createNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        setAuthentication(adminId);
        mockMvc.perform(post(REMIND, documentId))
                .andExpect(status().isOk());

        // 本丸1: 業務処理（手動リマインド送信そのもの）は、あとから発生する通知永続化失敗と無関係に
        // 既にコミット済みである（AFTER_COMMIT で通知配送が起きるため、業務処理は通知の成否を待たない）。
        // ここでは remindDocument が正常応答したこと自体がその証跡であり、追加で document の状態を確認する。
        String status = (String) transactionTemplate.execute(tx -> em.createNativeQuery(
                        "SELECT status FROM circulation_documents WHERE id = :id")
                .setParameter("id", documentId)
                .getSingleResult());
        assertThat(status).isEqualTo("ACTIVE");

        // 本丸2: 失敗した受信者以外の通知が実際にコミットされて残っていること（1受信者=1独立トランザクション）。
        long okCount = transactionTemplate.execute(tx -> countNotifications(em, recipientOkId));
        long ok2Count = transactionTemplate.execute(tx -> countNotifications(em, recipientOk2Id));
        long brokenCount = transactionTemplate.execute(tx -> countNotifications(em, recipientBrokenId));

        assertThat(okCount)
                .as("失敗させていない受信者Bの通知は、他受信者の失敗に巻き添えられずコミットされて残る")
                .isEqualTo(1);
        assertThat(ok2Count)
                .as("失敗させていない受信者Dの通知も同様にコミットされて残る")
                .isEqualTo(1);
        assertThat(brokenCount)
                .as("NOT NULL 制約違反で失敗した受信者Cの通知は作られない（他へは巻き添えない）")
                .isZero();
    }

    @Test
    @DisplayName("全員成功時は3人全員の通知が独立にコミットされる")
    void 全員成功時は全受信者の通知が独立にコミットされる() throws Exception {
        setAuthentication(adminId);
        mockMvc.perform(post(REMIND, documentId))
                .andExpect(status().isOk());

        long okCount = transactionTemplate.execute(tx -> countNotifications(em, recipientOkId));
        long brokenCount = transactionTemplate.execute(tx -> countNotifications(em, recipientBrokenId));
        long ok2Count = transactionTemplate.execute(tx -> countNotifications(em, recipientOk2Id));
        assertThat(okCount).isEqualTo(1);
        assertThat(brokenCount).isEqualTo(1);
        assertThat(ok2Count).isEqualTo(1);
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private static Long insertUser(EntityManager em, String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CIRCPF', 'テスト', 'CIRCPF テスト', 'ACTIVE', "
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

    private static Long insertTeam(EntityManager em, String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('circpf-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private static Long insertDocument(EntityManager em, String scopeType, Long scopeId, Long createdBy, String status) {
        String title = "circpf 通知分離検証文書 " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO circulation_documents "
                                + "(scope_type, scope_id, created_by, title, body, "
                                + "circulation_mode, sequential_count, status, priority, "
                                + "reminder_enabled, reminder_interval_hours, stamp_display_style, "
                                + "total_recipient_count, stamped_count, attachment_count, comment_count, "
                                + "export_status, created_at, updated_at) "
                                + "VALUES (:scopeType, :scopeId, :createdBy, :title, '本文', "
                                + "'SIMULTANEOUS', 0, :status, 'NORMAL', "
                                + "0, 24, 'STANDARD', "
                                + "3, 0, 0, 0, "
                                + "'NOT_GENERATED', NOW(), NOW())")
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM circulation_documents WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    private static void insertRecipient(EntityManager em, Long documentId, Long userId, String status) {
        em.createNativeQuery(
                        "INSERT INTO circulation_recipients "
                                + "(document_id, user_id, sort_order, status, tilt_angle, is_flipped, "
                                + "created_at, updated_at) "
                                + "VALUES (:docId, :userId, 0, :status, 0, 0, NOW(), NOW())")
                .setParameter("docId", documentId)
                .setParameter("userId", userId)
                .setParameter("status", status)
                .executeUpdate();
    }

    private static long countNotifications(EntityManager em, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notifications WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }
}
