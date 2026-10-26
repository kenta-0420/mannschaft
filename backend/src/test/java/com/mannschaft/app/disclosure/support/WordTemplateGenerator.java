package com.mannschaft.app.disclosure.support;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * F09.14 Phase 3-B 用の Word テンプレート docx を生成するワンショットツール。
 *
 * <p>用途: {@code src/main/resources/docx/disclosure/} および
 * {@code src/test/resources/docx/disclosure/} 配下に 6 種の docx 雛形を物理出力する。</p>
 *
 * <p>実行方法:
 * <pre>
 *   ./gradlew test --tests com.mannschaft.app.disclosure.support.WordTemplateGenerator
 * </pre>
 * ではなく、main メソッドとして起動する。Gradle から動かす場合は:
 * <pre>
 *   ./gradlew compileTestJava
 *   java -cp "build/classes/java/main:build/classes/java/test:..." \
 *        com.mannschaft.app.disclosure.support.WordTemplateGenerator
 * </pre>
 *
 * <p>本クラス自体はテスト本体ではなく、テンプレート初期生成のためのオフラインツール。
 * 通常のビルドサイクルでは実行されない（出力先のファイルが既にコミットされていれば
 * 再生成不要）。Phase 4 以降で正式な国交省/都道府県書式 docx に置き換えること。</p>
 */
public final class WordTemplateGenerator {

    private WordTemplateGenerator() {
    }

    /** テンプレート定義: code → タイトル / 作成元名称。 */
    private static final List<TemplateSpec> TEMPLATES = List.of(
            new TemplateSpec("MLIT_STANDARD_2024",
                    "重要事項説明書（参考）— 国土交通省 標準書式 2024",
                    "国土交通省（標準書式）"),
            new TemplateSpec("TOKYO_2024",
                    "重要事項説明書（参考）— 東京都 様式 2024",
                    "東京都"),
            new TemplateSpec("KANAGAWA_2024",
                    "重要事項説明書（参考）— 神奈川県 様式 2024",
                    "神奈川県"),
            new TemplateSpec("OSAKA_2024",
                    "重要事項説明書（参考）— 大阪府 様式 2024",
                    "大阪府"),
            new TemplateSpec("AICHI_2024",
                    "重要事項説明書（参考）— 愛知県 様式 2024",
                    "愛知県"),
            new TemplateSpec("FUKUOKA_2024",
                    "重要事項説明書（参考）— 福岡県 様式 2024",
                    "福岡県")
    );

    public static void main(String[] args) throws Exception {
        Path mainDir = Paths.get("src", "main", "resources", "docx", "disclosure");
        Path testDir = Paths.get("src", "test", "resources", "docx", "disclosure");
        Files.createDirectories(mainDir);
        Files.createDirectories(testDir);

        for (TemplateSpec spec : TEMPLATES) {
            byte[] docx = buildTemplate(spec);
            Path mainFile = mainDir.resolve(spec.code + ".docx");
            Path testFile = testDir.resolve(spec.code + ".docx");
            Files.write(mainFile, docx);
            Files.write(testFile, docx);
            System.out.println("Generated: " + mainFile + " (" + docx.length + " bytes)");
            System.out.println("Generated: " + testFile + " (" + docx.length + " bytes)");
        }
        System.out.println("Done. " + TEMPLATES.size() + " templates x 2 directories.");
    }

    /**
     * 1 件のテンプレート docx を組み立てる。
     * Apache POI XWPF で生成し、本文に {@code ${key}} プレースホルダーを埋め込む。
     */
    static byte[] buildTemplate(TemplateSpec spec) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {

            // タイトル
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText(spec.title);

            // 出力情報
            XWPFParagraph metaSrc = doc.createParagraph();
            XWPFRun metaSrcRun = metaSrc.createRun();
            metaSrcRun.setText("作成元: " + spec.issuer);

            XWPFParagraph dateP = doc.createParagraph();
            XWPFRun dateRun = dateP.createRun();
            dateRun.setText("出力日時: ${outputDate}");

            // 本書類は… 注意書き
            XWPFParagraph notice = doc.createParagraph();
            XWPFRun noticeRun = notice.createRun();
            noticeRun.setItalic(true);
            noticeRun.setText("本書類は管理組合が物件調査に応じて作成した参考情報です。"
                    + "実際の取引では宅地建物取引士による説明・記名押印が必須です。");

            // 区切り段落
            doc.createParagraph();

            // 基本情報テーブル（label / placeholder の 2 列）
            XWPFTable table = doc.createTable(8, 2);
            // 1行目をヘッダーにする
            setCellText(table.getRow(0).getCell(0), "項目", true);
            setCellText(table.getRow(0).getCell(1), "値", true);

            String[][] rows = {
                    {"様式コード",   "${templateCode}"},
                    {"様式バージョン", "${templateVersion}"},
                    {"ドラフト名",   "${draftTitle}"},
                    {"物件名",       "${物件名}"},
                    {"所在地",       "${所在地}"},
                    {"部屋番号",     "${部屋番号}"},
                    {"備考",         "${備考}"},
            };
            for (int i = 0; i < rows.length; i++) {
                XWPFTableRow row = table.getRow(i + 1);
                setCellText(row.getCell(0), rows[i][0], false);
                setCellText(row.getCell(1), rows[i][1], false);
            }

            // 末尾に署名欄プレースホルダー
            doc.createParagraph();
            XWPFParagraph footer = doc.createParagraph();
            XWPFRun footerRun = footer.createRun();
            footerRun.setText("出力者: ${outputUserName}");

            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void setCellText(XWPFTableCell cell, String text, boolean bold) {
        // POI は createTable 時にデフォルト段落 + run を持っていることがあるので、
        // 既存段落をクリアしてから新規追加する。
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        XWPFRun run = p.createRun();
        run.setBold(bold);
        run.setText(text);
    }

    /** テンプレート仕様。 */
    record TemplateSpec(String code, String title, String issuer) {
    }

    /** 任意キー → docx バイナリの Map を返す（ユニットテスト等から呼び出し可能）。 */
    public static Map<String, byte[]> buildAll() throws Exception {
        java.util.LinkedHashMap<String, byte[]> result = new java.util.LinkedHashMap<>();
        for (TemplateSpec spec : TEMPLATES) {
            result.put(spec.code, buildTemplate(spec));
        }
        return result;
    }
}
