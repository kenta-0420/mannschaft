package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.support.WordTemplateGenerator;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WordGeneratorService} 単体テスト（F09.14 Phase 3-B）。
 *
 * <p>POI で動的に docx テンプレートを組み立て → fillTemplate でプレースホルダーを置換 →
 * 結果を再度 XWPFDocument としてパースし、本文・テーブルセルの置換が正しく行われている
 * ことを検証する。</p>
 */
@DisplayName("WordGeneratorService 単体テスト")
class WordGeneratorServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WordGeneratorService service = new WordGeneratorService(objectMapper);

    @Test
    @DisplayName("fillTemplate(): docx 内の ${key} が data の値で置換される（本文 + テーブル）")
    void fillTemplate_replacesPlaceholders() throws Exception {
        // given: WordTemplateGenerator で実テンプレ docx を生成
        byte[] template = WordTemplateGenerator.buildAll().get("MLIT_STANDARD_2024");
        assertThat(template).isNotEmpty();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateCode", "MLIT_STANDARD_2024");
        data.put("templateVersion", "2024.1");
        data.put("draftTitle", "サンプル下書き");
        data.put("outputDate", "2026/05/09 10:00");
        data.put("outputUserName", "山田太郎");
        data.put("物件名", "サンプルマンション");
        data.put("所在地", "東京都新宿区西新宿1-1-1");
        data.put("部屋番号", "301号室");
        data.put("備考", "テスト備考");

        // when
        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        // then: 結果を docx として再パース
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            String body = collectText(doc);

            // 期待される置換結果が含まれている
            assertThat(body)
                    .contains("MLIT_STANDARD_2024")
                    .contains("2024.1")
                    .contains("サンプル下書き")
                    .contains("2026/05/09 10:00")
                    .contains("山田太郎")
                    .contains("サンプルマンション")
                    .contains("東京都新宿区西新宿1-1-1")
                    .contains("301号室")
                    .contains("テスト備考");

            // プレースホルダーが残っていないこと
            assertThat(body).doesNotContain("${物件名}");
            assertThat(body).doesNotContain("${templateCode}");
            assertThat(body).doesNotContain("${outputDate}");
        }
    }

    @Test
    @DisplayName("fillTemplate(): data に存在しないキーは ${key} のまま残る（誤データ消失防止）")
    void fillTemplate_keepsMissingKeys() throws Exception {
        byte[] template = WordTemplateGenerator.buildAll().get("TOKYO_2024");

        // 一部キーのみ提供
        Map<String, Object> data = Map.of(
                "templateCode", "TOKYO_2024",
                "物件名", "東京テストマンション");

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            String body = collectText(doc);
            assertThat(body).contains("TOKYO_2024");
            assertThat(body).contains("東京テストマンション");
            // 未提供のキーは元の ${...} 表記が残る
            assertThat(body).contains("${所在地}");
            assertThat(body).contains("${部屋番号}");
        }
    }

    @Test
    @DisplayName("generate(): ドラフト + テンプレートからプレースホルダーが置換される")
    void generate_fromDraftAndTemplate() throws Exception {
        DisclosureFormTemplateEntity template = template("MLIT_STANDARD_2024", "2024.1");
        DisclosureFormDraftEntity draft = draft("{\"物件名\":\"テスト物件\","
                + "\"所在地\":\"東京都港区\",\"部屋番号\":\"101\",\"備考\":\"特記事項なし\"}");

        // テンプレ docx を main resources に未配置の前提でも generate がフォールバックする
        // が、resources/docx/disclosure/MLIT_STANDARD_2024.docx が配置されていれば
        // それを優先的に使う。フォールバック / テンプレ利用のどちらでも、formData の値が
        // 出力に含まれることを期待する。
        byte[] result = service.generate(draft, template);
        assertThat(result).isNotEmpty();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            String body = collectText(doc);
            assertThat(body)
                    .contains("テスト物件")
                    .contains("東京都港区")
                    .contains("101")
                    .contains("特記事項なし");
        }
    }

    @Test
    @DisplayName("generate(): null draft は DISCLOSURE_004")
    void generate_nullDraftThrows() {
        DisclosureFormTemplateEntity template = template("MLIT_STANDARD_2024", "2024.1");
        assertThatThrownBy(() -> service.generate(null, template))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("generate(): null template は DISCLOSURE_004")
    void generate_nullTemplateThrows() {
        DisclosureFormDraftEntity draft = draft("{}");
        assertThatThrownBy(() -> service.generate(draft, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("buildAll(): 6 種のテンプレが全て生成される（テンプレ生成ヘルパーの動作確認）")
    void wordTemplateGenerator_buildAll_returnsAllTemplates() throws Exception {
        Map<String, byte[]> all = WordTemplateGenerator.buildAll();
        assertThat(all).containsKeys(
                "MLIT_STANDARD_2024",
                "TOKYO_2024",
                "KANAGAWA_2024",
                "OSAKA_2024",
                "AICHI_2024",
                "FUKUOKA_2024");
        for (Map.Entry<String, byte[]> entry : all.entrySet()) {
            assertThat(entry.getValue())
                    .as("docx for %s", entry.getKey())
                    .isNotEmpty();
            // PK ZIP マジックナンバー
            assertThat(entry.getValue()[0]).isEqualTo((byte) 0x50);
            assertThat(entry.getValue()[1]).isEqualTo((byte) 0x4B);
        }
    }

    // ===== ヘルパー =====

    private static String collectText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            sb.append(p.getText()).append('\n');
        }
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                row.getTableCells().forEach(cell -> sb.append(cell.getText()).append('\n'));
            }
        }
        return sb.toString();
    }

    private DisclosureFormTemplateEntity template(String code, String version) {
        DisclosureFormTemplateEntity e = DisclosureFormTemplateEntity.builder()
                .code(code).name("テスト様式").version(version)
                .isSystemTemplate(true).isStandard(true)
                .formSchema("{\"sections\":[]}").isActive(true).build();
        try {
            setBaseEntityId(e, 1L);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private DisclosureFormDraftEntity draft(String formData) {
        DisclosureFormDraftEntity e = DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION").scopeId(100L)
                .templateId(1L).templateVersionSnapshot("2024.1")
                .title("テストドラフト").formData(formData)
                .status(DraftStatus.DRAFT).createdBy(1L)
                .build();
        try {
            setBaseEntityId(e, 10L);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private static void setBaseEntityId(Object entity, Long id) throws Exception {
        Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }
}
