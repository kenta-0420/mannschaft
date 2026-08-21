package com.mannschaft.app.contact;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.contact.dto.SendContactRequestBody;
import com.mannschaft.app.contact.dto.SendContactRequestResponse;
import com.mannschaft.app.contact.entity.ContactRequestEntity;
import com.mannschaft.app.contact.repository.ContactRequestRepository;
import com.mannschaft.app.contact.service.ContactRequestService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2834 / CMP-056 型確立PR — 通知トランザクション分離の実 DB 検証（{@code ContactRequestService}）。
 *
 * <h2>この IT で実証すること</h2>
 * <ul>
 *   <li>AC-1: 通知配送（{@link NotificationDeliveryRunner#sendOne}）が DB 例外を投げても、
 *       {@code sendRequest} の業務トランザクション（{@code contact_requests} の INSERT）は
 *       コミットされる。</li>
 *   <li>AC-2: {@code sendRequest} の業務トランザクション自体がロールバックした場合、
 *       通知（{@code notifications} 行）は作られない
 *       （{@code AFTER_COMMIT} は業務トランザクションがロールバックすると発火しないため）。</li>
 *   <li>AC-3: 通知配送はコミット後に非同期発火するため、配送時点で
 *       {@code contact_requests} の新規行が実際に読み取れる（{@code ContactRequestRepository}
 *       で検証）。</li>
 * </ul>
 *
 * <h2>{@code ScheduleCommentNotificationPartialFailureIT} と同型の手法</h2>
 * <p>{@link NotificationDeliveryRunner} だけを {@code @MockitoSpyBean} で spy し、
 * {@code NotificationService} は実 Bean のまま残す。クラスに {@code @Transactional} を
 * 付けない（付けるとコミットが起きず {@code AFTER_COMMIT} が発火せず、「通知が作られない」ことを
 * 確認できたという偽の緑になるため）。フィクスチャ投入・後始末は {@link TransactionTemplate} で
 * 明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2834/CMP-056 通知トランザクション分離の実DB検証（ContactRequestService）")
class ContactRequestNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContactRequestService contactRequestService;

    @Autowired
    private ContactRequestRepository contactRequestRepository;

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
    @DisplayName("AC-1: 通知配送のDB例外が起きても、連絡先申請のINSERTはコミットされる")
    void 通知配送が失敗しても申請INSERTはコミットされる() {
        String nonce = String.valueOf(System.nanoTime());
        Long requesterId = insertUser("cr-ac1-a-" + nonce + "@example.com");
        Long targetId = insertUser("cr-ac1-b-" + nonce + "@example.com");

        // 通知配送（REQUIRES_NEW）で例外を発生させる。
        willThrow(new RuntimeException("模擬通知配送失敗（AC-1検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        SendContactRequestBody body = buildBody(targetId);

        SendContactRequestResponse response = contactRequestService.sendRequest(requesterId, body);
        assertThat(response.getRequestId()).isNotNull();

        // 本丸: sendRequest 自体は @Transactional で呼び出し元テストのトランザクション外で
        // 即コミットされる（Spring の @Transactional プロキシは呼び出しごとに commit する）。
        List<ContactRequestEntity> saved = transactionTemplate.execute(
                tx -> contactRequestRepository.findByTargetIdAndStatusOrderByCreatedAtDesc(targetId, "PENDING"));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRequesterId()).isEqualTo(requesterId);

        // AFTER_COMMIT + @Async のリスナーが Runner を呼び、例外を投げて配送は失敗するが、
        // 業務行（上記 INSERT）には影響しない（非同期のため到達を awaitility で待つ）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                org.mockito.Mockito.verify(notificationDeliveryRunner).sendOne(any()));
    }

    @Test
    @DisplayName("AC-2: 業務トランザクションがロールバックした場合、通知は作られない")
    void 業務トランザクションのロールバック時は通知が作られない() {
        String nonce = String.valueOf(System.nanoTime());
        Long requesterId = insertUser("cr-ac2-a-" + nonce + "@example.com");
        Long targetId = insertUser("cr-ac2-b-" + nonce + "@example.com");

        // sendRequest の業務トランザクションをテスト側で強制ロールバックさせる
        // （TransactionTemplate 内で例外を投げてロールバックを起こす）。
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            contactRequestService.sendRequest(requesterId, buildBody(targetId));
            throw new RuntimeException("強制ロールバック（AC-2検証用）");
        })).isInstanceOf(RuntimeException.class);

        // 業務行がロールバックされていること（前提の確認）。
        List<ContactRequestEntity> saved = transactionTemplate.execute(
                tx -> contactRequestRepository.findByTargetIdAndStatusOrderByCreatedAtDesc(targetId, "PENDING"));
        assertThat(saved).isEmpty();

        // 本丸: AFTER_COMMIT は業務トランザクションがロールバックすると発火しないため、
        // 通知配送 Runner は一度も呼ばれない（=通知が作られない）。
        org.mockito.Mockito.verifyNoInteractions(notificationDeliveryRunner);
    }

    @Test
    @DisplayName("AC-3: コミット直後の新規申請行を、通知配送タイミングで参照できる（deny されず生成される）")
    void コミット直後の新規申請行を通知配送タイミングで参照できる() {
        String nonce = String.valueOf(System.nanoTime());
        Long requesterId = insertUser("cr-ac3-a-" + nonce + "@example.com");
        Long targetId = insertUser("cr-ac3-b-" + nonce + "@example.com");

        SendContactRequestResponse response = contactRequestService.sendRequest(requesterId, buildBody(targetId));
        assertThat(response.getRequestId()).isNotNull();

        // 通知が実際に作られる（= visibility ガードで deny されない）ことを実DBで確認する。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> notifications = transactionTemplate.execute(
                    tx -> notificationRepository.findByUserIdOrderByCreatedAtDesc(
                            targetId, org.springframework.data.domain.PageRequest.of(0, 10)).getContent());
            assertThat(notifications)
                    .as("申請受信通知が deny されずに作られていること")
                    .anyMatch(n -> "CONTACT_REQUEST_RECEIVED".equals(n.getNotificationType())
                            && response.getRequestId().equals(n.getSourceId()));
        });
    }

    private SendContactRequestBody buildBody(Long targetUserId) {
        SendContactRequestBody body = new SendContactRequestBody();
        ReflectionTestUtils.setField(body, "targetUserId", targetUserId);
        ReflectionTestUtils.setField(body, "sourceType", "DIRECT");
        return body;
    }

    private Long insertUser(String email) {
        return transactionTemplate.execute(tx -> userRepository.save(UserEntity.builder()
                .email(email)
                .lastName("通知試験")
                .firstName("太郎")
                .displayName("通知試験ユーザー")
                .status(UserEntity.UserStatus.ACTIVE)
                .build()).getId());
    }
}
