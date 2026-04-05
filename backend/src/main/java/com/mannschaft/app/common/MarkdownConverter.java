package com.mannschaft.app.common;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

/**
 * Markdown → HTML 変換ユーティリティ。
 * flexmark-java を使用し、Markdown テキストをサニタイズ済み HTML に変換する。
 */
public final class MarkdownConverter {

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();
    }

    private MarkdownConverter() {}

    /**
     * Markdown テキストを HTML に変換する。
     *
     * @param markdown Markdown テキスト（null の場合は空文字を返す）
     * @return 変換された HTML 文字列
     */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = PARSER.parse(markdown);
        return RENDERER.render(document);
    }
}
