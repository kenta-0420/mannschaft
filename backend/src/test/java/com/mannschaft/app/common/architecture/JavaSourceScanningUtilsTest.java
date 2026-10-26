package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JavaSourceScanningUtils} 自体の回帰ガード（CMP-022 第二波）。
 *
 * <p>ここで検証した性質は {@code PagingTotalCountSizeGuardTest}・
 * {@code ErrorCodeHttpStatusDeclarationGuardTest}・{@code SecurityConfigRules} の
 * 3 番人すべてが前提にしている。共通ユーティリティ側でこれらを守ることで、
 * 個々の番人にフィクスチャを重複して書かずに欠陥再発を一度に防ぐ。
 *
 * <h2>是正した欠陥（実測）</h2>
 * <p>是正前の 3 番人はそれぞれ独自にコメント・文字列マスク処理を持っていたが、いずれも
 * <b>テキストブロック（{@code """ ... """}）を認識しない</b>単純な {@code "} トグル方式だった。
 * テキストブロック本文に奇数個の生クォート（{@code 12" tall} のような単位記号 1 個等）が
 * 含まれると、以降のクォート対応がずれ、<b>後続の実コードが「文字列の内側」と誤認されて
 * 丸ごとマスクされてしまう</b>（= 走査から消え、番人がそこにある違反を静かに見逃す）。
 * {@link #textBlockWithStrayQuoteDoesNotSwallowFollowingCode()} がこの欠陥の再発を防ぐ。</p>
 */
class JavaSourceScanningUtilsTest {

    @Test
    @DisplayName("行コメント内の言及は除去され本文として残らない")
    void lineCommentIsBlankedOut() {
        String src = "// new PageImpl<>(content, pageable, filtered.size()); は違反の例\n"
            + "int real = 1;";
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(src);

        assertThat(masked).doesNotContain("PageImpl");
        assertThat(masked).contains("int real = 1;");
    }

    @Test
    @DisplayName("ブロックコメント/Javadoc内の言及は除去され本文として残らない")
    void blockCommentIsBlankedOut() {
        String src = "/**\n * 例: new PageImpl<>(content, pageable, filtered.size());\n */\n"
            + "int real = 1;";
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(src);

        assertThat(masked).doesNotContain("PageImpl");
        assertThat(masked).contains("int real = 1;");
    }

    @Test
    @DisplayName("複数行にまたがるブロックコメントも除去され改行数は保持される")
    void multilineBlockCommentPreservesLineCount() {
        String src = "/* line1\nline2\nline3 */\nint real = 1;";
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(src);

        long originalLines = src.chars().filter(c -> c == '\n').count();
        long maskedLines = masked.chars().filter(c -> c == '\n').count();
        assertThat(maskedLines).isEqualTo(originalLines);
        assertThat(masked).doesNotContain("line1", "line2", "line3");
    }

    @Test
    @DisplayName("文字列リテラル内のコメント風/キーワード風の記述は解釈されない（maskCommentsAndLiterals）")
    void stringLiteralContentIsBlankedByFullMask() {
        String src = "String note = \"// not a comment; new PageImpl<>(a, b, c.size())\";\n"
            + "int real = 1;";
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(src);

        assertThat(masked).doesNotContain("PageImpl");
        assertThat(masked).contains("int real = 1;");
    }

    @Test
    @DisplayName("maskCommentsOnly は文字列リテラルの中身を保持する")
    void maskCommentsOnlyKeepsStringLiteralContent() {
        String src = "// comment\nString code = \"HttpStatus.NOT_FOUND\";";
        String masked = JavaSourceScanningUtils.maskCommentsOnly(src);

        assertThat(masked).doesNotContain("comment");
        assertThat(masked).contains("\"HttpStatus.NOT_FOUND\"");
    }

    @Test
    @DisplayName("退行防止(欠陥6): テキストブロック本文に奇数個の生クォートがあっても後続の実コードを飲み込まない（maskCommentsAndLiterals）")
    void textBlockWithStrayQuoteDoesNotSwallowFollowingCode() {
        // 是正前は "12\" tall" のような奇数個の生クォートを含むテキストブロックの直後で
        // クォート対応がずれ、後続の実コード（本物の違反）が「文字列の内側」として
        // 丸ごとマスクされ、番人から見えなくなっていた（偽陰性）。
        String src = "String note = \"\"\"\n"
            + "    inch mark: 12\" tall\n"
            + "    \"\"\";\n"
            + "Page<X> p = new PageImpl<>(content, pageable, filtered.size());\n";

        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(src);

        assertThat(masked)
            .as("テキストブロックの後に続く実コードはマスクされず走査対象として残るべき")
            .contains("new PageImpl<>(content, pageable, filtered.size());");
    }

    @Test
    @DisplayName("退行防止(欠陥6): maskCommentsOnly でもテキストブロック内の奇数クォートで後続コメントを誤認しない")
    void maskCommentsOnlyHandlesStrayQuoteInTextBlockToo() {
        String src = "String note = \"\"\"\n"
            + "    inch mark: 12\" tall\n"
            + "    \"\"\";\n"
            + "// このコメントは除去されるべき PageImpl\n"
            + "int real = 1;";

        String masked = JavaSourceScanningUtils.maskCommentsOnly(src);

        assertThat(masked).doesNotContain("PageImpl");
        assertThat(masked).contains("int real = 1;");
        // テキストブロックの中身自体は保持される（maskCommentsOnly の仕様）。
        assertThat(masked).contains("inch mark: 12\" tall");
    }

    @Test
    @DisplayName("文字列内のバックスラッシュエスケープで走査が暴走しない")
    void backslashEscapeDoesNotDerailScan() {
        String src = "String s = \"a\\\"b\";\n"
            + "// comment after\n"
            + "int real = 1;";
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(src);

        assertThat(masked).doesNotContain("comment after");
        assertThat(masked).contains("int real = 1;");
    }

    @Test
    @DisplayName("オフセット保持: マスク後も原文と同じ長さ・改行位置を保つ")
    void maskingPreservesLengthAndNewlines() {
        String src = "class Foo {\n    // comment\n    int x = 1;\n}\n";
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(src);

        assertThat(masked).hasSameSizeAs(src);
        long originalLines = src.chars().filter(c -> c == '\n').count();
        long maskedLines = masked.chars().filter(c -> c == '\n').count();
        assertThat(maskedLines).isEqualTo(originalLines);
    }
}
