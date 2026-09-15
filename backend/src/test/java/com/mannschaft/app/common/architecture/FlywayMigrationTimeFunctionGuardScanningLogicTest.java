package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FlywayMigrationTimeFunctionGuardTest} の<b>走査ロジック自体</b>を固定する回帰テスト。
 *
 * <p>番人は「何を検出し、何を検出しないか」が正しくて初めて意味を持つ。とりわけ本番人は
 * SQL のコメント・文字列リテラル・列 DEFAULT という 3 種類の<b>除外</b>を持つため、
 * 除外が広がりすぎると静かに偽陰性化する（＝番人が居るのに何も守らない）。
 * ここでは検体を直接与えて、検出側・非検出側の両方を実証する。</p>
 *
 * @see FlywayMigrationTimeFunctionGuardTest
 */
@DisplayName("走査ロジック検証: migration の「今」番人（CMP-260912-2258）")
class FlywayMigrationTimeFunctionGuardScanningLogicTest {

    private static List<FlywayMigrationTimeFunctionGuardTest.Violation> scan(String sql) {
        return FlywayMigrationTimeFunctionGuardTest.collectViolationsInFile(sql, "V1.001__test.sql");
    }

    @Nested
    @DisplayName("検出する（違反）")
    class Detects {

        @Test
        @DisplayName("INSERT の VALUES に書かれた NOW()")
        void insertValuesNow() {
            assertThat(scan("INSERT INTO permissions (name, created_at) VALUES ('X', NOW());"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("UPDATE の SET に書かれた NOW()")
        void updateSetNow() {
            assertThat(scan("UPDATE teams SET updated_at = NOW() WHERE id = 1;")).hasSize(1);
        }

        @Test
        @DisplayName("精度指定つきの NOW(6)")
        void nowWithPrecision() {
            assertThat(scan("UPDATE t SET a_at = NOW(6);")).hasSize(1);
        }

        @Test
        @DisplayName("大文字小文字・空白の揺れ（ now ( ) ）")
        void caseAndSpacingInsensitive() {
            assertThat(scan("UPDATE t SET a_at = now ( );")).hasSize(1);
        }

        @Test
        @DisplayName("裸の CURRENT_TIMESTAMP（DML 文脈）")
        void bareCurrentTimestamp() {
            assertThat(scan("UPDATE t SET a_at = CURRENT_TIMESTAMP;")).hasSize(1);
        }

        @Test
        @DisplayName("SYSDATE() と LOCALTIMESTAMP")
        void sysdateAndLocaltimestamp() {
            assertThat(scan("UPDATE t SET a_at = SYSDATE(), b_at = LOCALTIMESTAMP;")).hasSize(2);
        }

        @Test
        @DisplayName("同一ファイル内の複数箇所をすべて数える")
        void countsEveryOccurrence() {
            assertThat(scan("INSERT INTO t (a_at, b_at) VALUES (NOW(), NOW()), (NOW(), NOW());"))
                    .hasSize(4);
        }

        @Test
        @DisplayName("WHERE 句の比較に使われた NOW() も対象（基準がずれれば抽出範囲がずれるため）")
        void whereClauseNow() {
            assertThat(scan("DELETE FROM t WHERE expires_at < NOW();")).hasSize(1);
        }

        // ────────────────────────────────────────────────────────────
        // 日付系・時刻系の別名（Codex 検分の指摘 #1・初版の偽陰性）
        //
        // これらも等しくセッションの time_zone に従う。日時型だけを見ていると
        // 「DELETE ... WHERE target_date < CURDATE()」が素通りし、日単位でずれうる。
        // プロジェクト規約（backend/.claudecode.md）も CURDATE() を名指ししている。
        // ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("CURDATE()（規約が名指ししている日付系）")
        void curdate() {
            assertThat(scan("DELETE FROM t WHERE target_date < CURDATE();")).hasSize(1);
        }

        @Test
        @DisplayName("裸の CURRENT_DATE と CURRENT_DATE()")
        void currentDate() {
            assertThat(scan("UPDATE t SET d = CURRENT_DATE WHERE e = CURRENT_DATE();")).hasSize(2);
        }

        @Test
        @DisplayName("CURTIME() と裸の CURRENT_TIME")
        void curtimeAndCurrentTime() {
            assertThat(scan("UPDATE t SET a = CURTIME(), b = CURRENT_TIME;")).hasSize(2);
        }

        @Test
        @DisplayName("裸の LOCALTIME（LOCALTIMESTAMP の接頭辞でもある）")
        void localtime() {
            assertThat(scan("UPDATE t SET a_at = LOCALTIME;")).hasSize(1);
        }

        @Test
        @DisplayName("接頭辞関係のある別名が互いを食い合わず、それぞれ1件ずつ数えられる")
        void prefixOverlappingAliasesCountedOnce() {
            assertThat(scan("SELECT CURRENT_TIMESTAMP, CURRENT_TIME, LOCALTIMESTAMP, LOCALTIME;"))
                    .hasSize(4);
        }

        // ────────────────────────────────────────────────────────────
        // 名前と括弧の間に挟まりうるもの（Codex 検分の指摘 #1・2巡目の偽陰性）
        //
        // 初版・2版とも空白を \s{0,4} で吸っており、5文字以上は素通りしていた。
        // 可変長部分を正規表現から追い出したので、いまは任意長を受理する。
        // ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("名前と括弧の間の空白が5文字以上でも検出する")
        void longWhitespaceBeforeParenthesis() {
            assertThat(scan("DELETE FROM t WHERE d < CURDATE     ();")).hasSize(1);
        }

        @Test
        @DisplayName("名前と括弧の間にコメントが挟まっていても検出する（潰すと長い空白になる）")
        void commentBetweenNameAndParenthesis() {
            assertThat(scan("DELETE FROM t WHERE d < CURDATE /* なぜか注釈 */ ();")).hasSize(1);
        }

        @Test
        @DisplayName("名前と括弧の間に改行が挟まっていても検出する")
        void newlineBeforeParenthesis() {
            assertThat(scan("UPDATE t SET a_at = NOW\n      (\n        6\n      );")).hasSize(1);
        }

        @Test
        @DisplayName("列 DEFAULT の判定も空白が5文字以上で壊れない（逆向きの誤検出を作らない）")
        void columnDefaultWithLongWhitespaceStillExcluded() {
            assertThat(scan("CREATE TABLE t (a_at DATETIME NOT NULL DEFAULT       NOW());")).isEmpty();
        }

        @Test
        @DisplayName("ON UPDATE の判定も空白が5文字以上で壊れない")
        void onUpdateWithLongWhitespaceStillExcluded() {
            assertThat(scan("CREATE TABLE t (a_at DATETIME(6) NOT NULL "
                    + "DEFAULT CURRENT_TIMESTAMP(6) ON     UPDATE      CURRENT_TIMESTAMP(6));")).isEmpty();
        }
    }

    @Nested
    @DisplayName("検出しない（誤検出しない）")
    class DoesNotDetect {

        @Test
        @DisplayName("行コメント（--）の中の NOW()")
        void lineComment() {
            assertThat(scan("-- バッチは expires_at <= NOW() で絞り込む\nSELECT 1;")).isEmpty();
        }

        @Test
        @DisplayName("ブロックコメントの中の NOW()")
        void blockComment() {
            assertThat(scan("/* 旧実装では NOW() を使っていた */\nSELECT 1;")).isEmpty();
        }

        @Test
        @DisplayName("文字列リテラルの中の NOW()（COMMENT の説明文など）")
        void stringLiteral() {
            assertThat(scan(
                    "ALTER TABLE t ADD COLUMN next_attempt_at DATETIME(3) COMMENT '次回試行時刻 (enqueue 時=NOW())';"))
                    .isEmpty();
        }

        @Test
        @DisplayName("列 DEFAULT NOW() は射程外")
        void columnDefaultNow() {
            assertThat(scan("CREATE TABLE t (searched_at DATETIME NOT NULL DEFAULT NOW());")).isEmpty();
        }

        @Test
        @DisplayName("列 DEFAULT CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP は射程外")
        void columnDefaultCurrentTimestamp() {
            assertThat(scan("CREATE TABLE t (updated_at DATETIME(6) NOT NULL "
                    + "DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6));")).isEmpty();
        }

        @Test
        @DisplayName("正解である UTC_TIMESTAMP() は違反ではない")
        void utcTimestampIsCorrect() {
            assertThat(scan("UPDATE t SET a_at = UTC_TIMESTAMP() WHERE b_at < UTC_TIMESTAMP();")).isEmpty();
        }

        @Test
        @DisplayName("識別子の一部に now を含むだけの語（known_at / snowflake）")
        void identifierContainingNow() {
            assertThat(scan("UPDATE snowflake_ids SET known_at = UTC_TIMESTAMP();")).isEmpty();
        }

        @Test
        @DisplayName("「今」を一切含まない migration")
        void unrelatedMigration() {
            assertThat(scan("ALTER TABLE t ADD COLUMN memo VARCHAR(255) NULL;")).isEmpty();
        }

        // ────────────────────────────────────────────────────────────
        // 識別子の接頭辞に一致しないこと（Codex 検分の指摘 #2・初版の偽陽性）
        //
        // 括弧なしの別名は、末尾の境界が無いと通常のカラム名の接頭辞にも一致する。
        // その場合、セッション依存関数を1つも含まない migration が凍結件数の差分で
        // 赤くなり、無関係な作業を妨げる番人になってしまう。
        // ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("CURRENT_TIMESTAMP を接頭辞に持つカラム名は関数ではない")
        void identifierPrefixedByCurrentTimestamp() {
            assertThat(scan("ALTER TABLE t ADD COLUMN current_timestamp_format VARCHAR(32) NULL;"))
                    .isEmpty();
        }

        @Test
        @DisplayName("LOCALTIMESTAMP / LOCALTIME を接頭辞に持つカラム名は関数ではない")
        void identifierPrefixedByLocaltimestamp() {
            assertThat(scan("UPDATE t SET localtimestamp_backup = 1, localtime_zone = 'x';")).isEmpty();
        }

        @Test
        @DisplayName("CURRENT_DATE / CURRENT_TIME を接頭辞に持つカラム名は関数ではない")
        void identifierPrefixedByCurrentDate() {
            assertThat(scan("UPDATE t SET current_date_label = 'a', current_time_slot = 'b';")).isEmpty();
        }

        @Test
        @DisplayName("CURDATE / CURTIME を接頭辞に持つカラム名は関数ではない")
        void identifierPrefixedByCurdate() {
            assertThat(scan("UPDATE t SET curdate_cache = 1, curtime_cache = 2;")).isEmpty();
        }

        @Test
        @DisplayName("正解である UTC_DATE() / UTC_TIME() は違反ではない")
        void utcDateAndUtcTimeAreCorrect() {
            assertThat(scan("UPDATE t SET d = UTC_DATE(), ti = UTC_TIME();")).isEmpty();
        }

        // ────────────────────────────────────────────────────────────
        // 先頭側の境界（Codex 検分の指摘 #2・2巡目の偽陽性）
        //
        // MySQL の引用なし識別子は 0-9 a-z A-Z $ _ と U+0080 以上を許す。
        // 正規表現の \b は $ を単語構成文字と見なさないため、先頭境界を \b に
        // 委ねると audit$current_date の途中に一致してしまう。
        // 引用識別子（バッククォート）と修飾名（ドット）も同様に関数ではない。
        // ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("$ を含む識別子の途中に一致しない（\\b では守れない）")
        void identifierContainingDollarSign() {
            assertThat(scan("UPDATE t SET audit$current_date = 1, x$now = 2;")).isEmpty();
        }

        @Test
        @DisplayName("バッククォートで囲んだ引用識別子は関数ではない")
        void quotedIdentifier() {
            assertThat(scan("UPDATE t SET `current_date` = 1, `now` = 2;")).isEmpty();
        }

        @Test
        @DisplayName("テーブル修飾されたカラム名は関数ではない")
        void qualifiedColumnName() {
            assertThat(scan("UPDATE t SET x = 1 WHERE t.current_date > 0 AND t.now < 1;")).isEmpty();
        }

        @Test
        @DisplayName("括弧が必須の名前は、裸で書かれていれば関数ではない（単なる識別子）")
        void bareNameOfParenthesesRequiredFunction() {
            assertThat(scan("UPDATE t SET x = 1 WHERE now > 0 AND curdate < 1;")).isEmpty();
        }
    }

    @Nested
    @DisplayName("付随情報")
    class Metadata {

        @Test
        @DisplayName("行番号はコメント・リテラルを潰したあとも原文と一致する")
        void lineNumberMatchesOriginal() {
            String sql = """
                    -- 1行目のコメント
                    /* 2行目
                       3行目 */
                    UPDATE t SET a_at = NOW();
                    """;
            assertThat(scan(sql)).singleElement()
                    .extracting(FlywayMigrationTimeFunctionGuardTest.Violation::line)
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("違反の説明にファイル名・行番号・該当文字列が含まれる")
        void describeContainsContext() {
            assertThat(scan("UPDATE t SET a_at = NOW();").get(0).describe())
                    .contains("V1.001__test.sql").contains("L1").contains("NOW()");
        }
    }
}
