package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverdueInvoiceBatchService {

    private final AdInvoiceRepository adInvoiceRepository;

    /**
     * OVERDUE 自動化バッチ。毎日 AM 6:00 (JST) に実行。
     * status = ISSUED かつ due_date < TODAY の請求書を OVERDUE に更新。
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Tokyo")
    @Transactional
    public void markOverdueInvoices() {
        LocalDate today = LocalDate.now();
        List<AdInvoiceEntity> overdueInvoices =
                adInvoiceRepository.findByStatusAndDueDateBefore(InvoiceStatus.ISSUED, today);

        if (overdueInvoices.isEmpty()) {
            return;
        }

        log.info("OVERDUE バッチ開始: 対象件数={}", overdueInvoices.size());

        int count = 0;
        for (AdInvoiceEntity invoice : overdueInvoices) {
            try {
                invoice.markOverdue();
                count++;
                // TODO: 広告主へのメール通知、SYSTEM_ADMIN へのプッシュ通知
            } catch (Exception e) {
                log.error("OVERDUE 更新エラー: invoiceId={}", invoice.getId(), e);
            }
        }

        log.info("OVERDUE バッチ完了: 更新件数={}", count);
    }
}
