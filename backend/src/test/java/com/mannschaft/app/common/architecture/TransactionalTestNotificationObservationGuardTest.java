package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通知の配送検証が {@code @Transactional} で無効化されていないことの番人（#3140 / #2990 L9）。
 *
 * <h2>塞ぐ欠陥（実在した形）</h2>
 * <p>#2990 では付随通知の配送を業務トランザクション内の直接呼び出しから
 * {@code @TransactionalEventListener(AFTER_COMMIT)} へ移した。この移設は、
 * <b>既存のテストを赤くせずに無力化する</b>という副作用を持つ。</p>
 *
 * <p>テストクラスに {@code @Transactional} が付いていると、テストメソッドのトランザクションは
 * 最後にロールバックされ<b>コミットされない</b>。よって {@code AFTER_COMMIT} のリスナーは
 * 一度も発火せず、{@code notifications} テーブルには行が 1 件も生まれない。
 * その状態で「通知が 0 件であること」を検証しているテストは<b>何を壊しても必ず通る</b>
 * （＝偽の緑）。「1 件以上であること」を検証していれば赤くなって気づけるが、
 * 0 件検証は永遠に検出できない。</p>
 *
 * <p>L8（PR #3135）で {@code ScheduleKeepConvertContractIT} が実際にこの形だった。
 * 是正前は {@code @Transactional} なクラスの中で、外側 TX から見えない通知行を
 * 素の {@code DataSource} から新しい接続で数え、
 * {@code assertThat(countConvertedNotifications(supporterId)).isZero()} と検証していた。
 * 隣のテストが赤くなったから気づけただけで、単独なら CI では永遠に緑だった。</p>
 *
 * <h2>判定規則</h2>
 * <p>{@code src/test/java} 配下の Java ソースのうち、次の <b>両方</b>を満たすものを違反とする。</p>
 * <ol>
 *   <li><b>実効的に {@code @Transactional}</b>である
 *       — クラス／メソッドに {@code @Transactional} 注釈が付く、暗黙にトランザクショナルな
 *       スライス注釈（{@code @DataJpaTest} / {@code @JdbcTest}）が付く、
 *       またはそのいずれかを持つテストクラスを（推移的に）継承している。
 *       Javadoc 中の「{@code @Transactional} を付けない理由」のような<b>言及</b>は
 *       コメントを潰してから走査するため拾わない。</li>
 *   <li><b>通知の配送結果を表明している</b>
 *       — 1 つの文の中に、検証表現（{@code assertThat(} / {@code verify(} / {@code assertEquals(}）と
 *       配送観測表現（{@code notifications} テーブルへの SQL・{@code notification_type} 列・
 *       {@code countNotification*} ヘルパ・{@code notificationRepository.find|count|exists}・
 *       通知コラボレータへの {@code verify}）が同居している。</li>
 * </ol>
 *
 * <h2>正しい直し方（違反になったら）</h2>
 * <ol>
 *   <li><b>配送まで検証したい場合</b>: クラスから {@code @Transactional} を外し、
 *       フィクスチャ投入を {@code TransactionTemplate} で明示コミットする。
 *       これが本戦役の正規形である（金型:
 *       {@code ScheduleCommentNotificationPartialFailureIT} の「クラスに {@code @Transactional} を
 *       付けない理由」節、{@code ScheduleNotificationTransactionBoundaryIT}）。</li>
 *   <li><b>業務 TX 内で観測できる契約までで割り切る場合</b>: {@code @RecordApplicationEvents} を付け、
 *       {@code ApplicationEvents} で配送イベントが publish されたことを検証へ切り替える。
 *       配送内容はリスナー側の単体テストが受け持つ（先例:
 *       {@code ScheduleKeepConvertContractIT} の AC-15b、PR #3135）。</li>
 * </ol>
 *
 * <h2>走査対象外</h2>
 * <ul>
 *   <li>{@code com.mannschaft.app.common.architecture} 配下（番人自身と検体。番人が自分の
 *       ソースに書いた検出語で自分を違反判定するのを避ける。検体に対する判定は
 *       {@link TransactionalTestNotificationObservationGuardConditionTest} が行う）。</li>
 * </ul>
 *
 * <h2>既知の適法例外</h2>
 * <p>{@link #ALLOWED} を参照。通知ドメイン自身の read/update API を検証するテストは、
 * テスト内で自分が投入した行を読み直しているだけで配送は関与しない。</p>
 */
@DisplayName("番人: @Transactional なテストが通知の配送結果を検証していないこと（#3140）")
class TransactionalTestNotificationObservationGuardTest {

    /**
     * 適法な例外（FQCN）。
     *
     * <p>{@code NotificationSelfScopeContractIT} は通知ドメイン自身の
     * 「既読にする」API の契約テストである。テストが {@code notificationRepository.saveAndFlush} で
     * 自ら投入した行を同一トランザクション内で読み直し、{@code isRead} が反転したことを見ている。
     * 配送（{@code AFTER_COMMIT} リスナー）は一切関与しないため、
     * {@code @Transactional} でコミットが起きなくても検証は自明に成立しない
     * （＝壊せば赤くなる）。</p>
     */
    static final Set<String> ALLOWED = Set.of(
            "com.mannschaft.app.notification.NotificationSelfScopeContractIT");

    /** 走査対象外パッケージ（番人自身と検体）。 */
    private static final String ARCHITECTURE_PACKAGE = "com.mannschaft.app.common.architecture.";

    // ═════════════════════════════════════════════════════════════════════
    // 判定ロジック（単一正準・検体テストから直接呼ばれる）
    // ═════════════════════════════════════════════════════════════════════

    /** 検証表現。ここに配送観測が同居していたら「配送結果を表明している」とみなす。 */
    private static final Pattern ASSERTION =
            Pattern.compile("assertThat\\s*\\(|verify\\s*\\(|assertEquals\\s*\\(");

    /**
     * 配送観測表現。
     *
     * <p>{@code notifications} テーブルを直接読む SQL、通知件数ヘルパ、
     * {@code notificationRepository} の読み取り、通知コラボレータへの {@code verify} を拾う。
     * {@code notificationRepository.save} のような<b>投入</b>は拾わない（フィクスチャ作成であり
     * 配送の観測ではないため）。</p>
     */
    private static final Pattern DELIVERY_OBSERVATION = Pattern.compile(
            "from\\s+notifications\\b"
                    + "|notification_type"
                    + "|count\\w*notification\\w*"
                    + "|notificationRepository\\s*\\.\\s*(find|count|exists)"
                    + "|verify\\s*\\(\\s*\\w*notif\\w*",
            Pattern.CASE_INSENSITIVE);

    /** 実効的にトランザクショナルであることを示す注釈。 */
    private static final Pattern TX_ANNOTATION =
            Pattern.compile("^\\s*@(Transactional|DataJpaTest|JdbcTest)\\b", Pattern.MULTILINE);

    /** {@code class Foo extends Bar} の親クラス単純名。 */
    private static final Pattern EXTENDS =
            Pattern.compile("\\bclass\\s+\\w+(?:<[^>]*>)?\\s+extends\\s+(\\w+)");

    /** 違反 1 件。 */
    record Violation(String fqcn, int line, String statement) {
        String key() {
            return fqcn + ":" + line;
        }

        @Override
        public String toString() {
            return fqcn + " (" + line + "行目): " + statement;
        }
    }

    /**
     * メソッド宣言（本体開き波括弧まで）。
     *
     * <p>判定の単位を<b>文ではなくメソッド本体</b>にしているのは、観測と表明が別の文に分かれる
     * 書き方（{@code long count = jdbc.query("... FROM notifications ...");} の次の行で
     * {@code assertThat(count).isZero();}）を取り逃さないため。文単位に絞ると、
     * ローカル変数を1つ挟むだけで番人をすり抜けられる。</p>
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "\\n\\s{4,}(?:public |private |protected |static |final |void |[A-Za-z0-9_<>\\[\\], ]+ )*"
                    + "(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws [\\w., ]+)?\\{",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** メソッド 1 つ分（名前・本体・本体の開始オフセット）。 */
    private record Method(String name, String body, int bodyStart) {
    }

    private static List<Method> methodsOf(String masked) {
        List<Method> methods = new ArrayList<>();
        Matcher decl = METHOD_DECL.matcher(masked);
        while (decl.find()) {
            int bodyStart = decl.end();
            int end = matchingBrace(masked, bodyStart);
            methods.add(new Method(decl.group(1), masked.substring(bodyStart, end), bodyStart));
        }
        return methods;
    }

    /**
     * 1 ファイル分の判定。
     *
     * <p><b>2 パスで判定する。</b>1 パス目で「本体に生の配送観測を含むメソッド」（＝観測ヘルパ）の
     * 名前を集め、2 パス目で「生の観測」または「観測ヘルパの呼び出し」を配送観測とみなす。
     * ヘルパ名を語彙で当てにいくと取り逃すためである
     * （L8 で実在した {@code ScheduleKeepConvertContractIT} のヘルパ名は
     * {@code countConvertedNotifications} で、{@code countNotifications} を探す番人には見えなかった）。</p>
     *
     * @param fqcn          対象クラスの FQCN（診断表示用）
     * @param source        対象ソース（LF 正規化済み・生のまま渡すこと）
     * @param transactional そのクラスが実効的にトランザクショナルか
     * @return 違反（配送結果を表明しているメソッド）の一覧
     */
    static List<Violation> scanSource(String fqcn, String source, boolean transactional) {
        if (!transactional) {
            return List.of();
        }
        String masked = JavaSourceScanningUtils.maskCommentsOnly(source);
        List<Method> methods = methodsOf(masked);

        Set<String> observerHelpers = new HashSet<>();
        for (Method m : methods) {
            if (DELIVERY_OBSERVATION.matcher(m.body()).find()) {
                observerHelpers.add(m.name());
            }
        }
        Pattern helperCall = observerHelpers.isEmpty() ? null
                : Pattern.compile("\\b(?:" + String.join("|", observerHelpers) + ")\\s*\\(",
                        Pattern.UNICODE_CHARACTER_CLASS);

        List<Violation> found = new ArrayList<>();
        for (Method m : methods) {
            if (!ASSERTION.matcher(m.body()).find()) {
                continue;
            }
            Matcher raw = DELIVERY_OBSERVATION.matcher(m.body());
            int offset;
            if (raw.find()) {
                offset = m.bodyStart() + raw.start();
            } else if (helperCall != null) {
                Matcher call = helperCall.matcher(m.body());
                if (!call.find()) {
                    continue;
                }
                offset = m.bodyStart() + call.start();
            } else {
                continue;
            }
            found.add(new Violation(fqcn, lineOf(masked, offset), excerpt(masked, offset)));
        }
        return found;
    }

    /** {@code from}（本体先頭・開き波括弧の直後）に対応する閉じ波括弧の位置を返す。 */
    private static int matchingBrace(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return s.length();
    }

    /** 診断表示用に、観測位置を含む文（前後の {@code ;} で区切った範囲）を抜き出す。 */
    private static String excerpt(String s, int offset) {
        int begin = s.lastIndexOf(';', offset) + 1;
        int end = s.indexOf(';', offset);
        if (end < 0) {
            end = Math.min(s.length(), offset + 160);
        }
        return s.substring(begin, end).replaceAll("\\s+", " ").trim();
    }

    /**
     * ソース単体（継承を辿らない）でトランザクショナル注釈を持つか。
     *
     * <p>コメントを潰してから見るため、「{@code @Transactional} を付けない理由」といった
     * Javadoc の言及では true にならない。</p>
     */
    static boolean declaresTransactional(String source) {
        return TX_ANNOTATION.matcher(JavaSourceScanningUtils.maskCommentsOnly(source)).find();
    }

    /** 親クラスの単純名（{@code extends} が無ければ null）。 */
    static String superclassSimpleName(String source) {
        Matcher m = EXTENDS.matcher(JavaSourceScanningUtils.maskCommentsOnly(source));
        return m.find() ? m.group(1) : null;
    }

    /**
     * 継承を推移的に辿って実効的にトランザクショナルか判定する。
     *
     * @param simpleName  対象クラスの単純名
     * @param sourceBySimpleName テスト木の「単純名 -> ソース」
     */
    static boolean effectivelyTransactional(String simpleName, Map<String, String> sourceBySimpleName) {
        Set<String> seen = new HashSet<>();
        String current = simpleName;
        while (current != null && seen.add(current)) {
            String src = sourceBySimpleName.get(current);
            if (src == null) {
                return false;
            }
            if (declaresTransactional(src)) {
                return true;
            }
            current = superclassSimpleName(src);
        }
        return false;
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 走査
    // ═════════════════════════════════════════════════════════════════════

    static Path testSourceRoot() {
        for (String candidate : new String[]{"src/test/java", "backend/src/test/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "src/test/java が見つからない（CWD=" + Paths.get("").toAbsolutePath() + "）");
    }

    static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * ソースを読み、改行を LF に正規化する。
     *
     * <p>このリポジトリは {@code core.autocrlf=true} のため Windows の作業木では CRLF になる。
     * 正規化しないと行頭 {@code ^\s*@Transactional} の判定と変異文字列の一致が環境依存になる。</p>
     */
    static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String toFqcn(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
    }

    /** テスト木全体を走査して違反を集める。 */
    static List<Violation> scanTestTree() {
        Path root = testSourceRoot();
        List<Path> files = javaFiles(root);

        Map<String, String> sourceBySimpleName = new HashMap<>(files.size() * 2);
        Map<Path, String> sources = new HashMap<>(files.size() * 2);
        for (Path f : files) {
            String src = read(f);
            sources.put(f, src);
            String simple = f.getFileName().toString();
            sourceBySimpleName.put(simple.substring(0, simple.length() - ".java".length()), src);
        }

        List<Violation> all = new ArrayList<>();
        for (Path f : files) {
            String fqcn = toFqcn(root, f);
            if (fqcn.startsWith(ARCHITECTURE_PACKAGE)) {
                continue;
            }
            if (ALLOWED.contains(fqcn)) {
                continue;
            }
            String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            boolean tx = effectivelyTransactional(simple, sourceBySimpleName);
            all.addAll(scanSource(fqcn, sources.get(f), tx));
        }
        all.sort((a, b) -> a.key().compareTo(b.key()));
        return all;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 番人本体
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("@Transactional なテストは通知の配送結果を検証してはならない（コミットが起きず必ず通る）")
    void 実効トランザクショナルなテストは通知の配送結果を検証していない() {
        List<Violation> violations = scanTestTree();

        assertThat(violations)
                .as("""
                        @Transactional が効いたテストは業務トランザクションをコミットしないため、\
                        AFTER_COMMIT で配送される通知は 1 件も作られない。そこで通知の配送結果を\
                        検証すると（特に「0 件であること」を）テストは何も守らないまま必ず通る（偽の緑）。
                        直し方は 2 つ:
                          (1) 配送まで検証したい → @Transactional を外し TransactionTemplate で明示コミットする\
                        （金型: ScheduleCommentNotificationPartialFailureIT）
                          (2) 業務TX内の契約までで割り切る → @RecordApplicationEvents で配送イベントの publish を検証する\
                        （先例: ScheduleKeepConvertContractIT AC-15b / PR #3135）
                        検出:
                        %s""".formatted(violations.stream()
                        .map(Violation::toString)
                        .collect(Collectors.joining("\n"))))
                .isEmpty();
    }

    @Test
    @DisplayName("適法例外の台帳は実在するテストだけを指している（腐った例外を残さない）")
    void 適法例外の台帳が腐っていない() {
        Path root = testSourceRoot();
        Set<String> existing = javaFiles(root).stream()
                .map(f -> toFqcn(root, f))
                .collect(Collectors.toSet());

        assertThat(existing)
                .as("ALLOWED に載っているのに実在しないテストがある（改名・削除されたら台帳から外すこと）")
                .containsAll(ALLOWED);
    }
}
