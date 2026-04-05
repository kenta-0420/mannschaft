package com.mannschaft.app.advertising.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfService {

    private final AdInvoiceRepository adInvoiceRepository;
    private final AdInvoiceItemRepository adInvoiceItemRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final NumberFormat CURRENCY_FMT = NumberFormat.getCurrencyInstance(Locale.JAPAN);

    public byte[] generateInvoicePdf(Long invoiceId, Long advertiserAccountId) {
        AdInvoiceEntity invoice = adInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_013));
        if (!invoice.getAdvertiserAccountId().equals(advertiserAccountId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        List<AdInvoiceItemEntity> items = adInvoiceItemRepository.findByInvoiceId(invoiceId);

        try {
            return buildPdf(invoice, items);
        } catch (DocumentException e) {
            log.error("PDF生成エラー: invoiceId={}", invoiceId, e);
            throw new RuntimeException("PDF生成に失敗しました", e);
        }
    }

    public String getFilename(Long invoiceId) {
        AdInvoiceEntity invoice = adInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_013));
        String month = invoice.getInvoiceMonth().format(DATE_FMT).substring(0, 7);
        return "invoice_" + month + ".pdf";
    }

    private byte[] buildPdf(AdInvoiceEntity invoice, List<AdInvoiceItemEntity> items) throws DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 50, 50, 50, 50);
        PdfWriter.getInstance(document, out);
        document.open();

        // フォント定義（日本語対応なしの場合はHelvetica。日本語表示はテキストをASCIIに限定）
        Font titleFont = new Font(Font.HELVETICA, 20, Font.BOLD);
        Font headerFont = new Font(Font.HELVETICA, 10, Font.BOLD);
        Font normalFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
        Font smallFont = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.GRAY);
        Font totalFont = new Font(Font.HELVETICA, 12, Font.BOLD);

        // タイトル
        Paragraph title = new Paragraph("INVOICE", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(20);
        document.add(title);

        // プラットフォーム名
        Paragraph platform = new Paragraph("Mannschaft Advertising Platform", headerFont);
        platform.setAlignment(Element.ALIGN_CENTER);
        platform.setSpacingAfter(30);
        document.add(platform);

        // 請求書情報テーブル
        PdfPTable infoTable = new PdfPTable(2);
        infoTable.setWidthPercentage(100);
        infoTable.setWidths(new float[]{1, 1});
        infoTable.setSpacingAfter(20);

        addInfoCell(infoTable, "Invoice Number:", invoice.getInvoiceNumber(), headerFont, normalFont);
        addInfoCell(infoTable, "Invoice Month:", invoice.getInvoiceMonth().format(DATE_FMT).substring(0, 7), headerFont, normalFont);
        addInfoCell(infoTable, "Issued At:", invoice.getIssuedAt() != null ? invoice.getIssuedAt().format(DATETIME_FMT) : "-", headerFont, normalFont);
        addInfoCell(infoTable, "Status:", invoice.getStatus().name(), headerFont, normalFont);
        if (invoice.getDueDate() != null) {
            addInfoCell(infoTable, "Due Date:", invoice.getDueDate().format(DATE_FMT), headerFont, normalFont);
        }
        document.add(infoTable);

        // 明細テーブル
        PdfPTable itemTable = new PdfPTable(6);
        itemTable.setWidthPercentage(100);
        itemTable.setWidths(new float[]{3, 1, 1.5f, 1.5f, 1.5f, 1.5f});
        itemTable.setSpacingAfter(15);

        // ヘッダー
        String[] headers = {"Campaign", "Model", "Impressions", "Clicks", "Unit Price", "Subtotal"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
            cell.setBackgroundColor(new Color(240, 240, 240));
            cell.setPadding(5);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            itemTable.addCell(cell);
        }

        // 明細行
        for (AdInvoiceItemEntity item : items) {
            addItemCell(itemTable, truncate(item.getCampaignName(), 40), normalFont, Element.ALIGN_LEFT);
            addItemCell(itemTable, item.getPricingModel().name(), normalFont, Element.ALIGN_CENTER);
            addItemCell(itemTable, NumberFormat.getIntegerInstance().format(item.getImpressions()), normalFont, Element.ALIGN_RIGHT);
            addItemCell(itemTable, NumberFormat.getIntegerInstance().format(item.getClicks()), normalFont, Element.ALIGN_RIGHT);
            addItemCell(itemTable, formatPrice(item.getUnitPrice()), normalFont, Element.ALIGN_RIGHT);
            addItemCell(itemTable, formatAmount(item.getSubtotal()), normalFont, Element.ALIGN_RIGHT);
        }
        document.add(itemTable);

        // 合計欄
        PdfPTable totalTable = new PdfPTable(2);
        totalTable.setWidthPercentage(50);
        totalTable.setHorizontalAlignment(Element.ALIGN_RIGHT);

        addTotalRow(totalTable, "Subtotal (excl. tax):", formatAmount(invoice.getTotalAmount()), normalFont);
        addTotalRow(totalTable, "Tax (" + invoice.getTaxRate().stripTrailingZeros().toPlainString() + "%):", formatAmount(invoice.getTaxAmount()), normalFont);

        PdfPCell totalLabelCell = new PdfPCell(new Phrase("TOTAL:", totalFont));
        totalLabelCell.setBorder(PdfPCell.TOP);
        totalLabelCell.setPadding(5);
        totalLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalTable.addCell(totalLabelCell);

        PdfPCell totalValueCell = new PdfPCell(new Phrase(formatAmount(invoice.getTotalWithTax()), totalFont));
        totalValueCell.setBorder(PdfPCell.TOP);
        totalValueCell.setPadding(5);
        totalValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalTable.addCell(totalValueCell);

        document.add(totalTable);

        // 備考
        if (invoice.getNote() != null && !invoice.getNote().isBlank()) {
            Paragraph noteLabel = new Paragraph("Note:", headerFont);
            noteLabel.setSpacingBefore(20);
            document.add(noteLabel);
            document.add(new Paragraph(invoice.getNote(), normalFont));
        }

        // フッター
        Paragraph footer = new Paragraph("Generated by Mannschaft Advertising Platform", smallFont);
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(40);
        document.add(footer);

        document.close();
        return out.toByteArray();
    }

    private void addInfoCell(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(PdfPCell.NO_BORDER);
        labelCell.setPadding(3);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setBorder(PdfPCell.NO_BORDER);
        valueCell.setPadding(3);
        table.addCell(valueCell);
    }

    private void addItemCell(PdfPTable table, String value, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setPadding(4);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }

    private void addTotalRow(PdfPTable table, String label, String value, Font font) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(PdfPCell.NO_BORDER);
        labelCell.setPadding(3);
        labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setBorder(PdfPCell.NO_BORDER);
        valueCell.setPadding(3);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(valueCell);
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private String formatAmount(BigDecimal amount) {
        return CURRENCY_FMT.format(amount.setScale(0, RoundingMode.FLOOR));
    }

    private String formatPrice(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }
}
