package com.mannschaft.app.common.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SqlTextScanningUtils} 自体の回帰ガード（CMP-022）。
 *
 * <p>ここで検証した性質は {@code MigrationPrimaryKeyConventionTest}・
 * {@code CrossDomainForeignKeyArchTest}・{@code MigrationCollationDeclarationGuardTest} の
 * 3 番人すべてが前提にしている。共通ユーティリティ側でこれらを守ることで、
 * 個々の番人にフィクスチャを重複して書かずに 3 番人分の欠陥再発を一度に防ぐ。
 */
class SqlTextScanningUtilsTest {

    @Test
    @DisplayName("行コメント内のCREATE TABLE等の言及は除去され本文として残らない")
    void lineCommentIsBlankedOut() {
        String sql = "-- CREATE TABLE foo は AUTO_INCREMENT を使わない\nCREATE TABLE bar (id INT);";
        String stripped = SqlTextScanningUtils.stripComments(sql);

        assertThat(stripped).doesNotContain("foo");
        assertThat(stripped).contains("CREATE TABLE bar");
    }

    @Test
    @DisplayName("ブロックコメント内のFOREIGN KEY等の言及は除去され本文として残らない")
    void blockCommentIsBlankedOut() {
        String sql = "/* 例: FOREIGN KEY (x) REFERENCES other_table (id) */\n"
            + "CREATE TABLE bar (id INT);";
        String stripped = SqlTextScanningUtils.stripComments(sql);

        assertThat(stripped).doesNotContain("other_table");
        assertThat(stripped).contains("CREATE TABLE bar");
    }

    @Test
    @DisplayName("複数行にまたがるブロックコメントも除去され改行数は保持される")
    void multilineBlockCommentPreservesLineCount() {
        String sql = "/* line1\nline2\nline3 */\nCREATE TABLE bar (id INT);";
        String stripped = SqlTextScanningUtils.stripComments(sql);

        long originalLines = sql.chars().filter(c -> c == '\n').count();
        long strippedLines = stripped.chars().filter(c -> c == '\n').count();
        assertThat(strippedLines).isEqualTo(originalLines);
        assertThat(stripped).doesNotContain("line1", "line2", "line3");
    }

    @Test
    @DisplayName("文字列リテラル内の--やCREATE TABLEはコメントや文として解釈されない")
    void stringLiteralContentIsNotMisinterpretedAsCommentOrStatement() {
        String sql = "CREATE TABLE bar (id INT, note VARCHAR(64) "
            + "DEFAULT '-- not a comment; CREATE TABLE evil');";
        String stripped = SqlTextScanningUtils.stripComments(sql);

        // 文字列リテラル自体は保持される（コメントとして食われていない）。
        assertThat(stripped).contains("-- not a comment; CREATE TABLE evil");
    }

    @Test
    @DisplayName("文字列リテラル内のセミコロンは文の区切りとして扱われない")
    void semicolonInsideStringLiteralDoesNotSplitStatement() {
        String sql = "CREATE TABLE bar (id INT, note VARCHAR(64) DEFAULT 'a;b') "
            + "ENGINE=InnoDB COLLATE=utf8mb4_0900_ai_ci;\n"
            + "CREATE TABLE baz (id INT);";
        List<String> statements = SqlTextScanningUtils.splitStatements(sql);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0))
            .contains("DEFAULT 'a;b'")
            .contains("COLLATE=utf8mb4_0900_ai_ci");
        assertThat(statements.get(1)).contains("CREATE TABLE baz");
    }

    @Test
    @DisplayName("引用符の二重化(''）でエスケープされたリテラル内のセミコロンで文が誤終端しない")
    void doubledQuoteEscapeDoesNotBreakStatementBoundary() {
        // DEFAULT 'don''t' は標準SQLで合法な引用符二重化エスケープ。
        // 修正前の skipQuoted は1つ目の ' を閉じ引用符と誤認し、続く t' を新たな
        // 開き引用符として扱うため、対応する閉じ引用符が見つからず文の境界が
        // ファイル末尾まで暴走していた（本テストは修正前なら失敗する）。
        String sql = "CREATE TABLE bar (id INT, note VARCHAR(64) DEFAULT 'don''t') "
            + "ENGINE=InnoDB COLLATE=utf8mb4_0900_ai_ci;\n"
            + "CREATE TABLE baz (id INT);";
        List<String> statements = SqlTextScanningUtils.splitStatements(sql);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0))
            .contains("DEFAULT 'don''t'")
            .contains("COLLATE=utf8mb4_0900_ai_ci");
        assertThat(statements.get(1)).contains("CREATE TABLE baz");
    }

    @Test
    @DisplayName("バックスラッシュエスケープと引用符二重化が混在しても文の境界を誤らない")
    void backslashEscapeStillWorksAlongsideDoubledQuoteEscape() {
        // 'a\\' … バックスラッシュでエスケープされた \ の後に閉じ引用符が続く形。
        // 二重化エスケープ対応を入れてもバックスラッシュエスケープの既存挙動を壊さないこと。
        String sql = "CREATE TABLE bar (id INT, note VARCHAR(64) DEFAULT 'a\\\\') "
            + "ENGINE=InnoDB COLLATE=utf8mb4_0900_ai_ci;\n"
            + "CREATE TABLE baz (id INT);";
        List<String> statements = SqlTextScanningUtils.splitStatements(sql);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(1)).contains("CREATE TABLE baz");
    }

    @Test
    @DisplayName("CREATE TEMPORARY TABLEの本体は空白化され後続の走査から除外される")
    void temporaryTableBodyIsBlankedOut() {
        String sql = "CREATE TABLE real_table (id INT);\n"
            + "CREATE TEMPORARY TABLE tmp_scratch (\n"
            + "  id INT,\n"
            + "  FOREIGN KEY (x) REFERENCES temp_only_parent (id)\n"
            + ");\n"
            + "CREATE TABLE another_real (id INT);";
        String blanked = SqlTextScanningUtils.blankOutTemporaryTables(sql);

        assertThat(blanked).doesNotContain("tmp_scratch", "temp_only_parent");
        assertThat(blanked).contains("CREATE TABLE real_table", "CREATE TABLE another_real");
    }

    @Test
    @DisplayName("一時表を空白化しても行数・全体の文字数は変化しない（offset計算がずれない）")
    void blankingOutTemporaryTablePreservesLength() {
        String sql = "CREATE TABLE real_table (id INT);\n"
            + "CREATE TEMPORARY TABLE tmp_scratch (id INT);\n"
            + "CREATE TABLE another_real (id INT);";
        String blanked = SqlTextScanningUtils.blankOutTemporaryTables(sql);

        assertThat(blanked).hasSize(sql.length());
        long originalLines = sql.chars().filter(c -> c == '\n').count();
        long blankedLines = blanked.chars().filter(c -> c == '\n').count();
        assertThat(blankedLines).isEqualTo(originalLines);
    }
}
