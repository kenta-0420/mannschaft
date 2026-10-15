package com.mannschaft.app.event;

import com.mannschaft.app.event.dto.EventRsvpRequest;
import com.mannschaft.app.event.dto.RollCallEntryRequest;
import com.mannschaft.app.event.dto.RollCallSessionRequest;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.event.service.EventRollCallService;
import com.mannschaft.app.event.service.EventRsvpService;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.util.List;
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
 * Issue #2990 L5 — F03.12 ケア対象者見守り通知のトランザクション境界の実 DB 検証。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code EventRsvpService#submitRsvp} / {@code EventRollCallService#submitRollCall}
 * （および {@code EventCheckinService} の {@code selfCheckin} / {@code staffCheckin}）は
 * {@code @Transactional} の内側から {@code CareEventNotificationService} を直接呼んでいた。
 * 同サービスの {@code notifyRsvpConfirmed} / {@code notifyCheckin} は既定の {@code REQUIRED} 伝播で
 * 呼び出し元の業務トランザクションに参加するため、見守り者への {@code createNotification} が
 * 失敗すると<b>業務処理ごと巻き戻っていた</b>。台帳キーは 4 件（すべて {@code TX_NOTIFY_BARE}）。</p>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>ACTIVE なケアリンク（見守り者 1 名）を実 DB に投入して見守り通知の経路を実際に通し、
 * {@link NotificationService#createNotification} を spy して例外を投げさせる。
 * 是正前のコードでは RSVP 行 / 点呼の {@code event_checkins} 行が
 * {@code UnexpectedRollbackException} で巻き戻り、本テストは赤になる。</p>
 *
 * <h2>{@code selfCheckin} / {@code staffCheckin} を本 IT で直接踏まない理由</h2>
 * <p>この 2 メソッドは参加登録・チケット・（スタッフ側は）スコープ ADMIN 権限のフィクスチャを要するが、
 * <b>publish 位置と載せる内容は {@code submitRsvp} と完全に同一</b>
 * （{@code EventCareNotificationTriggerEvent} を 1 件・{@code Kind.CHECKIN} で publish）であり、
 * 配送は本 IT が踏むのと同じ {@code EventCareNotificationTriggerListener} 1 本に集約されている。
 * 巻き戻りの境界そのものは {@code submitRsvp} / {@code submitRollCall} の 2 本で実測している。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むとコミットが起きず
 * リスナーが発火しないまま緑になる（偽の緑）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L5 ケア対象者見守り通知のトランザクション境界（実DB）")
class EventCareNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private EventRsvpService rsvpService;

    @Autowired
    private EventRollCallService rollCallService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventRsvpResponseRepository rsvpResponseRepository;

    @Autowired
    private EventCheckinRepository checkinRepository;

    @Autowired
    private UserCareLinkRepository careLinkRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 是正前・是正後の双方で必ず通る一点。ここを失敗させて巻き戻りの有無を測る。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @Test
    @DisplayName("見守り通知が失敗しても、RSVP 回答はコミットされる")
    void 通知失敗でもRSVP回答はコミットされる() {
        long teamId = 910_000_000L + (System.nanoTime() % 1_000_000L);
        long careRecipientId = teamId + 1L;
        long watcherId = teamId + 2L;
        Long eventId = insertEvent(teamId, "l5-care-rsvp");
        insertActiveCareLink(careRecipientId, watcherId);

        failNotification();

        rsvpService.submitRsvp(eventId, careRecipientId, new EventRsvpRequest("ATTENDING", null));

        boolean rsvpCommitted = Boolean.TRUE.equals(transactionTemplate.execute(
                tx -> rsvpResponseRepository.findByEventIdAndUserId(eventId, careRecipientId).isPresent()));
        assertThat(rsvpCommitted)
                .as("見守り通知が失敗しても RSVP 回答は巻き戻らない")
                .isTrue();
        awaitDeliveryAttempted();
    }

    @Test
    @DisplayName("見守り通知が失敗しても、点呼セッションの出欠記録はコミットされる")
    void 通知失敗でも点呼記録はコミットされる() {
        long teamId = 920_000_000L + (System.nanoTime() % 1_000_000L);
        long careRecipientId = teamId + 1L;
        long watcherId = teamId + 2L;
        long operatorId = teamId + 3L;
        Long eventId = insertEvent(teamId, "l5-care-rollcall");
        insertActiveCareLink(careRecipientId, watcherId);
        String sessionId = UUID.randomUUID().toString();

        failNotification();

        rollCallService.submitRollCall(eventId, teamId, operatorId, new RollCallSessionRequest(
                sessionId,
                List.of(new RollCallEntryRequest(careRecipientId, "PRESENT", null, null)),
                true));

        boolean checkinCommitted = Boolean.TRUE.equals(transactionTemplate.execute(
                tx -> checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(
                        eventId, sessionId, careRecipientId).isPresent()));
        assertThat(checkinCommitted)
                .as("見守り通知が失敗しても点呼の出欠記録は巻き戻らない")
                .isTrue();
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
                .attendanceMode(EventAttendanceMode.RSVP)
                .build()).getId());
    }

    /** 見守り通知が実際に発火する条件（ACTIVE なケアリンク 1 本）を作る。 */
    private void insertActiveCareLink(long careRecipientUserId, long watcherUserId) {
        transactionTemplate.executeWithoutResult(tx -> careLinkRepository.save(
                UserCareLinkEntity.builder()
                        .careRecipientUserId(careRecipientUserId)
                        .watcherUserId(watcherUserId)
                        .careCategory(CareCategory.MINOR)
                        .status(CareLinkStatus.ACTIVE)
                        .invitedBy(CareLinkInvitedBy.WATCHER)
                        .notifyOnRsvp(true)
                        .notifyOnCheckin(true)
                        .createdBy(watcherUserId)
                        .build()));
    }

    private void failNotification() {
        willThrow(new RuntimeException("模擬通知失敗（#2990 L5 検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** AFTER_COMMIT + @Async のリスナーが実際に配送を試みたことの裏取り（非同期のため待つ）。 */
    private void awaitDeliveryAttempted() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()));
    }
}
