package com.mannschaft.app.safetycheck;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
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
 * 安否確認リマインド送信 — 通知の永続化失敗が業務処理（{@code lastReminderAt} 更新）を
 * 巻き戻さないことの実 DB 検証（Issue #2834 / CMP-056 横展開）。
 *
 * <h2>背景（根治した欠陥）</h2>
 * <p>是正前の {@code SafetyCheckService#sendReminder} は {@code @Transactional} な業務メソッドの
 * 内側で {@code NotificationHelper#notify}（内部で {@code NotificationService#createNotification}
 * を同一トランザクションで呼ぶ）を直接呼んでいた。通知永続化が DB 例外で失敗すると
 * rollback-only が立ち、{@code try/catch} で握りつぶしても {@code lastReminderAt} の更新ごと
 * 巻き戻っていた（Issue #2834 の典型形）。本クラスは是正後、業務トランザクションが
 * {@code SafetyCheckReminderNotificationEvent} を publish するだけに留め、通知配送は
 * {@code AFTER_COMMIT} の {@code SafetyCheckReminderNotificationListener} に分離されたことで、
 * 通知の永続化失敗が業務処理に波及しないことを実測する。</p>
 *
 * <h2>実際の永続化例外を起こす方法</h2>
 * <p>{@link NotificationService#createNotification} をモックで直接例外送出させるのではなく、
 * {@code notifications.notification_type}（{@code @Column(nullable = false)}）に意図的に
 * {@code null} を渡して<b>実際の {@code NotificationService} 実体</b>（{@link AopTestUtils}で
 * プロキシを外した本体）を呼び出し、MySQL の NOT NULL 制約違反という<b>本物の永続化例外</b>を
 * {@code NotificationDeliveryRunner}（{@code REQUIRES_NEW}）のトランザクション内で発生させる。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むとコミットが
 * 起きず {@code AFTER_COMMIT} が発火しないまま「巻き戻らないことを確認できた」ことになってしまう
 * （偽の緑）。よって本クラスはトランザクションを張らず、フィクスチャ投入は
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.4 安否確認リマインド 通知永続化失敗が業務処理を巻き戻さないこと")
class SafetyCheckReminderNotificationPartialFailureIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SafetyCheckRepository safetyCheckRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * {@link NotificationService#createNotification} を spy し、対象受信者宛の呼び出しだけを
     * NOT NULL 制約違反（実際の永続化例外）に差し替える。プロキシ経由の再帰呼び出しを避けるため、
     * {@link AopTestUtils#getUltimateTargetObject} で実体を取得してから呼び直す。
     */
    @MockitoSpyBean
    private NotificationService notificationService;

    @PersistenceContext
    private EntityManager em;

    private Long adminId;
    private Long safetyCheckId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> {
            Long teamId = insertTeam(em, "F034 通知分離検証", "scrmd-team-" + nonce);
            adminId = insertUser(em, "scrmd-admin-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            em.flush();

            safetyCheckId = safetyCheckRepository.save(SafetyCheckEntity.builder()
                    .scopeType(SafetyCheckScopeType.TEAM)
                    .scopeId(teamId)
                    .title("F034 通知分離検証地震")
                    .message("安否を報告してください")
                    .isDrill(true)
                    .status(SafetyCheckStatus.ACTIVE)
                    .totalTargetCount(1)
                    .build()).getId();
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("通知の永続化が失敗しても lastReminderAt の更新はコミットされたまま残る")
    void 通知永続化失敗でも業務処理は巻き戻らない() throws Exception {
        // 対象ユーザー（リマインド送信者=通知宛先）宛の createNotification だけ NOT NULL 違反で失敗させる。
        willAnswer(invocation -> {
            Long targetUserId = invocation.getArgument(0);
            if (targetUserId.equals(adminId)) {
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
        mockMvc.perform(post("/api/v1/safety-checks/{id}/remind", safetyCheckId))
                .andExpect(status().isNoContent());

        // 本丸: 通知の永続化が失敗しても、業務処理（lastReminderAt 更新）はコミットされて残る。
        SafetyCheckEntity persisted = transactionTemplate.execute(
                tx -> safetyCheckRepository.findById(safetyCheckId).orElseThrow());
        assertThat(persisted.getLastReminderAt())
                .as("通知の永続化失敗が業務トランザクション（lastReminderAt 更新）を巻き戻していない")
                .isNotNull();

        // 通知自体は失敗しているため作られていないことも確認する（配送失敗の実測）。
        long notificationCount = transactionTemplate.execute(tx -> countNotifications(em, adminId));
        assertThat(notificationCount)
                .as("NOT NULL 制約違反で失敗した通知は作られない")
                .isZero();
    }

    @Test
    @DisplayName("通知が正常に永続化される場合は業務処理・通知の両方がコミットされる")
    void 通知正常時は業務処理と通知の両方がコミットされる() throws Exception {
        setAuthentication(adminId);
        mockMvc.perform(post("/api/v1/safety-checks/{id}/remind", safetyCheckId))
                .andExpect(status().isNoContent());

        SafetyCheckEntity persisted = transactionTemplate.execute(
                tx -> safetyCheckRepository.findById(safetyCheckId).orElseThrow());
        assertThat(persisted.getLastReminderAt()).isNotNull();

        long notificationCount = transactionTemplate.execute(tx -> countNotifications(em, adminId));
        assertThat(notificationCount).isEqualTo(1);
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
                                + "VALUES (:email, 'F034', 'テスト', 'F034 テスト', 'ACTIVE', "
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

    private static Long insertTeam(EntityManager em, String name, String slug) {
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

    private static long countNotifications(EntityManager em, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notifications WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }
}
