package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.event.OverdueInvoiceNotificationEvent;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OverdueInvoiceMarkRunner} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>是正前は {@code OverdueInvoiceBatchService} がバッチ全体を 1 トランザクションで包み、
 * {@code markOverdue()} の結果を {@code save} せず dirty checking に任せていた。
 * 本テストは 1 件単位で状態遷移が確定すること、抽出後に状態が変わっていたら何もしないこと（冪等）、
 * 受信者解決を業務トランザクション内で 1 回だけ行いイベントに載せることを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverdueInvoiceMarkRunner 単体テスト")
class OverdueInvoiceMarkRunnerTest {

    @Mock private AdInvoiceRepository adInvoiceRepository;
    @Mock private AdvertiserAccountRepository advertiserAccountRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OverdueInvoiceMarkRunner runner;

    private AdInvoiceEntity invoice(Long id, InvoiceStatus status) {
        AdInvoiceEntity e = AdInvoiceEntity.builder()
                .advertiserAccountId(50L)
                .invoiceNumber("INV-001")
                .invoiceMonth(LocalDate.of(2026, 8, 1))
                .dueDate(LocalDate.of(2026, 8, 20))
                .status(status)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    @DisplayName("ISSUED の請求書を OVERDUE にして save し、受信者入りの配送要求を publish する")
    void ISSUEDをOVERDUEにする() {
        AdInvoiceEntity inv = invoice(1L, InvoiceStatus.ISSUED);
        given(adInvoiceRepository.findById(1L)).willReturn(Optional.of(inv));
        AdvertiserAccountEntity account = Mockito.mock(AdvertiserAccountEntity.class);
        given(account.getScopeId()).willReturn(900L);
        given(advertiserAccountRepository.findById(50L)).willReturn(Optional.of(account));
        given(userRoleRepository.findUserIdAndEmailByScopeAndRole("ORGANIZATION", 900L, "ADMIN"))
                .willReturn(List.of(new Object[]{10L, "admin@example.com"}));
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(99L));

        assertThat(runner.markOne(1L)).isTrue();

        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
        verify(adInvoiceRepository).save(inv);

        ArgumentCaptor<OverdueInvoiceNotificationEvent> captor =
                ArgumentCaptor.forClass(OverdueInvoiceNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        OverdueInvoiceNotificationEvent event = captor.getValue();
        assertThat(event.invoiceId()).isEqualTo(1L);
        assertThat(event.organizationId()).isEqualTo(900L);
        assertThat(event.organizationAdmins())
                .containsExactly(new OverdueInvoiceNotificationEvent.Recipient(10L, "admin@example.com"));
        assertThat(event.systemAdminUserIds()).containsExactly(99L);
    }

    @Test
    @DisplayName("AC-4: 抽出後に ISSUED でなくなっていたら何もせず false を返す（冪等）")
    void ISSUEDでなければ何もしない() {
        given(adInvoiceRepository.findById(1L)).willReturn(Optional.of(invoice(1L, InvoiceStatus.PAID)));

        assertThat(runner.markOne(1L)).isFalse();

        verify(adInvoiceRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(OverdueInvoiceNotificationEvent.class));
    }

    @Test
    @DisplayName("AC-4: 請求書が既に存在しなければ何もせず false を返す")
    void 請求書が無ければ何もしない() {
        given(adInvoiceRepository.findById(1L)).willReturn(Optional.empty());

        assertThat(runner.markOne(1L)).isFalse();

        verify(eventPublisher, never()).publishEvent(any(OverdueInvoiceNotificationEvent.class));
    }

    @Test
    @DisplayName("広告主アカウントが解決できなくても SYSTEM_ADMIN 宛には送る（業務状態は巻き戻さない）")
    void アカウント未解決でもSYSTEM_ADMIN宛は送る() {
        AdInvoiceEntity inv = invoice(1L, InvoiceStatus.ISSUED);
        given(adInvoiceRepository.findById(1L)).willReturn(Optional.of(inv));
        given(advertiserAccountRepository.findById(50L)).willReturn(Optional.empty());
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(99L));

        assertThat(runner.markOne(1L)).isTrue();
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);

        ArgumentCaptor<OverdueInvoiceNotificationEvent> captor =
                ArgumentCaptor.forClass(OverdueInvoiceNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().organizationAdmins()).isEmpty();
        assertThat(captor.getValue().systemAdminUserIds()).containsExactly(99L);
    }
}
