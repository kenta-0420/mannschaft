package com.mannschaft.app.common.architecture;

/**
 * Java ソース（{@code .java}）をテキストとして静的走査する番人テスト群が共有する前処理ユーティリティ
 * （CMP-022 第二波・migration 版 {@code SqlTextScanningUtils} の Java ソース版）。
 *
 * <h2>背景</h2>
 * <p>第一波（issue #2589 系）で migration SQL を走査する番人 4 クラスに同型の欠陥が見つかり
 * {@code SqlTextScanningUtils} へ前処理を共通化した。第二波の監査で、Java ソースを走査する番人にも
 * <b>テキストブロック（{@code """ ... """}）を認識しない</b>という同型の欠陥が見つかった。</p>
 *
 * <p>具体的には、{@code PagingTotalCountSizeGuardTest} / {@code ErrorCodeHttpStatusDeclarationGuardTest} /
 * {@code SecurityConfigRules} が持っていた独自のコメント・文字列マスク処理は、{@code "} を単純に
 * トグルとして扱っていた。テキストブロック本文に<b>奇数個の生クォート</b>（例:
 * {@code 12" tall} のような単位記号）が 1 個でも含まれると、以降のクォート対応がずれ、
 * <b>後続の実コードが「文字列の内側」と誤認されて丸ごとマスクされる</b>
 * （= 走査から消える。migration 版の欠陥5「引用符の二重化誤認による走査の暴走」と同型で、
 * こちらはテキストブロックの三重クォートが引き金になる）。この結果、そこに実在する違反を
 * 番人が静かに見逃す（偽陰性）。実測は本クラスのテスト
 * {@code JavaSourceScanningUtilsTest} を参照。</p>
 *
 * <p>{@link AuthzGateReturnValueGuardTest} / {@code ScopeSwitchExhaustivenessGuardTest} /
 * {@link SelfScopedEndpointMarkerGuardTest} は元々テキストブロックを正しく扱う {@code mask()} を
 * 個別に実装済みだった（3 クラスでほぼ同一実装が重複）。本クラスはその実装を土台に、
 * 「コメント・文字列/文字リテラルの中身を空白へ潰す」版と「コメントだけを潰し文字列は残す」版の
 * 2 通りを共通化する。<b>「何を違反とするか」の判定ロジックは番人ごとに固有のまま残し、
 * ここには含めない</b>（無理な共通化で各番人の意図が読めなくなるのを避けるため。SQL 用と
 * Java 用も、コメント構文は似ているが文字列リテラルの規則（二重化エスケープの有無・
 * テキストブロックの有無）が違うため、あえて一本化しない）。</p>
 */
public final class JavaSourceScanningUtils {

    private JavaSourceScanningUtils() {
    }

    /**
     * コメント（{@code //} 行コメント・{@code /* *}{@code /} ブロックコメント／Javadoc 含む）と
     * 文字列/文字リテラル・テキストブロック（{@code """ ... """}）の<b>中身</b>を空白へ置換する。
     * 長さ・改行・区切り文字（{@code "} {@code '}）は保持するため、原文とオフセットが 1:1 で
     * 対応する（部分文字列の切り出し・行番号計算がそのまま使える）。
     *
     * <p>テキストブロック本体に含まれる生のクォート（{@code "}）・バックスラッシュエスケープは
     * いずれも正しくスキップし、閉じテキストブロック {@code """} を誤認しない。</p>
     */
    public static String maskCommentsAndLiterals(String s) {
        char[] a = s.toCharArray();
        char[] out = a.clone();
        int n = a.length;
        int i = 0;
        while (i < n) {
            char c = a[i];
            if (c == '/' && i + 1 < n && a[i + 1] == '/') {
                while (i < n && a[i] != '\n') {
                    out[i] = ' ';
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && a[i + 1] == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                while (i < n && !(a[i] == '*' && i + 1 < n && a[i + 1] == '/')) {
                    if (a[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i] = ' ';
                    if (i + 1 < n) {
                        out[i + 1] = ' ';
                    }
                    i += 2;
                }
                continue;
            }
            if (c == '"' && i + 2 < n && a[i + 1] == '"' && a[i + 2] == '"') {
                i += 3;
                while (i < n && !(a[i] == '"' && i + 1 < n && a[i + 1] == '"'
                        && i + 2 < n && a[i + 2] == '"')) {
                    if (a[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                i = Math.min(n, i + 3);
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && a[i] != quote) {
                    if (a[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                        continue;
                    }
                    if (a[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    i++;
                }
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /**
     * コメント（行コメント・ブロックコメント／Javadoc）だけを空白へ置換し、文字列/文字リテラル・
     * テキストブロックは<b>中身を含めてそのまま残す</b>版。文字列リテラルの実際の値を後段で
     * 読み取りたい番人（{@code HttpStatus} マッピング表の読み取り等）向け。
     *
     * <p>{@link #maskCommentsAndLiterals(String)} と同じテキストブロック・エスケープ処理を用いる
     * ため、文字列内の {@code //} や {@code /*} をコメント開始と誤認しない。</p>
     */
    public static String maskCommentsOnly(String s) {
        char[] a = s.toCharArray();
        char[] out = a.clone();
        int n = a.length;
        int i = 0;
        while (i < n) {
            char c = a[i];
            if (c == '/' && i + 1 < n && a[i + 1] == '/') {
                while (i < n && a[i] != '\n') {
                    out[i] = ' ';
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && a[i + 1] == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                while (i < n && !(a[i] == '*' && i + 1 < n && a[i + 1] == '/')) {
                    if (a[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i] = ' ';
                    if (i + 1 < n) {
                        out[i + 1] = ' ';
                    }
                    i += 2;
                }
                continue;
            }
            if (c == '"' && i + 2 < n && a[i + 1] == '"' && a[i + 2] == '"') {
                // テキストブロック本文は改変せずコピーし、閉じ """ まで正しく読み飛ばす。
                i += 3;
                while (i < n && !(a[i] == '"' && i + 1 < n && a[i + 1] == '"'
                        && i + 2 < n && a[i + 2] == '"')) {
                    i++;
                }
                i = Math.min(n, i + 3);
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && a[i] != quote) {
                    if (a[i] == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    i++;
                }
                if (i < n) {
                    i++;
                }
                continue;
            }
            i++;
        }
        return new String(out);
    }
}
