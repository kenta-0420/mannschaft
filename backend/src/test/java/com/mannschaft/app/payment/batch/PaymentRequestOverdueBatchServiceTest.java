package com.mannschaft.app.payment.batch;

import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentRequestOverdueBatchService} の単体テスト（F08.9 P7 第二波・期限超過）。
 *
 * <p>Clock 固定で「期限当日は対象外・翌日（超過）は OVERDUE 化・SENT/VIEWED のみ遷移・PAID は不遷移」を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRequestOverdueBatchService（期限超過バッチ）")
class PaymentRequestOverdueBatchServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Mock private PaymentRequestRepository paymentRequestRepository;

    private PaymentRequestOverdueBatchService newService(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(JST).toInstant(), JST);
        return new PaymentRequestOverdueBatchService(paymentRequestRepository, clock);
    }

    private PaymentRequestEntity request(PaymentRequestStatus status, LocalDate dueDate) {
        PaymentRequestEntity r = PaymentRequestEntity.builder()
                .organizationId(1L)
                .payerScopeKind(ScopeKind.TEAM)
                .payerScopeId(10L)
                .status(status)
                .dueDate(dueDate)
                .build();
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    @DisplayName("正常系: SENT/VIEWED かつ期限超過は OVERDUE へ遷移し保存する")
    void 期限超過でOVERDUE() {
        LocalDate today = LocalDate.of(2026, 8, 1);
        PaymentRequestEntity sent = request(PaymentRequestStatus.SENT, LocalDate.of(2026, 7, 31));
        PaymentRequestEntity viewed = request(PaymentRequestStatus.VIEWED, LocalDate.of(2026, 7, 30));
        given(paymentRequestRepository.findByStatusInAndDueDateLessThanAndDeletedAtIsNull(
                anyCollection(), any(LocalDate.class), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(sent, viewed)));
        given(paymentRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        newService(today).execute();

        assertThat(sent.getStatus()).isEqualTo(PaymentRequestStatus.OVERDUE);
        assertThat(viewed.getStatus()).isEqualTo(PaymentRequestStatus.OVERDUE);
        verify(paymentRequestRepository).save(sent);
        verify(paymentRequestRepository).save(viewed);
    }

    @Test
    @DisplayName("境界: 期限当日（due_date == today）は対象外（クエリは due_date < today で抽出する）")
    void 期限当日は対象外() {
        LocalDate today = LocalDate.of(2026, 7, 31);
        // 当日は cutoff=today の due_date < today に該当しないため、リポジトリは空を返す前提を検証する。
        given(paymentRequestRepository.findByStatusInAndDueDateLessThanAndDeletedAtIsNull(
                anyCollection(), any(LocalDate.class), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of()));

        newService(today).execute();

        // cutoff として today（2026-07-31）が渡ること（due_date < today）を検証する。
        org.mockito.ArgumentCaptor<LocalDate> cutoff = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        verify(paymentRequestRepository).findByStatusInAndDueDateLessThanAndDeletedAtIsNull(
                anyCollection(), cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getValue()).isEqualTo(today);
        verify(paymentRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("不遷移: 抽出に PAID が混じっても markAsOverdueIfDue が弾く（二重防御）")
    void PAIDは不遷移() {
        LocalDate today = LocalDate.of(2026, 8, 1);
        PaymentRequestEntity paid = request(PaymentRequestStatus.PAID, LocalDate.of(2026, 7, 1));
        given(paymentRequestRepository.findByStatusInAndDueDateLessThanAndDeletedAtIsNull(
                anyCollection(), any(LocalDate.class), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(paid)));

        newService(today).execute();

        assertThat(paid.getStatus()).isEqualTo(PaymentRequestStatus.PAID);
        verify(paymentRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("対象抽出は SENT/VIEWED のみを status 条件に渡す")
    void 抽出条件はSENTとVIEWEDのみ() {
        LocalDate today = LocalDate.of(2026, 8, 1);
        given(paymentRequestRepository.findByStatusInAndDueDateLessThanAndDeletedAtIsNull(
                anyCollection(), any(LocalDate.class), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of()));

        newService(today).execute();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<PaymentRequestStatus>> statuses =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(paymentRequestRepository).findByStatusInAndDueDateLessThanAndDeletedAtIsNull(
                statuses.capture(), any(LocalDate.class), any(Pageable.class));
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(PaymentRequestStatus.SENT, PaymentRequestStatus.VIEWED);
    }
}
