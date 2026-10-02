package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("時間制限がプリエンプティブであること")
    class PreemptiveTimeout {

        /** 本検体で使う上限。実行時間を縮めるため本番の 30 秒ではなく 2 秒にする。 */
        private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

        /** スレッドの停止を待つ上限。 */
        private static final Duration STOP_DEADLINE = Duration.ofSeconds(20);


        /**
         * メモリを消費せずに「長時間終わらない正規表現走査」を作る合成入力。
         *
         * <p><b>なぜ「破滅的バックトラックする正規表現」を検体にしないのか</b>: 当初は
         * {@code (a+)+$} 等の古典的な検体を書いたが、<b>この JDK では一切爆発しなかった</b>
         * （{@code (a+)+$} / {@code (a|aa)+$} / {@code (x+x+)+y} / {@code ^(\\w+\\s?)+$} を
         * n=16〜45 で実測。いずれも数十 ms で完了）。Java 9 以降の正規表現最適化による。
         * 「この正規表現は爆発するはずだ」という前提に乗った検体は、<b>JDK が変わると黙って
         * 無検査になる</b>——まさに本番人が防ごうとしている失敗の形そのものである。</p>
         *
         * <p>そこで前提を「爆発すること」ではなく「<b>長い走査であること</b>」に置き換える。
         * {@code length()} を {@link Integer#MAX_VALUE} にして全文字を {@code 'a'} で供給すれば、
         * {@code "b"} を探す走査は 21 億回の {@code charAt} を要し、確実に数十秒級になる。
         * 実体を持たないのでメモリは使わない。JDK の最適化に依存しない。</p>
         */
        private static final class EndlessCharSequence implements CharSequence {
            @Override
            public int length() {
                return Integer.MAX_VALUE;
            }

            @Override
            public char charAt(int index) {
                return 'a';
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                throw new UnsupportedOperationException("本検体では部分列を取らない");
            }

            @Override
            public String toString() {
                return "EndlessCharSequence";
            }
        }

        /** {@code 'a'} だけの入力からは決して見つからない＝走査が最後まで走り切るパターン。 */
        private static final Pattern NEVER_MATCHES = Pattern.compile("b");

        /**
         * <b>割り込みに応答しない処理でも打ち切れること</b>の回帰テスト。
         *
         * <p>前版の検体は {@code Thread.sleep} を注入していたが、{@code sleep} は割り込みに応答するため
         * <b>本来の発生条件を再現していなかった</b>（Codex 検分の指摘）。Java の正規表現走査は
         * 割り込み状態を見ないので、{@code assertTimeoutPreemptively} だけでは走査スレッドが
         * 生き残って CPU を焼き続ける。</p>
         *
         * <p>ここでは長時間終わらない正規表現走査を注入し、<b>①上限で打ち切られること</b>と
         * <b>②走査スレッドが実際に停止すること</b>の両方を確かめる。②を確かめないと
         * 「テストが赤くなっただけでスレッドは焼き続けている」状態を見逃す。</p>
         *
         * <p><b>素の入力なら止まらないことの実測</b>: 同じ走査をラッパ無しで走らせて割り込むと、
         * 2.5 秒後もスレッドは生きていた。ラッパ付きでは割り込みから <b>3 ms</b> で停止した
         * （JDK 標準の {@code Pattern} で計測）。ここで素の側を回帰テストに含めないのは、
         * 止まらないスレッドを CPU を焼いたままテスト JVM に残すことになるためである。</p>
         */
        @Test
        @DisplayName("割り込みに応答しない正規表現走査でも、上限で打ち切られ、スレッドが実際に停止する")
        void nonInterruptibleRegexScanIsCutOffAndThreadActuallyStops() throws InterruptedException {
            AtomicReference<Thread> scanThread = new AtomicReference<>();
            long startNanos = System.nanoTime();

            assertThatThrownBy(() -> FlywayMigrationTimeFunctionGuardTest.scanWithinTimeout(
                    PROBE_TIMEOUT, () -> {
                        scanThread.set(Thread.currentThread());
                        // 入力を割り込み可能なラッパで包むのが是正の本体。
                        // 包まずに渡すと、この find() は割り込みを無視して走り続ける（上記の実測）。
                        Matcher m = NEVER_MATCHES.matcher(
                                FlywayMigrationTimeFunctionGuardTest.interruptible(new EndlessCharSequence()));
                        m.find();
                        return List.of();
                    })).isInstanceOf(AssertionError.class);

            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            assertThat(elapsedMillis)
                    .as("上限 %d ms で打ち切られるはずが %d ms かかった", PROBE_TIMEOUT.toMillis(), elapsedMillis)
                    .isLessThan(PROBE_TIMEOUT.toMillis() + STOP_DEADLINE.toMillis());

            assertThreadStops(scanThread.get(), STOP_DEADLINE);
        }

        /**
         * <b>本番の {@link Matcher} 生成</b>が割り込みに応答することの回帰テスト。
         *
         * <p>上の検体はラッパ単体の仕組みを確かめるもので、本番の走査がその仕組みを通しているかは
         * 保証しない。ここでは本番が使う唯一の生成入口
         * {@code FlywayMigrationTimeFunctionGuardTest#sessionTzNowMatcher} へ、<b>1 ファイル分の入力として</b>
         * 終わらない合成入力を流し込む。{@code sessionTzNowMatcher} から {@code interruptible} を外すと
         * この走査は割り込みを無視し、スレッドが停止しないので落ちる。</p>
         *
         * <h4>前版の検体を捨てた理由（Codex 検分の指摘）</h4>
         * <p>前版は実物の migration 走査を 300 ms で打ち切る形だった。これは2つの意味で誤りである。</p>
         * <ol>
         *   <li><b>実行速度に依存していた</b>。速い CI ホストや暖まったキャッシュで走査が 300 ms 以内に
         *       終わると {@code AssertionError} が起きず、テストが偽陽性で落ちる。番人テストが不安定に
         *       なると無関係な全 PR の CI を揺らす。</li>
         *   <li><b>主張を分離できていなかった</b>。matcher のラッパを外しても、{@code collectViolations} の
         *       <b>ファイル境界</b>にある割り込み検査で次のファイルへ進む際に停止してしまうため、
         *       「本番の matcher が割り込みに応答すること」を確かめたことにならない。</li>
         * </ol>
         * <p>本版は 1 ファイル内の {@code find()} が<b>決して終わらない</b>ので、ファイル境界の検査には
         * 到達せず、機械の速さにも依存しない（{@link EndlessCharSequence} は
         * {@code Integer.MAX_VALUE} 文字すべてが {@code 'a'} で、検出パターンのどの別名にも一致しない）。</p>
         */
        @Test
        @DisplayName("本番の matcher 生成も割り込みに応答する（1ファイル分の終わらない入力を本番経路へ流す）")
        void productionMatcherRespondsToInterrupt() throws InterruptedException {
            AtomicReference<Thread> scanThread = new AtomicReference<>();

            assertThatThrownBy(() -> FlywayMigrationTimeFunctionGuardTest.scanWithinTimeout(
                    PROBE_TIMEOUT, () -> {
                        scanThread.set(Thread.currentThread());
                        // 本番と同じ生成入口を使う。ここがラッパを通していなければ止まらない。
                        Matcher m = FlywayMigrationTimeFunctionGuardTest.sessionTzNowMatcher(
                                new EndlessCharSequence());
                        m.find();
                        return List.of();
                    })).isInstanceOf(AssertionError.class);

            assertThreadStops(scanThread.get(), STOP_DEADLINE);
        }

        private void assertThreadStops(Thread worker, Duration stopDeadline) throws InterruptedException {
            assertThat(worker).as("走査スレッドを捕捉できていない（検体の前提が壊れている）").isNotNull();

            long deadlineNanos = System.nanoTime() + stopDeadline.toNanos();
            while (worker.isAlive() && System.nanoTime() < deadlineNanos) {
                Thread.sleep(50);
            }

            assertThat(worker.isAlive())
                    .as("""
                        走査スレッド（%s）が打ち切り後も生きている。
                        assertTimeoutPreemptively は割り込みを送るだけで、Java の正規表現走査は
                        割り込みを見ないため、入力を InterruptibleCharSequence で包まないと
                        スレッドが CPU を焼き続ける。テストが赤くなるだけでは不十分である。""",
                            worker.getName())
                    .isFalse();
        }

        @Test
        @DisplayName("上限内に終わる走査は、そのまま結果を返す（打ち切りが誤爆しない）")
        void fastScanReturnsNormally() {
            assertThat(FlywayMigrationTimeFunctionGuardTest.scanWithinTimeout(PROBE_TIMEOUT, () -> scan(
                    "UPDATE t SET a_at = NOW();"))).hasSize(1);
        }

        @Test
        @DisplayName("割り込みされていなければ、ラッパは走査を妨げず、割り込みフラグも消さない")
        void wrapperDoesNotDisturbNormalScan() {
            Matcher m = Pattern.compile("a+").matcher(
                    FlywayMigrationTimeFunctionGuardTest.interruptible("aaa"));
            assertThat(m.find()).isTrue();
            assertThat(m.group()).isEqualTo("aaa");
            assertThat(Thread.currentThread().isInterrupted())
                    .as("ラッパは割り込みフラグを消してはならない（Thread.interrupted() を使わない理由）")
                    .isFalse();
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
