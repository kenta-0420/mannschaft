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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: {@code new PageImpl<>(content, pageable, N)} の第3引数（総件数）が、実は
 * <b>「このページで残った件数」</b>（＝ {@code .size()}）にすり替わっていないかを検出する。
 *
 * <h2>なぜこの番人が要るのか</h2>
 * <p>本戦役の家老三名による調査で、「SQL で1ページ分だけ取り、その後メモリ上で可視性・認可
 * フィルタをかけて {@code PageImpl} で包み直す」実装が横行していることが判明した。このとき
 * 第3引数（{@code total}）に <b>フィルタ後リストの {@code .size()}</b> をそのまま渡すと、
 * 画面のページャに表示される総件数が「絞り込み後の件数」という嘘になる
 * （DB の真の総件数と一致しなくなり、ページ送りが壊れる）。
 *
 * <p>正しい実装は DB が算出した {@code Page#getTotalElements()} を起点に、そのページで
 * フィルタにより除外された件数だけを差し引く（{@code activity.service.ActivityResultService}
 * や {@code tournament.service.TournamentService#listTournaments} が採用済みの金型）。</p>
 *
 * <p>家老による人手の列挙では 172 ファイルに及ぶ {@code .stream().filter(} を三度数えて
 * 三度とも「漏れがある」と申告されたため、<b>構文で機械的に検出できる本パターンだけでも
 * 番人化</b>し、数え上げを機械に委ねる。</p>
 *
 * <h2>検出対象（2パターン）</h2>
 * <ol>
 *   <li><b>直接パターン</b>: {@code new PageImpl<>(a, b, c.size())} — 第3引数が
 *       そのまま {@code .size()} 呼び出しになっている形。</li>
 *   <li><b>間接（変数経由）パターン</b>: {@code int total = filtered.size(); ...
 *       new PageImpl<>(content, pageable, total);} — 第3引数が単純な識別子で、
 *       同一メソッド内の直近の代入が {@code <同じ識別子> = 何か.size();} になっている形
 *       （{@code IncidentService#listIncidents} で実際に見つかった型）。</li>
 * </ol>
 *
 * <h2>本テストは ArchUnit ではない</h2>
 * <p>{@code PageImpl} コンストラクタ呼び出しの「第3引数の式が {@code .size()} かどうか」は
 * バイトコード解析では判定できない（ArchUnit はメソッド呼び出しの有無は追えても引数式の形は
 * 追えない）ため、{@link IntentionallyPublicMatcherGuardTest} と同じ<b>ソース走査型</b>で書いた。
 * したがって ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）は一切使わず、
 * 独自の凍結リスト（{@code src/test/resources/paging_guard/pageimpl_total_count_size_freeze.txt}）
 * を用いる。{@code --tests} 絞り込み実行をしても ArchUnit 側の凍結ストアは巻き込まない。</p>
 *
 * <h2>凍結リストの位置づけ【必読】</h2>
 * <p><b>このリストは「確定した病巣リスト」であり、削っていく対象である。新規に同じ型を
 * 書いて凍結リストへ追記することは禁止する。</b>新規違反は必ずこのテストを fail させ、
 * その場で根治（{@code getTotalElements()} 起点の計算へ是正）すること。既存分のみ、
 * 監査済みの負債として凍結を許す。</p>
 *
 * <h2>既知の制約（バグではない）</h2>
 * <p>囲みメソッド名の特定（{@link #enclosingMethodName}）は {@code public/private/protected}
 * 修飾子付きのメソッド宣言のみを認識する（{@link #METHOD_DECL} 参照）。package-private
 * （修飾子省略）のメソッド内で違反が見つかった場合、凍結キーのメソッド名部分は
 * {@code (不明メソッド)} となる。<b>検出そのものは正しく行われる</b>（違反を見逃すことはない）。
 * production の Service メソッドは通常 public/private/protected のいずれかであり実害は
 * 想定していないが、後任が「バグだ」と誤認しないよう明記しておく。</p>
 *
 * @see IntentionallyPublicMatcherGuardTest
 */
@DisplayName("番人: PageImpl の第3引数(総件数)がフィルタ後件数(.size())にすり替わっていないこと")
class PagingTotalCountSizeGuardTest {

    private static final Pattern PAGE_IMPL_CTOR = Pattern.compile("new\\s+PageImpl<[^>]*>\\s*\\(");

    /**
     * 第3引数<全体>が「単純なメソッド/フィールドチェーン + 末尾 {@code .size()}」のみで
     * 構成されている場合にマッチする。算術演算子（{@code + - * /} 等）や {@code Math.max(...)}
     * のようなラップを含む式は対象外（それらは {@code getTotalElements()} 起点の正しい是正パターン
     * であり得るため、部分文字列一致では誤検知する）。
     */
    private static final Pattern DIRECT_SIZE_CHAIN = Pattern.compile(
            "[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*(\\(\\))?)*\\.size\\(\\)");
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?[ \\t]*\\r?\\n[ \\t]*)*"
                    + "(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?"
                    + "(?:[\\w.<>\\[\\],? ]+?)\\s+(\\w+)\\s*\\([^;{]*\\)\\s*"
                    + "(?:throws\\s+[\\w.,\\s]+)?\\{");

    private static final Path FREEZE_FILE =
            Paths.get("src", "test", "resources", "paging_guard", "pageimpl_total_count_size_freeze.txt");

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

    private static Path freezeFile() {
        for (Path candidate : new Path[]{FREEZE_FILE, Paths.get("backend").resolve(FREEZE_FILE)}) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "凍結リストが見つからない: " + FREEZE_FILE + "（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    @Test
    @DisplayName("新規のPageImpl総件数すり替えは無い（既存は凍結リストのみ許容）")
    void noNewPageImplTotalCountSizeSubstitution() throws IOException {
        Path root = sourceRoot();
        Set<String> frozen = readFreezeList();

        List<Violation> found = collectViolations(root);

        assertThat(found)
                .as("production コードから PageImpl 呼び出しを 1 件も検出できなかった"
                        + "（走査パスの前提が壊れた可能性）")
                .isNotEmpty();

        Set<String> foundKeys = found.stream().map(Violation::key).collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> newViolations = found.stream()
                .filter(v -> !frozen.contains(v.key()))
                .map(Violation::describe)
                .sorted()
                .toList();

        assertThat(newViolations)
                .as("PageImpl の総件数(第3引数)がフィルタ後件数(.size())にすり替わっている新規箇所を検出した。%n"
                        + "『対処療法禁止・根治治療』原則により、DB算出の getTotalElements() を起点に"
                        + "フィルタで除外された件数を差し引く形へ是正すること"
                        + "（金型: activity.service.ActivityResultService / "
                        + "tournament.service.TournamentService#listTournaments）。%n"
                        + "既存の負債として凍結する場合は %s に追記すること（原則非推奨。"
                        + "本テストのJavadoc『凍結リストの位置づけ』を参照）。%n違反一覧:%n%s",
                        FREEZE_FILE, String.join(System.lineSeparator(), newViolations))
                .isEmpty();

        List<String> staleFrozenEntries = frozen.stream()
                .filter(k -> !foundKeys.contains(k))
                .sorted()
                .toList();
        assertThat(staleFrozenEntries)
                .as("凍結リストに、実コードにはもう存在しない古いエントリが残っている。%n"
                        + "根治済みなら %s から該当行を削除すること（chip-away）。%n"
                        + "根治していないのに消えた場合はメソッド名変更等で検出キーがずれた可能性があり、"
                        + "要調査。ずれたエントリ: %s",
                        FREEZE_FILE, staleFrozenEntries)
                .isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    // 凍結リストの読み書き
    // ────────────────────────────────────────────────────────────

    private static Set<String> readFreezeList() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(freezeFile(), StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            keys.add(trimmed);
        }
        return keys;
    }

    // ────────────────────────────────────────────────────────────
    // 検出本体
    // ────────────────────────────────────────────────────────────

    private record Violation(String classAndMethod, String kind) {
        String key() {
            return classAndMethod;
        }

        String describe() {
            return classAndMethod + " — " + kind;
        }
    }

    private static List<Violation> collectViolations(Path root) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String raw = read(file);
            if (!raw.contains("PageImpl")) {
                continue;
            }
            String code = blankOutCommentsAndStrings(raw);
            String fqcn = toFqcn(root, file);

            List<MethodSpan> methods = findMethodSpans(code);

            Matcher ctor = PAGE_IMPL_CTOR.matcher(code);
            while (ctor.find()) {
                int argsStart = ctor.end();
                int argsEnd = findMatchingParen(code, argsStart - 1);
                if (argsEnd < 0) {
                    continue;
                }
                String argsText = code.substring(argsStart, argsEnd);
                List<String> args = splitTopLevelArgs(argsText);
                if (args.size() != 3) {
                    continue;
                }
                String thirdArg = args.get(2).strip();
                String methodName = enclosingMethodName(methods, ctor.start());
                String classAndMethod = fqcn + "#" + methodName;

                // 直接パターン: 第3引数<全体>が「単純なメソッド/フィールドチェーン + .size()」のみで
                // 構成されている場合に限定する。算術式（page.getTotalElements() - x.size() 等）は
                // 正しい是正パターンであり、部分文字列に .size() を含むだけで誤検知してはならない。
                if (DIRECT_SIZE_CHAIN.matcher(thirdArg).matches()) {
                    violations.add(new Violation(classAndMethod, "直接パターン: 第3引数が \"" + thirdArg + "\""));
                    continue;
                }
                if (thirdArg.matches("\\w+")) {
                    // 変数経由パターン: 同一メソッド本文内で "<identifier> = ...size();" の代入があるか
                    MethodSpan span = methods.stream()
                            .filter(m -> m.name.equals(methodName) && m.start <= ctor.start() && ctor.start() < m.end)
                            .findFirst().orElse(null);
                    if (span != null) {
                        String body = code.substring(span.start, ctor.start());
                        Pattern assign = Pattern.compile(
                                "\\b" + Pattern.quote(thirdArg) + "\\s*=\\s*[^;=]*\\.size\\(\\)\\s*;");
                        if (assign.matcher(body).find()) {
                            violations.add(new Violation(classAndMethod,
                                    "間接パターン: 変数 \"" + thirdArg + "\" が同一メソッド内で .size() 由来"));
                        }
                    }
                }
            }
        }
        return violations;
    }

    private record MethodSpan(String name, int start, int end) {
    }

    private static List<MethodSpan> findMethodSpans(String code) {
        List<MethodSpan> spans = new ArrayList<>();
        Matcher m = METHOD_DECL.matcher(code);
        while (m.find()) {
            int braceStart = m.end() - 1; // '{' の位置
            int braceEnd = findMatchingBrace(code, braceStart);
            if (braceEnd < 0) {
                continue;
            }
            spans.add(new MethodSpan(m.group(1), braceStart, braceEnd));
        }
        return spans;
    }

    /** 最も範囲が狭い（＝最も内側の）メソッドを選ぶ。 */
    private static String enclosingMethodName(List<MethodSpan> methods, int offset) {
        MethodSpan best = null;
        for (MethodSpan s : methods) {
            if (s.start <= offset && offset < s.end) {
                if (best == null || (s.end - s.start) < (best.end - best.start)) {
                    best = s;
                }
            }
        }
        return best != null ? best.name : "(不明メソッド)";
    }

    private static int findMatchingParen(String s, int openParenIndex) {
        int depth = 0;
        for (int i = openParenIndex; i < s.length(); i++) {
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

    private static int findMatchingBrace(String s, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < s.length(); i++) {
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

    private static List<String> splitTopLevelArgs(String argsText) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < argsText.length(); i++) {
            char c = argsText.charAt(i);
            if (c == '(' || c == '<' || c == '[') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(argsText.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(argsText.substring(last));
        return parts;
    }

    private static String toFqcn(Path root, Path file) {
        Path rel = root.relativize(file);
        String s = rel.toString().replace('\\', '/').replace('/', '.');
        return s.substring(0, s.length() - ".java".length());
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

    /** コメント・文字列リテラルを空白へ置換する（行番号は保つ）。 */
    private static String blankOutCommentsAndStrings(String s) {
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
                appendMasked(out, s, start, i);
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
                appendMasked(out, s, start, i);
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int start = i;
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                appendMasked(out, s, start, i);
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int start = i;
                int end = s.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 2;
                appendMasked(out, s, start, i);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void appendMasked(StringBuilder out, String s, int start, int end) {
        for (int k = start; k < end; k++) {
            char ch = s.charAt(k);
            out.append(ch == '\n' || ch == '\r' ? ch : ' ');
        }
    }
}
