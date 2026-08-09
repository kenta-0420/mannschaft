package com.mannschaft.app.common.architecture;

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

/**
 * {@code SecurityConfig} の {@code authorizeHttpRequests} 宣言を軽量パーサで解析する共通部品。
 *
 * <p>{@code @IntentionallyPublic}（{@link IntentionallyPublicMatcherGuardTest}）と
 * {@code @AuthorizedByPathConfig}（{@code AuthorizedByPathConfigMatcherGuardTest}）は、
 * どちらも「注釈が引用する matcher 式が {@code SecurityConfig} の実態と一致しているか」を
 * 機械的に検証する番人であり、{@code SecurityConfig} の走査ロジックは共通である。
 * 二重管理（コピペ）による将来のドリフトを避けるため、本クラスへ一本化する。</p>
 *
 * <p>本クラスは ArchUnit を使わない素の JUnit ヘルパーであり、
 * ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）を一切読み書きしない。</p>
 */
final class SecurityConfigRules {

    private SecurityConfigRules() {
    }

    /** production ソースルート（{@code backend/} 実行と リポジトリルート実行の両方に対応）。 */
    static Path sourceRoot() {
        for (String candidate : new String[]{"src/main/java", "backend/src/main/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "src/main/java が見つからない（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> stringLiterals(String s) {
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
    static String blankOutCommentsAndStrings(String s, boolean keepStrings) {
        return keepStrings
            ? JavaSourceScanningUtils.maskCommentsOnly(s)
            : JavaSourceScanningUtils.maskCommentsAndLiterals(s);
    }

    /** SecurityConfig から抽出した matcher パターン → 認可判断。 */
    static final class Rules {

        private static final String[] DECISIONS = {
                ".permitAll()", ".hasRole(", ".hasAnyRole(", ".hasAuthority(", ".hasAnyAuthority(",
                ".authenticated()", ".denyAll()", ".anonymous()",
        };

        /** {@code .anyRequest()}（deny-by-default フォールバック）を申告するための sentinel 文字列。 */
        static final String ANY_REQUEST_SENTINEL_PREFIX = "anyRequest()";

        /** permitAll されているパターン。 */
        final Set<String> permitAll = new LinkedHashSet<>();
        /** permitAll 以外で登場するパターン → その判断（診断メッセージ用）。 */
        final Map<String, String> other = new LinkedHashMap<>();
        /** 何らかの認可決定（permitAll を含む）が下されている全パターン。 */
        final Set<String> anyDecision = new LinkedHashSet<>();

        static Rules parse(Path securityConfig) {
            Rules rules = new Rules();
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
                // CORS プリフライト（OPTIONS "/**"）は「公開／認可済みの根拠」にはならないため除外する。
                // これを収集対象に入れると "/**" を引用するだけで番人を通せてしまう。
                if (head.contains("HttpMethod.OPTIONS")) {
                    continue;
                }
                for (String literal : stringLiterals(head)) {
                    if (!literal.startsWith("/")) {
                        continue;
                    }
                    if (".permitAll()".equals(decision)) {
                        rules.permitAll.add(literal);
                        rules.anyDecision.add(literal);
                    } else if (decision != null) {
                        rules.other.putIfAbsent(literal, decision.replace("(", "").replace(")", ""));
                        rules.anyDecision.add(literal);
                    }
                }
            }

            // deny-by-default の .anyRequest() フォールバック（特定パスの requestMatcher を持たない
            // エンドポイント向け）。sentinel 文字列 "anyRequest().<decision>" として anyDecision へ
            // 登録する。これにより、パス固有の requestMatcher が存在しないが SecurityConfig の
            // deny-by-default で保護されているエンドポイントも、@AuthorizedByPathConfig が
            // "anyRequest().authenticated()" 等の sentinel を申告することで検証可能になる。
            int anyReqAt = code.indexOf(".anyRequest()");
            if (anyReqAt >= 0) {
                int i = anyReqAt + ".anyRequest()".length();
                while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
                    i++;
                }
                String rest = code.substring(i);
                for (String d : DECISIONS) {
                    if (rest.startsWith(d)) {
                        String sentinel = ANY_REQUEST_SENTINEL_PREFIX + d;
                        if (".permitAll()".equals(d)) {
                            rules.permitAll.add(sentinel);
                        } else {
                            rules.other.putIfAbsent(sentinel, d.replace("(", "").replace(")", ""));
                        }
                        rules.anyDecision.add(sentinel);
                        break;
                    }
                }
            }
            return rules;
        }
    }
}
