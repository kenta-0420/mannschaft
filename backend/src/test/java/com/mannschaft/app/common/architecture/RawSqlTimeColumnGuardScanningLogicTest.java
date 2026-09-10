package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RawSqlTimeColumnGuardTest} の<b>走査ロジック自体</b>の正しさを固定する自己検証
 * （{@link DateTimeAndZoneGuardScanningLogicTest} と同型・CMP-260909-1446 AC-09）。
 *
 * <p>番人は「検出できていない」ことを自分では訴えない。コメント・文字列リテラル・テキストブロックの
 * 扱いを誤ると、実在する違反を静かに見逃す（偽陰性）か、無関係な記述を違反と誤認して
 * 是正を妨げる（偽陽性）。両方向を合成ソースに対する回帰テストで実証する。</p>
 */
@DisplayName("番人の走査ロジック自己検証: 生SQL時刻列ガードはコメント・文字列を誤認しない")
class RawSqlTimeColumnGuardScanningLogicTest {

    private static List<RawSqlTimeColumnGuardTest.Violation> scan(String source) {
        return RawSqlTimeColumnGuardTest.collectViolationsInFile(source, "com.example.Sample");
    }

    private static long count(String source, RawSqlTimeColumnGuardTest.Category category) {
        return scan(source).stream().filter(v -> v.category() == category).count();
    }

    // ────────────────────────────────────────────────────────────
    // 偽陽性を出さないこと（コメント・Javadoc・識別子）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("行コメント／ブロックコメント／Javadoc に書かれた NOW() は検出しない")
    void コメント内のNOWは検出しない() {
        String src = """
                package com.example;
                /**
                 * 既読: is_read = TRUE AND created_at < NOW() - 90日
                 */
                class Sample {
                    // UPDATE t SET served_at = NOW() は禁止である
                    /* created_at < ? も禁止 */
                    void f() {}
                }
                """;
        assertThat(scan(src)).as("コメント内の記述は 1 件も検出しない: %s", scan(src)).isEmpty();
    }

    @Test
    @DisplayName("Java 識別子・メソッド名としての currentTimestamp は検出しない（リテラル外だから）")
    void リテラル外のJavaコードは検出しない() {
        String src = """
                package com.example;
                class Sample {
                    void f() {
                        var currentTimestamp = clock.instant();
                        long created_at = 1L;
                        boolean b = created_at < 2;
                    }
                }
                """;
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.SQL_LOCAL_TIME_FUNCTION_IN_SQL)).isZero();
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.TIME_COLUMN_JAVA_BOUND)).isZero();
    }

    @Test
    @DisplayName("UTC_TIMESTAMP() は正解なので検出しない")
    void UTC_TIMESTAMPは検出しない() {
        String src = """
                package com.example;
                class Sample {
                    static final String SQL = "INSERT INTO t (created_at) VALUES (UTC_TIMESTAMP())";
                    JdbcTemplate jdbcTemplate;
                }
                """;
        assertThat(scan(src)).as("UTC_TIMESTAMP() は禁止対象ではない: %s", scan(src)).isEmpty();
    }

    @Test
    @DisplayName("生SQLを発行しないクラスの時刻ゲッターは検出しない（JPA 経路は正しい）")
    void 生SQLを発行しないクラスのゲッターは検出しない() {
        String src = """
                package com.example;
                class Sample {
                    void f(Entity e) {
                        var x = e.getCreatedAt();
                        var y = e.getUpdatedAt();
                    }
                }
                """;
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.RAW_SQL_ENTITY_TIME_GETTER)).isZero();
    }

    // ────────────────────────────────────────────────────────────
    // 偽陰性を出さないこと（実在する違反は必ず拾う）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("文字列リテラル中の NOW()／CURRENT_TIMESTAMP／SYSDATE() を検出する")
    void 文字列中のセッションTZ依存関数を検出する() {
        String src = """
                package com.example;
                class Sample {
                    String a = "UPDATE t SET served_at = NOW() WHERE id = ?";
                    String b = "SELECT CURRENT_TIMESTAMP";
                    String c = "SELECT SYSDATE()";
                }
                """;
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.SQL_LOCAL_TIME_FUNCTION_IN_SQL))
                .as("3 種すべて検出する").isEqualTo(3);
    }

    @Test
    @DisplayName("テキストブロック中の NOW() と時刻列束縛も検出する（三重クォートの取りこぼし防止）")
    void テキストブロック中も検出する() {
        String src = """
                package com.example;
                class Sample {
                    String sql = \"""
                            INSERT INTO t (name, updated_at)
                            VALUES (?, NOW())
                            ON DUPLICATE KEY UPDATE updated_at = NOW()
                            \""";
                    String where = \"""
                            SELECT 1 FROM t WHERE created_at < ?
                            \""";
                }
                """;
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.SQL_LOCAL_TIME_FUNCTION_IN_SQL))
                .as("テキストブロック内の NOW() を 2 件検出する").isEqualTo(2);
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.TIME_COLUMN_JAVA_BOUND))
                .as("テキストブロック内の updated_at = NOW() と created_at < ? のうち、"
                        + "プレースホルダ束縛は created_at < ? の 1 件").isEqualTo(1);
    }

    @Test
    @DisplayName("時刻列へのプレースホルダ束縛（?・:name）を比較演算子ごとに検出する")
    void 時刻列のプレースホルダ束縛を検出する() {
        String src = """
                package com.example;
                class Sample {
                    String a = "WHERE created_at < ?";
                    String b = "WHERE deleted_at >= ?";
                    String c = "SET served_at = :now";
                    String d = "WHERE archived_at <= ?";
                }
                """;
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.TIME_COLUMN_JAVA_BOUND)).isEqualTo(4);
    }

    @Test
    @DisplayName("生SQLを発行するクラスがエンティティの時刻ゲッターを読む箇所を検出する")
    void 生SQLクラスの時刻ゲッターを検出する() {
        String src = """
                package com.example;
                class Sample {
                    private final JdbcTemplate jdbcTemplate;
                    void f(Entity e) {
                        args[0] = e.getCreatedAt();
                    }
                }
                """;
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.RAW_SQL_ENTITY_TIME_GETTER)).isEqualTo(1);
    }

    @Test
    @DisplayName("テキストブロック本文の生クォートで以降の走査がずれない（偽陰性の温床）")
    void テキストブロック内の生クォートで走査がずれない() {
        String src = """
                package com.example;
                class Sample {
                    String note = \"""
                            12" tall という単位記号を含む
                            \""";
                    String sql = "WHERE created_at < ?";
                }
                """;
        assertThat(count(src, RawSqlTimeColumnGuardTest.Category.TIME_COLUMN_JAVA_BOUND))
                .as("テキストブロック後の SQL リテラルを取りこぼさない").isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────
    // 凍結件数判定（クラス単位）の増減検知
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("クラス単位件数が 1 件でも増えたら検出する（共有する判定ロジックの回帰）")
    void 件数増加を検出する() {
        assertThat(DateTimeAndZoneGuardTest.classCountMismatches(
                Map.of("com.example.A", 3), Map.of("com.example.A", 2)))
                .anyMatch(s -> s.startsWith("増加"));
        assertThat(DateTimeAndZoneGuardTest.classCountMismatches(
                Map.of("com.example.A", 2), Map.of("com.example.A", 2)))
                .isEmpty();
    }
}
