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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ケア対象者の2段階不在アラートバッチサービス。F03.12 Phase4。
 *
 * <p>3分間隔で以下の2段階チェックを実行する。</p>
 * <ul>
 *   <li>第1段階 ({@link #runNoContactCheck()}): ソフト確認通知（NO_CONTACT_CHECK）</li>
 *   <li>第2段階 ({@link #runAbsentAlertCheck()}): 正式不在アラート（ABSENT_ALERT）</li>
 * </ul>
 *
 * <p>Phase8 §15 遅刻連絡対応: {@code expectedArrivalMinutesLate} が設定されている場合は
 * カットオフ時刻を遅刻分だけ後ろにずらし、実際の想定到着時刻を基準にアラートを送信する。</p>
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
     * <p>進行中イベントで ATTENDING かつ未チェックイン のケア対象者を検索し、
     * カテゴリ別の soft_check_minutes 以上経過していれば通知を送信する。</p>
     *
     * <p>Phase8 §15 遅刻連絡対応: expectedArrivalMinutesLate が設定されている場合は
     * カットオフを遅刻分だけ後ろにずらす（例: MINOR・10分ベース・遅刻30分申告 → 開始40分後に通知）。
     * N+1 防止のため、イベントのアクティブ RSVP をメモリマップに一括キャッシュする。</p>
     *
     * <p>冪等チェックは {@link CareEventNotificationService#sendNoContactCheck} に委譲する。</p>
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

        // 遅刻連絡なし（遅刻連絡がないもの）と遅刻連絡あり（遅刻オフセット考慮が必要なもの）の両方を処理する
        // N+1 防止: 対象イベントの全 RSVP（ATTENDING）を一括取得しメモリマップ化する
        List<EventRsvpResponseEntity> allCandidates =
                rsvpResponseRepository.findByEventIdInAndResponse(activeEventIds, RESPONSE_ATTENDING);
        Map<String, EventRsvpResponseEntity> rsvpMap = buildRsvpMap(allCandidates);

        for (EventRsvpResponseEntity rsvp : allCandidates) {
            try {
                processNoContactCheck(rsvp, now, rsvpMap);
            } catch (Exception e) {
                log.warn("NO_CONTACT_CHECK 処理中にエラー: eventId={}, userId={}, error={}",
                        rsvp.getEventId(), rsvp.getUserId(), e.getMessage(), e);
            }
        }
        log.debug("NO_CONTACT_CHECK バッチ完了: 候補件数={}", allCandidates.size());
    }

    /**
     * 第2段階: 正式不在アラート（ABSENT_ALERT）バッチ。
     *
     * <p>進行中イベントで ATTENDING かつ未チェックイン のケア対象者を検索し、
     * カテゴリ別の absent_alert_minutes 以上経過していれば正式不在アラートを送信する。</p>
     *
     * <p>Phase8 §15 遅刻連絡対応: expectedArrivalMinutesLate が設定されている場合は
     * カットオフを遅刻分だけ後ろにずらす。
     * N+1 防止のため、イベントのアクティブ RSVP をメモリマップに一括キャッシュする。</p>
     *
     * <p>冪等チェックは {@link CareEventNotificationService#sendAbsentAlert} に委譲する。</p>
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

        // N+1 防止: 対象イベントの全 RSVP（ATTENDING）を一括取得しメモリマップ化する
        List<EventRsvpResponseEntity> allCandidates =
                rsvpResponseRepository.findByEventIdInAndResponse(activeEventIds, RESPONSE_ATTENDING);
        Map<String, EventRsvpResponseEntity> rsvpMap = buildRsvpMap(allCandidates);

        for (EventRsvpResponseEntity rsvp : allCandidates) {
            try {
                processAbsentAlertCheck(rsvp, now, rsvpMap);
            } catch (Exception e) {
                log.warn("ABSENT_ALERT 処理中にエラー: eventId={}, userId={}, error={}",
                        rsvp.getEventId(), rsvp.getUserId(), e.getMessage(), e);
            }
        }
        log.debug("ABSENT_ALERT バッチ完了: 候補件数={}", allCandidates.size());
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * 1件の RSVP に対して NO_CONTACT_CHECK の送信判定を行う。
     *
     * <p>Phase8 §15 遅刻連絡対応:
     * expectedArrivalMinutesLate が設定されている場合は、ソフト確認のカットオフを
     * 「基本分数 + 遅刻申告分数」で計算する。</p>
     *
     * @param rsvp    RSVP回答エンティティ
     * @param now     現在日時
     * @param rsvpMap イベントIDとユーザーIDをキーにした RSVP マップ（N+1 防止用）
     */
    private void processNoContactCheck(EventRsvpResponseEntity rsvp,
                                        LocalDateTime now,
                                        Map<String, EventRsvpResponseEntity> rsvpMap) {
        Long userId = rsvp.getUserId();
        Long eventId = rsvp.getEventId();

        // ケア対象者でなければスキップ
        if (!careLinkService.isUnderCare(userId)) return;

        // チェックイン済みならスキップ
        if (eventCheckinRepository.existsByEventIdAndUserId(eventId, userId)) return;

        // Phase8 §15: 事前欠席連絡済みは無断不着扱いしない（既に欠席意思が示されているため）
        if (rsvp.getAdvanceAbsenceReason() != null) return;

        // カテゴリ別タイミング判定
        CareCategory category = resolveCategory(userId);
        int softMinutes = SOFT_CHECK_MINUTES.getOrDefault(category, MIN_SOFT_CHECK_MINUTES);

        // Phase8 §15: 遅刻申告がある場合はカットオフを遅刻分だけ後ろにずらす
        int lateOffset = resolveLateOffset(rsvp);
        int effectiveMinutes = softMinutes + lateOffset;
        LocalDateTime requiredCutoff = now.minusMinutes(effectiveMinutes);

        if (!isEventStartedBefore(eventId, requiredCutoff, now)) return;

        if (lateOffset > 0) {
            log.debug("NO_CONTACT_CHECK（遅刻オフセット適用）: eventId={}, userId={}, base={}分, offset={}分, effective={}分",
                    eventId, userId, softMinutes, lateOffset, effectiveMinutes);
        }

        careEventNotificationService.sendNoContactCheck(userId, eventId);
    }

    /**
     * 1件の RSVP に対して ABSENT_ALERT の送信判定を行う。
     *
     * <p>Phase8 §15 遅刻連絡対応:
     * expectedArrivalMinutesLate が設定されている場合は、正式アラートのカットオフを
     * 「基本分数 + 遅刻申告分数」で計算する。</p>
     *
     * @param rsvp    RSVP回答エンティティ
     * @param now     現在日時
     * @param rsvpMap イベントIDとユーザーIDをキーにした RSVP マップ（N+1 防止用）
     */
    private void processAbsentAlertCheck(EventRsvpResponseEntity rsvp,
                                          LocalDateTime now,
                                          Map<String, EventRsvpResponseEntity> rsvpMap) {
        Long userId = rsvp.getUserId();
        Long eventId = rsvp.getEventId();

        if (!careLinkService.isUnderCare(userId)) return;

        if (eventCheckinRepository.existsByEventIdAndUserId(eventId, userId)) return;

        // Phase8 §15: 事前欠席連絡済みは無断不着アラート対象外
        if (rsvp.getAdvanceAbsenceReason() != null) return;

        CareCategory category = resolveCategory(userId);
        int alertMinutes = ABSENT_ALERT_MINUTES.getOrDefault(category, MIN_ABSENT_ALERT_MINUTES);

        // Phase8 §15: 遅刻申告がある場合はカットオフを遅刻分だけ後ろにずらす
        int lateOffset = resolveLateOffset(rsvp);
        int effectiveMinutes = alertMinutes + lateOffset;
        LocalDateTime requiredCutoff = now.minusMinutes(effectiveMinutes);

        if (!isEventStartedBefore(eventId, requiredCutoff, now)) return;

        if (lateOffset > 0) {
            log.debug("ABSENT_ALERT（遅刻オフセット適用）: eventId={}, userId={}, base={}分, offset={}分, effective={}分",
                    eventId, userId, alertMinutes, lateOffset, effectiveMinutes);
        }

        careEventNotificationService.sendAbsentAlert(userId, eventId);
    }

    /**
     * RSVP から遅刻オフセット分数を取得する。
     * expectedArrivalMinutesLate が設定されていれば その値、なければ 0 を返す。
     *
     * @param rsvp RSVP回答エンティティ
     * @return 遅刻オフセット分数（0以上）
     */
    private int resolveLateOffset(EventRsvpResponseEntity rsvp) {
        Integer late = rsvp.getExpectedArrivalMinutesLate();
        return (late != null) ? late : 0;
    }

    /**
     * RSVP リストを「eventId:userId」の複合キーで Map 化する（N+1 防止用）。
     *
     * @param rsvps RSVP リスト
     * @return 複合キー → RSVP のマップ
     */
    private Map<String, EventRsvpResponseEntity> buildRsvpMap(List<EventRsvpResponseEntity> rsvps) {
        return rsvps.stream()
                .collect(Collectors.toMap(
                        r -> r.getEventId() + ":" + r.getUserId(),
                        Function.identity(),
                        (a, b) -> a   // 重複時は最初のものを使用
                ));
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
