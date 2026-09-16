package com.mannschaft.app.event;

import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventDelegationRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L5 — イベント代理出席の通知トランザクション境界の実 DB 検証。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code EventDelegationNotifier} は素の {@code @Component} で、
 * {@code EventDelegationService} の {@code @Transactional} メソッドから同期的に呼ばれていた。
 * 内部の {@code createNotification} は既定の {@code REQUIRED} 伝播で業務トランザクションに参加するため、
 * 通知の DB 例外が rollback-only を残して業務処理ごと巻き戻していた。台帳キーは 5 件
 * （{@code createDelegation} / {@code accept} / {@code reject} / {@code cancelInternal} /
 * {@code cancelOnMemberLeft}、いずれも {@code TX_NOTIFY_BARE}）。
 * schedule ドメインで L2（PR #3065）が是正した {@code ScheduleDelegationNotifier} と同一の欠陥である。</p>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>{@link NotificationService#createNotification} を spy して例外を投げさせる。ここは
 * 是正前・是正後のどちらの経路でも必ず通るため、是正前のコードでは委任の状態遷移が
 * {@code UnexpectedRollbackException} で巻き戻って本テストは赤になる。L2 の
 * {@code ScheduleDelegationNotificationTransactionIT} と同じ手法である。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むとコミットが起きず
 * リスナーが発火しないまま緑になる（偽の緑）。フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L5 イベント代理出席の通知トランザクション境界（実DB）")
class EventDelegationNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private EventDelegationService delegationService;

    @Autowired
    private EventDelegationRepository delegationRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 是正前・是正後の双方で必ず通る一点。ここを失敗させて巻き戻りの有無を測る。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @Test
    @DisplayName("退会連動の通知が失敗しても、孤立委任の CANCELLED 化はコミットされる（日次バッチ経路）")
    void 通知失敗でも退会連動の代理取消はコミットされる() {
        long teamId = 960_000_000L + (System.nanoTime() % 1_000_000L);
        long delegatorId = teamId + 1L;
        long delegateId = teamId + 2L;
        Long eventId = insertEvent(teamId, "l5-deleg-left");
        UUID delegationId = insertDelegation(
                eventId, teamId, delegatorId, delegateId, EventDelegationStatus.ACCEPTED);

        failNotification();

        EventDelegationEntity target = transactionTemplate.execute(
                tx -> delegationRepository.findById(delegationId).orElseThrow());
        delegationService.cancelOnMemberLeft(target, delegateId);

        assertThat(reloadStatus(delegationId))
                .as("通知が失敗しても孤立委任の取消は巻き戻らない")
                .isEqualTo(EventDelegationStatus.CANCELLED);
        awaitDeliveryAttempted();
    }

    @Test
    @DisplayName("承認の通知が失敗しても、PENDING → ACCEPTED の状態遷移はコミットされる")
    void 通知失敗でも代理承認はコミットされる() {
        long teamId = 970_000_000L + (System.nanoTime() % 1_000_000L);
        long delegatorId = teamId + 1L;
        long delegateId = teamId + 2L;
        Long eventId = insertEvent(teamId, "l5-deleg-accept");
        UUID delegationId = insertDelegation(
                eventId, teamId, delegatorId, delegateId, EventDelegationStatus.PENDING);

        failNotification();

        delegationService.accept(delegationId, delegateId);

        assertThat(reloadStatus(delegationId))
                .as("通知が失敗しても代理承認は巻き戻らない")
                .isEqualTo(EventDelegationStatus.ACCEPTED);
        awaitDeliveryAttempted();
    }

    @Test
    @DisplayName("拒否の通知が失敗しても、PENDING → REJECTED の状態遷移はコミットされる")
    void 通知失敗でも代理拒否はコミットされる() {
        long teamId = 980_000_000L + (System.nanoTime() % 1_000_000L);
        long delegatorId = teamId + 1L;
        long delegateId = teamId + 2L;
        Long eventId = insertEvent(teamId, "l5-deleg-reject");
        UUID delegationId = insertDelegation(
                eventId, teamId, delegatorId, delegateId, EventDelegationStatus.PENDING);

        failNotification();

        delegationService.reject(delegationId, delegateId);

        assertThat(reloadStatus(delegationId))
                .as("通知が失敗しても代理拒否は巻き戻らない")
                .isEqualTo(EventDelegationStatus.REJECTED);
        awaitDeliveryAttempted();
    }

    // ---- フィクスチャ / ヘルパ ----

    private Long insertEvent(long teamId, String slugPrefix) {
        return transactionTemplate.execute(tx -> eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(teamId)
                .slug(slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .build()).getId());
    }

    private UUID insertDelegation(Long eventId, long teamId, long delegatorId, long delegateId,
                                  EventDelegationStatus status) {
        return transactionTemplate.execute(tx -> delegationRepository.save(
                EventDelegationEntity.builder()
                        .eventId(eventId)
                        .delegatorId(delegatorId)
                        .delegateId(delegateId)
                        .teamId(teamId)
                        .status(status)
                        .reason("#2990 L5 検証")
                        .build()).getId());
    }

    private void failNotification() {
        willThrow(new RuntimeException("模擬通知失敗（#2990 L5 検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private EventDelegationStatus reloadStatus(UUID delegationId) {
        return transactionTemplate.execute(
                tx -> delegationRepository.findById(delegationId).orElseThrow().getStatus());
    }

    /** AFTER_COMMIT + @Async のリスナーが実際に配送を試みたことの裏取り（非同期のため待つ）。 */
    private void awaitDeliveryAttempted() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()));
    }
}
