package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ErrorCode が宣言した HTTP ステータスが実際に返るかを保証する番人テスト（認可根治戦役・基盤整備）。
 *
 * <h2>本テストが保証すること</h2>
 * <p>{@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} は ErrorCode → HTTP ステータスの
 * 個別対応表であり、<b>ここに登録されていないコードは {@code Severity} 既定
 * （{@code WARN}=400 / {@code ERROR}=500 / {@code INFO}=200）へフォールバックする</b>。
 * したがって ErrorCode の定義側 Javadoc や設計書のエラー表が「404 を返す」と宣言していても、
 * 本表に登録されていなければ宣言どおりのステータスにはならない。
 *
 * <p>とくに認可の是正では「リソースの存在自体を秘匿するため、不在も越境も同一コードに畳んで
 * 404 を返す」という設計を採る。この設計は <b>ステータスまで 404 に揃って初めて成立する</b>。
 * 登録漏れがあると、コード内 Javadoc・設計書・契約テストの記述と実挙動が乖離した状態
 * （宣言は 404、実挙動は 400）になり、秘匿の意図が実現されない。
 *
 * <p>本テストは、この「宣言と実挙動の乖離」を機械的に検出する。是正作業のたびに人が目視で
 * 登録漏れを拾う運用は、認可根治戦役の中で同じ見落としを繰り返す原因になっていた。
 *
 * <h2>2 つの判定ルール</h2>
 * <ol>
 *   <li><b>宣言ステータス一致（{@link Rules#DECLARED_STATUS}）</b> — ErrorCode 定数の直上の
 *       コメントが HTTP ステータス番号を明示している場合、実際に解決されるステータスが
 *       その番号（複数書かれていればそのいずれか）と一致しなければならない。</li>
 *   <li><b>存在秘匿の実効性（{@link Rules#CONCEALMENT}）</b> — 定数のコメントが「存在秘匿」
 *       {@code IDOR} {@code BOLA} といった秘匿意図の語彙を含み、<b>かつステータス番号を
 *       明示していない</b>場合、解決されるステータスは 404 または 403 でなければならない
 *       （既定の 400 のままでは秘匿の意図が実現されない）。番号を明示している定数は
 *       ルール1の管轄であり、番号と実挙動が一致していればそれが確定した契約とみなす
 *       （秘匿をコードの畳み込みで達成し 400/422 を返す設計も正当なため）。</li>
 * </ol>
 *
 * <p>「全 ErrorCode 定数を必ず本表に登録する」という形は採らない。本リポジトリの
 * ErrorCode 定数は 2,000 件超あり、その大半は入力値不正で既定の 400 が正しい。
 * 全数登録を要求すると 1,000 件超の免責リストが必要になり、無審査で膨れる baseline に堕する。
 * 上記 2 ルールは<b>ツリー内の宣言そのものを検証対象にする</b>ため免責リストを必要とせず、
 * 新しいコードを書いた人が Javadoc に「404」と書いた瞬間に登録が強制される。
 *
 * <h2>例外リストの運用ルール（{@link #DECLARATION_EXCEPTIONS}）</h2>
 * <p>例外リストは<b>意図の宣言であって免罪符ではない</b>。登録には以下を守ること:</p>
 * <ul>
 *   <li>例外は {@link #DECLARATION_EXCEPTIONS} 1 箇所にのみ置く。散らしてはならない。</li>
 *   <li>各エントリには<b>理由を必ず併記</b>する。理由なしのエントリは追加してはならない。</li>
 *   <li>「番人が赤いから足す」は禁止。まず宣言（Javadoc / 設計書）と実装のどちらが
 *       正しいかを実コードで判定し、正しい側へ寄せて解消することを既定とする。</li>
 *   <li>判断を保留する場合は理由に <b>「暫定・要再判断」</b> と明記し、別チケットとして残す。
 *       保留エントリは恒久的な承認ではない。</li>
 * </ul>
 *
 * <h2>本テスト自身の安全性（{@code --tests} 絞り込みでの自壊がない）</h2>
 * <p>本テストは {@code src/main/java} 配下のソースを<b>読み取るだけ</b>で、いかなるファイルも
 * 書き換えない。ArchUnit の解析や {@code FreezingArchRule} の凍結ストア書き戻しも行わないため、
 * {@link ArchUnitFreezeStoreIntegrityTest} の Javadoc に記録された
 * 「{@code ./gradlew test --tests "..."} 絞り込み実行で凍結ストアが誤って書き戻される」事故は
 * 本テストでは原理的に起こらない。単体で絞り込み実行しても結果は変わらない。</p>
 *
 * <p>読み取りベースゆえの弱点は「走査が壊れて 0 件になったのに合格してしまう」偽 green である。
 * これは {@link #走査そのものが機能している} の下限チェック（enum ファイル数・定数総数・
 * 対応表エントリ数）で塞いでいる。</p>
 */
class ErrorCodeHttpStatusDeclarationGuardTest {

    /** {@code backend} をカレントディレクトリとして解決するメインソースのルート。 */
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");

    private static final Path HANDLER_FILE = MAIN_JAVA.resolve(
        Paths.get("com", "mannschaft", "app", "common", "GlobalExceptionHandler.java"));

    /**
     * 宣言ステータス一致ルールの例外リスト（コード → 理由）。
     *
     * <p>運用ルールはクラス Javadoc の「例外リストの運用ルール」を必ず読むこと。
     * 理由なしの追加・「番人が赤いから足す」は禁止。</p>
     */
    private static final Map<String, String> DECLARATION_EXCEPTIONS = Map.of(
        "ERANK_001",
        "暫定・要再判断: F09.12 設計書と FE の E2E モックは 503 を宣言しているが、"
            + "5xx は GlobalExceptionHandler で error_reports 記録と Slack エスカレーションの"
            + "経路に乗るため、「初回バッチ未実行」という平常状態を障害として通知してよいかは"
            + "運用判断が必要。別チケットで 503 登録か宣言側の見直しかを決める。"
    );

    /** 走査が壊れていないことを担保する下限（実測: 123 enum / 2,162 定数 / 818 エントリ）。 */
    private static final int MIN_ERROR_CODE_ENUMS = 100;
    private static final int MIN_ERROR_CODE_CONSTANTS = 1_800;
    private static final int MIN_STATUS_MAP_ENTRIES = 700;

    /** 判定ルールの識別子（失敗メッセージ用）。 */
    private enum Rules {
        DECLARED_STATUS, CONCEALMENT
    }

    // =========================================================================
    // ルール1: 宣言ステータス一致
    // =========================================================================

    @Test
    @DisplayName("ErrorCode が Javadoc で宣言した HTTP ステータスが実際に解決される"
        + "（未登録コードは Severity 既定へフォールバックするため宣言が実現されない）")
    void 宣言したHTTPステータスが実際に解決される() {
        Map<String, String> statusMap = readErrorCodeStatusMap();
        List<String> failures = new ArrayList<>();

        for (ErrorCodeConstant constant : errorCodeConstants()) {
            Set<String> declared = declaredStatusNames(constant.docComment());
            if (declared.isEmpty()) {
                continue;
            }
            String effective = effectiveStatusName(constant, statusMap);
            if (declared.contains(effective)) {
                continue;
            }
            if (DECLARATION_EXCEPTIONS.containsKey(constant.code())) {
                continue;
            }
            failures.add(describeFailure(Rules.DECLARED_STATUS, constant, declared, effective));
        }

        assertNoFailures(failures);
    }

    // =========================================================================
    // ルール2: 存在秘匿の実効性
    // =========================================================================

    @Test
    @DisplayName("存在秘匿の意図を宣言した ErrorCode は 404 または 403 に解決される"
        + "（400 のままでは秘匿の設計が実現されない）")
    void 存在秘匿を宣言したコードは404か403に解決される() {
        Map<String, String> statusMap = readErrorCodeStatusMap();
        List<String> failures = new ArrayList<>();

        for (ErrorCodeConstant constant : errorCodeConstants()) {
            if (!declaresConcealment(constant.docComment())) {
                continue;
            }
            // ステータス番号を明示している定数はルール1の管轄。番号と実挙動が一致していれば
            // それが著者の確定した契約であり、本ルールで上書き判定しない
            //（「入力値の検証で他スコープの ID を同一コードに畳む」ように、秘匿を
            //  コードの畳み込みで達成し 400/422 を返す設計も正当なため）。
            if (!declaredStatusNames(constant.docComment()).isEmpty()) {
                continue;
            }
            String effective = effectiveStatusName(constant, statusMap);
            if (CONCEALMENT_ALLOWED_STATUSES.contains(effective)) {
                continue;
            }
            if (DECLARATION_EXCEPTIONS.containsKey(constant.code())) {
                continue;
            }
            failures.add(describeFailure(
                Rules.CONCEALMENT, constant, CONCEALMENT_ALLOWED_STATUSES, effective));
        }

        assertNoFailures(failures);
    }

    // =========================================================================
    // 偽 green 防止: 走査自体が機能していることの下限チェック
    // =========================================================================

    @Test
    @DisplayName("走査そのものが機能している（ソース走査が壊れて 0 件合格する偽 green の防止）")
    void 走査そのものが機能している() {
        List<ErrorCodeConstant> constants = errorCodeConstants();
        Map<String, String> statusMap = readErrorCodeStatusMap();

        long enumCount = constants.stream().map(ErrorCodeConstant::enumName).distinct().count();

        assertThat(enumCount)
            .as("*ErrorCode enum の検出数が下限を下回った。ソース走査が壊れていないか、"
                + "enum の書式（定数名(\"CODE\", \"message\", Severity.X)）が変わっていないか確認せよ")
            .isGreaterThanOrEqualTo(MIN_ERROR_CODE_ENUMS);

        assertThat(constants.size())
            .as("ErrorCode 定数の検出数が下限を下回った。ソース走査が壊れている疑いがある")
            .isGreaterThanOrEqualTo(MIN_ERROR_CODE_CONSTANTS);

        assertThat(statusMap.size())
            .as("ERROR_CODE_STATUS_MAP のエントリ検出数が下限を下回った。"
                + "GlobalExceptionHandler の対応表の書式が変わった疑いがある")
            .isGreaterThanOrEqualTo(MIN_STATUS_MAP_ENTRIES);

        assertThat(constants.stream().filter(c -> c.code() == null).toList())
            .as("コード文字列を読み取れなかった ErrorCode 定数がある（走査の取りこぼし）")
            .isEmpty();

        assertThat(constants.stream().filter(c -> c.severity() == null).toList())
            .as("Severity を読み取れなかった ErrorCode 定数がある（走査の取りこぼし）")
            .isEmpty();
    }

    @Test
    @DisplayName("例外リストの各エントリに理由が併記されている（免罪符化の防止）")
    void 例外リストには理由が併記されている() {
        List<String> withoutReason = DECLARATION_EXCEPTIONS.entrySet().stream()
            .filter(e -> e.getValue() == null || e.getValue().isBlank() || e.getValue().length() < 20)
            .map(Map.Entry::getKey)
            .toList();

        assertThat(withoutReason)
            .as("例外リストのエントリに理由が書かれていない（または短すぎる）。"
                + "例外は意図の宣言であって免罪符ではない。判断を保留する場合は"
                + "理由に「暫定・要再判断」と明記し、別チケットとして残すこと")
            .isEmpty();
    }

    // =========================================================================
    // 番人ロジック自体のユニットテスト（番人が本当に検出できることの確認）
    // =========================================================================

    @Nested
    @DisplayName("番人ロジック自体の検出能力")
    class GuardLogicItself {

        @Test
        @DisplayName("Javadoc の 404 宣言を検出し、Severity.WARN 既定の 400 との乖離を不一致と判定する")
        void 未登録の404宣言を乖離として検出する() {
            ErrorCodeConstant unregistered = new ErrorCodeConstant(
                "DummyErrorCode", "DUMMY_NOT_FOUND", "DUMMY_001", "WARN",
                "/** 対象が見つからない（404） */", "DummyErrorCode.java", 1);

            assertThat(declaredStatusNames(unregistered.docComment())).containsExactly("NOT_FOUND");
            assertThat(effectiveStatusName(unregistered, Map.of())).isEqualTo("BAD_REQUEST");
        }

        @Test
        @DisplayName("対応表に登録済みなら宣言と一致する")
        void 登録済みなら宣言と一致する() {
            ErrorCodeConstant registered = new ErrorCodeConstant(
                "DummyErrorCode", "DUMMY_NOT_FOUND", "DUMMY_001", "WARN",
                "/** 対象が見つからない（404） */", "DummyErrorCode.java", 1);

            assertThat(effectiveStatusName(registered, Map.of("DUMMY_001", "NOT_FOUND")))
                .isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("件数・時間などの数値は HTTP ステータス宣言とみなさない")
        void 単位付き数値はステータス宣言とみなさない() {
            assertThat(declaredStatusNames("/** 上限 500 件を超えた */")).isEmpty();
            assertThat(declaredStatusNames("/** 有効期限は 404 日 */")).isEmpty();
            assertThat(declaredStatusNames("/** 兄弟の FOO_404 と揃える */")).isEmpty();
        }

        @Test
        @DisplayName("複数のステータスが書かれていればそのいずれかに解決されれば一致とみなす")
        void 複数宣言はいずれかに一致すればよい() {
            Set<String> declared = declaredStatusNames("/** 403 ではなく存在秘匿のため 404 */");
            assertThat(declared).containsExactlyInAnyOrder("FORBIDDEN", "NOT_FOUND");
        }

        @Test
        @DisplayName("秘匿意図の語彙を検出する")
        void 秘匿語彙を検出する() {
            assertThat(declaresConcealment("/** 越境は存在秘匿する */")).isTrue();
            assertThat(declaresConcealment("/** IDOR 対策 */")).isTrue();
            assertThat(declaresConcealment("/** BOLA 対策 */")).isTrue();
            assertThat(declaresConcealment("/** 入力値が不正 */")).isFalse();
        }

        @Test
        @DisplayName("秘匿語彙とステータス番号を併記した定数はルール1（番号一致）の管轄になる")
        void 秘匿語彙と番号の併記は番号側が管轄() {
            String comment = "/** 他チームの ID も同一コードに畳む（存在秘匿）。入力不正なので 400。 */";
            assertThat(declaresConcealment(comment)).isTrue();
            assertThat(declaredStatusNames(comment)).containsExactly("BAD_REQUEST");
        }

        @Test
        @DisplayName("Severity 既定のフォールバックが仕様どおり（WARN=400 / ERROR=500 / INFO=200）")
        void Severity既定のフォールバック() {
            assertThat(effectiveStatusName(dummyWithSeverity("WARN"), Map.of()))
                .isEqualTo("BAD_REQUEST");
            assertThat(effectiveStatusName(dummyWithSeverity("ERROR"), Map.of()))
                .isEqualTo("INTERNAL_SERVER_ERROR");
            assertThat(effectiveStatusName(dummyWithSeverity("INFO"), Map.of()))
                .isEqualTo("OK");
        }

        private ErrorCodeConstant dummyWithSeverity(String severity) {
            return new ErrorCodeConstant("DummyErrorCode", "DUMMY", "DUMMY_001", severity,
                "", "DummyErrorCode.java", 1);
        }
    }

    // =========================================================================
    // 失敗メッセージ
    // =========================================================================

    private static String describeFailure(Rules rule, ErrorCodeConstant constant,
                                          Set<String> expected, String actual) {
        String head = switch (rule) {
            case DECLARED_STATUS -> "【宣言と実挙動の乖離】";
            case CONCEALMENT -> "【存在秘匿が実現されていない】";
        };
        return String.format(
            "%n%s %s.%s（コード: %s / Severity.%s）%n"
                + "  宣言: %s ／ 実際に返るステータス: %s%n"
                + "  場所: %s:%d%n"
                + "  なぜ問題か: ERROR_CODE_STATUS_MAP に未登録のコードは Severity 既定"
                + "（WARN=400 / ERROR=500）へフォールバックする。"
                + "宣言が 404 でも登録がなければ 404 は返らず、"
                + "「不在も越境も同一応答に畳んで存在を秘匿する」という設計意図が実現されない。%n"
                + "  どう直すか: (1) 宣言（Javadoc・設計書のエラー表）が正しいなら、"
                + "GlobalExceptionHandler.ERROR_CODE_STATUS_MAP に "
                + "Map.entry(\"%s\", HttpStatus.%s) を理由コメント付きで追加する。"
                + "(2) 実挙動が正しく宣言が古いなら、定義側 Javadoc と設計書を実挙動に合わせて直す"
                + "（どちらが正しいかは throw 箇所の実コードで判定すること。推測で登録すると"
                + "誤ったステータスが仕様として固定される）。"
                + "(3) 判断を保留する場合のみ DECLARATION_EXCEPTIONS に"
                + "「暫定・要再判断」と理由を明記して追加する。",
            head, constant.enumName(), constant.constantName(), constant.code(),
            constant.severity(),
            String.join(" または ", expected), actual,
            constant.file(), constant.line(),
            constant.code(), expected.iterator().next());
    }

    private static void assertNoFailures(List<String> failures) {
        if (failures.isEmpty()) {
            return;
        }
        fail("ErrorCode が宣言した HTTP ステータスと実挙動が一致していません（" + failures.size()
            + " 件）。本テストの意義はクラス Javadoc を参照。\n" + String.join("\n", failures));
    }

    // =========================================================================
    // 宣言の読み取り
    // =========================================================================

    /** HTTP ステータス番号 → {@code HttpStatus} 定数名。 */
    private static final Map<String, String> STATUS_NAMES = statusNames();

    private static Map<String, String> statusNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("400", "BAD_REQUEST");
        names.put("401", "UNAUTHORIZED");
        names.put("402", "PAYMENT_REQUIRED");
        names.put("403", "FORBIDDEN");
        names.put("404", "NOT_FOUND");
        names.put("405", "METHOD_NOT_ALLOWED");
        names.put("406", "NOT_ACCEPTABLE");
        names.put("408", "REQUEST_TIMEOUT");
        names.put("409", "CONFLICT");
        names.put("410", "GONE");
        names.put("411", "LENGTH_REQUIRED");
        names.put("412", "PRECONDITION_FAILED");
        names.put("413", "PAYLOAD_TOO_LARGE");
        names.put("414", "URI_TOO_LONG");
        names.put("415", "UNSUPPORTED_MEDIA_TYPE");
        names.put("422", "UNPROCESSABLE_ENTITY");
        names.put("423", "LOCKED");
        names.put("425", "TOO_EARLY");
        names.put("426", "UPGRADE_REQUIRED");
        names.put("428", "PRECONDITION_REQUIRED");
        names.put("429", "TOO_MANY_REQUESTS");
        names.put("431", "REQUEST_HEADER_FIELDS_TOO_LARGE");
        names.put("451", "UNAVAILABLE_FOR_LEGAL_REASONS");
        names.put("500", "INTERNAL_SERVER_ERROR");
        names.put("501", "NOT_IMPLEMENTED");
        names.put("502", "BAD_GATEWAY");
        names.put("503", "SERVICE_UNAVAILABLE");
        names.put("504", "GATEWAY_TIMEOUT");
        names.put("507", "INSUFFICIENT_STORAGE");
        names.put("511", "NETWORK_AUTHENTICATION_REQUIRED");
        return Map.copyOf(names);
    }

    private static final Set<String> CONCEALMENT_ALLOWED_STATUSES =
        Collections.unmodifiableSet(new LinkedHashSet<>(List.of("NOT_FOUND", "FORBIDDEN")));

    /** {@code FOO_404} のようなエラーコード自体のトークン（ステータス宣言と誤認しないため除去する）。 */
    private static final Pattern ERROR_CODE_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9]*_[0-9]+");

    /** 「500 件」「404 日」のような単位付き数値はステータス宣言とみなさない。 */
    private static final Pattern STATUS_NUMBER = Pattern.compile(
        "(?<![0-9])(" + String.join("|", statusNames().keySet()) + ")(?![0-9])"
            + "(?!\\s*(?:件|個|名|回|日|年|月|分|秒|文字|人|台|通|枚|円|KB|MB|GB|ms|バイト|byte))");

    private static final Pattern CONCEALMENT_VOCABULARY = Pattern.compile("存在秘匿|秘匿|IDOR|BOLA");

    /** コメント中で宣言されている HTTP ステータス（{@code HttpStatus} 定数名）の集合。 */
    static Set<String> declaredStatusNames(String comment) {
        String sanitized = ERROR_CODE_TOKEN.matcher(comment).replaceAll(" ");
        Set<String> declared = new LinkedHashSet<>();
        Matcher matcher = STATUS_NUMBER.matcher(sanitized);
        while (matcher.find()) {
            declared.add(STATUS_NAMES.get(matcher.group(1)));
        }
        return declared;
    }

    /** コメントが存在秘匿の意図を宣言しているか。 */
    static boolean declaresConcealment(String comment) {
        return CONCEALMENT_VOCABULARY.matcher(comment).find();
    }

    /**
     * 実際に返るステータス名。{@code GlobalExceptionHandler#resolveHttpStatus} と同じ規則
     * （対応表を優先し、なければ {@code Severity} 既定）。
     */
    static String effectiveStatusName(ErrorCodeConstant constant, Map<String, String> statusMap) {
        String mapped = statusMap.get(constant.code());
        if (mapped != null) {
            return mapped;
        }
        return switch (constant.severity()) {
            case "ERROR" -> "INTERNAL_SERVER_ERROR";
            case "INFO" -> "OK";
            default -> "BAD_REQUEST";
        };
    }

    // =========================================================================
    // ソース走査
    // =========================================================================

    /** ErrorCode 定数 1 件（enum 名・定数名・コード文字列・Severity・直上コメント・出典）。 */
    record ErrorCodeConstant(String enumName, String constantName, String code, String severity,
                             String docComment, String file, int line) {
    }

    private static final Pattern CONSTANT_DECLARATION =
        Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*\\(");

    /** 走査結果のキャッシュ（読み取り専用・テストメソッド間で共有）。 */
    private static List<ErrorCodeConstant> cachedConstants;

    private static synchronized List<ErrorCodeConstant> errorCodeConstants() {
        if (cachedConstants == null) {
            cachedConstants = scanErrorCodeConstants();
        }
        return cachedConstants;
    }

    /** {@code src/main/java} 配下の全 {@code *ErrorCode} enum 定数を列挙する（読み取りのみ）。 */
    private static List<ErrorCodeConstant> scanErrorCodeConstants() {
        assertTrue(Files.isDirectory(MAIN_JAVA),
            "メインソースのディレクトリが見つからない: " + MAIN_JAVA.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        List<ErrorCodeConstant> constants = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            List<Path> files = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith("ErrorCode.java"))
                .sorted()
                .toList();
            for (Path file : files) {
                constants.addAll(scanOneEnum(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ErrorCode ソースの走査に失敗した", e);
        }
        return constants;
    }

    private static List<ErrorCodeConstant> scanOneEnum(Path file) throws IOException {
        String simpleName = file.getFileName().toString().replace(".java", "");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String masked = maskCommentsAndStrings(source);

        Matcher enumHeader = Pattern
            .compile("\\benum\\s+" + Pattern.quote(simpleName) + "\\b[^{]*\\{")
            .matcher(masked);
        if (!enumHeader.find()) {
            // ErrorCode.java（共通インターフェース）などは対象外。
            return List.of();
        }

        int bodyStart = enumHeader.end();
        int constantsEnd = findConstantsSectionEnd(masked, bodyStart);
        int firstLine = countNewLines(source, 0, bodyStart);
        int lastLine = countNewLines(source, 0, constantsEnd);

        List<String> lines = List.of(source.split("\r?\n", -1));
        List<ErrorCodeConstant> constants = new ArrayList<>();
        for (int i = firstLine; i <= Math.min(lastLine, lines.size() - 1); i++) {
            Matcher declaration = CONSTANT_DECLARATION.matcher(lines.get(i));
            if (!declaration.find()) {
                continue;
            }
            String constantName = declaration.group(1);
            String arguments = argumentsOf(lines, i);
            constants.add(new ErrorCodeConstant(
                simpleName,
                constantName,
                firstStringLiteral(arguments).orElse(null),
                severityOf(arguments).orElse(null),
                docCommentAbove(lines, i),
                MAIN_JAVA.relativize(file).toString().replace('\\', '/'),
                i + 1));
        }
        return constants;
    }

    /** enum 定数セクションの終端（ネスト外の最初の {@code ;}）を返す。 */
    private static int findConstantsSectionEnd(String masked, int from) {
        int depth = 0;
        for (int i = from; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ';' && depth == 0) {
                return i;
            }
        }
        return masked.length();
    }

    /**
     * 定数宣言行から括弧が閉じるまでの引数テキストを連結して返す。
     * 文字列リテラル中の丸括弧を深さ計算に含めないよう、文字列は塊としてコピーする。
     */
    private static String argumentsOf(List<String> lines, int declarationLine) {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        for (int i = declarationLine; i < lines.size(); i++) {
            String line = lines.get(i);
            int j = 0;
            while (j < line.length()) {
                char c = line.charAt(j);
                if (depth >= 1 && c == '"') {
                    int k = j + 1;
                    while (k < line.length()) {
                        if (line.charAt(k) == '\\') {
                            k += 2;
                            continue;
                        }
                        if (line.charAt(k) == '"') {
                            k++;
                            break;
                        }
                        k++;
                    }
                    text.append(line, j, Math.min(k, line.length()));
                    j = k;
                    continue;
                }
                if (c == '(') {
                    depth++;
                    if (depth > 1) {
                        text.append(c);
                    }
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return text.toString();
                    }
                    text.append(c);
                } else if (depth >= 1) {
                    text.append(c);
                }
                j++;
            }
            text.append(' ');
        }
        return text.toString();
    }

    private static Optional<String> firstStringLiteral(String arguments) {
        Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(arguments);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> severityOf(String arguments) {
        Matcher matcher = Pattern.compile("Severity\\.([A-Z]+)").matcher(arguments);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** 定数宣言の直上にある連続したコメント行を 1 本の文字列として返す。 */
    private static String docCommentAbove(List<String> lines, int declarationLine) {
        List<String> block = new ArrayList<>();
        for (int i = declarationLine - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            boolean isComment = line.startsWith("*") || line.startsWith("/*")
                || line.startsWith("//") || line.endsWith("*/");
            if (isComment) {
                block.add(line);
                continue;
            }
            if (line.isEmpty()) {
                if (!block.isEmpty()) {
                    break;
                }
                continue;
            }
            break;
        }
        Collections.reverse(block);
        return String.join(" ", block);
    }

    // =========================================================================
    // ERROR_CODE_STATUS_MAP の読み取り
    // =========================================================================

    private static final Pattern MAP_ENTRY = Pattern.compile(
        "Map\\.entry\\(\\s*([^,]+?)\\s*,\\s*HttpStatus\\.([A-Z_]+)\\s*\\)");

    private static final Pattern GETTER_KEY = Pattern.compile(
        "(\\w*ErrorCode)\\.(\\w+)\\.getCode\\(\\)");

    /**
     * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} をソースから読み取り
     * 「コード文字列 → HttpStatus 定数名」に解決して返す。
     */
    private static Map<String, String> readErrorCodeStatusMap() {
        assertTrue(Files.isRegularFile(HANDLER_FILE),
            "GlobalExceptionHandler が見つからない: " + HANDLER_FILE.toAbsolutePath());

        String masked;
        try {
            masked = maskComments(Files.readString(HANDLER_FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("GlobalExceptionHandler の読み取りに失敗した", e);
        }

        int declaration = masked.indexOf("ERROR_CODE_STATUS_MAP");
        assertTrue(declaration >= 0, "ERROR_CODE_STATUS_MAP の宣言が見つからない");
        int open = masked.indexOf("ofEntries(", declaration);
        assertTrue(open >= 0, "ERROR_CODE_STATUS_MAP が Map.ofEntries(...) で構築されていない"
            + "（本テストの読み取り方法を実装に合わせて更新すること）");

        String block = masked.substring(open + "ofEntries(".length(),
            findMatchingParen(masked, open + "ofEntries(".length()));

        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        Matcher entries = MAP_ENTRY.matcher(block);
        while (entries.find()) {
            String key = entries.group(1).trim();
            String status = entries.group(2);
            Matcher literal = Pattern.compile("^\"([^\"]+)\"$").matcher(key);
            if (literal.matches()) {
                resolved.put(literal.group(1), status);
                continue;
            }
            Matcher getter = GETTER_KEY.matcher(key);
            if (getter.matches()) {
                String code = resolveCodeByConstantName(getter.group(1), getter.group(2));
                if (code != null) {
                    resolved.put(code, status);
                    continue;
                }
            }
            unresolved.add(key);
        }

        assertThat(unresolved)
            .as("ERROR_CODE_STATUS_MAP のキーをコード文字列へ解決できなかった。"
                + "本テストが対応表を読み取れないと登録漏れを検出できないため、"
                + "解決ロジックを実装の書き方に合わせて更新すること")
            .isEmpty();
        return resolved;
    }

    /** {@code XxxErrorCode.CONSTANT.getCode()} 形式のキーをコード文字列に解決する。 */
    private static String resolveCodeByConstantName(String enumName, String constantName) {
        return errorCodeConstants().stream()
            .filter(c -> c.enumName().equals(enumName) && c.constantName().equals(constantName))
            .map(ErrorCodeConstant::code)
            .findFirst()
            .orElse(null);
    }

    private static int findMatchingParen(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return text.length();
    }

    // =========================================================================
    // コメント・文字列のマスク（構文解析の代わりに十分な範囲で行う）
    // =========================================================================

    /** コメントを空白へ置き換える（文字列リテラルは保持する）。 */
    private static String maskComments(String source) {
        return JavaSourceScanningUtils.maskCommentsOnly(source);
    }

    /** コメントを空白へ、文字列リテラルの中身を空白へ置き換える。 */
    private static String maskCommentsAndStrings(String source) {
        return JavaSourceScanningUtils.maskCommentsAndLiterals(source);
    }

    private static int countNewLines(String text, int from, int to) {
        int lines = 0;
        for (int i = from; i < Math.min(to, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
