package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 請求書 PDF 生成サービス。
 * <p>
 * 現時点では OpenPDF ライブラリ未導入のため、CSV 形式のプレーンテキストで
 * 請求書データを出力する。OpenPDF 導入後に PDF 生成に切り替える。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class InvoicePdfService {

    private final AdInvoiceRepository adInvoiceRepository;
    private final AdInvoiceItemRepository adInvoiceItemRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 請求書をバイト配列として生成する。
     *
     * @return PDF (将来) またはテキスト形式の請求書データ
     */
    public byte[] generateInvoicePdf(Long invoiceId, Long advertiserAccountId) {
        AdInvoiceEntity invoice = adInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_013));
        if (!invoice.getAdvertiserAccountId().equals(advertiserAccountId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        List<AdInvoiceItemEntity> items = adInvoiceItemRepository.findByInvoiceId(invoiceId);

        // TODO: OpenPDF 導入後に本格的な PDF 生成に切り替える
        // 現時点ではテキスト形式の請求書を生成
        StringBuilder sb = new StringBuilder();

        sb.append("===================================\n");
        sb.append("          請 求 書\n");
        sb.append("===================================\n\n");
        sb.append("請求書番号: ").append(invoice.getInvoiceNumber()).append("\n");
        sb.append("請求対象月: ").append(invoice.getInvoiceMonth().format(DATE_FMT).substring(0, 7)).append("\n");
        sb.append("発行日: ").append(invoice.getIssuedAt() != null ? invoice.getIssuedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "-").append("\n");
        sb.append("ステータス: ").append(invoice.getStatus()).append("\n\n");

        sb.append("--- 明細 ---\n");
        sb.append(String.format("%-30s %-6s %12s %12s %12s %12s\n",
                "キャンペーン名", "課金", "imp", "click", "単価", "小計"));
        sb.append("-".repeat(90)).append("\n");

        for (AdInvoiceItemEntity item : items) {
            sb.append(String.format("%-30s %-6s %,12d %,12d %12s %12s\n",
                    truncate(item.getCampaignName(), 30),
                    item.getPricingModel().name(),
                    item.getImpressions(),
                    item.getClicks(),
                    item.getUnitPrice().toPlainString(),
                    item.getSubtotal().toPlainString()));
        }

        sb.append("-".repeat(90)).append("\n");
        sb.append(String.format("%70s %12s\n", "小計（税抜）:", formatAmount(invoice.getTotalAmount())));
        sb.append(String.format("%70s %12s\n", "消費税（" + invoice.getTaxRate() + "%）:", formatAmount(invoice.getTaxAmount())));
        sb.append(String.format("%70s %12s\n", "合計（税込）:", formatAmount(invoice.getTotalWithTax())));

        if (invoice.getDueDate() != null) {
            sb.append("\nお支払期限: ").append(invoice.getDueDate().format(DATE_FMT)).append("\n");
        }
        if (invoice.getNote() != null) {
            sb.append("備考: ").append(invoice.getNote()).append("\n");
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 請求書のファイル名を生成する。
     */
    public String getFilename(Long invoiceId) {
        AdInvoiceEntity invoice = adInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_013));
        String month = invoice.getInvoiceMonth().format(DATE_FMT).substring(0, 7);
        return "invoice_" + month + ".txt"; // PDF導入後は .pdf に変更
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private String formatAmount(BigDecimal amount) {
        return "¥" + amount.setScale(0, RoundingMode.FLOOR).toPlainString();
    }
}
