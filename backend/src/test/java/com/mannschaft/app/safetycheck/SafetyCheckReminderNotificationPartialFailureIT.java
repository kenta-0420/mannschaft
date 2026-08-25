package com.mannschaft.app.safetycheck;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;
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
 * <h2>実際の永続化例外を起こす方法（マスター指摘で是正: 2026-08-25）</h2>
 * <p>初版は {@link NotificationService#createNotification} を spy し、対象受信者宛の呼び出しだけ
 * {@code org.springframework.test.util.AopTestUtils#getUltimateTargetObject} で「実体」を取得して
 * 呼び直す方式だったが、{@code @MockitoSpyBean} は Bean 定義そのものを Mockito スパイに差し替える
 * ため、{@code getUltimateTargetObject} は同じスパイをそのまま返してしまい、
 * <b>スパイが自分自身を再帰的に呼び出す無限ループ</b>（CI 実測で559回）を引き起こしていた。
 * 本版は {@link NotificationRepository#saveAndFlush} を直接呼ぶことで、
 * {@code NotificationDeliveryRunner}（{@code REQUIRES_NEW}）が実行中の非同期ワーカースレッド上で
 * 同じトランザクションに参加させたまま、{@code notification_type} に {@code null} を渡して
 * MySQL の NOT NULL 制約違反という<b>本物の永続化例外</b>を発生させる。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むとコミットが
 * 起きず {@code AFTER_COMMIT} が発火しないまま「巻き戻らないことを確認できた」ことになってしまう
 * （偽の緑）。よって本クラスはトランザクションを張らず、フィクスチャ投入は
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 *
 * <h2>非同期配送の待ち方（マスター指摘で是正: 2026-08-25）</h2>
 * <p>通知配送は {@code AFTER_COMMIT} + {@code @Async("event-pool")} で非同期に発火するため、
 * mockMvc 呼び出し直後に DB を検証すると配送未完了のまま偽陰性（expected:1 but was:0）になる。
 * {@code org.awaitility.Awaitility#await()} で有界のタイムアウト付きに待つ
 * （固定 sleep は使わない。参考: {@code ContactRequestNotificationTransactionIT}）。</p>
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
    private NotificationRepository notificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * {@link NotificationService#createNotification} を spy し、対象受信者宛の呼び出しだけを
     * NOT NULL 制約違反（実際の永続化例外）に差し替える。
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
                // NotificationDeliveryRunner の REQUIRES_NEW トランザクション実行中の同一スレッド上で
                // notification_type=null の INSERT を行い、MySQL の NOT NULL 制約違反という
                // 実際の永続化例外を起こす（saveAndFlush で即座にflushして例外を確定させる）。
                return notificationRepository.saveAndFlush(NotificationEntity.builder()
                        .userId(targetUserId)
                        .notificationType(null)
                        .title("模擬通知（NOT NULL違反検証用）")
                        .sourceType("SAFETY_CHECK")
                        .scopeType(NotificationScopeType.TEAM)
                        .build());
            }
            return invocation.callRealMethod();
        }).given(notificationService).createNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        setAuthentication(adminId);
        mockMvc.perform(post("/api/v1/safety-checks/{id}/remind", safetyCheckId))
                .andExpect(status().isNoContent());

        // 本丸: 通知の永続化が失敗しても、業務処理（lastReminderAt 更新）はコミットされて残る。
        // sendReminder 自体は @Transactional で mockMvc の呼び出し中に同期コミットされるため、
        // ここは awaiting 不要。
        SafetyCheckEntity persisted = transactionTemplate.execute(
                tx -> safetyCheckRepository.findById(safetyCheckId).orElseThrow());
        assertThat(persisted.getLastReminderAt())
                .as("通知の永続化失敗が業務トランザクション（lastReminderAt 更新）を巻き戻していない")
                .isNotNull();

        // 通知配送は AFTER_COMMIT + @Async で非同期に発火するため、配送が実際に試みられる
        // （= spy が呼ばれる）まで有界のタイムアウトで待つ。Mockito は呼び出しを、その中で
        // 例外が投げられたかどうかに関わらず記録するため、verify が通った時点で
        // 配送（NOT NULL 制約違反による失敗）は同期的に完了している。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService).createNotification(
                        eq(adminId), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()));

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

        // 通知配送は AFTER_COMMIT + @Async で非同期に発火するため、有界のタイムアウトで待つ
        // （マスター指摘: 固定 sleep ではなく Awaitility の untilAsserted を使う）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            long notificationCount = transactionTemplate.execute(tx -> countNotifications(em, adminId));
            assertThat(notificationCount).isEqualTo(1);
        });
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
