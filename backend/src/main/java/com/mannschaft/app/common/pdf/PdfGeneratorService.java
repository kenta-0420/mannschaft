package com.mannschaft.app.common.pdf;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.config.PdfFontConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * 共通PDF生成サービス。
 * Thymeleaf テンプレートからHTMLを生成し、Flying Saucer で PDF に変換する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGeneratorService {

    private final TemplateEngine templateEngine;
    private final PdfFontConfig pdfFontConfig;

    /**
     * Thymeleaf テンプレートからPDFを生成する。
     *
     * @param templateName テンプレート名（例: "pdf/receipt"）
     * @param variables    テンプレートに渡す変数マップ
     * @return PDF の byte[]
     */
    public byte[] generateFromTemplate(String templateName, Map<String, Object> variables) {
        String html = renderHtml(templateName, variables);
        return convertHtmlToPdf(html);
    }

    private String renderHtml(String templateName, Map<String, Object> variables) {
        try {
            Context context = new Context();
            context.setVariables(variables);
            return templateEngine.process(templateName, context);
        } catch (Exception e) {
            log.error("Thymeleaf テンプレート処理失敗: template={}", templateName, e);
            throw new BusinessException(PdfErrorCode.PDF_001);
        }
    }

    private byte[] convertHtmlToPdf(String html) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            registerFonts(renderer);

            String baseUrl = new ClassPathResource("/").getURL().toExternalForm();
            renderer.setDocumentFromString(html, baseUrl);
            renderer.layout();
            renderer.createPDF(outputStream);

            return outputStream.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("PDF 変換失敗", e);
            throw new BusinessException(PdfErrorCode.PDF_002);
        }
    }

    private void registerFonts(ITextRenderer renderer) {
        ITextFontResolver fontResolver = renderer.getFontResolver();
        for (PdfFontConfig.FontEntry font : pdfFontConfig.getRegisteredFonts()) {
            try {
                ClassPathResource resource = new ClassPathResource(font.classpathLocation());
                try (InputStream is = resource.getInputStream()) {
                    String fontUrl = resource.getURL().toExternalForm();
                    fontResolver.addFont(fontUrl, true);
                }
            } catch (Exception e) {
                log.error("フォント登録失敗: {}", font.familyName(), e);
                throw new BusinessException(PdfErrorCode.PDF_003);
            }
        }
    }
}
