package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.excel.ExcelGeneratorService;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重要事項説明書 出力ファイル生成サービス（F09.14 Phase 4-A リファクタリング第 4 弾）。
 *
 * <p>{@link DisclosureExportService} ファサードから委譲される PDF/Excel/Word のバイナリ生成と
 * SHA-256 算出を集約する。R2 への保存・DB 登録は {@link DisclosureExportStorageService} 側に分離。</p>
 *
 * <p>本クラスは読込専用（DB 書き込みなし）。出力者氏名解決のみ {@link UserRepository} を参照する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureExportFileService {

    /** PDF 共通テンプレートのパス。 */
    private static final String PDF_TEMPLATE_COMMON = "pdf/disclosure/common";

    /** Excel テンプレートのリソース配置プレフィックス。 */
    private static final String EXCEL_TEMPLATE_PREFIX = "excel/disclosure/";

    private final PdfGeneratorService pdfGeneratorService;
    private final ExcelGeneratorService excelGeneratorService;
    private final WordGeneratorService wordGeneratorService;
    private final UserRepository userRepository;

    /**
     * PDF を生成する。Thymeleaf テンプレート（templates/pdf/disclosure/common.html 等）を
     * 経由して {@link PdfGeneratorService} に委譲する。
     */
    public byte[] generatePdf(DisclosureFormTemplateEntity template,
                              JsonNode formSchema, JsonNode formData,
                              OrganizationEntity organization,
                              String outputUserName) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("templateName", template.getName());
            variables.put("templateCode", template.getCode());
            variables.put("prefectureCode", template.getPrefectureCode());
            variables.put("effectiveDate", template.getEffectiveFrom());
            variables.put("outputDate", LocalDateTime.now());
            variables.put("outputUserName", outputUserName);
            variables.put("organizationName", organization != null ? organization.getName() : null);
            // Thymeleaf テンプレ (templates/pdf/disclosure/common.html) は Map で sections/fields を
            // th:each するため、JsonNode のままだと Iterable とみなされず PDF_001 を投げる。
            variables.put("formSchema", jsonNodeToMap(formSchema));
            variables.put("formData", jsonNodeToMap(formData));
            String templatePath = template.getPdfTemplatePath() != null
                    ? template.getPdfTemplatePath() : PDF_TEMPLATE_COMMON;
            return pdfGeneratorService.generateFromTemplate(templatePath, variables);
        } catch (BusinessException e) {
            log.error("重説書 PDF 生成失敗: templateId={}", template.getId(), e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /**
     * Excel 出力。
     *
     * <p>本フェーズでは標準書式 xlsx ファイルが未準備のため、テンプレ存在時は fillTemplate、
     * 未存在時は generateMultiSheetExcel によるフォールバック出力で対応する。</p>
     */
    public byte[] generateExcel(DisclosureFormTemplateEntity template,
                                JsonNode formSchema, JsonNode formData,
                                OrganizationEntity organization,
                                String outputUserName) {
        String templateKey = template.getExcelTemplateKey();
        if (templateKey == null || templateKey.isBlank()) {
            templateKey = EXCEL_TEMPLATE_PREFIX + template.getCode() + ".xlsx";
        }
        try {
            ClassPathResource resource = new ClassPathResource(templateKey);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = buildExcelTemplateData(formSchema, formData,
                            organization, outputUserName, template);
                    return excelGeneratorService.fillTemplate(is, data);
                }
            }
            // フォールバック: 共通の表形式 Excel を生成（FIXME: Phase 2-β-5 で xlsx テンプレ整備後に削除）
            log.warn("Excel テンプレ未配置のためフォールバック出力: key={}, templateId={}",
                    templateKey, template.getId());
            return excelGeneratorService.generateMultiSheetExcel(buildFallbackExcelSheets(
                    template, formSchema, formData, organization, outputUserName));
        } catch (IOException | RuntimeException e) {
            log.error("重説書 Excel 生成失敗: templateId={}", template.getId(), e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /**
     * Word 出力（F09.14 Phase 3-B）。
     *
     * <p>{@link WordGeneratorService} に委譲し、テンプレート (docx/disclosure/{templateCode}.docx)
     * 配下の docx を読み込んで {@code ${key}} プレースホルダーを置換する。テンプレート
     * 未配置の場合は {@link WordGeneratorService} 側のフォールバックで最低限の docx を生成する。</p>
     */
    public byte[] generateWord(DisclosureFormDraftEntity draft,
                               DisclosureFormTemplateEntity template) {
        try {
            return wordGeneratorService.generate(draft, template);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("重説書 Word 生成失敗: templateId={}", template.getId(), e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /** バイト列の SHA-256 を hex string で返す。 */
    public String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は JDK 標準のため通常発生しない
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /**
     * userId からユーザー氏名（displayName / lastName+firstName / email）を解決する。
     */
    public String resolveUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(this::formatUserName)
                .orElse(null);
    }

    private String formatUserName(UserEntity user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        StringBuilder sb = new StringBuilder();
        if (user.getLastName() != null) {
            sb.append(user.getLastName());
        }
        if (user.getFirstName() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(user.getFirstName());
        }
        return sb.length() > 0 ? sb.toString() : user.getEmail();
    }

    private Map<String, Object> buildExcelTemplateData(JsonNode formSchema, JsonNode formData,
                                                       OrganizationEntity organization,
                                                       String outputUserName,
                                                       DisclosureFormTemplateEntity template) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateName", template.getName());
        data.put("templateCode", template.getCode());
        data.put("outputDate", LocalDateTime.now());
        data.put("outputUserName", outputUserName);
        data.put("organizationName", organization != null ? organization.getName() : "");
        if (formData != null && formData.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = formData.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) {
                    data.put(e.getKey(), "");
                } else if (v.isValueNode()) {
                    data.put(e.getKey(), v.asText());
                } else {
                    data.put(e.getKey(), v.toString());
                }
            }
        }
        return data;
    }

    private List<ExcelGeneratorService.ExcelSheet> buildFallbackExcelSheets(
            DisclosureFormTemplateEntity template, JsonNode formSchema, JsonNode formData,
            OrganizationEntity organization, String outputUserName) {
        List<ExcelGeneratorService.ExcelSheet> sheets = new ArrayList<>();

        // シート1: 注意書き
        List<List<Object>> noticeRows = new ArrayList<>();
        noticeRows.add(List.of("様式", template.getName() != null ? template.getName() : ""));
        noticeRows.add(List.of("様式コード", template.getCode() != null ? template.getCode() : ""));
        noticeRows.add(List.of("出力日時", LocalDateTime.now()));
        noticeRows.add(List.of("出力者", outputUserName != null ? outputUserName : ""));
        noticeRows.add(List.of("物件名", organization != null && organization.getName() != null
                ? organization.getName() : ""));
        noticeRows.add(List.of("注意",
                "本書類は管理組合が物件調査に応じて作成した参考情報です。実際の取引では宅地建物取引士による説明・記名押印が必須です。"));
        sheets.add(new ExcelGeneratorService.ExcelSheet(
                "注意事項", List.of("項目", "値"), noticeRows));

        // シート2: フォームデータ
        List<List<Object>> dataRows = new ArrayList<>();
        if (formSchema != null && formSchema.isObject()) {
            JsonNode sections = formSchema.get("sections");
            if (sections != null && sections.isArray()) {
                for (JsonNode section : sections) {
                    JsonNode fields = section.get("fields");
                    if (fields == null || !fields.isArray()) {
                        continue;
                    }
                    for (JsonNode field : fields) {
                        if (field.get("type") != null
                                && "AUTO_TABLE".equals(field.get("type").asText())) {
                            continue; // AUTO_TABLE は別シート化が望ましいが、本フォールバックでは省略
                        }
                        String fieldId = field.get("id") != null ? field.get("id").asText() : "";
                        String label = field.get("label") != null ? field.get("label").asText() : fieldId;
                        JsonNode value = formData != null ? formData.get(fieldId) : null;
                        String text = (value == null || value.isNull()) ? ""
                                : (value.isValueNode() ? value.asText() : value.toString());
                        dataRows.add(List.of(
                                section.get("title") != null ? section.get("title").asText() : "",
                                label, text));
                    }
                }
            }
        }
        sheets.add(new ExcelGeneratorService.ExcelSheet(
                "重要事項説明書", List.of("セクション", "項目", "値"), dataRows));

        return sheets;
    }

    /** Thymeleaf 用に JsonNode を Map<String, Object> に変換（ヌル安全 + ネストオブジェクトはそのまま）。 */
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            map.put(e.getKey(), jsonNodeToObject(v));
        }
        return map;
    }

    private Object jsonNodeToObject(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isInt() || v.isLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : v) {
                list.add(jsonNodeToObject(item));
            }
            return list;
        }
        if (v.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            v.fields().forEachRemaining(e -> map.put(e.getKey(), jsonNodeToObject(e.getValue())));
            return map;
        }
        return v.toString();
    }
}
