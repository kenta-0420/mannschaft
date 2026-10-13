package com.mannschaft.app.ticket.service;

import com.mannschaft.app.ticket.TicketBookStatus;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.event.TicketExpiredNotificationEvent;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * チケット期限切れ遷移を 1 冊ずつ実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2997 / CMP-260827-1152 G6。金型: {@code ShiftSwapExpiryRunner}）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code TicketExpiryBatchService#expireTickets} に {@code @Transactional} が付き、
 * 全冊の EXPIRED 遷移と通知を 1 トランザクションで包んでいた。通知は業務TXに参加する（{@code REQUIRED}）ため、
 * 1 通の DB 例外が rollback-only を残し、<b>{@code book.expire()} による遷移そのものと他の冊ぶんまで巻き戻して</b>いた。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>独立トランザクション内で読み直し、まだ ACTIVE かつ期限超過であることを再判定してから遷移させる。
 * 抽出からここまでの間に延長・消化・払戻しで状態が変わっていた場合も、バッチの再実行時も、何もせず {@code false} を返す。
 * EXPIRED になった冊は抽出対象（ACTIVE のみ）から外れるため、通知が二重に飛ぶこともない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketExpiryRunner {

    private final TicketBookRepository bookRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 冊を独立トランザクションで EXPIRED に遷移し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は 1 件も作られない。</p>
     *
     * @param bookId 回数券ID
     * @param now    判定基準時刻
     * @return 遷移させた場合 {@code true}、対象外（既に ACTIVE でない・期限内・不在）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(Long bookId, LocalDateTime now) {
        TicketBookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null || book.getStatus() != TicketBookStatus.ACTIVE
                || book.getExpiresAt() == null || !book.getExpiresAt().isBefore(now)) {
            return false;
        }
        book.expire();
        bookRepository.save(book);
        eventPublisher.publishEvent(new TicketExpiredNotificationEvent(book.getId(), book.getUserId()));
        return true;
    }
}
