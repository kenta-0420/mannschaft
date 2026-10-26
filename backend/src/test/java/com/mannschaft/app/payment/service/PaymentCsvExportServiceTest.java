package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * PaymentCsvExportService ユニットテスト（F08.9 P8 T-CSV-01〜05）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>T-CSV-01: BOM が先頭についていること</li>
 *   <li>T-CSV-02: ヘッダー行が正しいこと</li>
 *   <li>T-CSV-03: PAID ステータスのレコードが正しく出力されること</li>
 *   <li>T-CSV-04: CSV インジェクション防止（=evil → '=evil）</li>
 *   <li>T-CSV-05: カンマを含む値がダブルクォートで囲まれること</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCsvExportService ユニットテスト（T-CSV-01〜05）")
class PaymentCsvExportServiceTest {

    private static final Long PAYMENT_ITEM_ID = 100L;
    private static final Long TEAM_ID = 1L;
    private static final Long ACTOR_USER_ID = 10L;
    private static final Long PAYER_USER_ID = 20L;
    private static final Long BENEFICIARY_USER_ID = 30L;

    @Mock
    private MemberPaymentRepository memberPaymentRepository;

    @Mock
    private PaymentItemRepository paymentItemRepository;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PaymentCsvExportService paymentCsvExportService;

    /**
     * テスト用 PaymentItemEntity を生成する。
     */
    private PaymentItemEntity buildPaymentItem() {
        return PaymentItemEntity.builder()
                .teamId(TEAM_ID)
                .name("月会費")
                .build();
    }

    /**
     * テスト用 MemberPaymentEntity を生成する。
     */
    private MemberPaymentEntity buildPayment(Long payerUserId, Long userId,
                                              String status, BigDecimal amount,
                                              LocalDateTime paidAt, LocalDate validUntil) {
        PaymentStatus paymentStatus = PaymentStatus.valueOf(status);
        return MemberPaymentEntity.builder()
                .payerUserId(payerUserId)
                .userId(userId)
                .paymentItemId(PAYMENT_ITEM_ID)
                .amountPaid(amount)
                .currency("JPY")
                .paymentMethod(PaymentMethod.STRIPE)
                .status(paymentStatus)
                .paidAt(paidAt)
                .validUntil(validUntil)
                .build();
    }

    // -------------------------------------------------------------------------
    // T-CSV-01: BOM が先頭についていること
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CSV-01: BOM（U+FEFF）が CSV 先頭に付与されること")
    void exportToCsv_hasBom() {
        given(paymentItemRepository.findById(PAYMENT_ITEM_ID))
                .willReturn(Optional.of(buildPaymentItem()));
        given(memberPaymentRepository.findByPaymentItemIdOrderByCreatedAtDesc(PAYMENT_ITEM_ID))
                .willReturn(List.of());
        given(nameResolverService.resolveUserFullNames(any())).willReturn(Map.of());

        String csv = paymentCsvExportService.exportToCsv(PAYMENT_ITEM_ID, TEAM_ID, ACTOR_USER_ID);

        // BOM: U+FEFF
        assertThat(csv).startsWith("﻿");
    }

    // -------------------------------------------------------------------------
    // T-CSV-02: ヘッダー行が正しいこと
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CSV-02: ヘッダー行が正しい列名を含むこと")
    void exportToCsv_correctHeader() {
        given(paymentItemRepository.findById(PAYMENT_ITEM_ID))
                .willReturn(Optional.of(buildPaymentItem()));
        given(memberPaymentRepository.findByPaymentItemIdOrderByCreatedAtDesc(PAYMENT_ITEM_ID))
                .willReturn(List.of());
        given(nameResolverService.resolveUserFullNames(any())).willReturn(Map.of());

        String csv = paymentCsvExportService.exportToCsv(PAYMENT_ITEM_ID, TEAM_ID, ACTOR_USER_ID);

        // BOM を除いた最初の行がヘッダー
        String withoutBom = csv.substring(1);
        String headerLine = withoutBom.split("\r\n")[0];
        assertThat(headerLine).isEqualTo("払い手ID,払い手名,受益者ID,受益者名,金額（円）,通貨,ステータス,支払日時,有効期限");
    }

    // -------------------------------------------------------------------------
    // T-CSV-03: PAID ステータスのレコードが正しく出力されること
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CSV-03: PAID ステータスのレコードが正しく CSV に出力されること")
    void exportToCsv_paidRecord_correctOutput() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 6, 11, 10, 30, 0);
        LocalDate validUntil = LocalDate.of(2027, 6, 10);
        MemberPaymentEntity payment = buildPayment(
                PAYER_USER_ID, BENEFICIARY_USER_ID, "PAID",
                new BigDecimal("5000.00"), paidAt, validUntil);

        given(paymentItemRepository.findById(PAYMENT_ITEM_ID))
                .willReturn(Optional.of(buildPaymentItem()));
        given(memberPaymentRepository.findByPaymentItemIdOrderByCreatedAtDesc(PAYMENT_ITEM_ID))
                .willReturn(List.of(payment));
        given(nameResolverService.resolveUserFullNames(anyCollection()))
                .willReturn(Map.of(
                        PAYER_USER_ID, "山田 太郎",
                        BENEFICIARY_USER_ID, "鈴木 花子"
                ));

        String csv = paymentCsvExportService.exportToCsv(PAYMENT_ITEM_ID, TEAM_ID, ACTOR_USER_ID);

        String withoutBom = csv.substring(1);
        String[] lines = withoutBom.split("\r\n");
        // lines[0] はヘッダー、lines[1] はデータ行
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        String dataLine = lines[1];
        assertThat(dataLine).contains(String.valueOf(PAYER_USER_ID));
        assertThat(dataLine).contains("山田 太郎");
        assertThat(dataLine).contains(String.valueOf(BENEFICIARY_USER_ID));
        assertThat(dataLine).contains("鈴木 花子");
        assertThat(dataLine).contains("5000.00");
        assertThat(dataLine).contains("JPY");
        assertThat(dataLine).contains("PAID");
        assertThat(dataLine).contains("2026-06-11 10:30:00");
        assertThat(dataLine).contains("2027-06-10");
    }

    // -------------------------------------------------------------------------
    // T-CSV-04: CSV インジェクション防止
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CSV-04: CSV インジェクション防止（=evil → '=evil）")
    void escapeCell_injectionPrevention() {
        // escapeCell は package-private なので直接テスト
        assertThat(paymentCsvExportService.escapeCell("=evil")).isEqualTo("'=evil");
        assertThat(paymentCsvExportService.escapeCell("+evil")).isEqualTo("'+evil");
        assertThat(paymentCsvExportService.escapeCell("-evil")).isEqualTo("'-evil");
        assertThat(paymentCsvExportService.escapeCell("@evil")).isEqualTo("'@evil");
        // 通常値は変換なし
        assertThat(paymentCsvExportService.escapeCell("normal")).isEqualTo("normal");
    }

    // -------------------------------------------------------------------------
    // T-CSV-05: カンマを含む値がダブルクォートで囲まれること
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("T-CSV-05: カンマを含む値がダブルクォートで囲まれること")
    void escapeCell_commaValue_quotedCorrectly() {
        assertThat(paymentCsvExportService.escapeCell("山田, 太郎")).isEqualTo("\"山田, 太郎\"");
        // ダブルクォート自体の二重化
        assertThat(paymentCsvExportService.escapeCell("say \"hello\"")).isEqualTo("\"say \"\"hello\"\"\"");
    }

    // -------------------------------------------------------------------------
    // 異常系: 支払い項目が存在しない場合
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("支払い項目が存在しない場合は BusinessException が投げられること")
    void exportToCsv_itemNotFound_throwsException() {
        given(paymentItemRepository.findById(PAYMENT_ITEM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                paymentCsvExportService.exportToCsv(PAYMENT_ITEM_ID, TEAM_ID, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class);
    }

    // -------------------------------------------------------------------------
    // 監査ログが記録されること
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("エクスポート実行時に監査ログが記録されること")
    void exportToCsv_auditLogRecorded() {
        given(paymentItemRepository.findById(PAYMENT_ITEM_ID))
                .willReturn(Optional.of(buildPaymentItem()));
        given(memberPaymentRepository.findByPaymentItemIdOrderByCreatedAtDesc(PAYMENT_ITEM_ID))
                .willReturn(List.of());
        given(nameResolverService.resolveUserFullNames(any())).willReturn(Map.of());

        paymentCsvExportService.exportToCsv(PAYMENT_ITEM_ID, TEAM_ID, ACTOR_USER_ID);

        verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq("PAYMENT_CSV_EXPORTED"),
                org.mockito.ArgumentMatchers.eq(ACTOR_USER_ID),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }
}
