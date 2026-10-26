package com.mannschaft.app.receipt.dto;

import com.mannschaft.app.receipt.ReceiptSourceRef;
import com.mannschaft.app.receipt.ReceiptSourceType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 運営領収書の発行指示（F08.12 §5.2）。
 *
 * <p>発行契機は 3 系統（広告費・通知クレジット・将来のサブスク）＋手動補填にまたがるが、
 * すべてこの 1 つの型に正規化してから
 * {@link com.mannschaft.app.receipt.service.PlatformReceiptIssueService} の単一の入口へ通す。</p>
 *
 * @param sourceType         元データ種別
 * @param sourceRef          元データ ID の値オブジェクト
 * @param recipientName      宛名（支払者の名称。暗号化して保存される）
 * @param recipientUserId    受領者ユーザー ID（判る場合のみ）
 * @param description        但し書き
 * @param amount             税込金額（<b>実際の入金額</b>を正とする。§5.3）
 * @param taxRate            適用税率（%）
 * @param taxAmount          税額
 * @param amountExclTax      税抜金額
 * @param paymentDate        支払日
 * @param paymentMethodLabel 支払い方法の表示名
 */
public record PlatformReceiptIssueCommand(
        ReceiptSourceType sourceType,
        ReceiptSourceRef sourceRef,
        String recipientName,
        Long recipientUserId,
        String description,
        BigDecimal amount,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal amountExclTax,
        LocalDate paymentDate,
        String paymentMethodLabel) {
}
