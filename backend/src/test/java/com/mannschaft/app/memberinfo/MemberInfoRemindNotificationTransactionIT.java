package com.mannschaft.app.memberinfo;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.memberinfo.service.MemberInfoResponseService;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue #2997（CMP-260827-1152）G8 — {@code MemberInfoResponseService#sendRemind} の通知トランザクション分離を実 DB で検証する。
 *
 * <ul>
 *   <li>AC-A2: コミットされたら、受信者に通知行が実際に作られる（かつ last_reminder_sent_at が確定している）。</li>
 *   <li>AC-A: 業務TXがロールバックしたら、通知行は 1 件も作られない（配送 Runner は呼ばれない）。</li>
 *   <li>AC-B: 通知配送が失敗しても、last_reminder_sent_at の記録はコミット済みで呼び出しは正常に返る。
 *       障害は {@link NotificationDeliveryRunner#sendOne} を spy で例外化して注入する。
 *       限界: 例外は Runner 境界で注入しており、通知の永続化 SQL そのものを MySQL 上で失敗させてはいない。
 *       ただし是正前の欠陥（通知の例外が業務TXの rollback-only になる）は、Runner が業務TXの外
 *       （AFTER_COMMIT・別スレッド）で呼ばれることで構造的に閉じていることを AC-A と合わせて固定する。</li>
 *   <li>AC-B2（受信者複数）: sendRemind は受信者 1 名の API であるため該当しない。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} は付けない（付けるとコミットが起きず AFTER_COMMIT が発火しない偽の緑になる）。
 * フィクスチャは {@link TransactionTemplate} で明示的にコミットする。認可は本 IT の関心外なので
 * {@link AccessControlService#checkAdminOrAbove} だけを無効化する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G8 sendRemind の通知トランザクション分離の実DB検証")
class MemberInfoRemindNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired private MemberInfoResponseService memberInfoResponseService;
    @Autowired private TeamMemberInfoFieldRepository fieldRepository;
    @Autowired private TeamMemberInfoResponseRepository responseRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoSpyBean private NotificationDeliveryRunner notificationDeliveryRunner;
    @MockitoSpyBean private AccessControlService accessControlService;

    @Test
    @DisplayName("AC-A2: コミットされたら受信者に通知行が作られ、last_reminder_sent_at も確定している")
    void commitCreatesNotificationAndRecordsReminder() {
        long teamId = uniqueTeamId();
        Long adminId = insertUser("mi-a2-admin-" + System.nanoTime() + "@example.com");
        Long targetId = insertUser("mi-a2-target-" + System.nanoTime() + "@example.com");
        Long fieldId = insertField(teamId);
        willDoNothing().given(accessControlService).checkAdminOrAbove(any(), any(), any());

        memberInfoResponseService.sendRemind(teamId, targetId, adminId);

        assertThat(lastReminderSentAt(teamId, targetId, fieldId)).as("クールダウンの冪等キーが確定").isNotNull();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> notifications = notificationsOf(targetId);
            assertThat(notifications)
                    .as("受信者に MEMBER_INFO_UPDATE_REMINDER の通知行が作られること")
                    .anyMatch(n -> "MEMBER_INFO_UPDATE_REMINDER".equals(n.getNotificationType())
                            && teamId == n.getSourceId());
        });
    }

    @Test
    @DisplayName("AC-A: 業務TXがロールバックしたら通知行は作られず、配送 Runner も呼ばれない")
    void rollbackCreatesNoNotification() {
        long teamId = uniqueTeamId();
        Long adminId = insertUser("mi-a-admin-" + System.nanoTime() + "@example.com");
        Long targetId = insertUser("mi-a-target-" + System.nanoTime() + "@example.com");
        Long fieldId = insertField(teamId);
        willDoNothing().given(accessControlService).checkAdminOrAbove(any(), any(), any());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            memberInfoResponseService.sendRemind(teamId, targetId, adminId);
            throw new RuntimeException("強制ロールバック（AC-A 検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(lastReminderSentAt(teamId, targetId, fieldId)).as("記録もロールバックされている").isNull();
        // AFTER_COMMIT は発火しない。非同期配送の取りこぼしを避けるため少し待ってから確認する。
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationsOf(targetId)).isEmpty());
        verifyNoInteractions(notificationDeliveryRunner);
    }

    @Test
    @DisplayName("AC-B: 通知配送が失敗しても、last_reminder_sent_at の記録はコミット済みで呼び出しは正常に返る")
    void deliveryFailureDoesNotRollBackReminderRecord() {
        long teamId = uniqueTeamId();
        Long adminId = insertUser("mi-b-admin-" + System.nanoTime() + "@example.com");
        Long targetId = insertUser("mi-b-target-" + System.nanoTime() + "@example.com");
        Long fieldId = insertField(teamId);
        willDoNothing().given(accessControlService).checkAdminOrAbove(any(), any(), any());
        willThrow(new RuntimeException("模擬通知配送失敗（AC-B 検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        memberInfoResponseService.sendRemind(teamId, targetId, adminId);

        // 別接続（別TX）から読んで、業務状態がコミット済みであることを確認する。
        assertThat(lastReminderSentAt(teamId, targetId, fieldId)).isNotNull();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> verify(notificationDeliveryRunner).sendOne(any()));
        assertThat(notificationsOf(targetId)).as("配送が失敗したので通知行は無い").isEmpty();
    }

    private static long uniqueTeamId() {
        return 9_000_000_000L + (System.nanoTime() % 1_000_000_000L);
    }

    private java.time.LocalDateTime lastReminderSentAt(long teamId, Long userId, Long fieldId) {
        return transactionTemplate.execute(tx -> responseRepository.findByUserIdAndFieldId(userId, fieldId)
                .map(TeamMemberInfoResponseEntity::getLastReminderSentAt).orElse(null));
    }

    private List<NotificationEntity> notificationsOf(Long userId) {
        return transactionTemplate.execute(tx -> notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).getContent());
    }

    private Long insertField(long teamId) {
        return transactionTemplate.execute(tx -> fieldRepository.save(TeamMemberInfoFieldEntity.builder()
                .teamId(teamId).fieldName("緊急連絡先").build()).getId());
    }

    private Long insertUser(String email) {
        return transactionTemplate.execute(tx -> userRepository.save(UserEntity.builder()
                .email(email)
                .lastName("通知試験")
                .firstName("太郎")
                .displayName("通知試験ユーザー")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build()).getId());
    }
}
