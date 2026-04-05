package com.mannschaft.app.receipt;

import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.SvgToPngConverter;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 領収書PDF生成の本実装。
 * 既存の ReceiptPdfGenerator インターフェースのシグネチャを維持しつつ、
 * PdfGeneratorService（Thymeleaf + Flying Saucer）に委譲するブリッジパターン。
 */
@Component
@RequiredArgsConstructor
public class ReceiptPdfGeneratorImpl implements ReceiptPdfGenerator {

    private final PdfGeneratorService pdfGeneratorService;

    @Override
    public byte[] generate(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                           byte[] logoBytes, String sealSvg, String customFooter) {
        Map<String, Object> vars = buildVariables(receipt, lineItems, logoBytes, sealSvg, customFooter);
        vars.put("title", "領収書");
        return pdfGeneratorService.generateFromTemplate("pdf/receipt", vars);
    }

    @Override
    public byte[] generateVoided(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                                  byte[] logoBytes, String sealSvg, String customFooter) {
        Map<String, Object> vars = buildVariables(receipt, lineItems, logoBytes, sealSvg, customFooter);
        vars.put("title", "領収書（無効）");
        return pdfGeneratorService.generateFromTemplate("pdf/receipt-voided", vars);
    }

    private Map<String, Object> buildVariables(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                                                byte[] logoBytes, String sealSvg, String customFooter) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("receipt", receipt);
        vars.put("lineItems", lineItems);
        if (logoBytes != null) {
            vars.put("logoBase64", Base64.getEncoder().encodeToString(logoBytes));
        }
        if (sealSvg != null) {
            byte[] sealPng = SvgToPngConverter.convert(sealSvg, 120, 120);
            vars.put("sealBase64", Base64.getEncoder().encodeToString(sealPng));
        }
        vars.put("customFooter", customFooter);
        return vars;
    }
}
