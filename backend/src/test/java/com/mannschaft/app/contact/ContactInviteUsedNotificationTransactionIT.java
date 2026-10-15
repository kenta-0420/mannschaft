package com.mannschaft.app.contact;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.contact.dto.SendContactRequestResponse;
import com.mannschaft.app.contact.entity.ContactInviteTokenEntity;
import com.mannschaft.app.contact.repository.ContactInviteTokenRepository;
import com.mannschaft.app.contact.service.ContactInviteTokenService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2834 / CMP-056 第1群ロットA — 通知トランザクション分離の実 DB 検証
 * （{@code ContactInviteTokenService#acceptInvite}）。
 *
 * <h2>この IT で実証すること</h2>
 * <ul>
 *   <li>AC-1: 通知配送（{@link NotificationDeliveryRunner#sendOne}）が例外を投げても、
 *       {@code acceptInvite} の業務トランザクション（招待トークンの利用回数インクリメント）は
 *       コミットされる。是正前は {@code createNotification} が同一トランザクションに参加していたため、
 *       DB 層例外で rollback-only が残り<b>招待受諾ごと巻き戻っていた</b>。</li>
 *   <li>AC-2: 業務トランザクション自体がロールバックした場合、通知は作られない
 *       （{@code AFTER_COMMIT} は業務トランザクションがロールバックすると発火しないため）。</li>
 *   <li>AC-3: 通知配送はコミット後に非同期発火するため、配送時点で source 行
 *       （{@code contact_invite_tokens}）が実際に読み取れ、visibility ガードで deny されずに
 *       通知が作られる。</li>
 * </ul>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むとコミットが
 * 起きず通知が1件も作られないまま「作られないことを確認できた」という<b>偽の緑</b>になる。よって
 * トランザクションを張らず、フィクスチャ投入・検証読み取りは {@link TransactionTemplate} で
 * 明示的にコミットする（{@code ContactRequestNotificationTransactionIT} と同型）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2834/CMP-056 通知トランザクション分離の実DB検証（ContactInviteTokenService）")
class ContactInviteUsedNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContactInviteTokenService contactInviteTokenService;

    @Autowired
    private ContactInviteTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * AC-1 / AC-2 検証用 spy。{@code NotificationService} は実 Bean のまま（モックしない）で、
     * {@link NotificationDeliveryRunner#sendOne} だけを部分的に差し替える。
     */
    @MockitoSpyBean
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Test
    @DisplayName("AC-1: 通知配送のDB例外が起きても、招待受諾（利用回数インクリメント）はコミットされる")
    void 通知配送が失敗しても招待受諾はコミットされる() {
        String nonce = String.valueOf(System.nanoTime());
        Long issuerId = insertUser("ci-ac1-issuer-" + nonce + "@example.com", false);
        Long actorId = insertUser("ci-ac1-actor-" + nonce + "@example.com", true);
        String token = insertToken(issuerId);

        willThrow(new RuntimeException("模擬通知配送失敗（AC-1検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        SendContactRequestResponse response = contactInviteTokenService.acceptInvite(actorId, token);
        assertThat(response.getStatus()).isEqualTo("ACCEPTED");

        // 本丸: 業務行（利用回数）はコミットされている。
        ContactInviteTokenEntity saved = transactionTemplate.execute(
                tx -> tokenRepository.findByToken(token).orElseThrow());
        assertThat(saved.getUsedCount())
                .as("通知配送が失敗しても招待受諾の永続化は巻き戻らない")
                .isEqualTo(1);

        // AFTER_COMMIT + @Async のリスナーが Runner を呼び、例外で配送は失敗する（非同期のため待つ）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                org.mockito.Mockito.verify(notificationDeliveryRunner).sendOne(any()));
    }

    @Test
    @DisplayName("AC-2: 業務トランザクションがロールバックした場合、通知は作られない")
    void 業務トランザクションのロールバック時は通知が作られない() {
        String nonce = String.valueOf(System.nanoTime());
        Long issuerId = insertUser("ci-ac2-issuer-" + nonce + "@example.com", false);
        Long actorId = insertUser("ci-ac2-actor-" + nonce + "@example.com", true);
        String token = insertToken(issuerId);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            contactInviteTokenService.acceptInvite(actorId, token);
            throw new RuntimeException("強制ロールバック（AC-2検証用）");
        })).isInstanceOf(RuntimeException.class);

        // 業務行がロールバックされていること（前提の確認）。
        ContactInviteTokenEntity saved = transactionTemplate.execute(
                tx -> tokenRepository.findByToken(token).orElseThrow());
        assertThat(saved.getUsedCount()).isZero();

        // 本丸: AFTER_COMMIT は発火しないため通知配送は一度も呼ばれない。
        org.mockito.Mockito.verifyNoInteractions(notificationDeliveryRunner);
    }

    @Test
    @DisplayName("AC-3: コミット直後の招待トークン行を通知配送タイミングで参照でき、deny されず通知が作られる")
    void コミット直後のトークン行を参照して通知が作られる() {
        String nonce = String.valueOf(System.nanoTime());
        Long issuerId = insertUser("ci-ac3-issuer-" + nonce + "@example.com", false);
        Long actorId = insertUser("ci-ac3-actor-" + nonce + "@example.com", true);
        String token = insertToken(issuerId);

        contactInviteTokenService.acceptInvite(actorId, token);

        Long tokenId = transactionTemplate.execute(
                tx -> tokenRepository.findByToken(token).orElseThrow().getId());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> notifications = transactionTemplate.execute(
                    tx -> notificationRepository.findByUserIdOrderByCreatedAtDesc(
                            issuerId, PageRequest.of(0, 10)).getContent());
            assertThat(notifications)
                    .as("招待リンク使用通知が deny されずに作られていること")
                    .anyMatch(n -> "CONTACT_INVITE_USED".equals(n.getNotificationType())
                            && tokenId.equals(n.getSourceId()));
        });
    }

    /** 有効な招待トークンを1件作成し token 文字列を返す。 */
    private String insertToken(Long issuerId) {
        String token = UUID.randomUUID().toString();
        transactionTemplate.execute(tx -> tokenRepository.save(ContactInviteTokenEntity.builder()
                .userId(issuerId)
                .token(token)
                .label("CMP-056 検証用")
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build()));
        return token;
    }

    /**
     * ACTIVE な users 行を1件作成し id を返す。
     *
     * <p>{@code ContactRequestNotificationTransactionIT} と同じ注意点（実 DDL の NOT NULL 制約は
     * test プロファイルの {@code ddl-auto: create} では再現せず、実 DB で初めて落ちる）に従い、
     * {@code @Builder.Default} を持たない必須8フィールドを明示する。</p>
     *
     * @param approvalRequired {@code false} なら自動承認（招待受諾が即 ACCEPTED になる経路）
     */
    private Long insertUser(String email, boolean approvalRequired) {
        return transactionTemplate.execute(tx -> userRepository.save(UserEntity.builder()
                .email(email)
                .lastName("招待試験")
                .firstName("太郎")
                .displayName("招待試験ユーザー")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .contactApprovalRequired(approvalRequired)
                .build()).getId());
    }
}
