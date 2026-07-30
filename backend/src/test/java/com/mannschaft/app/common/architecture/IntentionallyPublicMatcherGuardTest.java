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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: {@code @IntentionallyPublic} が引用する matcher 式が、実際に {@code SecurityConfig} で
 * {@code permitAll()} されていることを機械的に検証する。
 *
 * <p><b>なぜこの番人が要るのか</b>:
 * {@code @IntentionallyPublic} は「この EP は認可漏れではなく意図的な公開である」と宣言する
 * 監査済マーカーであり、その正当性は「{@code SecurityConfig} で permitAll されている」という
 * 根拠に完全に依存している。旧規約はこの根拠を
 * {@code SecurityConfig.java:88 — ...} という<b>行番号</b>で引用していたが、
 * {@code SecurityConfig} に 1 行挿入されるだけで以降の引用がすべてずれる。
 * 実測（2026-07-30）では行番号引用 35 件のうち <b>34 件が既に別の行を指していた</b>。
 * つまり「なぜ公開してよいのか」を追う唯一の手掛かりが、誰にも気づかれず嘘になっていた。</p>
 *
 * <p><b>是正の考え方</b>: 引用対象を行番号から <b>matcher 式（パス文字列）</b> に変えた。
 * matcher 式は行挿入で腐らず、文字列であるため<b>番人が機械的に突き合わせられる</b>。
 * 引用が腐れば静かに嘘になるのではなく、ビルドが赤くなる。</p>
 *
 * <p><b>本テストは ArchUnit ではない</b>（素の JUnit + ソース走査）。
 * したがって ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）を
 * 一切読み書きしない。{@code --tests} での絞り込み実行をしても凍結ストアは壊れない。</p>
 *
 * @see com.mannschaft.app.common.security.IntentionallyPublic
 */
@DisplayName("番人: @IntentionallyPublic の matcher 式は SecurityConfig で permitAll されていること")
class IntentionallyPublicMatcherGuardTest {

    private static final String ANNOTATION = "@IntentionallyPublic";

    /** production ソースルート（{@code backend/} 実行と リポジトリルート実行の両方に対応）。 */
    private static Path sourceRoot() {
        for (String candidate : new String[]{"src/main/java", "backend/src/main/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "src/main/java が見つからない（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    // ────────────────────────────────────────────────────────────
    // テスト本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("引用された matcher 式はすべて SecurityConfig に実在し permitAll されている")
    void everyCitedMatcherIsPermitAllInSecurityConfig() {
        Path root = sourceRoot();
        SecurityRules rules = SecurityRules.parse(root.resolve("com/mannschaft/app/config/SecurityConfig.java"));

        assertThat(rules.permitAll)
                .as("SecurityConfig の permitAll 解析に失敗している（パーサーの前提が壊れた可能性）")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        List<Usage> usages = collectUsages(root);

        assertThat(usages)
                .as("production コードから @IntentionallyPublic を 1 件も検出できなかった"
                        + "（走査パスの前提が壊れた可能性）")
                .isNotEmpty();

        for (Usage u : usages) {
            if (u.matchers.isEmpty()) {
                violations.add(String.format(
                        "%s:%d — @IntentionallyPublic に matcher 式が宣言されていない。%n"
                                + "        直し方: SecurityConfig の permitAll matcher のパス文字列を"
                                + " そのまま列挙すること。%n"
                                + "        例: @IntentionallyPublic(\"/api/v1/public/stats\")",
                        u.file, u.line));
                continue;
            }
            for (String m : u.matchers) {
                if (rules.permitAll.contains(m)) {
                    continue;
                }
                String detail = rules.other.containsKey(m)
                        ? String.format("SecurityConfig には存在するが permitAll ではない（%s）。"
                                + "意図的な公開ではないなら @IntentionallyPublic を外すこと。",
                        rules.other.get(m))
                        : "SecurityConfig にこのパターンは存在しない。"
                                + "パスの綴り・ワイルドカードの階層数を SecurityConfig と一字一句合わせること。";
                violations.add(String.format(
                        "%s:%d — 引用された matcher \"%s\" が permitAll ではない。%n"
                                + "        %s%n"
                                + "        SecurityConfig.java の .requestMatchers(...) を Ctrl+F で確認せよ。",
                        u.file, u.line, m, detail));
            }
        }

        assertThat(violations)
                .as("@IntentionallyPublic の公開根拠（matcher 式）が SecurityConfig の実態と食い違っている。%n"
                        + "この注釈は『認可漏れではなく意図的な公開である』と宣言するものであり、"
                        + "根拠が嘘になると認可漏れの永久凍結と区別がつかなくなる。%n"
                        + "違反一覧:%n%s", String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    @DisplayName("行番号引用（SecurityConfig.java:NNN）は新規に持ち込まれていない")
    void noLineNumberCitationsRemainInMarkerFiles() {
        Path root = sourceRoot();
        Path annotationDef = root.resolve("com/mannschaft/app/common/security/IntentionallyPublic.java");

        List<String> offenders = javaFiles(root).stream()
                // 注釈定義自身は「旧規約はこうだった」という経緯説明で行番号表記を含むため対象外
                .filter(p -> !p.equals(annotationDef))
                .filter(p -> {
                    String src = read(p);
                    return src.contains(ANNOTATION) && src.contains("SecurityConfig.java:");
                })
                .map(Path::toString)
                .sorted()
                .collect(Collectors.toList());

        assertThat(offenders)
                .as("@IntentionallyPublic を持つファイルに行番号引用（SecurityConfig.java:NNN）が残っている。%n"
                        + "行番号は SecurityConfig に行が挿入されるたびに腐るため禁止。%n"
                        + "matcher 式（パス文字列）を @IntentionallyPublic の属性に列挙する規約に統一すること。%n"
                        + "対象: %s", offenders)
                .isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    // @IntentionallyPublic の使用箇所収集
    // ────────────────────────────────────────────────────────────

    /** 1 箇所の {@code @IntentionallyPublic} 付与とその matcher 宣言。 */
    private record Usage(String file, int line, List<String> matchers) {
    }

    private static List<Usage> collectUsages(Path root) {
        List<Usage> usages = new ArrayList<>();
        javaFiles(root).stream().sorted().forEach(p -> {
            String raw = read(p);
            if (!raw.contains(ANNOTATION)) {
                return;
            }
            // コメント・文字列リテラルを空白へ潰す（行番号は保つ）。
            // Javadoc 内の注釈言及を使用箇所と誤検出しないため。
            String code = blankOutCommentsAndStrings(raw, false);
            String withStrings = blankOutCommentsAndStrings(raw, true);

            int from = 0;
            while (true) {
                int at = code.indexOf(ANNOTATION, from);
                if (at < 0) {
                    break;
                }
                from = at + ANNOTATION.length();
                // 直後が識別子文字なら別注釈（現状該当なしだが誤検出防止）
                if (from < code.length() && Character.isJavaIdentifierPart(code.charAt(from))) {
                    continue;
                }
                int line = (int) raw.substring(0, at).chars().filter(c -> c == '\n').count() + 1;

                int i = from;
                while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
                    i++;
                }
                List<String> matchers = new ArrayList<>();
                if (i < code.length() && code.charAt(i) == '(') {
                    int depth = 0;
                    int end = i;
                    while (end < code.length()) {
                        char c = code.charAt(end);
                        if (c == '(') {
                            depth++;
                        } else if (c == ')') {
                            depth--;
                            if (depth == 0) {
                                break;
                            }
                        }
                        end++;
                    }
                    // 文字列リテラルを保持した版から引数を読む
                    matchers.addAll(stringLiterals(withStrings.substring(i, Math.min(end + 1,
                            withStrings.length()))));
                }
                usages.add(new Usage(p.toString(), line, matchers));
            }
        });
        return usages;
    }

    private static List<String> stringLiterals(String s) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (in) {
                if (c == '\\' && i + 1 < s.length()) {
                    sb.append(s.charAt(++i));
                } else if (c == '"') {
                    out.add(sb.toString());
                    sb.setLength(0);
                    in = false;
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                in = true;
            }
        }
        return out;
    }

    /**
     * コメントを空白へ置換する（行番号を保つため改行は残す）。
     *
     * @param keepStrings true なら文字列リテラルの中身を残す。false なら文字列も空白化する。
     */
    private static String blankOutCommentsAndStrings(String s, boolean keepStrings) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int start = i;
                i++;
                while (i < n && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                appendMasked(out, s, start, i, keepStrings);
                continue;
            }
            if (c == '\'') {
                int start = i;
                i++;
                while (i < n && s.charAt(i) != '\'') {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                appendMasked(out, s, start, i, false);
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int start = i;
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                appendMasked(out, s, start, i, false);
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int start = i;
                int end = s.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 2;
                appendMasked(out, s, start, i, false);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void appendMasked(StringBuilder out, String s, int start, int end, boolean keep) {
        for (int k = start; k < end; k++) {
            char ch = s.charAt(k);
            out.append(keep || ch == '\n' ? ch : (ch == '\r' ? ch : ' '));
        }
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
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

    // ────────────────────────────────────────────────────────────
    // SecurityConfig の authorizeHttpRequests 解析
    // ────────────────────────────────────────────────────────────

    /** SecurityConfig から抽出した matcher パターン → 認可判断。 */
    private static final class SecurityRules {

        private static final String[] DECISIONS = {
                ".permitAll()", ".hasRole(", ".hasAnyRole(", ".hasAuthority(", ".hasAnyAuthority(",
                ".authenticated()", ".denyAll()", ".anonymous()",
        };

        /** permitAll されているパターン。 */
        final Set<String> permitAll = new LinkedHashSet<>();
        /** permitAll 以外で登場するパターン → その判断（診断メッセージ用）。 */
        final Map<String, String> other = new LinkedHashMap<>();

        static SecurityRules parse(Path securityConfig) {
            SecurityRules rules = new SecurityRules();
            // コメントは潰し、文字列リテラルは残す
            String code = blankOutCommentsAndStrings(read(securityConfig), true);

            String[] chunks = code.split("\\.requestMatchers\\(", -1);
            for (int i = 1; i < chunks.length; i++) {
                String chunk = chunks[i];
                int cut = -1;
                String decision = null;
                for (String d : DECISIONS) {
                    int p = chunk.indexOf(d);
                    if (p >= 0 && (cut < 0 || p < cut)) {
                        cut = p;
                        decision = d;
                    }
                }
                String head = (cut >= 0) ? chunk.substring(0, cut) : chunk;
                // CORS プリフライト（OPTIONS "/**"）は「公開の根拠」にはならないため除外する。
                // これを permitAll 集合に入れると "/**" を引用するだけで番人を通せてしまう。
                if (head.contains("HttpMethod.OPTIONS")) {
                    continue;
                }
                for (String literal : stringLiterals(head)) {
                    if (!literal.startsWith("/")) {
                        continue;
                    }
                    if (".permitAll()".equals(decision)) {
                        rules.permitAll.add(literal);
                    } else if (decision != null) {
                        rules.other.putIfAbsent(literal, decision.replace("(", "").replace(")", ""));
                    }
                }
            }
            return rules;
        }
    }
}
