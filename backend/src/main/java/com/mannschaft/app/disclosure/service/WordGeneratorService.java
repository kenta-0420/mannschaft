package com.mannschaft.app.disclosure.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 重要事項説明書 Word 出力サービス（F09.14 Phase 3-B）。
 *
 * <p>Apache POI XWPF を用いて {@code docx/disclosure/{templateCode}.docx}
 * 配下の Word テンプレートを読み込み、{@code ${key}} 形式のプレースホルダーを
 * フォームデータで置換した byte 配列を返す。</p>
 *
 * <p>SHA-256 計算 / R2 アップロード / SharedFile 連携は呼び出し側
 * （{@link DisclosureExportService}）の責務。本サービスはバイト列を返すだけ。</p>
 *
 * <p>テンプレート未配置の場合は最低限のフォールバック（フォーム項目の単純列挙）を
 * 生成する。Phase 4 以降で都道府県別の正式書式 docx に置き換える前提。</p>
 *
 * <p>プレースホルダー記法は {@link com.mannschaft.app.common.excel.ExcelGeneratorService}
 * の {@code fillTemplate} と揃える（{@code ${key}}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordGeneratorService {

    /** テンプレートのリソース配置プレフィックス。 */
    static final String WORD_TEMPLATE_PREFIX = "docx/disclosure/";

    /** プレースホルダー {@code ${key}} を抽出する正規表現。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final ObjectMapper objectMapper;

    /**
     * Word ファイルを生成する。
     *
     * @param draft    対象ドラフト
     * @param template 適用テンプレート
     * @return docx の byte 配列
     */
    public byte[] generate(DisclosureFormDraftEntity draft, DisclosureFormTemplateEntity template) {
        if (draft == null || template == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        Map<String, Object> data = buildPlaceholderData(draft, template);
        String templatePath = WORD_TEMPLATE_PREFIX + template.getCode() + ".docx";
        ClassPathResource resource = new ClassPathResource(templatePath);
        try {
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return fillTemplate(is, data);
                }
            }
            // フォールバック: テンプレ未配置時は最低限の docx を組み立てる
            log.warn("Word テンプレ未配置のためフォールバック出力: path={}, templateId={}",
                    templatePath, template.getId());
            return generateFallback(template, data);
        } catch (IOException e) {
            log.error("重説書 Word 生成失敗: templateId={}, code={}",
                    template.getId(), template.getCode(), e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /**
     * docx テンプレートを読み込み、本文 (paragraphs) およびテーブル内のセルに含まれる
     * {@code ${key}} を {@code data} で置換した結果を byte 配列で返す。
     *
     * <p>POI XWPF はラン (run) 単位でテキストを保持するため、プレースホルダーが
     * 複数 run に分割されているケースを考慮し、段落 (paragraph) 全体のテキストを
     * 連結 → 置換 → 単一 run に書き戻す。これによりフォントなど run 個別のスタイルは
     * 段落先頭 run のスタイルに統一されるが、テンプレート用途では問題ない。</p>
     */
    public byte[] fillTemplate(InputStream templateStream, Map<String, Object> data) throws IOException {
        if (templateStream == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        Map<String, Object> safeData = (data != null) ? data : Map.of();
        try (XWPFDocument doc = new XWPFDocument(templateStream);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 本文段落
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                replaceParagraph(paragraph, safeData);
            }
            // テーブルセル
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            replaceParagraph(paragraph, safeData);
                        }
                    }
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 段落単位でプレースホルダーを置換する。
     * 段落内の全 run のテキストを連結し、置換した結果を先頭 run に書き込んで
     * 残り run のテキストをクリアする。
     */
    private void replaceParagraph(XWPFParagraph paragraph, Map<String, Object> data) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            return;
        }
        StringBuilder combined = new StringBuilder();
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            if (text != null) {
                combined.append(text);
            }
        }
        String original = combined.toString();
        if (!original.contains("${")) {
            return;
        }
        String replaced = applyPlaceholders(original, data);
        // 先頭 run に置換結果を書き戻す（既存スタイルを保持）
        runs.get(0).setText(replaced, 0);
        // 残り run はテキストをクリア
        for (int i = 1; i < runs.size(); i++) {
            runs.get(i).setText("", 0);
        }
    }

    /** 文字列内の {@code ${key}} を {@code data} の値で置換。未提供キーは元のまま残す。 */
    private String applyPlaceholders(String text, Map<String, Object> data) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = data.get(key);
            String replacement = (value == null) ? matcher.group(0) : value.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** ドラフト + テンプレートからプレースホルダー置換用 Map を組み立てる。 */
    private Map<String, Object> buildPlaceholderData(DisclosureFormDraftEntity draft,
                                                     DisclosureFormTemplateEntity template) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateName", nullSafe(template.getName()));
        data.put("templateCode", nullSafe(template.getCode()));
        data.put("templateVersion", nullSafe(template.getVersion()));
        data.put("outputDate", LocalDateTime.now().format(DATE_TIME_FORMAT));
        data.put("draftTitle", nullSafe(draft.getTitle()));

        // formData の各フィールドを Map に展開
        if (draft.getFormData() != null && !draft.getFormData().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(draft.getFormData());
                if (node.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> entry = it.next();
                        JsonNode v = entry.getValue();
                        if (v == null || v.isNull()) {
                            data.put(entry.getKey(), "");
                        } else if (v.isValueNode()) {
                            data.put(entry.getKey(), v.asText());
                        } else {
                            data.put(entry.getKey(), v.toString());
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("Word 生成: formData JSON パース失敗（空 Map で続行）: draftId={}",
                        draft.getId(), e);
            }
        }
        return data;
    }

    /**
     * テンプレ docx 未配置時のフォールバック。
     * 様式コード・出力日時・フォームデータの key=value 一覧を含む docx を組み立てる。
     */
    private byte[] generateFallback(DisclosureFormTemplateEntity template,
                                    Map<String, Object> data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XWPFParagraph title = doc.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText("重要事項説明書（参考）");

            XWPFParagraph notice = doc.createParagraph();
            XWPFRun noticeRun = notice.createRun();
            noticeRun.setText("本書類は管理組合が物件調査に応じて作成した参考情報です。"
                    + "実際の取引では宅地建物取引士による説明・記名押印が必須です。");

            XWPFParagraph meta = doc.createParagraph();
            XWPFRun metaRun = meta.createRun();
            metaRun.setText("様式: " + nullSafe(template.getName())
                    + " (code=" + nullSafe(template.getCode())
                    + ", version=" + nullSafe(template.getVersion()) + ")");

            XWPFParagraph date = doc.createParagraph();
            XWPFRun dateRun = date.createRun();
            dateRun.setText("出力日時: "
                    + nullSafe(String.valueOf(data.getOrDefault("outputDate", ""))));

            // データ一覧
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if ("templateName".equals(entry.getKey())
                        || "templateCode".equals(entry.getKey())
                        || "templateVersion".equals(entry.getKey())
                        || "outputDate".equals(entry.getKey())) {
                    continue; // 上で出力済
                }
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setText(entry.getKey() + ": " + nullSafe(String.valueOf(entry.getValue())));
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
