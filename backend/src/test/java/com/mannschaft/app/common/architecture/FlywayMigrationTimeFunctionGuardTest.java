package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人（CMP-260912-2258）: <b>Flyway migration の DML が「今」をセッション TZ 依存の関数で書く</b>
 * 書き方が新規に増えないようにする。
 *
 * <h2>なぜ migration も射程に入れるのか</h2>
 * <p>既存の番人 {@link RawSqlTimeColumnGuardTest} と {@link DateTimeAndZoneGuardTest} は
 * <b>{@code src/main/java} の {@code .java} だけ</b>を走査する。したがって同型の欠陥が
 * {@code db/migration/*.sql} 経由で入っても<b>一切検出されない</b>。実際、調査時点で
 * migration 側には {@code NOW()} 系の DML が 65 ファイル・746 箇所あり、{@code UTC_TIMESTAMP()} は
 * 0 箇所だった。この穴を塞ぐのが本番人の役割である。</p>
 *
 * <h2>今ずれているわけではない — それでも禁じる理由【必読】</h2>
 * <p>本戦役の調査で、{@code NOW()} が返す値は<b>全環境で {@code UTC_TIMESTAMP()} と同値</b>であることを
 * 実測で確認した。MySQL の {@code NOW()} は<b>セッションの {@code time_zone}</b> に従い、本プロジェクトは
 * 全環境でそれを UTC に固定しているためである（local: {@code docker-compose.yml} の
 * {@code --default-time-zone=+00:00} / CI: ランナー UTC の {@code SYSTEM} / test: Testcontainers の
 * {@code mysql:8.0} が {@code SYSTEM}=UTC / prod: RDS パラメータ {@code time_zone=UTC}）。
 * JDBC の {@code serverTimezone=UTC} はドライバ側の解釈を決めるだけでセッション TZ を書き換えない
 * （Connector/J の {@code forceConnectionTimeZoneToSession} は既定 false）。
 * 実測値: dev MySQL で {@code TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW()) = 0}。</p>
 *
 * <p>つまり既存 746 箇所は<b>ずれていない</b>。ただしその正しさは「セッション TZ が UTC である」という
 * <b>外部設定への依存</b>の上に乗っており、設定が 1 つ崩れた瞬間に 746 箇所が同時に 9 時間ずれる。
 * {@code UTC_TIMESTAMP()} は設定に依らず無条件に UTC 壁時計を返すため、この依存そのものが消える。
 * 生 SQL 側（{@link RawSqlTimeColumnGuardTest}）が既に {@code UTC_TIMESTAMP()} を正としている以上、
 * migration だけ別の流儀を許す理由は無い。そこで<b>既存は凍結・新規は禁止</b>とする。</p>
 *
 * <p>「セッション TZ が UTC である」という前提そのものは
 * {@code FlywayMigrationSessionTimeZoneUtcIT} が実 MySQL 接続で固定する（本番人の対になる番人）。</p>
 *
 * <h2>列 DEFAULT / ON UPDATE を対象外にしたのは「見落とし」ではない【必読】</h2>
 * <p>{@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP} / {@code DEFAULT NOW()} は
 * DDL のカラム定義であり、調査時点で <b>598 ファイル・1558 箇所</b>に及ぶ。それだけの量がありながら本番人が
 * 一切見ていないのは<b>意図的な線引き</b>であって、走査漏れではない。理由の因果は次のとおり。</p>
 * <ol>
 *   <li><b>そもそもずれていない</b>。列既定の {@code CURRENT_TIMESTAMP} も上の {@code NOW()} と
 *       まったく同じ理屈で<b>セッションの {@code time_zone}</b> に従う。本プロジェクトはそれを全環境で
 *       UTC に固定しており（上表・実測済み）、したがって 1558 箇所も UTC 壁時計を書く。
 *       <b>実害のある課題ではなく、今後どう書かせるかという規約だけの話である。</b></li>
 *   <li><b>その前提は放置されていない</b>。「セッション {@code time_zone} が UTC である」ことは
 *       {@code FlywayMigrationSessionTimeZoneUtcIT} が実 MySQL 接続で実測し、CI の不変条件として守る。
 *       つまり 1558 箇所の正しさは<b>あちらの番人が担保しており</b>、本番人が重ねて見る必要がない。</li>
 *   <li><b>塞ごうとすると代償が釣り合わない</b>。MySQL の式 DEFAULT（{@code DEFAULT (UTC_TIMESTAMP())}）へ
 *       一括で倒すには<b>適用済み migration の書き換え</b>が要り、これは Flyway のチェックサム不一致を招いて
 *       全環境（dev / CI / 本番相当）の起動を止める。実害ゼロの案件でその代償は払えない。</li>
 * </ol>
 * <p>また実運用上、列既定は「その列を省いた INSERT が来たときだけ」効く保険であり、
 * 実際の INSERT は JPA 経路（{@code @PrePersist}）か明示列指定のどちらかで必ず値を与えるため、
 * 発火機会自体が乏しい。以上より本番人は<b>DML の「今」だけ</b>を対象とする。
 * この線引きを動かす（＝列既定も禁じる）なら、対象は<b>新規 migration だけ</b>にすること。
 * 既存への遡及は上記 3. の理由で採ってはならない。</p>
 *
 * <p><b>同じ理由で {@code src/test} 配下の SQL も射程外である</b>。テストのフィクスチャは
 * Testcontainers 上の使い捨てデータであり、本番データの格納基準を汚さない（別戦役の扱い）。</p>
 *
 * <h2>凍結キーは migration ファイル単位の件数である</h2>
 * <p>{@link RawSqlTimeColumnGuardTest} と同じ思想で、行番号・出現順を凍結キーに含めない。
 * 適用済み migration はチェックサムにより不変なので、実際には件数も動かない。</p>
 *
 * @see RawSqlTimeColumnGuardTest
 * @see FlywayMigrationTimeFunctionGuardScanningLogicTest
 */
@DisplayName("番人: migration の DML がセッションTZ依存の「今」を新規に増やしていないこと（CMP-260912-2258）")
class FlywayMigrationTimeFunctionGuardTest {

    /** 凍結台帳（{@code <migration ファイル名>|<件数>}）。 */
    private static final Path FREEZE_FILE = Paths.get(
            "src", "test", "resources", "flyway_migration_time_guard", "session_tz_time_function_freeze.txt");

    /**
     * セッション TZ に従って「今」を返す MySQL 組み込み関数の<b>名前だけ</b>を拾う。
     * {@code UTC_TIMESTAMP()} / {@code UTC_DATE()} / {@code UTC_TIME()} は正解なので含めない。
     *
     * <h3>列挙したのは検体ではなく「判定の軸」である</h3>
     * <p>対象は<b>「セッションの {@code time_zone} に従って現在時刻を返す MySQL 組み込み関数」全部</b>であり、
     * 日時型（{@code TIMESTAMP}）だけではない。日付型・時刻型の別名も同じセッション TZ に従うため、
     * {@code WHERE target_date < CURDATE()} のような書き方は日単位でずれうる。
     * プロジェクト規約（{@code backend/.claudecode.md} の「ネイティブクエリの
     * {@code NOW()} / {@code CURRENT_TIMESTAMP} / {@code CURDATE()}」）も日付系を名指ししている。</p>
     * <ul>
     *   <li>日時: {@code NOW()} / {@code SYSDATE()} / {@code CURRENT_TIMESTAMP} / {@code LOCALTIMESTAMP}</li>
     *   <li>日付: {@code CURDATE()} / {@code CURRENT_DATE}</li>
     *   <li>時刻: {@code CURTIME()} / {@code CURRENT_TIME} / {@code LOCALTIME}</li>
     * </ul>
     * <p>接頭辞関係（{@code CURRENT_TIME} ⊂ {@code CURRENT_TIMESTAMP}、
     * {@code LOCALTIME} ⊂ {@code LOCALTIMESTAMP}）があるため<b>長い名前を先に並べる</b>。</p>
     *
     * <h3>この正規表現に量指定子が 1 つも無い理由【必読】</h3>
     * <p>本パターンは<b>純粋なリテラル選択肢だけ</b>で構成してあり、{@code \\s*} も {@code \\s{0,4}} も
     * 含まない。境界判定・括弧の有無・名前と括弧の間の空白は、すべて Java 側で<b>前方向へ 1 度だけ走る
     * 線形処理</b>（{@link #isFunctionCallAt}）として書いてある。</p>
     * <p>初版は {@code \\s{0,4}} で空白を吸っていたが、これは
     * {@code CURDATE     ()}（空白 5 文字以上）や、コメントを空白へ潰した結果生じる
     * {@code CURDATE                ()} を<b>取りこぼす</b>（Codex 検分の指摘）。かといって素朴に
     * {@code \\s*} へ広げるのは危険で、本リポジトリには<b>空白を含む文字クラスの量指定子が破滅的
     * バックトラックを起こして 55 分ハングした実例</b>がある（{@link RawSqlTimeColumnGuardTest} の
     * Javadoc と、走査正規表現に関する同種の記録）。
     * <b>「上限を上げる」でも「{@code *} へ広げる」でもなく、可変長部分を正規表現から追い出す</b>のが
     * 本質的な解である。こうすると空白は何文字でも受理でき、かつバックトラックは原理的に起こり得ない。</p>
     */
    static final Pattern SESSION_TZ_NOW_NAME = Pattern.compile(
            "(?i)(?:CURRENT_TIMESTAMP|LOCALTIMESTAMP|CURRENT_DATE|CURRENT_TIME|LOCALTIME"
                    + "|SYSDATE|CURDATE|CURTIME|NOW)");

    /**
     * 括弧が<b>必須</b>の関数名（小文字）。これらは裸で書いても関数呼び出しにならないため、
     * 括弧が無ければ単なる識別子とみなす。
     *
     * <p>逆に {@code CURRENT_TIMESTAMP} / {@code CURRENT_DATE} / {@code CURRENT_TIME} /
     * {@code LOCALTIME} / {@code LOCALTIMESTAMP} は括弧なしでも関数として評価される
     * （SQL 標準の日時キーワード）。</p>
     */
    private static final java.util.Set<String> REQUIRES_PARENTHESES =
            java.util.Set.of("now", "sysdate", "curdate", "curtime");


    /**
     * 直前が列既定（{@code DEFAULT} / {@code ON UPDATE}）かを、後ろ向きに 1 度走って判定する。
     *
     * <p>ここも空白を任意長で受理する。{@code \\s{0,4}$} のような上限付き正規表現にすると、
     * {@code DEFAULT      NOW()}（空白 5 文字以上）が射程外判定から漏れて<b>逆向きの誤検出</b>
     * （列既定なのに違反として数える）になる。判定の軸が同じなので処理も揃えてある。</p>
     */
    static boolean isColumnDefaultContext(String text, int nameStart) {
        int i = skipWhitespaceBackward(text, nameStart);
        int wordEnd = i;
        while (i > 0 && isIdentifierPart(text.charAt(i - 1))) {
            i--;
        }
        if (i == wordEnd) {
            return false;
        }
        String word = text.substring(i, wordEnd).toLowerCase(Locale.ROOT);
        if (word.equals("default")) {
            return true;
        }
        if (!word.equals("update")) {
            return false;
        }
        // "ON UPDATE" の ON まで遡る
        int j = skipWhitespaceBackward(text, i);
        int onEnd = j;
        while (j > 0 && isIdentifierPart(text.charAt(j - 1))) {
            j--;
        }
        return j < onEnd && text.substring(j, onEnd).equalsIgnoreCase("on");
    }

    /** 空白を後ろ向きに任意長読み飛ばす（線形）。 */
    private static int skipWhitespaceBackward(String text, int from) {
        int i = from;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    record Violation(String file, int line, String snippet) {
        String describe() {
            return "%s (L%d): %s".formatted(file, line, snippet);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 走査本体（package-private: スキャンロジックテストから直接呼ぶ）
    // ────────────────────────────────────────────────────────────

    /**
     * 1 migration 分の走査。
     *
     * <p>SQL の行コメント（{@code --} / {@code #}）・ブロックコメントと、文字列リテラル
     * （{@code '...'} / {@code "..."}）の<b>中身</b>を空白へ潰してから照合する。潰さないと
     * {@code COMMENT '次回試行時刻 (enqueue 時=NOW())'} のような説明文が違反として数えられてしまう
     * （実在: {@code V68.001__create_email_outbox.sql}）。</p>
     */
    static List<Violation> collectViolationsInFile(String raw, String fileName) {
        // 事前フィルタ。偽陰性を作らないことが唯一の要件なので、検出パターンが一致しうる文字列の
        // 「必要条件」だけを並べる。current_time は current_timestamp の、localtime は localtimestamp の
        // 部分文字列なので、短い方を挙げれば長い方も必ず拾える。
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.contains("now") && !lower.contains("sysdate")
                && !lower.contains("curdate") && !lower.contains("curtime")
                && !lower.contains("current_date") && !lower.contains("current_time")
                && !lower.contains("localtime")) {
            return List.of();
        }
        String scan = maskCommentsAndLiterals(raw);
        List<Violation> violations = new ArrayList<>();
        // 行番号は「前回の一致位置からの差分」で数える。一致は必ず昇順に出てくるので、
        // ファイル全体を毎回先頭から数え直す必要がない。数え直すと 1 ファイルあたり
        // O(ファイル長 × 一致件数) となり、117 件の一致を持つ migration（V2.027）などで
        // 走査全体が桁違いに遅くなる（実測 8.8 秒 → 是正後は後述の scanFinishesQuickly を参照）。
        LineCounter lines = new LineCounter();
        Matcher m = sessionTzNowMatcher(scan);
        while (m.find()) {
            if (precededByIdentifierPart(scan, m.start())) {
                continue; // より長い識別子の一部（audit$current_date / `current_date` / t.current_date）
            }
            int callEnd = isFunctionCallAt(scan, m.start(), m.end());
            if (callEnd < 0) {
                continue; // 関数呼び出しの形になっていない
            }
            if (isColumnDefaultContext(scan, m.start())) {
                continue; // 列 DEFAULT / ON UPDATE は射程外（クラス Javadoc 参照）
            }
            violations.add(new Violation(fileName, lines.lineAt(raw, m.start()),
                    raw.substring(m.start(), Math.min(raw.length(), callEnd)).strip()));
        }
        return violations;
    }

    /**
     * 昇順に問い合わされる前提で行番号を差分計算するカーソル。
     *
     * <p>1 ファイルにつき 1 インスタンス。{@link #lineAt} は前回位置から今回位置までの改行だけを数えるので、
     * ファイル全体の走査は一致件数によらず O(ファイル長) に収まる。</p>
     */
    static final class LineCounter {
        private int lastOffset;
        private int lastLine = 1;

        int lineAt(String raw, int offset) {
            if (offset < lastOffset) { // 想定外の逆行。安全側に倒して先頭から数え直す
                lastOffset = 0;
                lastLine = 1;
            }
            for (int i = lastOffset; i < offset && i < raw.length(); i++) {
                if (raw.charAt(i) == '\n') {
                    lastLine++;
                }
            }
            lastOffset = Math.min(offset, raw.length());
            return lastLine;
        }
    }

    // ────────────────────────────────────────────────────────────
    // 判定の軸1: 「識別子の一部である」とは何か
    // ────────────────────────────────────────────────────────────

    /**
     * MySQL の引用なし識別子を構成しうる文字か。
     *
     * <p>MySQL が許すのは {@code 0-9 a-z A-Z $ _} と U+0080 以上の文字である。
     * <b>{@code $} を落とすと {@code audit$current_date} のような正当な識別子の途中に一致してしまう</b>
     * （Codex 検分の指摘。正規表現の {@code \\b} は {@code $} を単語構成文字として扱わないため、
     * 先頭側の境界を {@code \\b} に委ねてはならない）。</p>
     */
    static boolean isIdentifierPart(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || c == '_' || c == '$' || c >= 0x80;
    }

    /**
     * 一致位置の直前を見て「これは識別子の続きである」と言えるか。
     *
     * <p>識別子構成文字のほか、<b>バッククォート</b>（引用識別子 {@code `current_date`}）と
     * <b>ドット</b>（修飾名 {@code t.current_date}）も直前に来たら関数呼び出しではない。</p>
     */
    static boolean precededByIdentifierPart(String text, int start) {
        if (start <= 0) {
            return false;
        }
        char prev = text.charAt(start - 1);
        return isIdentifierPart(prev) || prev == '`' || prev == '.';
    }

    // ────────────────────────────────────────────────────────────
    // 判定の軸2: 「関数呼び出しである」とは何か
    // ────────────────────────────────────────────────────────────

    /**
     * 名前の直後を線形に読み進め、関数呼び出しになっているなら<b>その終端位置</b>を、
     * なっていないなら {@code -1} を返す。
     *
     * <p>ここが可変長（名前と括弧の間の空白は任意長）を扱う唯一の場所であり、
     * <b>前方向へ 1 度走るだけで後戻りしない</b>ので、入力長に対して線形かつバックトラック不能である。
     * 正規表現へ {@code \\s*} を書かずに済ませているのはこのためである（クラス Javadoc 参照）。</p>
     *
     * <p>判定は次のとおり:</p>
     * <ol>
     *   <li>名前の直後が識別子構成文字またはバッククォートなら、より長い識別子なので呼び出しではない
     *       （{@code current_timestamp_format} / {@code `current_date`}）</li>
     *   <li>空白を任意長読み飛ばした先が {@code (} なら、{@code ( 空白 数字列? 空白 )} の形を確かめる。
     *       確かめられれば呼び出し（{@code NOW(6)} / {@code CURDATE     ()}）</li>
     *   <li>括弧が無い場合、括弧必須の名前（{@link #REQUIRES_PARENTHESES}）は呼び出しではない。
     *       それ以外（SQL 標準の日時キーワード）は裸でも呼び出しである（{@code CURRENT_TIMESTAMP}）</li>
     * </ol>
     */
    static int isFunctionCallAt(String text, int nameStart, int nameEnd) {
        int n = text.length();
        if (nameEnd < n) {
            char next = text.charAt(nameEnd);
            if (isIdentifierPart(next) || next == '`') {
                return -1;
            }
        }
        boolean parenthesesRequired =
                REQUIRES_PARENTHESES.contains(text.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT));

        int i = skipWhitespace(text, nameEnd);
        if (i < n && text.charAt(i) == '(') {
            int j = skipWhitespace(text, i + 1);
            while (j < n && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
                j++; // 精度指定（NOW(6) など）。桁数に上限を設けない
            }
            j = skipWhitespace(text, j);
            if (j < n && text.charAt(j) == ')') {
                return j + 1;
            }
            // 括弧はあるが「空白と数字だけ」ではない＝この名前の呼び出しとしては不正な形。
            // 括弧必須の名前なら呼び出しではなく、裸で成立する名前なら名前だけで呼び出しとみなす。
            return parenthesesRequired ? -1 : nameEnd;
        }
        return parenthesesRequired ? -1 : nameEnd;
    }

    /** 空白を任意長読み飛ばす（前方向のみ・線形）。コメントは既に空白へ潰されている。 */
    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * SQL のコメントと文字列リテラルの中身を空白へ潰す（改行は保つので行番号は変わらない）。
     *
     * <p>MySQL の文字列リテラルは {@code ''} と {@code \'} の両方でクォートを escape できるため、
     * どちらも「リテラル継続」として扱う。</p>
     */
    static String maskCommentsAndLiterals(String raw) {
        char[] out = raw.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if ((c == '-' && i + 1 < n && out[i + 1] == '-') || c == '#') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else if (c == '\'' || c == '"') {
                char quote = c;
                i++; // 開きクォートはそのまま残す（位置合わせのため潰す必要がない）
                while (i < n) {
                    if (out[i] == '\\' && i + 1 < n) {
                        if (out[i] != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                        if (i < n && out[i] != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                        continue;
                    }
                    if (out[i] == quote) {
                        if (i + 1 < n && out[i + 1] == quote) { // '' = escape されたクォート
                            out[i++] = ' ';
                            out[i++] = ' ';
                            continue;
                        }
                        i++; // 閉じクォート
                        break;
                    }
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    // ────────────────────────────────────────────────────────────
    // テスト本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("migration ファイル単位の凍結件数から増えていない（新規 migration の NOW() は禁止）")
    void noNewSessionTzTimeFunctionInMigrations() throws IOException {
        Path root = migrationRoot();
        List<Violation> all = scanWithinTimeout(() -> collectViolations(root));

        assertThat(all)
                .as("migration から走査対象を1件も検出できなかった（走査パスの前提が壊れた可能性）: %s", root)
                .isNotEmpty();

        Map<String, Integer> actual = countByFile(all);
        Map<String, Integer> frozen = readFreezeCounts();
        List<String> mismatches = DateTimeAndZoneGuardTest.classCountMismatches(actual, frozen);

        assertThat(mismatches)
                .as("""
                    【migration の「今」がファイル単位の凍結件数から動いた】
                    『対処療法禁止・根治治療』原則により、新しい migration で時刻列へ「今」を書くときは
                    NOW() / CURRENT_TIMESTAMP / SYSDATE ではなく UTC_TIMESTAMP() を使うこと
                    （docs/architecture/datetime_policy_utc_instant_vs_wallclock.md 4.2節）。
                    凍結台帳へ追記して黙らせてはならない。台帳ファイル: %s
                    差分: %s""".formatted(FREEZE_FILE, mismatches))
                .isEmpty();
    }

    /**
     * 走査時間の上限（ミリ秒）。{@link RawSqlTimeColumnGuardTest} が同じ目的で使う 30 秒に揃えてある。
     *
     * <p><b>なぜ 1 秒ではなく 30 秒なのか</b>: この上限が捕まえたいのは<b>破滅的バックトラック</b>であり、
     * それは「数倍遅い」ではなく「分〜時間」のオーダーで現れる（本リポジトリの実例は 55 分）。
     * 一方で本プロジェクトの開発機・CI は複数のビルドが同時に走るため、秒オーダーの絶対値を閾値にすると
     * <b>検出力を増やさないまま、機械の混み具合で赤くなる不安定なテスト</b>になる。
     * 30 秒なら混雑の影響では落ちず、バックトラック級の劣化は確実に捕まえられる。</p>
     *
     * <p>実測値は常に標準出力へ出しているので、退行の傾向は数値で追える
     * （是正時点の実測: 1156 ファイルで約 6 秒。行番号計算が O(ファイル長 × 一致件数) だった頃は
     * 約 8.8 秒であり、{@link LineCounter} の導入で短縮した）。</p>
     */
    static final long SCAN_TIMEOUT_MILLIS = 30_000L;

    /**
     * 走査を<b>プリエンプティブな</b>時間制限つきで実行する。
     *
     * <h3>なぜ「終わってから測る」ではいけないのか【必読】</h3>
     * <p>初版は {@code collectViolations} を普通に呼び、<b>戻ってきてから</b>経過時間を測って
     * 閾値と比べていた。これは<b>ハングしたときにだけ働かない番人</b>である。破滅的バックトラックで
     * 走査が停止すると制御が戻らず、経過時間の比較へ到達しないため、30 秒では落ちずに
     * CI のジョブ上限まで居座る。捕まえたい 55 分ハングの再発は、まさにこの経路で起こる
     * （Codex 検分の指摘）。JUnit の {@code assertTimeout}（非プリエンプティブ）も同じ性質で、
     * <b>処理が自力で終わるまで待ってから</b>超過を判定する。</p>
     *
     * <p>そこで {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively} を使い、
     * 走査を別スレッドで走らせて上限で<b>打ち切る</b>。これで「時間切れを検出する番人」が
     * 実際に時間切れで落ちるようになる。</p>
     *
     * <h3>別スレッド実行で不安定にならないこと</h3>
     * <p>{@code collectViolations} が触るのは引数と局所変数だけである。
     * {@link #SESSION_TZ_NOW_NAME} などの {@link Pattern} は不変かつスレッドセーフ、
     * {@link LineCounter} はファイルごとに新規生成される局所オブジェクト、
     * 可変な静的フィールドは持たない。{@code ThreadLocal} も Spring のコンテキストも使わないため、
     * {@code assertTimeoutPreemptively} の既知の注意点（{@code ThreadLocal} 依存の状態が
     * 別スレッドへ伝播しない・{@code @Transactional} と併用するとロールバックが効かない）に
     * 該当しない。ファイル読み取りはスレッドに紐づかない。</p>
     */
    static <T> T scanWithinTimeout(org.junit.jupiter.api.function.ThrowingSupplier<T> scan) {
        return scanWithinTimeout(Duration.ofMillis(SCAN_TIMEOUT_MILLIS), scan);
    }

    /** 上限を指定する版（走査ロジックテストが短い上限で打ち切りを検証するために使う）。 */
    static <T> T scanWithinTimeout(Duration timeout, org.junit.jupiter.api.function.ThrowingSupplier<T> scan) {
        return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                timeout, scan,
                () -> """
                    migration の走査が %d ms 以内に終わらなかった（打ち切った）。
                    検出パターンに可変長の量指定子を持ち込むと破滅的バックトラックで停止しうる
                    （本リポジトリには 55 分ハングの実例がある）。可変長は正規表現ではなく線形処理で
                    扱うこと（SESSION_TZ_NOW_NAME の Javadoc）。""".formatted(timeout.toMillis()));
    }

    /** 走査が割り込まれたことを表す。{@link InterruptibleCharSequence} だけが投げる。 */
    static final class ScanInterruptedException extends RuntimeException {
        ScanInterruptedException() {
            super("走査が割り込まれたため中断した（時間制限による打ち切り）");
        }
    }

    /**
     * 正規表現走査を<b>割り込みに応答させる</b>ための入力ラッパ。
     *
     * <h3>なぜ必要か【必読】</h3>
     * <p>{@code assertTimeoutPreemptively} は<b>実行スレッドへ割り込みを送るだけ</b>で強制終了はしない。
     * ところが Java の正規表現走査（{@link Matcher#find()} など）は<b>割り込み状態を一切見ない</b>。
     * したがって検出パターンが破滅的バックトラックへ退行すると、テストは赤くなるものの
     * <b>走査スレッドは生き残って CPU を焼き続け</b>、テスト JVM を停止不能または高負荷にする。
     * つまりプリエンプティブにしただけでは<b>保険が効かない</b>（Codex 検分の指摘）。</p>
     *
     * <p>{@link Matcher} は入力から {@link CharSequence#charAt(int)} で 1 文字ずつ読み進めるので、
     * その {@code charAt} で割り込み状態を見て例外を投げれば、走査は割り込みに応答するようになる。
     * バックトラック中も文字の読み直しが起きるため、停止までの遅れは高々数文字ぶんである。</p>
     *
     * <h3>{@code Thread.interrupted()} ではなく {@code isInterrupted()} を使う理由</h3>
     * <p>{@code Thread.interrupted()} は<b>割り込みフラグを消す</b>。ここで消してしまうと、
     * 最初の 1 回しか例外を投げられないうえ、{@code assertTimeoutPreemptively} 側の後処理
     * （{@code shutdownNow} による再割り込み）とも噛み合わなくなる。フラグを消さない
     * {@code isInterrupted()} を使う。</p>
     */
    static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence delegate;

        InterruptibleCharSequence(CharSequence delegate) {
            this.delegate = delegate;
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ScanInterruptedException();
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        /** 部分列も割り込み可能なまま返す（{@code Matcher#group()} 等が使う）。 */
        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(delegate.subSequence(start, end));
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /** 走査対象文字列を割り込み可能にして返す。 */
    static CharSequence interruptible(CharSequence text) {
        return new InterruptibleCharSequence(text);
    }

    /**
     * 本番の走査が使う {@link Matcher} を組み立てる<b>唯一の入口</b>。
     *
     * <p>入力を必ず {@link #interruptible} で包む。ここを経由しない {@code matcher()} を書くと、
     * その走査だけが割り込みに応答しなくなり、退行時に走査スレッドが CPU を焼き続ける。
     * 生成を 1 箇所に集約してあるのは、<b>この結線自体を回帰テストで固定できるようにする</b>ためである
     * （{@code FlywayMigrationTimeFunctionGuardScanningLogicTest} の
     * {@code productionMatcherRespondsToInterrupt}。ラッパを外すと当該テストが落ちる）。</p>
     */
    static Matcher sessionTzNowMatcher(CharSequence maskedText) {
        return SESSION_TZ_NOW_NAME.matcher(interruptible(maskedText));
    }

    @Test
    @DisplayName("走査が現実的な時間で終わる（上限で打ち切られること＝ハングしても落ちること）")
    void scanFinishesQuickly() {
        Path root = migrationRoot();
        int fileCount = sqlFiles(root).size();
        long startNanos = System.nanoTime();
        // 経過時間の比較ではなく、この呼び出し自体が上限で打ち切られることが番人の本体である。
        List<Violation> all = scanWithinTimeout(() -> collectViolations(root));
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(all).as("走査対象が 0 件（走査パスの前提が壊れた可能性）").isNotEmpty();
        System.out.printf("[migration番人] 走査 %d ファイル／検出 %d 件／所要 %d ms%n",
                fileCount, all.size(), millis);
    }

    @Test
    @DisplayName("凍結件数が記録済みスナップショットと一致している（増減とも検知）")
    void freezeCountsMatchRecordedSnapshot() throws IOException {
        Map<String, Integer> frozen = readFreezeCounts();
        int files = frozen.size();
        int total = frozen.values().stream().mapToInt(Integer::intValue).sum();

        assertThat(total)
                .as("凍結総件数が EXPECTED_FROZEN_TOTAL と一致しない（実=%d, 記録=%d）。"
                                + "増えた場合は新規の凍結追記であり禁止。減った場合は是正の反映なので定数を更新すること。",
                        total, EXPECTED_FROZEN_TOTAL)
                .isEqualTo(EXPECTED_FROZEN_TOTAL);
        assertThat(files)
                .as("凍結ファイル数が EXPECTED_FROZEN_FILES と一致しない（実=%d, 記録=%d）", files, EXPECTED_FROZEN_FILES)
                .isEqualTo(EXPECTED_FROZEN_FILES);
    }

    /** 返済台帳のスナップショット（CMP-260912-2258 実装時点）。減ったら追随更新し、増やしてはならない。 */
    static final int EXPECTED_FROZEN_TOTAL = 746;
    static final int EXPECTED_FROZEN_FILES = 65;

    // ────────────────────────────────────────────────────────────
    // 走査・凍結ファイル入出力
    // ────────────────────────────────────────────────────────────

    static List<Violation> collectViolations(Path root) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : sqlFiles(root)) {
            // ファイル単位でも割り込みを見る。1 ファイルあたりが速くても、対象が膨れて全体が
            // 長引いた場合に打ち切れるようにするため（1 ファイル内の停止は上の入力ラッパが見る）。
            if (Thread.currentThread().isInterrupted()) {
                throw new ScanInterruptedException();
            }
            violations.addAll(collectViolationsInFile(read(file), relativeName(root, file)));
        }
        return violations;
    }

    private static Map<String, Integer> countByFile(List<Violation> all) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Violation v : all) {
            counts.merge(v.file(), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> readFreezeCounts() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Path path = resolve(FREEZE_FILE);
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("凍結ファイルの行形式が不正: " + path + " の行 \"" + trimmed
                        + "\"。期待形式: <migrationファイル名>|<件数>");
            }
            counts.merge(parts[0], Integer.parseInt(parts[1].strip()), Integer::sum);
        }
        return counts;
    }

    private static Path migrationRoot() {
        for (String candidate : new String[]{
                "src/main/resources/db/migration", "backend/src/main/resources/db/migration"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("db/migration が見つからない（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    private static Path resolve(Path relative) {
        for (Path candidate : new Path[]{relative, Paths.get("backend").resolve(relative)}) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "凍結リストが見つからない: " + relative + "（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static List<Path> sqlFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".sql")).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
