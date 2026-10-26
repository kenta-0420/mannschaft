package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ────────────────────────────────────────────────────────────
    // 凍結件数スナップショット照合（freezeCountMismatches）が増減を実際に検知することの実証
    // ────────────────────────────────────────────────────────────

    private static Map<DateTimeAndZoneGuardTest.Category, Integer> allCounts(int noArgNow, int zoneSystemDefault,
            int zoneLiteral, int localDateTimeField) {
        Map<DateTimeAndZoneGuardTest.Category, Integer> m =
                new EnumMap<>(DateTimeAndZoneGuardTest.Category.class);
        m.put(DateTimeAndZoneGuardTest.Category.NO_ARG_NOW, noArgNow);
        m.put(DateTimeAndZoneGuardTest.Category.ZONE_SYSTEM_DEFAULT, zoneSystemDefault);
        m.put(DateTimeAndZoneGuardTest.Category.ZONE_LITERAL, zoneLiteral);
        m.put(DateTimeAndZoneGuardTest.Category.LOCAL_DATE_TIME_FIELD, localDateTimeField);
        return m;
    }

    @Test
    @DisplayName("凍結件数が記録スナップショットと完全一致する場合はミスマッチなし")
    void freezeCountMismatches_noDiff_whenExactMatch() {
        Map<DateTimeAndZoneGuardTest.Category, Integer> snapshot = allCounts(10, 10, 10, 10);
        assertThat(DateTimeAndZoneGuardTest.freezeCountMismatches(snapshot, snapshot)).isEmpty();
    }

    @Test
    @DisplayName("凍結件数が1件でも増えたらミスマッチとして検出する（増加=禁止の実証）")
    void freezeCountMismatches_detectsIncrease() {
        Map<DateTimeAndZoneGuardTest.Category, Integer> snapshot = allCounts(10, 10, 10, 10);
        // ZONE_SYSTEM_DEFAULT だけ凍結ファイルへ1行こっそり追記された想定（新規違反の凍結による誤魔化し）。
        Map<DateTimeAndZoneGuardTest.Category, Integer> actualAfterSneakyAppend = allCounts(10, 11, 10, 10);

        List<String> mismatches = DateTimeAndZoneGuardTest.freezeCountMismatches(actualAfterSneakyAppend, snapshot);

        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0))
                .contains("ZONE_SYSTEM_DEFAULT")
                .contains("実件数=11")
                .contains("記録スナップショット=10")
                .contains("増加=禁止");
    }

    @Test
    @DisplayName("凍結件数が減った場合もミスマッチとして検出する（要追随更新の実証）")
    void freezeCountMismatches_detectsDecrease() {
        Map<DateTimeAndZoneGuardTest.Category, Integer> snapshot = allCounts(10, 10, 10, 10);
        // 是正が進み NO_ARG_NOW が1件減った想定（chip-away）。
        Map<DateTimeAndZoneGuardTest.Category, Integer> actualAfterFix = allCounts(9, 10, 10, 10);

        List<String> mismatches = DateTimeAndZoneGuardTest.freezeCountMismatches(actualAfterFix, snapshot);

        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0))
                .contains("NO_ARG_NOW")
                .contains("実件数=9")
                .contains("記録スナップショット=10")
                .contains("減少=要追随更新");
    }

    // ────────────────────────────────────────────────────────────
    // クラス単位の凍結件数比較（classCountMismatches）— PR #2725 の事故（メソッド名変更で
    // CI が赤くなった）の再発防止。凍結キーがクラス単位の件数であり、メソッド名を含まないことを
    // 直接実証する。
    // ────────────────────────────────────────────────────────────

    private static Map<String, Integer> counts(Object... fqcnAndCountPairs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < fqcnAndCountPairs.length; i += 2) {
            m.put((String) fqcnAndCountPairs[i], (Integer) fqcnAndCountPairs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("あるクラスの検出件数が1件増えたら新規違反として検出する（番人の本質の実証）")
    void classCountMismatches_detectsIncreaseWithinRegisteredClass() {
        Map<String, Integer> frozen = counts("com.example.FooService", 3);
        Map<String, Integer> actual = counts("com.example.FooService", 4); // 新規に1件増えた

        List<String> mismatches = DateTimeAndZoneGuardTest.classCountMismatches(actual, frozen);

        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0))
                .contains("com.example.FooService")
                .contains("実測4件")
                .contains("台帳3件")
                .contains("新規違反1件")
                .contains("禁止");
    }

    @Test
    @DisplayName("未登録のクラスで違反を検出したら新規クラスでの違反として検出する")
    void classCountMismatches_detectsViolationInUnregisteredClass() {
        Map<String, Integer> frozen = counts("com.example.FooService", 3);
        Map<String, Integer> actual = counts("com.example.FooService", 3, "com.example.NewOffenderService", 2);

        List<String> mismatches = DateTimeAndZoneGuardTest.classCountMismatches(actual, frozen);

        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0))
                .contains("com.example.NewOffenderService")
                .contains("2件")
                .contains("台帳未登録");
    }

    @Test
    @DisplayName("実コードから消えたクラスは陳腐化エントリとして検出する（chip-awayの促進）")
    void classCountMismatches_detectsStaleClassEntry() {
        Map<String, Integer> frozen = counts("com.example.FooService", 3, "com.example.RemediatedService", 1);
        Map<String, Integer> actual = counts("com.example.FooService", 3); // RemediatedServiceは是正済みで消えた

        List<String> mismatches = DateTimeAndZoneGuardTest.classCountMismatches(actual, frozen);

        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0))
                .contains("com.example.RemediatedService")
                .contains("陳腐化")
                .contains("chip-away");
    }

    @Test
    @DisplayName("完全一致する場合はミスマッチなし")
    void classCountMismatches_noDiff_whenExactMatch() {
        Map<String, Integer> counts = counts("com.example.FooService", 3, "com.example.BarService", 1);
        assertThat(DateTimeAndZoneGuardTest.classCountMismatches(counts, counts)).isEmpty();
    }

    /**
     * PR #2725 の事故そのものの再発防止テスト。{@code RecommendationService#getRecommendations}
     * のメソッド名変更だけで CI が赤くなった。同種のリファクタ（メソッド名変更・メソッド分割の
     * 名前替え）をクラス単位の件数だけで見た場合、<b>件数が変わらなければ何も検出されない</b>
     * ことを、実際のスキャン関数（{@link DateTimeAndZoneGuardTest#collectViolationsInFile}）に
     * 2種類の合成ソース（メソッド名だけが違う）を通して直接実証する。
     */
    @Test
    @DisplayName("同一クラス内でメソッド名だけを変更してもクラス単位の検出件数は変わらない（PR #2725の事故の再発防止）")
    void methodRenameDoesNotChangeClassLevelCount() {
        String before = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                class SyntheticSample {
                    LocalDateTime getRecommendations() {
                        return LocalDateTime.now();
                    }
                }
                """;
        String afterRename = """
                package com.mannschaft.app.example;
                import java.time.LocalDateTime;
                class SyntheticSample {
                    LocalDateTime fetchRecommendations() {
                        return LocalDateTime.now();
                    }
                }
                """;

        long countBefore = DateTimeAndZoneGuardTest.collectViolationsInFile(before, FQCN).stream()
                .filter(v -> v.category() == DateTimeAndZoneGuardTest.Category.NO_ARG_NOW)
                .count();
        long countAfter = DateTimeAndZoneGuardTest.collectViolationsInFile(afterRename, FQCN).stream()
                .filter(v -> v.category() == DateTimeAndZoneGuardTest.Category.NO_ARG_NOW)
                .count();

        assertThat(countBefore).isEqualTo(1);
        assertThat(countAfter).isEqualTo(countBefore);

        // クラス単位の凍結件数比較でも、メソッド名変更だけでは何も検出されないことを実証する。
        Map<String, Integer> frozen = counts(FQCN, (int) countBefore);
        Map<String, Integer> actualAfterRename = counts(FQCN, (int) countAfter);
        assertThat(DateTimeAndZoneGuardTest.classCountMismatches(actualAfterRename, frozen)).isEmpty();
    }
}
