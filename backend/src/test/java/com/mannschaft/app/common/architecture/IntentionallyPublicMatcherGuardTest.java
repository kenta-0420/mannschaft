package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.mannschaft.app.common.architecture.SecurityConfigRules.blankOutCommentsAndStrings;
import static com.mannschaft.app.common.architecture.SecurityConfigRules.javaFiles;
import static com.mannschaft.app.common.architecture.SecurityConfigRules.read;
import static com.mannschaft.app.common.architecture.SecurityConfigRules.sourceRoot;
import static com.mannschaft.app.common.architecture.SecurityConfigRules.stringLiterals;
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
 * <p>{@code SecurityConfig} の走査ロジックは {@link SecurityConfigRules} へ共通化されている
 * （{@code @AuthorizedByPathConfig} 用の番人と共有。二重管理を避けるため）。</p>
 *
 * @see com.mannschaft.app.common.security.IntentionallyPublic
 */
@DisplayName("番人: @IntentionallyPublic の matcher 式は SecurityConfig で permitAll されていること")
class IntentionallyPublicMatcherGuardTest {

    private static final String ANNOTATION = "@IntentionallyPublic";

    // ────────────────────────────────────────────────────────────
    // テスト本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("引用された matcher 式はすべて SecurityConfig に実在し permitAll されている")
    void everyCitedMatcherIsPermitAllInSecurityConfig() {
        Path root = sourceRoot();
        SecurityConfigRules.Rules rules =
                SecurityConfigRules.Rules.parse(root.resolve("com/mannschaft/app/config/SecurityConfig.java"));

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
}
