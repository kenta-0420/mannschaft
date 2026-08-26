package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.dto.DismissalReminderTargetResponse;
import com.mannschaft.app.event.dto.DismissalRequest;
import com.mannschaft.app.event.dto.DismissalStatusResponse;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.event.EventDismissalNotificationEvent;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRepository.DismissalReminderTargetProjection;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Issue #2834 / CMP-056 第1群ロットB: 解散通知は業務コミット後に配送リスナーへ委譲する
     * （{@code EventDismissalNotificationListener}）。本サービスは通知を直接組み立てない。
     */
    private final ApplicationEventPublisher applicationEventPublisher;

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
     *   <li>RSVP=ATTENDING の全参加者＋チェックインのみの参加者（補完）を重複排除して集約</li>
     *   <li>{@link EventDismissalNotificationEvent} を publish する（通知の生成・配信は行わない）</li>
     * </ol>
     *
     * <p>Issue #2834 / CMP-056 第1群ロットB: 通知は本トランザクションの中では送らない。
     * 業務コミット後（{@code AFTER_COMMIT}）に {@code EventDismissalNotificationListener} が
     * 受信者ごとに独立トランザクション（{@code REQUIRES_NEW}）で送る。見守り者への通知
     * （{@code CareEventNotificationService#notifyDismissal}）も同リスナーへ移した。
     * 是正前は通知側の DB 例外が rollback-only を立て、<b>「解散通知済み」の記録ごと巻き戻していた</b>。</p>
     *
     * <p>戻り値は元から {@code void} であり、非同期化による外向き契約の変化はない。</p>
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

        // Issue #2834 / CMP-056 第1群ロットB: 通知は業務コミット後（AFTER_COMMIT）に
        // EventDismissalNotificationListener が受信者ごと独立トランザクションで送る。
        if (!targetUserIds.isEmpty()) {
            applicationEventPublisher.publishEvent(new EventDismissalNotificationEvent(
                    eventId, teamId, operatorUserId, req.getMessage(), req.isNotifyGuardians(),
                    List.copyOf(targetUserIds)));
        }

        log.info("解散通知送信要求: eventId={}, operatorUserId={}, 参加者数={}, notifyGuardians={}",
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

    /**
     * ログインユーザーが主催している、終了予定時刻を過ぎたが未解散のイベント一覧を取得する。
     * F03.12 Phase11 / §16 Widget 連携。
     *
     * <p>主催者向けダッシュボード Widget {@code WidgetEventDismissalReminder} がカード描画に使う。
     * チームスコープのイベントのみを対象とする（個人スコープ・組織スコープでは「解散通知」自体が
     * 機能しないため）。</p>
     *
     * @param userId ログインユーザーID
     * @return 解散通知未送信イベントのレスポンスリスト（endAt 昇順）
     */
    public List<DismissalReminderTargetResponse> getMyDismissalReminderTargets(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<DismissalReminderTargetProjection> projections =
                eventRepository.findMyOrganizingUndismissedExpiredEvents(userId, now);

        return projections.stream()
                .map(p -> toDismissalReminderTargetResponse(p, now))
                .collect(Collectors.toList());
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * リマインダー対象 Projection を DTO に変換する。
     *
     * <p>イベント名は {@code subtitle} 優先で {@code slug} fallback、
     * 経過分数は {@code now - endAt} を分換算（負値は 0 にクランプ）して算出する。</p>
     *
     * @param p   投影
     * @param now 現在時刻
     * @return レスポンス DTO
     */
    private DismissalReminderTargetResponse toDismissalReminderTargetResponse(
            DismissalReminderTargetProjection p, LocalDateTime now) {
        String eventName = (p.getSubtitle() != null && !p.getSubtitle().isBlank())
                ? p.getSubtitle() : p.getSlug();
        long minutes = Duration.between(p.getEndAt(), now).toMinutes();
        if (minutes < 0) minutes = 0;
        int reminderCount = p.getReminderCount() != null ? p.getReminderCount().intValue() : 0;

        return DismissalReminderTargetResponse.builder()
                .eventId(p.getEventId())
                .eventName(eventName)
                .teamId(p.getTeamId())
                .teamName(p.getTeamName())
                .endAt(p.getEndAt())
                .minutesPassed(minutes)
                .reminderCount(reminderCount)
                .build();
    }

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

}
