package com.mannschaft.app.ticket.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * チケット期限切れバッチサービス。
 *
 * <p>日次で実行し、期限切れチケットのステータス遷移と PENDING クリーンアップを行う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketExpiryBatchService {

    private final TicketBookRepository bookRepository;
    private final NotificationHelper notificationHelper;
    /** Issue #2715 ロットB: 受信者 locale の解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    /**
     * 期限切れチケットを EXPIRED に遷移する。毎日 00:30 JST に実行。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チケットの期限切れ遷移・放置分のキャンセル・期限前事前通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "ticket-expiry-daily", description = "期限切れチケットを毎日 00:30 に EXPIRED へ遷移する")
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 00:30。期限切れチケットの一括状態遷移でチケット数に比例する。余裕を取り 1 時間を上限とする。
    @SchedulerLock(name = "ticketExpiryDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    @Transactional
    public void expireTickets() {
        LocalDateTime now = LocalDateTime.now();
        List<TicketBookEntity> expiredBooks = bookRepository.findExpiredActiveBooks(now);

        if (expiredBooks.isEmpty()) {
            log.debug("期限切れチケットなし");
            return;
        }

        // locale を一括解決（N+1 防止。Issue #2715 ロットB）。
        Map<Long, String> locales = userLocaleCache.getLocales(
                expiredBooks.stream().map(TicketBookEntity::getUserId).toList());

        for (TicketBookEntity book : expiredBooks) {
            book.expire();
            bookRepository.save(book);
            Locale locale = Locale.forLanguageTag(locales.getOrDefault(book.getUserId(), "ja"));
            String title = messageSource.getMessage(
                    "notification.ticket.expired.title", null, "チケット期限切れ", locale);
            String body = messageSource.getMessage(
                    "notification.ticket.expired.body", null, "お持ちのチケットが期限切れになりました。", locale);
            notificationHelper.notify(book.getUserId(), "TICKET_EXPIRED",
                    title, body,
                    "TICKET_BOOK", book.getId(), NotificationScopeType.PERSONAL, null,
                    "/tickets/my", null);
        }

        log.info("期限切れチケット処理完了: {}件", expiredBooks.size());
    }

    /**
     * PENDING のまま放置されたチケットをクリーンアップする。毎日 01:00 JST に実行。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チケットの期限切れ遷移・放置分のキャンセル・期限前事前通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "ticket-pending-cleanup-daily", description = "PENDING のまま 2 時間放置されたチケットを毎日 01:00 にキャンセルする")
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 01:00。2 時間以上 PENDING のチケットのキャンセルのみで対象は少数。余裕を取り 30 分を上限とする。
    @SchedulerLock(name = "ticketPendingCleanupDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    @Transactional
    public void cleanupPendingBooks() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<TicketBookEntity> staleBooks = bookRepository.findStalePendingBooks(cutoff);

        if (staleBooks.isEmpty()) {
            log.debug("クリーンアップ対象の PENDING チケットなし");
            return;
        }

        for (TicketBookEntity book : staleBooks) {
            book.cancel();
            bookRepository.save(book);
        }

        log.info("PENDING チケットクリーンアップ完了: {}件", staleBooks.size());
    }

    /**
     * 期限切れ事前通知を送信する。毎日 09:00 JST に実行。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チケットの期限切れ遷移・放置分のキャンセル・期限前事前通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "ticket-expiry-pre-notification-daily", description = "チケット期限 30/7/3/1 日前の事前通知を毎日 09:00 に送信する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 09:00。30/7/3/1 日前の事前通知送出で通知件数に比例する。余裕を取り 1 時間を上限とする。
    @SchedulerLock(name = "ticketExpiryPreNotificationDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    @Transactional(readOnly = true)
    public void sendExpiryNotifications() {
        java.time.LocalDate today = java.time.LocalDate.now();
        int[] notificationDays = {30, 7, 3, 1};

        for (int days : notificationDays) {
            // DATEDIFF(expiresAt, now) == days と等価: 期限日が「今日 + days 日」の暦日に該当するチケットを抽出する。
            java.time.LocalDate targetDate = today.plusDays(days);
            LocalDateTime from = targetDate.atStartOfDay();
            LocalDateTime to = targetDate.plusDays(1).atStartOfDay();
            List<TicketBookEntity> books = bookRepository.findBooksExpiringBetween(from, to);
            Map<Long, String> locales = userLocaleCache.getLocales(
                    books.stream().map(TicketBookEntity::getUserId).toList());
            for (TicketBookEntity book : books) {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(book.getUserId(), "ja"));
                String title = messageSource.getMessage(
                        "notification.ticket.expiryWarning.title", null, "チケット期限切れ予告", locale);
                String body = messageSource.getMessage(
                        "notification.ticket.expiryWarning.body", new Object[]{days},
                        "お持ちのチケットが" + days + "日後に期限切れになります。", locale);
                notificationHelper.notify(book.getUserId(), "TICKET_EXPIRY_WARNING",
                        title,
                        body,
                        "TICKET_BOOK", book.getId(), NotificationScopeType.PERSONAL, null,
                        "/tickets/my", null);
                log.debug("期限切れ事前通知: bookId={}, days={}", book.getId(), days);
            }
            if (!books.isEmpty()) {
                log.info("期限切れ{}日前通知: {}件", days, books.size());
            }
        }
    }
}
