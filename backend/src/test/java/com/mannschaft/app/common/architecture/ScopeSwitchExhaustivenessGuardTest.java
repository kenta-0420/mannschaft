package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * スコープ分岐 {@code switch} の網羅性番人（認可根治 Wave6・予防番人）。
 *
 * <h2>背景 — なぜ ArchUnit バイトコード方式ではなくソース走査なのか</h2>
 * <p>認可の番人（{@code AuthzControllerGuardArchTest}）は「認可クラスを呼んでいるか」までは
 * 見られるが、「その呼び出しが <b>全スコープ値に対して効いているか</b>」までは見られない。
 * スコープ種別（{@code TEAM}/{@code ORGANIZATION}/{@code PERSONAL} 等）を {@code switch}
 * で分岐して認可・可視性・配信を切り替えるとき、次の 2 つは意味が大きく異なる:</p>
 * <ul>
 *   <li><b>switch 式（{@code case X -> ...} 矢印形・値を返す）</b> — enum に対しては
 *       コンパイラが網羅性を強制するため、新しい定数を足し忘れるとビルドが落ちる＝安全。</li>
 *   <li><b>switch 文（{@code case X:} コロン形）</b> — enum でも網羅性は強制されず、
 *       とりわけセレクタが <b>{@code String}</b> の場合はコンパイラの網羅性検査が
 *       <b>一切効かない</b>。{@code default} を欠くと、未知／将来追加のスコープ値が
 *       <b>黙って分岐をすり抜ける</b>。</li>
 * </ul>
 * <p>この「矢印形か・コロン形か」「{@code default} があるか」「{@code default} が拒否
 * （{@code throw}）するか」は、いずれも <b>コンパイル後のバイトコードからは判別しにくい</b>
 * （switch 文/式や default の有無・default 本体の内容が同型の分岐命令に落ちる）。
 * よって本番人は ArchUnit のバイトコード方式ではなく、{@link FlywayTimestampNamingGuardTest}
 * と同じ <b>ソース走査（{@code Files.walk} ＋ 軽量パーサ ＋ {@code fail()} で違反列挙）</b>で作る。</p>
 *
 * <h2>検査対象と違反条件（誤検出を出さないための厳密な限定）</h2>
 * <p>ノイズ（false positive）で無関係な {@code switch} を巻き込まないことを最優先に、
 * 対象を <b>スコープ種別を分岐しているコロン形 switch 文</b>に限定する。具体的には、
 * {@code case} ラベルに次のいずれかのトークンを持つ switch を対象とする:</p>
 * <ul>
 *   <li>enum 定数形（コロン形）: {@code case TEAM:} {@code case ORGANIZATION:}
 *       {@code case ORG:} {@code case PERSONAL:} {@code case USER:} {@code case SYSTEM:}</li>
 *   <li>{@code String} リテラル形: {@code case "TEAM":} … {@code case "PERSONAL":} 等</li>
 * </ul>
 * <p>矢印形（{@code case X -> ...}）switch は網羅性がコンパイラ側で担保される（enum 式）ため
 * <b>対象外</b>。違反条件は次の 2 本立て:</p>
 * <ol>
 *   <li><b>ルール1（default 欠落）</b>: 上記スコープ switch 文が {@code default} 節を
 *       <b>一切持たない</b>。未知／将来値が無言で素通りする構造そのもの。enum 形・String 形の双方に適用。</li>
 *   <li><b>ルール2（String の素通り default）</b>: セレクタが {@code String} のスコープ switch 文で、
 *       {@code default} 節が <b>単独（既知スコープ {@code case} と融合していない）</b>かつ
 *       その実行本体が <b>{@code throw} で拒否していない</b>。{@code String} 分岐は
 *       コンパイラの網羅性検査が原理的に効かないため、明示的な拒否 default を要求する。</li>
 * </ol>
 *
 * <h2>ルール2 で enum 形の「素通り default」を違反にしない理由（実測に基づく校正）</h2>
 * <p>「{@code default} はあるが {@code throw} しない」を無条件に違反とすると、スコープ種別を
 * <b>認可ではなく分類</b>に使っている正当な helper（非営利判定・価格バンド写像・ラベル分類 等）を
 * 巻き込む。実測では、enum スコープ switch 文で {@code default} が {@code throw} しないものは
 * すべてこの分類系（例: {@code return false} / {@code return null}）であり、認可の穴ではなかった。
 * よってルール2 は、コンパイラの網羅性検査が <b>原理的に効かない String 形</b>にのみ適用する。
 * enum 形は {@code default} の存在（ルール1）だけを要求する。</p>
 *
 * <h2>免除リスト</h2>
 * <p>正当な理由で対象外にするものは {@link #EXEMPTIONS} に理由コメント付きで静的登録できる。
 * 発足時点では <b>0 件（クリーン発足）</b>。凍結ストアへの書き戻しは一切行わないため、
 * {@code --tests} 絞り込み実行で ArchUnit 凍結ストアを破壊する事故は起こさない。</p>
 */
class ScopeSwitchExhaustivenessGuardTest {

    /** 走査ルート（{@code backend/} を CWD とする Gradle テスト実行に合わせた相対パス）。 */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /** スコープ種別を表すトークン集合（enum 定数名・String リテラル値の双方でこの集合と照合）。 */
    private static final Set<String> SCOPE_TOKENS =
        Set.of("TEAM", "ORGANIZATION", "ORG", "PERSONAL", "USER", "SYSTEM");

    /**
     * 免除リスト（{@code "<相対パス>:<switch の行番号>"} 形式）。
     * 正当な理由で本番人の対象から外すものを理由コメント付きで登録する。発足時点では空。
     */
    private static final Set<String> EXEMPTIONS = Set.of(
        // 例: "src/main/java/.../FooService.java:123"  // 理由: 〇〇のため（別ドメインで認可済み 等）
    );

    private static final Pattern SWITCH_KEYWORD = Pattern.compile("\\bswitch\\b");

    @Test
    @DisplayName("スコープ種別を分岐するコロン形switch文はdefault節を持つこと（未知/将来値の無言素通り防止）")
    void スコープswitch文はdefaultを持つ() throws IOException {
        List<Violation> violations = new ArrayList<>();
        for (SwitchInfo sw : scanAllSwitches()) {
            if (sw.exempt || sw.arrowForm || !sw.scopeSwitch) {
                continue;
            }
            if (!sw.hasDefault) {
                violations.add(new Violation(sw.relPath, sw.line,
                    (sw.stringForm ? "String" : "enum") + " スコープ switch 文に default 節がありません"));
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        fail(buildMessage(
            "スコープ種別（TEAM/ORGANIZATION/ORG/PERSONAL/USER/SYSTEM）を分岐するコロン形 switch 文に "
                + "default 節がありません。switch 文は enum でも網羅性がコンパイラで強制されず、"
                + "String 形では網羅性検査が一切効かないため、未知／将来追加のスコープ値が"
                + "無言で分岐をすり抜けます。",
            "switch 式（case X -> ...）へ書き換えて網羅性をコンパイラに保証させるか、"
                + "default 節を追加して未知値を明示的に拒否（throw）してください。",
            violations));
    }

    @Test
    @DisplayName("Stringスコープswitch文の単独defaultは黙って素通りせずthrowで拒否すること")
    void Stringスコープswitch文のdefaultは拒否する() throws IOException {
        List<Violation> violations = new ArrayList<>();
        for (SwitchInfo sw : scanAllSwitches()) {
            if (sw.exempt || sw.arrowForm || !sw.scopeSwitch || !sw.stringForm || !sw.hasDefault) {
                continue;
            }
            if (sw.stringDefaultSilent) {
                violations.add(new Violation(sw.relPath, sw.line,
                    "String スコープ switch 文の default が既知スコープと融合せず、かつ throw で拒否していません"));
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        fail(buildMessage(
            "String をセレクタにするスコープ switch 文は、コンパイラの網羅性検査が原理的に効きません。"
                + "その default 節が既知スコープ case と融合（明示写像）もせず、throw でも拒否していない場合、"
                + "未知／将来値が既定分岐として黙って処理されます。",
            "default で未知スコープ値を明示的に拒否（throw）するか、既知スコープ case と融合して"
                + "意図的な写像であることを明示するか、enum セレクタ＋switch 式へ移行してください。",
            violations));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 走査本体
    // ═══════════════════════════════════════════════════════════════════════

    private static List<SwitchInfo> scanAllSwitches() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
            "ソースルートが見つかりません: " + SOURCE_ROOT.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        List<SwitchInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    String src;
                    try {
                        src = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String rel = SOURCE_ROOT.resolve(SOURCE_ROOT.relativize(p)).toString()
                        .replace('\\', '/');
                    scanFile(rel, src, result);
                });
        }
        return result;
    }

    /** 1 ファイル分の全 switch を解析して {@code out} に追加する。 */
    private static void scanFile(String relPath, String src, List<SwitchInfo> out) {
        String masked = mask(src);
        Matcher m = SWITCH_KEYWORD.matcher(masked);
        while (m.find()) {
            int kw = m.start();
            // 「switch (」の形のみ対象（selector の '(' を必須とする）。
            int i = kw + "switch".length();
            i = skipWs(masked, i);
            if (i >= masked.length() || masked.charAt(i) != '(') {
                continue;
            }
            int selClose = matchParen(masked, i);
            if (selClose < 0) {
                continue;
            }
            int bodyOpen = skipWs(masked, selClose + 1);
            if (bodyOpen >= masked.length() || masked.charAt(bodyOpen) != '{') {
                continue; // switch 式の一部などで即座に本体が来ない形は対象外
            }
            int bodyClose = matchBrace(masked, bodyOpen);
            if (bodyClose < 0) {
                continue;
            }
            SwitchInfo info = analyzeSwitch(relPath, src, masked, kw, bodyOpen + 1, bodyClose);
            if (info != null) {
                out.add(info);
            }
        }
    }

    /** 1 つの switch 本体 [bodyStart, bodyEnd) を解析する。矢印形・非スコープは flag のみ立てて返す。 */
    private static SwitchInfo analyzeSwitch(String relPath, String src, String m,
                                            int switchKw, int bodyStart, int bodyEnd) {
        List<Label> labels = new ArrayList<>();
        boolean arrow = false;
        int depth = 0;
        int i = bodyStart;
        while (i < bodyEnd) {
            char c = m.charAt(i);
            if (c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && isWordAt(m, i, "case")) {
                int kwEnd = i + 4;
                int term = findTerminator(m, kwEnd, bodyEnd);
                if (term == ARROW_TERMINATOR) {
                    arrow = true;
                    break;
                }
                if (term < 0) {
                    break; // 解析不能 — 安全側に倒して打ち切り
                }
                Label label = new Label(false, i, term);
                parseCaseTokens(src, m, kwEnd, term, label);
                labels.add(label);
                i = term + 1;
                continue;
            }
            if (depth == 0 && isWordAt(m, i, "default")) {
                int kwEnd = i + 7;
                int term = findTerminator(m, kwEnd, bodyEnd);
                if (term == ARROW_TERMINATOR) {
                    arrow = true;
                    break;
                }
                if (term < 0) {
                    break;
                }
                labels.add(new Label(true, i, term));
                i = term + 1;
                continue;
            }
            i++;
        }

        int line = lineOf(src, switchKw);
        boolean exempt = EXEMPTIONS.contains(relPath + ":" + line);

        if (arrow) {
            return new SwitchInfo(relPath, line, true, false, false, false, false, exempt);
        }

        boolean scopeEnum = labels.stream().anyMatch(l -> l.scopeEnum);
        boolean scopeString = labels.stream().anyMatch(l -> l.scopeString);
        boolean scopeSwitch = scopeEnum || scopeString;
        boolean hasDefault = labels.stream().anyMatch(l -> l.isDefault);

        boolean stringDefaultSilent = false;
        if (scopeSwitch && scopeString && hasDefault) {
            stringDefaultSilent = isStringDefaultSilent(m, labels, bodyEnd);
        }

        return new SwitchInfo(relPath, line, false, scopeSwitch, scopeString,
            hasDefault, stringDefaultSilent, exempt);
    }

    /**
     * String スコープ switch の default が「黙って素通り」しているかを判定する。
     * 「既知スコープ case と融合（間に文が無い）」または「実行本体が throw で拒否」なら安全（false）。
     */
    private static boolean isStringDefaultSilent(String m, List<Label> labels, int bodyEnd) {
        int d = -1;
        for (int k = 0; k < labels.size(); k++) {
            if (labels.get(k).isDefault) {
                d = k;
                break;
            }
        }
        if (d < 0) {
            return false;
        }

        // (1) 融合判定: default から後ろ向きに「間の文が空白のみ」で連なる case 群に
        //     スコープ case が含まれれば、未知値を既知スコープへ明示写像しているとみなし安全。
        int k = d;
        while (k - 1 >= 0) {
            Label prev = labels.get(k - 1);
            Label cur = labels.get(k);
            if (!isBlank(m, prev.colonPos + 1, cur.kwStart)) {
                break; // 間に文がある＝融合していない
            }
            if (prev.scopeEnum || prev.scopeString) {
                return false; // 既知スコープと融合 = 明示写像で安全
            }
            k--;
        }

        // (2) throw 判定: default の実行本体（フォールスルー先を含む）に throw があれば安全。
        for (int j = d; j < labels.size(); j++) {
            int segStart = labels.get(j).colonPos + 1;
            int segEnd = (j + 1 < labels.size()) ? labels.get(j + 1).kwStart : bodyEnd;
            if (isBlank(m, segStart, segEnd)) {
                continue; // フォールスルー — 次のラベルの本体が実行される
            }
            return !containsWord(m, segStart, segEnd, "throw");
        }
        // 実行本体が空（default: のみで本体無し）＝無言素通り。
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 軽量パーサ・ユーティリティ
    // ═══════════════════════════════════════════════════════════════════════

    private static final int ARROW_TERMINATOR = -2;

    /**
     * ラベルの終端（コロン形 {@code :} か矢印形 {@code ->}）を探す。
     * @return コロンの位置 / 矢印なら {@link #ARROW_TERMINATOR} / 見つからなければ -1
     */
    private static int findTerminator(String m, int from, int end) {
        int paren = 0;
        for (int j = from; j < end; j++) {
            char c = m.charAt(j);
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren--;
            } else if (paren == 0 && c == '-' && j + 1 < end && m.charAt(j + 1) == '>') {
                return ARROW_TERMINATOR;
            } else if (paren == 0 && c == ':') {
                // 「::」（メソッド参照）は終端ではない。
                boolean prevColon = j > from && m.charAt(j - 1) == ':';
                boolean nextColon = j + 1 < end && m.charAt(j + 1) == ':';
                if (prevColon || nextColon) {
                    continue;
                }
                return j;
            } else if (paren == 0 && (c == '{' || c == ';' || c == '}')) {
                return -1; // 終端前に本体境界 — 解析不能
            }
        }
        return -1;
    }

    /** {@code case} と終端の間のラベル式を解析し、スコープ enum 定数 / スコープ String を検出する。 */
    private static void parseCaseTokens(String src, String m, int from, int colon, Label label) {
        int i = from;
        while (i < colon) {
            char c = m.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (c == '"') {
                int close = m.indexOf('"', i + 1);
                if (close < 0 || close > colon) {
                    break;
                }
                String value = src.substring(i + 1, close);
                if (SCOPE_TOKENS.contains(value.toUpperCase())) {
                    label.scopeString = true;
                }
                i = close + 1;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < colon && (Character.isJavaIdentifierPart(m.charAt(j)) || m.charAt(j) == '.')) {
                    j++;
                }
                String id = m.substring(i, j);
                int dot = id.lastIndexOf('.');
                String simple = dot >= 0 ? id.substring(dot + 1) : id;
                if (SCOPE_TOKENS.contains(simple)) {
                    label.scopeEnum = true;
                }
                i = j;
                continue;
            }
            i++;
        }
    }

    /**
     * コメント・文字列/文字リテラルの内側を空白へ潰した文字列を返す。
     * 長さ・改行・区切り文字（{@code "} {@code '}）は保持し、原文とオフセットが 1:1 で対応する。
     */
    private static String mask(String s) {
        char[] a = s.toCharArray();
        char[] out = a.clone();
        int n = a.length;
        int i = 0;
        while (i < n) {
            char c = a[i];
            // 行コメント
            if (c == '/' && i + 1 < n && a[i + 1] == '/') {
                while (i < n && a[i] != '\n') {
                    out[i] = ' ';
                    i++;
                }
                continue;
            }
            // ブロックコメント
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
            // テキストブロック """ ... """
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
            // 文字列リテラル
            if (c == '"') {
                i++; // 開きクォートは保持
                while (i < n && a[i] != '"') {
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
                    i++; // 閉じクォートは保持
                }
                continue;
            }
            // 文字リテラル
            if (c == '\'') {
                i++;
                while (i < n && a[i] != '\'') {
                    if (a[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                        continue;
                    }
                    out[i] = ' ';
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

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isWordAt(String s, int i, String word) {
        if (!s.startsWith(word, i)) {
            return false;
        }
        boolean leftOk = i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1));
        int after = i + word.length();
        boolean rightOk = after >= s.length() || !Character.isJavaIdentifierPart(s.charAt(after));
        return leftOk && rightOk;
    }

    private static boolean isBlank(String s, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(s.length(), to); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsWord(String s, int from, int to, String word) {
        int idx = Math.max(0, from);
        int limit = Math.min(s.length(), to);
        while (idx < limit) {
            int found = s.indexOf(word, idx);
            if (found < 0 || found >= limit) {
                return false;
            }
            if (isWordAt(s, found, word)) {
                return true;
            }
            idx = found + 1;
        }
        return false;
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        int limit = Math.min(offset, src.length());
        for (int i = 0; i < limit; i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String buildMessage(String why, String remedy, List<Violation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append(why).append('\n').append(remedy).append("\n違反箇所:\n");
        for (Violation v : violations) {
            sb.append("  ✗ ").append(v.relPath).append(':').append(v.line)
                .append("  ").append(v.detail).append('\n');
        }
        sb.append("（正当な例外は ")
            .append(ScopeSwitchExhaustivenessGuardTest.class.getSimpleName())
            .append(".EXEMPTIONS に理由付きで登録できます）");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部保持型
    // ═══════════════════════════════════════════════════════════════════════

    /** switch 本体内の 1 ラベル。 */
    private static final class Label {
        final boolean isDefault;
        final int kwStart;
        final int colonPos;
        boolean scopeEnum;
        boolean scopeString;

        Label(boolean isDefault, int kwStart, int colonPos) {
            this.isDefault = isDefault;
            this.kwStart = kwStart;
            this.colonPos = colonPos;
        }
    }

    /** 1 つの switch の解析結果。 */
    private static final class SwitchInfo {
        final String relPath;
        final int line;
        final boolean arrowForm;
        final boolean scopeSwitch;
        final boolean stringForm;
        final boolean hasDefault;
        final boolean stringDefaultSilent;
        final boolean exempt;

        SwitchInfo(String relPath, int line, boolean arrowForm, boolean scopeSwitch,
                   boolean stringForm, boolean hasDefault, boolean stringDefaultSilent,
                   boolean exempt) {
            this.relPath = relPath;
            this.line = line;
            this.arrowForm = arrowForm;
            this.scopeSwitch = scopeSwitch;
            this.stringForm = stringForm;
            this.hasDefault = hasDefault;
            this.stringDefaultSilent = stringDefaultSilent;
            this.exempt = exempt;
        }
    }

    /** 違反 1 件。 */
    private static final class Violation {
        final String relPath;
        final int line;
        final String detail;

        Violation(String relPath, int line, String detail) {
            this.relPath = relPath;
            this.line = line;
            this.detail = detail;
        }
    }
}
