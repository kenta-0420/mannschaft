package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.event.OverdueInvoiceNotificationEvent;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 広告請求書 OVERDUE 遷移の 1 件更新用 {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2834 / CMP-056 第2群ロット1。金型: {@code NotificationCreditResetRunner}・CMP-035）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code OverdueInvoiceBatchService#markOverdueInvoices} が<b>バッチ全体を 1 つの
 * {@code @Transactional} で包みながら</b>、ループ内で 1 件ずつ catch して継続していた。
 * 1 件の DB 例外が rollback-only を残すため、catch して続けた<b>他の請求書の OVERDUE 遷移も
 * コミット時にまとめて巻き戻っていた</b>（{@code UnexpectedRollbackException}）。
 * さらに {@code markOverdue()} の結果は dirty checking 頼みで {@code save} されておらず、
 * 独立トランザクション化に伴い明示的に保存する。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>対象抽出は {@code status = ISSUED} で行うが、抽出からこのメソッドの実行までに
 * 支払い等で状態が変わっている可能性がある。よって<b>再読込したうえで {@code ISSUED} か再判定</b>し、
 * そうでなければ何もせず {@code false} を返す（{@code markOverdue()} が
 * {@code IllegalStateException} を投げる経路に入らない）。バッチ全体の再実行も安全になる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverdueInvoiceMarkRunner {

    private final AdInvoiceRepository adInvoiceRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final UserRoleRepository userRoleRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 件の請求書を独立トランザクションで OVERDUE に更新し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は 1 件も作られない。</p>
     *
     * @param invoiceId 請求書ID
     * @return 実際に OVERDUE へ更新した場合 {@code true}、対象外（既に遷移済み・削除済み）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markOne(Long invoiceId) {
        AdInvoiceEntity invoice = adInvoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null || invoice.getStatus() != InvoiceStatus.ISSUED) {
            return false;
        }

        invoice.markOverdue();
        adInvoiceRepository.save(invoice);

        eventPublisher.publishEvent(buildNotificationEvent(invoice));
        return true;
    }

    /**
     * 通知配送要求を組み立てる（受信者の解決は業務トランザクション内で 1 回だけ行う）。
     *
     * <p>広告主アカウントが解決できない場合は組織 ADMIN 宛の受信者を空とし、SYSTEM_ADMIN 宛だけを送る。
     * 業務状態（OVERDUE 遷移）自体は成立しているため、ここで例外にして巻き戻すことはしない。</p>
     */
    private OverdueInvoiceNotificationEvent buildNotificationEvent(AdInvoiceEntity invoice) {
        Long organizationId = advertiserAccountRepository.findById(invoice.getAdvertiserAccountId())
                .map(account -> account.getScopeId())
                .orElse(null);

        List<OverdueInvoiceNotificationEvent.Recipient> organizationAdmins = organizationId == null
                ? List.of()
                : userRoleRepository.findUserIdAndEmailByScopeAndRole("ORGANIZATION", organizationId, "ADMIN")
                        .stream()
                        .map(row -> new OverdueInvoiceNotificationEvent.Recipient(
                                ((Number) row[0]).longValue(), (String) row[1]))
                        .toList();

        if (organizationId == null) {
            log.warn("広告主アカウントを解決できないため組織 ADMIN 宛の延滞通知をスキップします: invoiceId={}, advertiserAccountId={}",
                    invoice.getId(), invoice.getAdvertiserAccountId());
        }

        return new OverdueInvoiceNotificationEvent(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getDueDate(),
                organizationId,
                organizationAdmins,
                userRoleRepository.findSystemAdminUserIds());
    }
}
