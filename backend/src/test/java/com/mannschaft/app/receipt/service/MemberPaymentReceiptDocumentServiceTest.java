package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.receipt.ReceiptPdfGenerator;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.MemberPaymentReceiptDocument;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemberPaymentReceiptDocumentServiceTest {
    @Mock private ReceiptIssuerSettingsRepository issuerSettingsRepository;
    @Mock private NameResolverService nameResolverService;
    @Mock private ReceiptPdfGenerator receiptPdfGenerator;
    @Mock private Clock clock;
    @InjectMocks private MemberPaymentReceiptDocumentService service;

    @Test
    void generateUsesIssuerSettingsAndFixedClock() {
        ReceiptIssuerSettingsEntity settings = ReceiptIssuerSettingsEntity.builder()
                .issuerName("Test club").customFooter("Thank you").build();
        given(issuerSettingsRepository.findByScopeTypeAndScopeId(ReceiptScopeType.TEAM, 100L))
                .willReturn(Optional.of(settings));
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(Instant.parse("2026-09-20T12:00:00Z"));
        given(receiptPdfGenerator.generate(any(), any(), any(), any(), any())).willReturn(new byte[]{1});
        MemberPaymentReceiptDocument document = new MemberPaymentReceiptDocument(
                1L, "TEAM", 100L, 10L, "Payer", "Annual fee", new BigDecimal("5000"),
                "CARD", LocalDate.of(2026, 9, 19));

        byte[] result = service.generate(document);

        ArgumentCaptor<ReceiptEntity> receipt = ArgumentCaptor.forClass(ReceiptEntity.class);
        verify(receiptPdfGenerator).generate(receipt.capture(), any(), any(), any(), any());
        assertThat(result).containsExactly(1);
        assertThat(receipt.getValue().getReceiptNumber()).isEqualTo("MP-1");
        assertThat(receipt.getValue().getIssuerName()).isEqualTo("Test club");
        assertThat(receipt.getValue().getRecipientName()).isEqualTo("Payer");
        assertThat(receipt.getValue().getIssuedAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 12, 0));
    }
}
