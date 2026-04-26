package com.mannschaft.app.family.service;

import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.CareCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ケア対象者の2段階不在アラートバッチサービス。F03.12 Phase4。
 *
 * <p>3分間隔で以下の2段階チェックを実行する。</p>
 * <ul>
 *   <li>第1段階 ({@link #runNoContactCheck()}): ソフト確認通知（NO_CONTACT_CHECK）</li>
 *   <li>第2段階 ({@link #runAbsentAlertCheck()}): 正式不在アラート（ABSENT_ALERT）</li>
 * </ul>
 *
 * <p>{@code care.absent-alert.enabled=false} でテスト時に無効化できる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "care.absent-alert.enabled", matchIfMissing = true)
public class CareAbsentAlertBatchService {

    /**
     * ケアカテゴリ別の第1段階通知タイミング（分）。
     * イベント開始からこの分数以上経過したときにソフト確認通知を送る。
     */
    private static final Map<CareCategory, Integer> SOFT_CHECK_MINUTES = Map.of(
            CareCategory.MINOR,            10,
            CareCategory.ELDERLY,           5,
            CareCategory.DISABILITY_SUPPORT, 5,
            CareCategory.GENERAL_FAMILY,   15
    );

    /**
     * ケアカテゴリ別の第2段階通知タイミング（分）。
     * イベント開始からこの分数以上経過したときに正式不在アラートを送る。
     */
    private static final Map<CareCategory, Integer> ABSENT_ALERT_MINUTES = Map.of(
            CareCategory.MINOR,            30,
            CareCategory.ELDERLY,          15,
            CareCategory.DISABILITY_SUPPORT, 15,
            CareCategory.GENERAL_FAMILY,   45
    );

    /** RSVP 回答の「参加予定」を表す文字列値。 */
    private static final String RESPONSE_ATTENDING = "ATTENDING";

    /**
     * 全カテゴリのうち soft_check_minutes の最小値。
     * これ以上経過したイベントのみをクエリ対象にする粗フィルタとして使用する。
     */
    private static final int MIN_SOFT_CHECK_MINUTES = 5;

    /**
     * 全カテゴリのうち absent_alert_minutes の最小値（粗フィルタ用）。
     */
    private static final int MIN_ABSENT_ALERT_MINUTES = 15;

    private final EventRepository eventRepository;
    private final EventRsvpResponseRepository rsvpResponseRepository;
    private final EventCheckinRepository eventCheckinRepository;
    private final CareLinkService careLinkService;
    private final CareEventNotificationService careEventNotificationService;

    // =========================================================
    // スケジュールバッチ（3分間隔）
    // =========================================================

    /**
     * 第1段階: ソフト確認通知（NO_CONTACT_CHECK）バッチ。
     *
     * <p>進行中イベントで ATTENDING かつ未チェックイン かつ遅刻連絡なし のケア対象者を検索し、
     * カテゴリ別の soft_check_minutes 以上経過していれば通知を送信する。
     * 冪等チェックは {@link CareEventNotificationService#sendNoContactCheck} に委譲する。</p>
     */
    @Scheduled(fixedDelay = 180_000)
    @Transactional
    public void runNoContactCheck() {
        log.debug("NO_CONTACT_CHECK バッチ開始");
        LocalDateTime now = LocalDateTime.now();

        // 全カテゴリの最小タイミングより経過したイベントを一括取得
        LocalDateTime softCutoff = now.minusMinutes(MIN_SOFT_CHECK_MINUTES);
        List<Long> activeEventIds = eventRepository.findActiveEventIdsStartedBefore(now, softCutoff);
        if (activeEventIds.isEmpty()) {
            log.debug("NO_CONTACT_CHECK: 対象イベントなし");
            return;
        }

        List<EventRsvpResponseEntity> candidates =
                rsvpResponseRepository.findByEventIdInAndResponseAndExpectedArrivalMinutesLateIsNull(
                        activeEventIds, RESPONSE_ATTENDING);

        for (EventRsvpResponseEntity rsvp : candidates) {
            try {
                processNoContactCheck(rsvp, now, activeEventIds);
            } catch (Exception e) {
                log.warn("NO_CONTACT_CHECK 処理中にエラー: eventId={}, userId={}, error={}",
                        rsvp.getEventId(), rsvp.getUserId(), e.getMessage(), e);
            }
        }
        log.debug("NO_CONTACT_CHECK バッチ完了: 候補件数={}", candidates.size());
    }

    /**
     * 第2段階: 正式不在アラート（ABSENT_ALERT）バッチ。
     *
     * <p>進行中イベントで ATTENDING かつ未チェックイン かつ遅刻連絡なし のケア対象者を検索し、
     * カテゴリ別の absent_alert_minutes 以上経過していれば正式不在アラートを送信する。
     * 冪等チェックは {@link CareEventNotificationService#sendAbsentAlert} に委譲する。</p>
     */
    @Scheduled(fixedDelay = 180_000)
    @Transactional
    public void runAbsentAlertCheck() {
        log.debug("ABSENT_ALERT バッチ開始");
        LocalDateTime now = LocalDateTime.now();

        LocalDateTime alertCutoff = now.minusMinutes(MIN_ABSENT_ALERT_MINUTES);
        List<Long> activeEventIds = eventRepository.findActiveEventIdsStartedBefore(now, alertCutoff);
        if (activeEventIds.isEmpty()) {
            log.debug("ABSENT_ALERT: 対象イベントなし");
            return;
        }

        List<EventRsvpResponseEntity> candidates =
                rsvpResponseRepository.findByEventIdInAndResponseAndExpectedArrivalMinutesLateIsNull(
                        activeEventIds, RESPONSE_ATTENDING);

        for (EventRsvpResponseEntity rsvp : candidates) {
            try {
                processAbsentAlertCheck(rsvp, now, activeEventIds);
            } catch (Exception e) {
                log.warn("ABSENT_ALERT 処理中にエラー: eventId={}, userId={}, error={}",
                        rsvp.getEventId(), rsvp.getUserId(), e.getMessage(), e);
            }
        }
        log.debug("ABSENT_ALERT バッチ完了: 候補件数={}", candidates.size());
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * 1件の RSVP に対して NO_CONTACT_CHECK の送信判定を行う。
     *
     * @param rsvp          RSVP回答エンティティ
     * @param now           現在日時
     * @param activeEventIds 進行中イベントIDリスト（バッチ呼び出し元で取得済み）
     */
    private void processNoContactCheck(EventRsvpResponseEntity rsvp,
                                        LocalDateTime now,
                                        List<Long> activeEventIds) {
        Long userId = rsvp.getUserId();
        Long eventId = rsvp.getEventId();

        // ケア対象者でなければスキップ
        if (!careLinkService.isUnderCare(userId)) return;

        // チェックイン済みならスキップ
        if (eventCheckinRepository.existsByEventIdAndUserId(eventId, userId)) return;

        // カテゴリ別タイミング判定
        CareCategory category = resolveCategory(userId);
        int softMinutes = SOFT_CHECK_MINUTES.getOrDefault(category, MIN_SOFT_CHECK_MINUTES);
        LocalDateTime requiredCutoff = now.minusMinutes(softMinutes);

        // 「開始から softMinutes 以上経過したイベント」かどうかを activeEventIds との照合で確認
        // activeEventIds は MIN_SOFT_CHECK_MINUTES 基準で取得済みなので、
        // カテゴリ固有のタイミングで再チェックする
        if (!isEventStartedBefore(eventId, requiredCutoff, now)) return;

        careEventNotificationService.sendNoContactCheck(userId, eventId);
    }

    /**
     * 1件の RSVP に対して ABSENT_ALERT の送信判定を行う。
     *
     * @param rsvp          RSVP回答エンティティ
     * @param now           現在日時
     * @param activeEventIds 進行中イベントIDリスト（バッチ呼び出し元で取得済み）
     */
    private void processAbsentAlertCheck(EventRsvpResponseEntity rsvp,
                                          LocalDateTime now,
                                          List<Long> activeEventIds) {
        Long userId = rsvp.getUserId();
        Long eventId = rsvp.getEventId();

        if (!careLinkService.isUnderCare(userId)) return;

        if (eventCheckinRepository.existsByEventIdAndUserId(eventId, userId)) return;

        CareCategory category = resolveCategory(userId);
        int alertMinutes = ABSENT_ALERT_MINUTES.getOrDefault(category, MIN_ABSENT_ALERT_MINUTES);
        LocalDateTime requiredCutoff = now.minusMinutes(alertMinutes);

        if (!isEventStartedBefore(eventId, requiredCutoff, now)) return;

        careEventNotificationService.sendAbsentAlert(userId, eventId);
    }

    /**
     * 指定イベントが cutoff より前に開始し、かつ現在も進行中かどうかを判定する。
     *
     * <p>カテゴリ固有のタイミングで再フィルタするために使用する。
     * DB アクセスを避けるため、{@link EventRepository#findActiveEventIdsStartedBefore} を
     * cutoff を変えて再呼び出しする。</p>
     *
     * @param eventId  対象イベントID
     * @param cutoff   開始時刻の上限（これより前に開始していること）
     * @param now      現在日時
     * @return 条件を満たす場合 true
     */
    private boolean isEventStartedBefore(Long eventId, LocalDateTime cutoff, LocalDateTime now) {
        List<Long> ids = eventRepository.findActiveEventIdsStartedBefore(now, cutoff);
        return ids.contains(eventId);
    }

    /**
     * ユーザーのケアカテゴリを解決する。
     *
     * <p>CareLinkService のキャッシュ経由で ACTIVE なケアリンクを取得し、
     * 最初のリンクのカテゴリを返す。取得できない場合は GENERAL_FAMILY を返す。</p>
     *
     * @param userId ユーザーID
     * @return ケアカテゴリ
     */
    private CareCategory resolveCategory(Long userId) {
        return careLinkService.getActiveLinksForCareRecipient(userId).stream()
                .findFirst()
                .map(link -> link.getCareCategory())
                .orElse(CareCategory.GENERAL_FAMILY);
    }
}
