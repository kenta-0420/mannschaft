package com.mannschaft.app.ticket.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.event.TicketExpiryWarningNotificationEvent;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * チケット期限切れバッチサービス。
 *
 * <p>日次で実行し、期限切れチケットのステータス遷移と PENDING クリーンアップを行う。</p>
 *
 * <h2>通知のトランザクション境界（Issue #2997 / CMP-260827-1152 G6）</h2>
 * <p>{@link #expireTickets} は<b>非トランザクションのオーケストレータ</b>で、1 冊ごとに
 * {@link TicketExpiryRunner#expireOne}（{@code REQUIRES_NEW}）で EXPIRED 遷移を確定し、同じトランザクションで
 * 配送要求を publish する。{@link #sendExpiryNotifications} は状態を更新しない読み取りTXの中で配送要求を publish
 * するだけ。実通知はどちらも {@code AFTER_COMMIT} の {@code TicketExpiryNotificationListener} が行うため、
 * 通知の失敗が遷移を巻き戻すことはない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketExpiryBatchService {

    private final TicketBookRepository bookRepository;
    /** 1 冊ぶんの期限切れ遷移の項目TX（遷移の確定 + 配送要求の publish）。 */
    private final TicketExpiryRunner expiryRunner;
    /** 事前通知の配送要求の publish 用。 */
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier("wallClock")
    private final Clock wallClock;

    /**
     * 期限切れチケットを EXPIRED に遷移する。毎日 00:30 JST に実行。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チケットの期限切れ遷移・放置分のキャンセル・期限前事前通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "ticket-expiry-daily", description = "期限切れチケットを毎日 00:30 に EXPIRED へ遷移する")
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 00:30。期限切れチケットの一括状態遷移でチケット数に比例する。余裕を取り 1 時間を上限とする。
    @SchedulerLock(name = "ticketExpiryDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    public void expireTickets() {
        LocalDateTime now = LocalDateTime.now(wallClock);
        List<TicketBookEntity> expiredBooks = bookRepository.findExpiredActiveBooks(now);

        if (expiredBooks.isEmpty()) {
            log.debug("期限切れチケットなし");
            return;
        }

        int expired = 0;
        for (TicketBookEntity book : expiredBooks) {
            try {
                // 1 冊 = 1 独立TX。失敗しても他の冊の遷移と通知には影響しない（次回バッチで再試行）。
                if (expiryRunner.expireOne(book.getId(), now)) {
                    expired++;
                }
            } catch (Exception e) {
                log.error("期限切れチケットの遷移に失敗しました（スキップ）: bookId={}", book.getId(), e);
            }
        }

        log.info("期限切れチケット処理完了: {}件", expired);
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
        LocalDateTime cutoff = LocalDateTime.now(wallClock).minusHours(2);
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
        LocalDate today = LocalDate.now(wallClock);
        int[] notificationDays = {30, 7, 3, 1};

        for (int days : notificationDays) {
            // DATEDIFF(expiresAt, now) == days と等価: 期限日が「今日 + days 日」の暦日に該当するチケットを抽出する。
            java.time.LocalDate targetDate = today.plusDays(days);
            LocalDateTime from = targetDate.atStartOfDay();
            LocalDateTime to = targetDate.plusDays(1).atStartOfDay();
            List<TicketBookEntity> books = bookRepository.findBooksExpiringBetween(from, to);
            for (TicketBookEntity book : books) {
                // 業務TX内では配送要求の publish のみ。実通知は AFTER_COMMIT のリスナーが行う。
                eventPublisher.publishEvent(new TicketExpiryWarningNotificationEvent(
                        book.getId(), book.getUserId(), days));
                log.debug("期限切れ事前通知: bookId={}, days={}", book.getId(), days);
            }
            if (!books.isEmpty()) {
                log.info("期限切れ{}日前通知: {}件", days, books.size());
            }
        }
    }
}
