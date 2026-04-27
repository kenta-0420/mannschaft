package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.dto.DismissalRequest;
import com.mannschaft.app.event.dto.DismissalStatusResponse;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * イベント解散通知サービス。F03.12 §16。
 *
 * <p>主催者がワンタップで全参加者・見守り者に「解散しました」を送る機能と、
 * 解散通知状態の参照を提供する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventDismissalService {

    /** RSVP 参加確定ステータス */
    private static final String RESPONSE_ATTENDING = "ATTENDING";

    private final EventRepository eventRepository;
    private final EventRsvpResponseRepository rsvpResponseRepository;
    private final EventCheckinRepository checkinRepository;
    private final NotificationService notificationService;
    private final NotificationDispatchService dispatchService;
    private final CareEventNotificationService careEventNotificationService;
    private final CareLinkService careLinkService;

    // =========================================================
    // 公開 API
    // =========================================================

    /**
     * 解散通知を全参加者・見守り者に送信する。
     *
     * <p>処理手順:</p>
     * <ol>
     *   <li>既に解散通知済みの場合は {@link BusinessException}（ALREADY_DISMISSED）をスロー</li>
     *   <li>{@code EventEntity.recordDismissal(operatorUserId)} を呼び出して送信日時を記録</li>
     *   <li>RSVP=ATTENDING の全参加者にプッシュ通知を送信</li>
     *   <li>チェックインのみ（RSVP 未登録）の参加者にも通知（補完）</li>
     *   <li>{@code req.notifyGuardians=true} の場合、ケア対象者の見守り者全員に追加通知</li>
     * </ol>
     *
     * @param eventId          対象イベントID
     * @param teamId           チームID（スコープ検証用）
     * @param operatorUserId   解散通知を送信する操作者のユーザーID
     * @param req              解散通知リクエスト
     * @throws BusinessException ALREADY_DISMISSED: 既に解散通知済みの場合
     * @throws BusinessException EVENT_NOT_FOUND: イベントが存在しない or チーム不一致の場合
     */
    @Transactional
    public void sendDismissalNotification(Long eventId, Long teamId,
                                          Long operatorUserId, DismissalRequest req) {
        // イベント取得（チームスコープ検証付き）
        EventEntity event = findEventByTeam(eventId, teamId);

        // 重複送信ガード
        if (event.getDismissalNotificationSentAt() != null) {
            throw new BusinessException(EventErrorCode.ALREADY_DISMISSED);
        }

        // 解散通知送信日時を記録（ドメインメソッド経由）
        event.recordDismissal(operatorUserId);
        eventRepository.save(event);

        // 通知対象参加者の収集（RSVP ATTENDING + チェックイン補完、重複排除）
        List<Long> attendingUserIds = rsvpResponseRepository
                .findUserIdsByEventIdAndResponse(eventId, RESPONSE_ATTENDING);
        List<Long> checkedInUserIds = checkinRepository.findCheckedInUserIdsByEventId(eventId);

        // 重複を排除してマージ
        Set<Long> targetUserIds = new HashSet<>(attendingUserIds);
        targetUserIds.addAll(checkedInUserIds);

        String message = req.resolveMessage();
        String eventLabel = resolveEventLabel(event);

        // 参加者へのプッシュ通知
        for (Long targetUserId : targetUserIds) {
            sendParticipantDismissalNotification(targetUserId, eventId, eventLabel, message);
        }

        // 見守り者への追加通知
        if (req.isNotifyGuardians()) {
            notifyGuardiansForCareRecipients(targetUserIds, eventId);
        }

        log.info("解散通知送信完了: eventId={}, operatorUserId={}, 参加者数={}, notifyGuardians={}",
                eventId, operatorUserId, targetUserIds.size(), req.isNotifyGuardians());
    }

    /**
     * 解散通知の送信状態を取得する。
     *
     * @param eventId イベントID
     * @param teamId  チームID（スコープ検証用）
     * @return 解散通知状態レスポンス
     * @throws BusinessException EVENT_NOT_FOUND: イベントが存在しない or チーム不一致の場合
     */
    public DismissalStatusResponse getDismissalStatus(Long eventId, Long teamId) {
        EventEntity event = findEventByTeam(eventId, teamId);

        return DismissalStatusResponse.builder()
                .dismissalNotificationSentAt(event.getDismissalNotificationSentAt())
                .dismissalNotifiedByUserId(event.getDismissalNotifiedBy())
                .reminderCount(event.getOrganizerReminderSentCount() != null
                        ? event.getOrganizerReminderSentCount().intValue() : 0)
                .lastReminderAt(event.getLastOrganizerReminderAt())
                .dismissed(event.getDismissalNotificationSentAt() != null)
                .build();
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * チームスコープ検証付きでイベントを取得する。
     *
     * @param eventId イベントID
     * @param teamId  チームID
     * @return イベントエンティティ
     * @throws BusinessException EVENT_NOT_FOUND: 存在しない or スコープ不一致
     */
    private EventEntity findEventByTeam(Long eventId, Long teamId) {
        return eventRepository.findByIdAndTeamScopeId(eventId, teamId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));
    }

    /**
     * 参加者個人へ解散通知プッシュを送信する。
     *
     * @param targetUserId 通知先ユーザーID
     * @param eventId      イベントID
     * @param eventLabel   イベント表示名
     * @param message      解散メッセージ
     */
    private void sendParticipantDismissalNotification(Long targetUserId, Long eventId,
                                                       String eventLabel, String message) {
        String title = "「" + eventLabel + "」が解散しました";
        String body = message;

        NotificationEntity notification = notificationService.createNotification(
                targetUserId,
                "EVENT_DISMISSAL",
                NotificationPriority.NORMAL,
                title, body,
                "EVENT", eventId,
                NotificationScopeType.PERSONAL, targetUserId,
                "/events/" + eventId, null);

        dispatchService.dispatch(notification);
        log.debug("解散通知送信: eventId={}, targetUserId={}", eventId, targetUserId);
    }

    /**
     * ケア対象者の見守り者全員へ解散通知を送信する。
     *
     * <p>参加者の中でケア対象者を特定し、各ケア対象者の見守り者に
     * {@link CareEventNotificationService#notifyDismissal} を通じて通知する。</p>
     *
     * @param targetUserIds 参加者ユーザーIDセット（RSVP ATTENDING + チェックイン）
     * @param eventId       イベントID
     */
    private void notifyGuardiansForCareRecipients(Set<Long> targetUserIds, Long eventId) {
        for (Long userId : targetUserIds) {
            try {
                if (careLinkService.isUnderCare(userId)) {
                    // CareEventNotificationService.notifyDismissal() に委譲
                    careEventNotificationService.notifyDismissal(userId, eventId);
                    log.debug("見守り者への解散通知送信: eventId={}, careRecipientUserId={}", eventId, userId);
                }
            } catch (Exception e) {
                // 個別の見守り者通知失敗は全体の解散通知処理を停止させない
                log.warn("見守り者への解散通知送信中にエラー: eventId={}, userId={}, error={}",
                        eventId, userId, e.getMessage(), e);
            }
        }
    }

    /**
     * イベントの表示ラベルを解決する。subtitle が設定されていれば使用し、なければ slug を使用する。
     *
     * @param event イベントエンティティ
     * @return イベント表示ラベル（非 null）
     */
    private String resolveEventLabel(EventEntity event) {
        String subtitle = event.getSubtitle();
        return (subtitle != null && !subtitle.isBlank()) ? subtitle : event.getSlug();
    }
}
