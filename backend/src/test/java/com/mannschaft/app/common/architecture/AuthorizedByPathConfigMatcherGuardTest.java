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
 * 番人: {@code @AuthorizedByPathConfig} が申告する matcher 式が、実際に {@code SecurityConfig} で
 * 「permitAll ではない何らかの認可決定」（{@code hasRole} / {@code hasAnyRole} /
 * {@code hasAuthority} / {@code hasAnyAuthority} / {@code authenticated} 等）を受けていることを
 * 機械的に検証する。
 *
 * <p><b>なぜこの番人が要るのか</b>:
 * {@code @AuthorizedByPathConfig} は「この EP は Controller/Service にコードが無くても
 * {@code SecurityConfig} のパス単位認可で守られている」と宣言する監査済マーカーである。
 * 旧規約はその根拠を {@code SecurityConfig.java:123 — ...} という<b>行番号</b>で javadoc に
 * 書くだけの自己申告であり、<b>検証する番人が存在しなかった</b>。
 * 実測（2026-08-05）では行番号引用 42 箇所のうち<b>確認した全件が実物より一律 +31 行ずれていた</b>
 * （例: {@code SystemAdminDashboardController.java:37} が引く {@code :419} は
 * 実物では {@code :450}）。行番号が腐っていた以上、そこに何が本当に書いてあったのかを
 * 保証するものは何も無かった。</p>
 *
 * <p><b>本丸は「permitAll への誤付与」の検知</b>: {@code @AuthorizedByPathConfig} は
 * 「認可済み」を主張する注釈であるため、申告した matcher が実は {@code permitAll()} だった場合、
 * それは「認可漏れを認可済みと偽って隠蔽する」最悪の事故になる。本番人はこれを機械的に撃つ。</p>
 *
 * <p><b>検証の強さ</b>: ロール名（{@code hasRole("SYSTEM_ADMIN")} 等）までの一致は要求しない。
 * {@code @AuthorizedByPathConfig} には対象クラス内で複数ロールが絡む例（例: 将来的な
 * READ/WRITE 権限分離）が出ても破綻しないよう、「permitAll ではない何らかの認可決定が
 * 存在するか」の二値検証に留めた。ロール名まで申告・突き合わせる場合は本注釈へロール属性を
 * 追加する必要がありスコープが膨らむため、今回は見送る。</p>
 *
 * <p><b>本テストは ArchUnit ではない</b>（素の JUnit + ソース走査）。
 * したがって ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）を
 * 一切読み書きしない。{@code SecurityConfig} の走査ロジックは {@link SecurityConfigRules} へ
 * 共通化し、{@code @IntentionallyPublic} 用の番人（{@link IntentionallyPublicMatcherGuardTest}）
 * と共有している。</p>
 *
 * @see com.mannschaft.app.common.security.AuthorizedByPathConfig
 */
@DisplayName("番人: @AuthorizedByPathConfig の matcher 式は SecurityConfig で permitAll 以外の認可決定を受けていること")
class AuthorizedByPathConfigMatcherGuardTest {

    private static final String ANNOTATION = "@AuthorizedByPathConfig";

    // ────────────────────────────────────────────────────────────
    // テスト本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("申告された matcher 式はすべて SecurityConfig に実在し permitAll ではない")
    void everyCitedMatcherIsAuthorizedNotPermitAllInSecurityConfig() {
        Path root = sourceRoot();
        SecurityConfigRules.Rules rules =
                SecurityConfigRules.Rules.parse(root.resolve("com/mannschaft/app/config/SecurityConfig.java"));

        assertThat(rules.anyDecision)
                .as("SecurityConfig の authorizeHttpRequests 解析に失敗している（パーサーの前提が壊れた可能性）")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        List<Usage> usages = collectUsages(root);

        assertThat(usages)
                .as("production コードから @AuthorizedByPathConfig を 1 件も検出できなかった"
                        + "（走査パスの前提が壊れた可能性）")
                .isNotEmpty();

        for (Usage u : usages) {
            // AC-C1: 空申告（属性なし、または @AuthorizedByPathConfig({})）はバックドアになるため落とす。
            if (u.matchers.isEmpty()) {
                violations.add(String.format(
                        "%s:%d — @AuthorizedByPathConfig に matcher 式が1つも申告されていない。%n"
                                + "        直し方: SecurityConfig の該当 .requestMatchers(...) のパス文字列を"
                                + " そのまま列挙すること。%n"
                                + "        例: @AuthorizedByPathConfig(\"/api/v1/system-admin/**\")",
                        u.file, u.line));
                continue;
            }
            for (String m : u.matchers) {
                if (rules.permitAll.contains(m)) {
                    // AC-C3（本丸）: 認可済みと自称しているのに実体は permitAll。認可漏れの隠蔽になる。
                    violations.add(String.format(
                            "%s:%d — 申告された matcher \"%s\" は SecurityConfig で permitAll() されている。%n"
                                    + "        @AuthorizedByPathConfig は『SecurityConfig の認可決定で守られている』"
                                    + "ことの申告であり、permitAll への付与は認可漏れを『認可済み』と偽装する"
                                    + "事故である。%n"
                                    + "        本当に意図的な公開なら @IntentionallyPublic を使うこと。",
                            u.file, u.line, m));
                    continue;
                }
                if (!rules.anyDecision.contains(m)) {
                    // AC-C2: SecurityConfig に対応する規則が実在しない。
                    violations.add(String.format(
                            "%s:%d — 申告された matcher \"%s\" が SecurityConfig に存在しない。%n"
                                    + "        パスの綴り・ワイルドカードの階層数を SecurityConfig と"
                                    + " 一字一句合わせること。",
                            u.file, u.line, m));
                }
            }
        }

        assertThat(violations)
                .as("@AuthorizedByPathConfig の認可根拠（matcher 式）が SecurityConfig の実態と食い違っている。%n"
                        + "この注釈は『Controller/Service にコードは無いが SecurityConfig で認可済みである』"
                        + "と宣言するものであり、根拠が嘘になると認可漏れの永久凍結と区別がつかなくなる。%n"
                        + "違反一覧:%n%s", String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    @DisplayName("行番号引用（SecurityConfig.java:NNN）は新規に持ち込まれていない")
    void noLineNumberCitationsRemainInMarkerFiles() {
        Path root = sourceRoot();
        Path annotationDef = root.resolve("com/mannschaft/app/common/security/AuthorizedByPathConfig.java");

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
                .as("@AuthorizedByPathConfig を持つファイルに行番号引用（SecurityConfig.java:NNN）が残っている。%n"
                        + "行番号は SecurityConfig に行が挿入されるたびに腐るため禁止。%n"
                        + "matcher 式（パス文字列）を @AuthorizedByPathConfig の属性に列挙する規約に"
                        + "統一すること。%n"
                        + "対象: %s", offenders)
                .isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    // @AuthorizedByPathConfig の使用箇所収集
    // ────────────────────────────────────────────────────────────

    /** 1 箇所の {@code @AuthorizedByPathConfig} 付与とその matcher 申告。 */
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
                // 直後が識別子文字なら別注釈（誤検出防止。現状該当なし）
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
