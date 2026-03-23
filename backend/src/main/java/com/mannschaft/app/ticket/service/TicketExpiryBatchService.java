package com.mannschaft.app.ticket.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 期限切れチケットを EXPIRED に遷移する。毎日 00:30 JST に実行。
     */
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Tokyo")
    @Transactional
    public void expireTickets() {
        LocalDateTime now = LocalDateTime.now();
        List<TicketBookEntity> expiredBooks = bookRepository.findExpiredActiveBooks(now);

        if (expiredBooks.isEmpty()) {
            log.debug("期限切れチケットなし");
            return;
        }

        for (TicketBookEntity book : expiredBooks) {
            book.expire();
            bookRepository.save(book);
            notificationHelper.notify(book.getUserId(), "TICKET_EXPIRED",
                    "チケット期限切れ", "お持ちのチケットが期限切れになりました。",
                    "TICKET_BOOK", book.getId(), NotificationScopeType.PERSONAL, null,
                    "/tickets/my", null);
        }

        log.info("期限切れチケット処理完了: {}件", expiredBooks.size());
    }

    /**
     * PENDING のまま放置されたチケットをクリーンアップする。毎日 01:00 JST に実行。
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Tokyo")
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
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @Transactional(readOnly = true)
    public void sendExpiryNotifications() {
        LocalDateTime now = LocalDateTime.now();
        int[] notificationDays = {30, 7, 3, 1};

        for (int days : notificationDays) {
            List<TicketBookEntity> books = bookRepository.findBooksExpiringInDays(now, days);
            for (TicketBookEntity book : books) {
                notificationHelper.notify(book.getUserId(), "TICKET_EXPIRY_WARNING",
                        "チケット期限切れ予告",
                        String.format("お持ちのチケットが%d日後に期限切れになります。", days),
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
