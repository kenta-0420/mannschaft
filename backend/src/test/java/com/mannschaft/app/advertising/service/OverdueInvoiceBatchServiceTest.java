package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OverdueInvoiceBatchService} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>本クラスは<b>非トランザクションのオーケストレータ</b>になったため、関心は
 * 「対象抽出 → 項目ごとに {@link OverdueInvoiceMarkRunner} を呼ぶ → 失敗しても次へ」に絞る。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverdueInvoiceBatchService 単体テスト")
class OverdueInvoiceBatchServiceTest {

    @Mock private AdInvoiceRepository adInvoiceRepository;
    @Mock private OverdueInvoiceMarkRunner overdueInvoiceMarkRunner;

    @InjectMocks
    private OverdueInvoiceBatchService service;

    private AdInvoiceEntity invoice(Long id) {
        AdInvoiceEntity e = AdInvoiceEntity.builder()
                .advertiserAccountId(50L)
                .invoiceNumber("INV-" + id)
                .invoiceMonth(LocalDate.of(2026, 8, 1))
                .dueDate(LocalDate.of(2026, 8, 20))
                .status(InvoiceStatus.ISSUED)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    @DisplayName("抽出した請求書ごとに Runner が独立トランザクションで呼ばれる")
    void 請求書ごとにRunnerが呼ばれる() {
        given(adInvoiceRepository.findByStatusAndDueDateBefore(eq(InvoiceStatus.ISSUED), any(LocalDate.class)))
                .willReturn(List.of(invoice(1L), invoice(2L)));
        given(overdueInvoiceMarkRunner.markOne(anyLong())).willReturn(true);

        service.markOverdueInvoices();

        verify(overdueInvoiceMarkRunner).markOne(1L);
        verify(overdueInvoiceMarkRunner).markOne(2L);
    }

    @Test
    @DisplayName("AC-1: 1件が例外でも後続の請求書は処理される（バッチ全体を巻き戻さない）")
    void 一件失敗しても後続は処理される() {
        given(adInvoiceRepository.findByStatusAndDueDateBefore(eq(InvoiceStatus.ISSUED), any(LocalDate.class)))
                .willReturn(List.of(invoice(1L), invoice(2L), invoice(3L)));
        willThrow(new RuntimeException("模擬DB例外")).given(overdueInvoiceMarkRunner).markOne(2L);
        given(overdueInvoiceMarkRunner.markOne(eq(1L))).willReturn(true);
        given(overdueInvoiceMarkRunner.markOne(eq(3L))).willReturn(true);

        assertThatCode(() -> service.markOverdueInvoices()).doesNotThrowAnyException();

        verify(overdueInvoiceMarkRunner).markOne(1L);
        verify(overdueInvoiceMarkRunner).markOne(3L);
    }

    @Test
    @DisplayName("対象が 0 件の場合は Runner を呼ばない")
    void 対象なしは処理なし() {
        given(adInvoiceRepository.findByStatusAndDueDateBefore(eq(InvoiceStatus.ISSUED), any(LocalDate.class)))
                .willReturn(List.of());

        service.markOverdueInvoices();

        verify(overdueInvoiceMarkRunner, never()).markOne(any());
    }
}
