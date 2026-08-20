package com.mannschaft.app.inbox.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.inbox.InboxNotificationTypes;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F04.11 Phase3 ②：スヌーズ復帰 push 再通知バッチ。
 *
 * <p>スヌーズした通知の {@code snoozed_until} 到来時、現状は「次回インボックスを開くと受信箱に戻る」
 * だけで能動的な再通知が無い。本バッチは 5 分毎に <b>全ユーザー横断</b>で「復帰期限到来かつ未通知かつ
 * 非アーカイブ」の {@code inbox_item_states} を拾い、その都度ユーザーへ push（WebSocket＋Web Push）して
 * 「あとで見るがそろそろ」と促す。push は <b>1 度だけ</b>送り、{@code snooze_notified_at} に時刻を刻む。
 * 設計書: docs/features/F04.11_notification_inbox/03_business_logic.md §5。</p>
 *
 * <h2>二重 push / 自己増殖の回避（最重要設計判断）</h2>
 * <p>push 経路は {@link NotificationHelper#notify}（通知行を作成し WebSocket＋Web Push を配信）に
 * 一本化する。push 基盤（{@code NotificationDispatchService.dispatch}）は {@code NotificationEntity}
 * を要求し「通知行を作らず push のみ送る」クリーンな経路が無いため、専用通知種別
 * {@link InboxNotificationTypes#INBOX_SNOOZE_REVIVAL} で通知を発行する方式（設計書 §5 の方針 2）を採る。
 * この種別は {@code NotificationInboxAdapter.fetch} で <b>インボックス集約から除外</b>するため、
 * ベル/通知一覧には「そろそろ」の催促として出るが、インボックス受信箱には元のスヌーズ項目が
 * 復帰するのみで <b>新規カードを生まない</b>（自己増殖の防止）。</p>
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>5 分毎（{@code 0 *​/5 * * * *} JST）。スヌーズ復帰の「そろそろ」は分単位の即時性で十分。</li>
 *   <li>{@link SchedulerLock} により複数インスタンス起動時の重複実行を防ぐ。</li>
 * </ul>
 *
 * <p>{@code @Transactional} は付けない（push は best-effort の外部 I/O を含み、トランザクション内で
 * 長時間ロックを保持しないため）。各行の stamp は {@code save} の自動コミットに委ねる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxSnoozeRevivalBatchService {

    /** 1 回の実行で処理する最大件数（暴走防止）。次回 5 分後に残りを拾う。 */
    private static final int BATCH_LIMIT = 500;

    /** 復帰 push のタイトル（ロケール鍵。日本語はフォールバック用デフォルト値）。 */
    private static final String PUSH_TITLE_KEY = "notification.inbox.snoozeRevival.title";
    private static final String PUSH_TITLE_DEFAULT = "あとで見るがそろそろ";

    /** 復帰 push の本文（ロケール鍵。日本語はフォールバック用デフォルト値）。 */
    private static final String PUSH_BODY_KEY = "notification.inbox.snoozeRevival.body";
    private static final String PUSH_BODY_DEFAULT = "スヌーズした通知の時間になりました。インボックスで確認しましょう。";

    /** 復帰 push のタップ遷移先（受信箱へ）。 */
    private static final String ACTION_URL = "/inbox";

    /**
     * 通知の {@code source_type}。{@code ReferenceType} に解決できない種別のため、
     * {@code NotificationService} の visibility ガードは fail-soft で通過する
     * （元項目の可視性はスヌーズ時に検証済み）。
     */
    private static final String PUSH_SOURCE_TYPE = "INBOX_REVIVAL";

    private final InboxItemStateRepository itemStateRepository;
    private final NotificationHelper notificationHelper;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * 復帰期限到来済みのスヌーズ項目へ復帰 push を送る（5 分毎・JST）。
     */
    @BatchEndpoint(name = "inbox-snooze-revival", description = "スヌーズ復帰期限到来の通知へ push 再通知を 5 分毎に 1 度だけ送る")
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "inboxSnoozeRevivalBatch", lockAtMostFor = "PT15M", lockAtLeastFor = "PT10S")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        List<InboxItemStateEntity> due =
                itemStateRepository.findDueForRevival(now, PageRequest.of(0, BATCH_LIMIT));
        if (due.isEmpty()) {
            return;
        }
        log.info("[InboxSnoozeRevivalBatch] 復帰 push 対象: {}件", due.size());

        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        Map<Long, String> locales = userLocaleCache.getLocales(
                due.stream().map(InboxItemStateEntity::getUserId).toList());

        int sent = 0;
        for (InboxItemStateEntity row : due) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(row.getUserId(), "ja"));
                // 専用種別で push（通知行は作るが、インボックス受信箱からは除外される）。
                notificationHelper.notify(
                        row.getUserId(),
                        InboxNotificationTypes.INBOX_SNOOZE_REVIVAL,
                        messageSource.getMessage(PUSH_TITLE_KEY, null, PUSH_TITLE_DEFAULT, locale),
                        messageSource.getMessage(PUSH_BODY_KEY, null, PUSH_BODY_DEFAULT, locale),
                        PUSH_SOURCE_TYPE,
                        null,                              // sourceId: 集約行を生まないため持たない
                        NotificationScopeType.PERSONAL,
                        null,                              // scopeId: 個人スコープ
                        ACTION_URL,
                        null);                             // actorId: システムトリガー
                sent++;
            } catch (RuntimeException ex) {
                // 症状を隠さない: 失敗した事実は必ずログに残す。ただし stamp は下で無条件に刻むため
                // 失敗しても再試行しない（best-effort 1 回）。恒久失敗のサブスク失効掃除は
                // WebPushService の 410/404 deleteByEndpoint に委譲する（DLQ/リトライ上限は設けない）。
                log.error("[InboxSnoozeRevivalBatch] 復帰 push 失敗(best-effort・再試行しない): "
                                + "userId={}, sourceType={}, sourceId={}",
                        row.getUserId(), row.getSourceType(), row.getSourceId(), ex);
            }
            // 成否に関わらず一度きり: stamp して次回バッチで再送しない（無限再試行の根絶＝上限1回・冪等）。
            // 恒久失敗のサブスク掃除は WebPushService の 410/404 失効掃除に委譲済み。
            row.setSnoozeNotifiedAt(now);
            itemStateRepository.save(row);
        }
        log.info("[InboxSnoozeRevivalBatch] 完了: 送信={}/{}件", sent, due.size());
    }
}
