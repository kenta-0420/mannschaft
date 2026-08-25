package com.mannschaft.app.contact;

import com.mannschaft.app.contact.entity.ContactInviteTokenEntity;
import com.mannschaft.app.contact.repository.ContactInviteTokenRepository;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
import java.util.UUID;
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
 * 連絡先招待リンク使用 — 通知の永続化失敗が業務処理（招待トークンの利用回数インクリメント・
 * 連絡先の双方向追加）を巻き戻さないことの実 DB 検証（Issue #2834 / CMP-056 横展開）。
 *
 * <h2>背景（根治した欠陥）</h2>
 * <p>是正前の {@code ContactInviteTokenService#sendInviteUsedNotification} は
 * {@code acceptInvite}（{@code @Transactional}）の内側で
 * {@code NotificationService#createNotification} を同一トランザクションで直接呼んでいた。
 * 通知永続化が DB 例外で失敗すると rollback-only が立ち、{@code try/catch} で握りつぶしても
 * 招待トークンの利用回数インクリメント・連絡先追加ごと巻き戻っていた（Issue #2834 の典型形）。
 * 本クラスは是正後、業務トランザクションが {@code ContactInviteUsedNotificationEvent} を publish
 * するだけに留め、通知配送は {@code AFTER_COMMIT} の {@code ContactInviteUsedNotificationListener}
 * に分離されたことで、通知の永続化失敗が業務処理に波及しないことを実測する。</p>
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
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F0x 連絡先招待リンク使用 通知永続化失敗が業務処理を巻き戻さないこと")
class ContactInviteUsedNotificationPartialFailureIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactInviteTokenRepository tokenRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * {@link NotificationService#createNotification} を spy し、発行者（通知宛先）宛の呼び出しだけを
     * NOT NULL 制約違反（実際の永続化例外）に差し替える。
     */
    @MockitoSpyBean
    private NotificationService notificationService;

    @PersistenceContext
    private EntityManager em;

    private Long issuerId;
    private Long accepterId;
    private String token;
    private Long tokenId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> {
            // 自動承認（contact_approval_required = false）にして accept 一発で
            // sendInviteUsedNotification が発火する経路を使う。
            issuerId = insertUser(em, "civnpf-issuer-" + nonce + "@example.com", false);
            accepterId = insertUser(em, "civnpf-accepter-" + nonce + "@example.com", true);
            em.flush();

            token = UUID.randomUUID().toString();
            ContactInviteTokenEntity entity = tokenRepository.save(ContactInviteTokenEntity.builder()
                    .userId(issuerId)
                    .token(token)
                    .label("F0x 通知分離検証")
                    .build());
            tokenId = entity.getId();
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("通知の永続化が失敗しても招待トークンの利用回数はコミットされたまま残る")
    void 通知永続化失敗でも業務処理は巻き戻らない() throws Exception {
        // 発行者（issuerId、通知宛先）宛の createNotification だけ NOT NULL 違反で失敗させる。
        willAnswer(invocation -> {
            Long targetUserId = invocation.getArgument(0);
            if (targetUserId.equals(issuerId)) {
                // NotificationDeliveryRunner の REQUIRES_NEW トランザクション実行中の同一スレッド上で
                // notification_type=null の INSERT を行い、MySQL の NOT NULL 制約違反という
                // 実際の永続化例外を起こす（saveAndFlush で即座にflushして例外を確定させる）。
                return notificationRepository.saveAndFlush(NotificationEntity.builder()
                        .userId(targetUserId)
                        .notificationType(null)
                        .title("模擬通知（NOT NULL違反検証用）")
                        .sourceType("CONTACT_INVITE_TOKEN")
                        .scopeType(NotificationScopeType.PERSONAL)
                        .build());
            }
            return invocation.callRealMethod();
        }).given(notificationService).createNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        setAuthentication(accepterId);
        mockMvc.perform(post("/api/v1/contact-invite/{token}/accept", token))
                .andExpect(status().isOk());

        // 本丸: 通知の永続化が失敗しても、業務処理（usedCount インクリメント）はコミットされて残る。
        // acceptInvite 自体は @Transactional で mockMvc の呼び出し中に同期コミットされるため、
        // ここは awaiting 不要。
        ContactInviteTokenEntity persisted = transactionTemplate.execute(
                tx -> tokenRepository.findById(tokenId).orElseThrow());
        assertThat(persisted.getUsedCount())
                .as("通知の永続化失敗が業務トランザクション（usedCount インクリメント）を巻き戻していない")
                .isEqualTo(1);

        // 連絡先の双方向追加も業務処理の一部としてコミットされている。
        long contactCount = transactionTemplate.execute(tx -> countContacts(em, issuerId, accepterId));
        assertThat(contactCount)
                .as("連絡先の双方向追加も通知失敗に巻き込まれずコミットされている")
                .isEqualTo(1);

        // 通知配送は AFTER_COMMIT + @Async で非同期に発火するため、配送が実際に試みられる
        // （= spy が呼ばれる）まで有界のタイムアウトで待つ。Mockito は呼び出し自体を、その中で
        // 例外が投げられたかどうかに関わらず記録するため、verify が通った時点で
        // 配送（NOT NULL 制約違反による失敗）は同期的に完了している。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService).createNotification(
                        eq(issuerId), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()));

        // 通知自体は失敗しているため作られていないことも確認する（配送失敗の実測）。
        long notificationCount = transactionTemplate.execute(tx -> countNotifications(em, issuerId));
        assertThat(notificationCount)
                .as("NOT NULL 制約違反で失敗した通知は作られない")
                .isZero();
    }

    @Test
    @DisplayName("通知が正常に永続化される場合は業務処理・通知の両方がコミットされる")
    void 通知正常時は業務処理と通知の両方がコミットされる() throws Exception {
        setAuthentication(accepterId);
        mockMvc.perform(post("/api/v1/contact-invite/{token}/accept", token))
                .andExpect(status().isOk());

        ContactInviteTokenEntity persisted = transactionTemplate.execute(
                tx -> tokenRepository.findById(tokenId).orElseThrow());
        assertThat(persisted.getUsedCount()).isEqualTo(1);

        // 通知配送は AFTER_COMMIT + @Async で非同期に発火するため、有界のタイムアウトで待つ
        // （マスター指摘: 固定 sleep ではなく Awaitility の untilAsserted を使う）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            long notificationCount = transactionTemplate.execute(tx -> countNotifications(em, issuerId));
            assertThat(notificationCount).isEqualTo(1);
        });
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private static Long insertUser(EntityManager em, String email, boolean approvalRequired) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'F0x', 'テスト', 'F0x テスト', 'ACTIVE', "
                                + "1, 1, :approvalRequired, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("approvalRequired", approvalRequired ? 1 : 0)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    /** 連絡先フォルダ経由の追加（{@code ContactService#addToDefaultFolder}）を実体で検証する。 */
    private static long countContacts(EntityManager em, Long ownerId, Long contactUserId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM chat_contact_folder_items i "
                                + "JOIN chat_contact_folders f ON f.id = i.folder_id "
                                + "WHERE f.user_id = :ownerId AND i.item_type = 'CONTACT' AND i.item_id = :contactUserId")
                .setParameter("ownerId", ownerId)
                .setParameter("contactUserId", contactUserId)
                .getSingleResult()).longValue();
    }

    private static long countNotifications(EntityManager em, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notifications WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }
}
