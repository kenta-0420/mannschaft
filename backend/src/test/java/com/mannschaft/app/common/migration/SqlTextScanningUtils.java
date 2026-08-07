package com.mannschaft.app.common.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * migration {@code .sql} をテキストとして静的走査する番人テスト群が共有する前処理ユーティリティ
 * （CMP-022・issue #2589 系フォローアップ）。
 *
 * <h2>背景</h2>
 * <p>issue #2589 の PR #2591 で {@code MigrationPrimaryKeyConventionTest} に 3 件の走査ロジック欠陥
 * （コメント未除去による誤検知・{@code CREATE TEMPORARY TABLE} 未認識による誤帰属・
 * テーブル本体の範囲が無制限に伸びる問題）が見つかり是正された。同型の欠陥が
 * {@code CrossDomainForeignKeyArchTest}（ブロックコメント未除去・一時表未認識）にも存在し、
 * {@code MigrationCollationDeclarationGuardTest} は独自にコメント除去を実装していた
 * （＝対処が番人ごとに不統一）。
 *
 * <p>本クラスは「SQL テキストの前処理」（コメント除去・引用符スキップ・文末検出・一時表の除去）
 * だけを共通化する。<b>「何を違反とするか」の判定ロジックは番人ごとに固有のまま残し、
 * ここには含めない</b>（無理な共通化で各番人の意図が読めなくなるのを避けるため）。
 */
public final class SqlTextScanningUtils {

    private SqlTextScanningUtils() {
    }

    /**
     * SQL コメント（行コメント {@code --} とブロックコメント {@code /* *}{@code /} の両方）を
     * 空白へ置換する。削除ではなく同じ長さ・改行位置を保つ置換にすることで、以降の
     * offset 計算（行番号・部分文字列の切り出し）がずれないようにする。
     * 引用符（{@code '} {@code "} {@code `}）の中身はコメントではないため読み飛ばす
     * （引用符内の {@code --} や {@code /*} を誤ってコメット開始と解釈しない）。
     */
    public static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql);
        int n = sb.length();
        for (int i = 0; i < n; i++) {
            char c = sb.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sb, i, c);
            } else if (c == '-' && i + 1 < n && sb.charAt(i + 1) == '-') {
                while (i < n && sb.charAt(i) != '\n') {
                    sb.setCharAt(i++, ' ');
                }
            } else if (c == '/' && i + 1 < n && sb.charAt(i + 1) == '*') {
                int end = sb.indexOf("*/", i + 2);
                end = (end < 0) ? n : end + 2;
                for (int j = i; j < end; j++) {
                    if (sb.charAt(j) != '\n') {
                        sb.setCharAt(j, ' ');
                    }
                }
                i = end - 1;
            }
        }
        return sb.toString();
    }

    /**
     * {@code from} の位置にある引用符（{@code quote}）に対応する閉じ引用符の位置を返す。
     * バックスラッシュエスケープを 1 文字スキップする（MySQL 既定のエスケープ規則）。
     * 閉じ引用符が見つからない場合は文字列末尾を返す。
     */
    public static int skipQuoted(CharSequence s, int from, char quote) {
        int n = s.length();
        for (int i = from + 1; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return n - 1;
    }

    /**
     * {@code from} 以降で文を終える {@code ;} の位置を返す（引用符内の {@code ;} は無視）。
     * 見つからなければ文字列末尾（{@code content.length()}）を返す。
     */
    public static int findStatementEnd(String content, int from) {
        int n = content.length();
        for (int i = from; i < n; i++) {
            char c = content.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(content, i, c);
            } else if (c == ';') {
                return i;
            }
        }
        return n;
    }

    /**
     * コメント除去済みの SQL テキストを、引用符内の {@code ;} を無視した上で
     * ステートメント単位（末尾の {@code ;} を含まない）に分割する。
     * 単純な {@code String.split(";")} は引用符内の {@code ;}（例:
     * {@code DEFAULT 'a;b'}）で誤分割するため、規約チェック対象の
     * {@code CREATE TABLE} 等を静的走査する番人はこちらを使うこと。
     */
    public static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        int from = 0;
        int n = sql.length();
        while (from < n) {
            int end = findStatementEnd(sql, from);
            statements.add(sql.substring(from, end));
            from = end + 1; // ';' の次から
        }
        return statements;
    }

    /**
     * {@code CREATE TEMPORARY TABLE ... ;} の本体（テーブル定義文全体）を、
     * 文字数・改行を保ったまま空白へ置換する。{@code CREATE TEMPORARY TABLE} は
     * 接続内で閉じており JOIN 相手にもならず、新規テーブルの主キー規約・
     * クロスドメイン FK 禁止規約いずれの対象でもない「ドメインの永続表」ではないため、
     * 走査対象から丸ごと除外する。
     *
     * <p>個々の番人が持つ「テーブル文の検出パターン」に {@code TEMPORARY} 対応を
     * 都度追加するのではなく、ここで一時表の中身自体を消してしまうことで、
     * 認識漏れによる誤帰属（一時表の内容が直前の実テーブルの本体に紛れ込む）を
     * 構造的に防ぐ。呼び出しは {@link #stripComments(String)} の後に行うこと
     * （コメント中の {@code CREATE TEMPORARY TABLE} という文言を誤検出しないため）。
     */
    public static String blankOutTemporaryTables(String sqlWithoutComments) {
        java.util.regex.Pattern createTemp = java.util.regex.Pattern.compile(
            "CREATE\\s+TEMPORARY\\s+TABLE\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
        StringBuilder sb = new StringBuilder(sqlWithoutComments);
        java.util.regex.Matcher m = createTemp.matcher(sb);
        int searchFrom = 0;
        while (m.find(searchFrom)) {
            int start = m.start();
            int end = findStatementEnd(sb.toString(), m.end());
            for (int j = start; j < end; j++) {
                if (sb.charAt(j) != '\n') {
                    sb.setCharAt(j, ' ');
                }
            }
            searchFrom = end;
        }
        return sb.toString();
    }
}
