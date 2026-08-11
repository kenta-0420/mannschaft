package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DateTimeAndZoneGuardTest} の走査ロジックそのものの正しさを固定する回帰テスト。
 *
 * <h2>なぜ要るか（CMP-022 第二波の教訓）</h2>
 * <p>CMP-022 の監査で、Java ソースを走査する番人群にブロックコメント・文字列リテラル・
 * テキストブロックの誤認という同型の欠陥が繰り返し見つかった。「番人が緑になった」だけでは
 * 「本来検出すべきものを実際に検出する」ことの証明にならない（検出器自身が自分の偽陰性を
 * 最初に晒すべき、という教訓）。本クラスは合成ソース（実際の production ファイルには置かない）
 * に対して直接スキャン関数を呼び、次を実証する。</p>
 * <ul>
 *   <li>4種の違反パターンそれぞれを実際に検出できること（検出力の実証。issue #2700 受け入れ条件1）</li>
 *   <li>行コメント・ブロックコメント・文字列リテラル・テキストブロックの<b>中</b>に同じ文字面が
 *       あっても誤検出しないこと（CMP-022と同型の欠陥を踏んでいないことの実証。受け入れ条件4）</li>
 *   <li>フィールド宣言とローカル変数／メソッド引数を区別できること（フィールドのみ検出対象。
 *       受け入れ条件4）</li>
 * </ul>
 */
@DisplayName("DateTimeAndZoneGuardTest の走査ロジックの正しさ（検出力＋誤検出耐性）")
class DateTimeAndZoneGuardScanningLogicTest {

    private static final String FQCN = "com.mannschaft.app.example.SyntheticSample";

    // ────────────────────────────────────────────────────────────
    // 検出力の実証（本来検出すべきものを実際に検出する）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("引数なしLocalDateTime.now()/LocalDate.now()/LocalTime.now()を検出する")
    void detectsNoArgNow() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                import java.time.LocalDate;
                import java.time.LocalTime;
                class SyntheticSample {
                    void touch() {
                        LocalDateTime a = LocalDateTime.now();
                        LocalDate b = LocalDate.now();
                        LocalTime c = LocalTime.now();
                    }
                }
                """;
        List<DateTimeAndZoneGuardTest.Violation> found =
                DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN);

        assertThat(found)
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.NO_ARG_NOW)
                .hasSize(3);
    }

    @Test
    @DisplayName("ZoneId.systemDefault()を検出する")
    void detectsZoneSystemDefault() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.ZoneId;
                class SyntheticSample {
                    void touch() {
                        ZoneId z = ZoneId.systemDefault();
                    }
                }
                """;
        List<DateTimeAndZoneGuardTest.Violation> found =
                DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN);

        assertThat(found)
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.ZONE_SYSTEM_DEFAULT)
                .hasSize(1);
    }

    @Test
    @DisplayName("ZoneId.of(タイムゾーンリテラル)を検出する")
    void detectsZoneLiteral() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.ZoneId;
                class SyntheticSample {
                    void touch() {
                        ZoneId z = ZoneId.of("Asia/Tokyo");
                    }
                }
                """;
        List<DateTimeAndZoneGuardTest.Violation> found =
                DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN);

        assertThat(found)
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.ZONE_LITERAL)
                .hasSize(1);
    }

    @Test
    @DisplayName("LocalDateTime型のフィールド宣言を検出する")
    void detectsLocalDateTimeField() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                class SyntheticSample {
                    private LocalDateTime createdAt;
                    private final LocalDateTime updatedAt = null;
                }
                """;
        List<DateTimeAndZoneGuardTest.Violation> found =
                DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN);

        assertThat(found)
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.LOCAL_DATE_TIME_FIELD)
                .hasSize(2);
    }

    @Test
    @DisplayName("recordのLocalDateTime型コンポーネント(DTOプロパティ)を検出する")
    void detectsLocalDateTimeRecordComponent() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                record SyntheticSample(String name, LocalDateTime occurredAt) {
                }
                """;
        List<DateTimeAndZoneGuardTest.Violation> found =
                DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN);

        assertThat(found)
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.LOCAL_DATE_TIME_FIELD)
                .hasSize(1);
    }

    // ────────────────────────────────────────────────────────────
    // 誤検出耐性（コメント・文字列・テキストブロックの中身は違反ではない）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("行コメント中のLocalDateTime.now()は検出しない")
    void ignoresLineComment() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticSample {
                    void touch() {
                        // 古い実装は LocalDateTime.now() を使っていた
                        int x = 1;
                    }
                }
                """;
        assertThat(DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN)).isEmpty();
    }

    @Test
    @DisplayName("ブロックコメント/Javadoc中のLocalDateTime.now()やZoneId.systemDefault()は検出しない")
    void ignoresBlockCommentAndJavadoc() {
        String code = """
                package com.mannschaft.app.example;
                /**
                 * 禁止パターン例: LocalDateTime.now() や ZoneId.systemDefault() は使わないこと。
                 */
                class SyntheticSample {
                    /* ZoneId.of("Asia/Tokyo") もコメントの中 */
                    void touch() {
                        int x = 1;
                    }
                }
                """;
        assertThat(DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN)).isEmpty();
    }

    @Test
    @DisplayName("文字列リテラル中のLocalDateTime.now()は検出しない(now()/systemDefault()系)")
    void ignoresStringLiteralForNowAndSystemDefault() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticSample {
                    void touch() {
                        String msg = "LocalDateTime.now() と ZoneId.systemDefault() は禁止です";
                    }
                }
                """;
        assertThat(DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN)).isEmpty();
    }

    @Test
    @DisplayName("テキストブロック中の記述は検出しない（奇数個の生クォートで走査が暴走しないことも含む）")
    void ignoresTextBlockIncludingOddQuoteContent() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                class SyntheticSample {
                    void touch() {
                        String doc = \"""
                                12" のタイムゾーンは "Asia/Tokyo" とは無関係。
                                LocalDateTime.now() もここに書いても違反ではない。
                                \""";
                        LocalDateTime real = LocalDateTime.now();
                    }
                }
                """;
        List<DateTimeAndZoneGuardTest.Violation> found =
                DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN);

        // テキストブロックより後ろの本物の違反だけが検出され、
        // テキストブロック本体（奇数個の生クォートを含む）に走査が引きずられて
        // 後続コードごとマスクされてしまう(=見逃す)ことがないのを実証する。
        assertThat(found)
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.NO_ARG_NOW)
                .hasSize(1);
    }

    // ────────────────────────────────────────────────────────────
    // フィールド vs ローカル変数／メソッド引数の区別
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("メソッド内のローカル変数宣言はフィールド違反として検出しない")
    void doesNotFlagLocalVariableAsField() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                class SyntheticSample {
                    void touch(LocalDateTime instant) {
                        LocalDateTime local = instant;
                    }
                }
                """;
        assertThat(DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN))
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.LOCAL_DATE_TIME_FIELD)
                .isEmpty();
    }

    @Test
    @DisplayName("コンストラクタ引数はフィールド違反として検出しない")
    void doesNotFlagConstructorParameterAsField() {
        String code = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                class SyntheticSample {
                    private final String name;
                    public SyntheticSample(String name, LocalDateTime touchedAt) {
                        this.name = name;
                    }
                }
                """;
        assertThat(DateTimeAndZoneGuardTest.collectViolationsInFile(code, FQCN))
                .filteredOn(v -> v.category() == DateTimeAndZoneGuardTest.Category.LOCAL_DATE_TIME_FIELD)
                .isEmpty();
    }
}
