package com.mannschaft.app.receipt;

import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 領収書 PDF 生成のプレースホルダー実装。
 * 本番では OpenPDF 2.x + IPAex明朝 + Apache Batik を使用して実装する。
 */
@Slf4j
@Component
public class ReceiptPdfGeneratorPlaceholder implements ReceiptPdfGenerator {

    @Override
    public byte[] generate(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                           byte[] logoBytes, String sealSvg, String customFooter) {
        log.info("PDF生成（プレースホルダー）: receiptId={}, receiptNumber={}",
                receipt.getId(), receipt.getReceiptNumber());
        // TODO: OpenPDF 2.x で実装
        String placeholder = "RECEIPT_PDF_PLACEHOLDER: " + receipt.getReceiptNumber();
        return placeholder.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] generateVoided(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                                 byte[] logoBytes, String sealSvg, String customFooter) {
        log.info("無効化PDF生成（プレースホルダー）: receiptId={}, receiptNumber={}",
                receipt.getId(), receipt.getReceiptNumber());
        // TODO: OpenPDF 2.x で「無効」赤スタンプオーバーレイ付きPDFを実装
        String placeholder = "VOIDED_RECEIPT_PDF_PLACEHOLDER: " + receipt.getReceiptNumber();
        return placeholder.getBytes(StandardCharsets.UTF_8);
    }
}
